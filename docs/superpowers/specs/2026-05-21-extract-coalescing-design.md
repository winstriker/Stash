# Extract Coalescing — Design

**Date:** 2026-05-21
**Branch:** `feat/extract-coalescing` (cut from `feat/yt-fallback-resolver`)
**Status:** Spec — pending implementation plan
**Supersedes:** `feat/tap-cancel-hardening` (closed without merge)

## Goal

Stop the cascade of fast-failing yt-dlp calls that produces "Couldn't find this track" snackbars on rapid Search retaps, and make the per-row spinner work for Library tracks where the YouTube videoId is unknown until resolve-time. Eliminate the 20-30 s perceived latency that the previous tap-cancel-hardening branch tried (and failed) to address.

The 11 s yt-dlp baseline is unchanged; this branch addresses the *layering* that was wrong on the previous branch.

## Context

The `feat/tap-cancel-hardening` branch ran an on-device verification (Task 8) that showed the gate did not prevent rapid retaps from cascading into yt-dlp's 3-120 ms `YoutubeDLException` fast-fail. Three root causes were identified post-mortem:

1. **`PreviewPrefetcher.kt:71` calls `extractStreamUrl(id)` directly**, bypassing the `PlayerRepository` gate entirely. Every search triggers `prefetchTopN`, which fires up to `PREFETCH_TOP_N = 6` background yt-dlp resolves. When a user taps a row mid-prefetch, `playFromStream` acquires the gate, but the prefetcher's call is already in flight on a separate code path. yt-dlp-android cannot handle concurrent JNI invocations — one fails with `YoutubeDLException` at ~120 ms, propagating as `NotAvailable` → snackbar.

2. **Library tracks have `youtubeId = null` at tap-time** for the Spotify-imported population. The gate keys on `firstTrack.youtubeId`, so the gate path is skipped entirely; the StateFlow is never set; no spinner appears during the 1-3 s `searchYouTubeForVideoId` call followed by the ~11 s `extractStreamUrl` call.

3. **Other ungated call sites** also call `extractStreamUrl` directly: `TrackActionsDelegate.kt:226`, `SearchPreviewMediaSource.kt:54`, `FailedMatchesViewModel.kt:306, 528`. Any single-gate-on-PlayerRepository architecture was always going to be defeated by these.

The fix is to move dedup down to the resolver layer where every caller participates automatically, and keep spinner UI state up at the VM layer where it can be keyed on `track.id` (always non-null) instead of `youtubeId` (sometimes null).

## Architecture

Two independent layers:

```
RESOLVE-DEDUP LAYER (correctness — prevents yt-dlp cascade)
    │
PreviewUrlExtractor
    ├─ inFlightExtracts: ConcurrentHashMap<videoId, Deferred<String>>
    ├─ extractorScope: SupervisorJob + Dispatchers.IO   (singleton-scoped)
    ├─ YTDLP_CONCURRENCY = 1   (serializes yt-dlp JNI calls)
    │
    └─ extractStreamUrl(videoId):
            existing = map[videoId]
            if existing != null: return existing.await()
            new = extractorScope.async { race(...) }
            new.invokeOnCompletion { map.remove(videoId) }
            map[videoId] = new
            return new.await()


SPINNER UI LAYER (intent — what the user tapped)
    │
SearchViewModel + 4 Library *DetailViewModels
    ├─ tappedTrackId: MutableStateFlow<Long?>(null)
    │
    └─ on tap:
            tappedTrackId.value = track.id
            try { playerRepository.playFromStream(item) or setQueue(...) }
            finally { tappedTrackId.value = null }


Row composable in each screen:
    isResolving = (track.id == tappedTrackId)
```

**Behavioural contract.**

- **Coalescing happens transparently.** Prefetcher fires extract for Track A → user taps Track A → user's call finds the in-flight `Deferred` and awaits it. One yt-dlp invocation, two consumers. No cascade.
- **yt-dlp serialization happens implicitly** via the cap=1 semaphore. Even if two different videoIds want extraction simultaneously, they queue at the JNI boundary.
- **Spinner is independent.** Set on tap, cleared when the tap's coroutine completes (regardless of why — `Item`, `NotAvailable`, `CancellationException`, etc.).
- **No state on `PlayerRepository`.** The previous branch's `inFlightStreamingTaps` Set and `resolvingTrackVideoId` StateFlow go away. Their replacement happens at two different layers.

**Why this fixes both observed bugs:**

- *Cascade*: every caller of `extractStreamUrl` (prefetcher, playFromStream, setQueue, retry path, FailedMatchesViewModel, SearchPreviewMediaSource) automatically participates in the coalesce. yt-dlp never sees two simultaneous calls.
- *Library no-spinner*: VM-local state keyed on `track.id` works regardless of whether `track.youtubeId` is known yet. The Spotify-imported track that needs `searchYouTubeForVideoId` to discover its videoId now spins from the moment the user taps.

## Components

### `PreviewUrlExtractor` (data/download)

Three edits to the existing class:

**1. Add the shared scope + in-flight map** as `@Singleton` fields:

```kotlin
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
) {
    /**
     * Shared scope for in-flight extracts. Caller cancellation only stops
     * the *caller's* await(); the underlying extract keeps running so any
     * other awaiter (or a future tap) still gets the URL. The scope lives
     * for the singleton's lifetime; entries clean up via invokeOnCompletion.
     */
    private val extractorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-flight cache. Key = videoId; value = the in-flight Deferred.
     * On completion (success OR failure) the entry self-removes via
     * invokeOnCompletion so the next call for the same videoId starts a
     * fresh extract.
     */
    private val inFlightExtracts = ConcurrentHashMap<String, Deferred<String>>()
```

**2. Replace `extractStreamUrl`'s body** with the coalescing wrapper:

```kotlin
suspend fun extractStreamUrl(videoId: String): String {
    // Single-flight fast path — share an in-flight extract if one exists.
    inFlightExtracts[videoId]?.let { return it.await() }

    // Create-or-find race: two concurrent callers can both reach this
    // point; the loser's freshly-created Deferred (LAZY) never starts.
    val freshlyCreated = extractorScope.async(start = CoroutineStart.LAZY) {
        doExtract(videoId)
    }
    val deferred = inFlightExtracts.putIfAbsent(videoId, freshlyCreated)
        ?: freshlyCreated.also {
            it.invokeOnCompletion { inFlightExtracts.remove(videoId) }
            it.start()
        }
    return deferred.await()
}

/** Renamed from the existing public extractStreamUrl body; logic unchanged. */
private suspend fun doExtract(videoId: String): String {
    val t0 = System.currentTimeMillis()
    Log.d("LATDIAG", "extract-start videoId=$videoId")
    return try {
        val url = race(
            videoId = videoId,
            innerTubeExtract = { /* existing inline lambda */ },
            ytDlpExtract     = { /* existing inline lambda */ },
            itSem = innerTubeSemaphore,
            ytSem = ytDlpSemaphore,
        )
        Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
        url
    } catch (t: Throwable) {
        Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms err=${t.javaClass.simpleName}")
        throw t
    }
}
```

The `CoroutineStart.LAZY` + `putIfAbsent` pattern handles the race where two callers arrive at the same instant: only the winner's `Deferred` actually starts; the loser's `Deferred` is garbage-collected having done no work.

**3. Drop `YTDLP_CONCURRENCY` from 2 to 1**:

```kotlin
companion object {
    private const val INNERTUBE_CONCURRENCY = 8
    // yt-dlp-android throws YoutubeDLException at ~120ms when a second
    // execute() runs while one is in flight (or shortly after a cancelled
    // one). On-device LATDIAG from feat/tap-cancel-hardening showed
    // cascading failures with cap=2. Cap=1 forces serialization at the
    // JNI boundary; coalescing (above) ensures redundant calls don't
    // queue. See spec for the investigation.
    private const val YTDLP_CONCURRENCY = 1   // was 2
```

**No public interface change.** `extractStreamUrl(videoId): String` keeps the same signature. Every caller in the codebase (prefetcher, PlayerRepositoryImpl via the stream resolver chain, TrackActionsDelegate, SearchPreviewMediaSource, FailedMatchesViewModel) gets the coalescing for free without any wiring change.

**Failure propagation contract.** If `doExtract` throws, the `Deferred` completes exceptionally. *All* awaiters see the same exception — by design. The entry self-removes via `invokeOnCompletion`, so the next call for that videoId starts a fresh extract.

### VM-local `tappedTrackId` state (5 ViewModels)

**Scope clarification:** SearchViewModel + 4 *DetailViewModels (`LikedSongsDetailViewModel`, `PlaylistDetailViewModel`, `AlbumDetailViewModel`, `ArtistDetailViewModel`). `LibraryViewModel` is intentionally excluded — its `playTrack(track: Track, allTracks: List<Track>)` early-returns when `track.filePath == null` (line 241), meaning it doesn't handle streaming at all today. The Library tab shows downloaded files only; streaming taps happen from the four DetailScreens. Adding streaming to `LibraryViewModel` is out of scope.

Each of the 5 VMs gets the same pattern:

```kotlin
private val _tappedTrackId = MutableStateFlow<Long?>(null)
val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()
```

The tap handler wraps the existing call in a try/finally.

**SearchViewModel.onResultTap**:

```kotlin
fun onResultTap(item: TrackItem) {
    viewModelScope.launch {
        val tapId = item.videoId.hashCode().toLong()  // matches PlayerRepositoryImpl synthetic id
        _tappedTrackId.value = tapId
        try {
            if (streamingPreference.current()) {
                val result = playerRepository.playFromStream(item)
                when (result) { /* existing snackbar handling — unchanged */ }
            } else {
                delegate.previewTrack(item)
            }
        } finally {
            _tappedTrackId.value = null
        }
    }
}
```

**Library VMs (5 of them) playTrack**:

```kotlin
fun playTrack(trackId: Long) {
    viewModelScope.launch {
        _tappedTrackId.value = trackId
        try {
            // existing setQueue logic — unchanged
            val streamingOn = streamingPreference.current()
            val playable = if (streamingOn) uiState.value.tracks
                           else uiState.value.tracks.filter { it.filePath != null }
            if (playable.isEmpty()) return@launch
            val index = playable.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
            playerRepository.setQueue(playable, index)
        } finally {
            _tappedTrackId.value = null
        }
    }
}
```

**Lifecycle semantics:**
- Set on tap entry, cleared in `finally` regardless of outcome.
- `viewModelScope` cancellation on navigation clears via `finally`.
- No cross-screen persistence — if the user navigates mid-load, the spinner does not follow them, but the underlying extract continues on `extractorScope` so coming back finds a warm `PreviewUrlCache`.

### Row composables — re-do on this branch (not cherry-picked)

The previous branch added `isResolving: Boolean = false` parameters + spinner branches to `DetailTrackRow.kt`, `TrackListItem.kt`, `PreviewDownloadRow.kt`, and `TopResultCard` (inline in `SearchScreen.kt`). At the `feat/yt-fallback-resolver` baseline these parameters do NOT exist — those commits (`f1f7cdb`, `3b5c215`, `9facc84`, `d7eb72d`) are intentionally NOT in the cherry-pick list because they were paired with the wrong-key comparison.

**This branch re-adds the parameters and spinner branches** as a clean standalone commit:

- `DetailTrackRow.kt`: new `isResolving: Boolean = false` parameter + `CircularProgressIndicator(20.dp, primary, 2.dp stroke)` branch in the trailing slot.
- `TrackListItem.kt`: same parameter + same spinner; priority order `isResolving > isPlaying > duration`.
- `PreviewDownloadRow.kt`: same parameter; spinner branch ORs with the existing `isPreviewLoading`.
- `TopResultCard` (private in `SearchScreen.kt`): same parameter + same spinner OR pattern as `PreviewDownloadRow`.

These are pure parameter additions with `= false` defaults — every existing caller continues to compile (including `BlockedSongsScreen.kt` + `FailedMatchesScreen.kt` which use private row composables and aren't affected anyway).

The screen-level wiring changes:

```kotlin
// SearchScreen, Library screens:
val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
// ... row call site:
isResolving = (track.id == tappedTrackId),   // Library — direct DB id
// or
isResolving = (track.videoId.hashCode().toLong() == tappedTrackId),   // Search — synthetic id
```

`Long == Long?` is null-safe in Kotlin; no explicit guard needed.

**Synthetic-id consistency.** Both the Search ViewModel (`item.videoId.hashCode().toLong()` in `onResultTap`) and the Search row (`track.videoId.hashCode().toLong()` in the composable comparison) compute the same derivation. To avoid drift, extract a one-line helper:

```kotlin
internal fun TrackItem.syntheticId(): Long = videoId.hashCode().toLong()
```

Then `_tappedTrackId.value = item.syntheticId()` in the VM and `isResolving = (track.syntheticId() == tappedTrackId)` in the screen. Same formula, one place. If `PlayerRepositoryImpl` ever changes its synthetic-id derivation, this helper changes with it.

## Branch migration

**Close `feat/tap-cancel-hardening` without merge.** No PR, no rebase. The branch is documentation of an approach that didn't work; keep the spec/plan/commits for the historical record.

**Cut `feat/extract-coalescing` from `feat/yt-fallback-resolver`** (same baseline as the closed branch). Cherry-pick:

1. **Test-module type-mismatch repair from `8c892e0`** (Task 2 of the closed branch). Path-filtered to take only the test fixes — production code stays at the baseline:
   ```bash
   git checkout feat/yt-fallback-resolver
   git checkout -b feat/extract-coalescing
   git show 8c892e0 -- \
       core/media/src/test/kotlin/com/stash/core/media/service/StreamingPrefetchTest.kt \
       core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt \
       core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt \
       | git apply
   git commit -am "test(media): repair StreamSourceRegistry type-mismatch in test mocks"
   ```
   This was pre-existing breakage since `5583e29` (the YT fallback resolver commit), independent of any spike.

2. **YouTubeStreamResolver CE propagation from `0a0d165` + `1551a11`** (Task 4 + polish of the closed branch). Clean cherry-picks:
   ```bash
   git cherry-pick 0a0d165 1551a11
   ```
   The CE fix is independently correct and stops false `NotAvailable` snackbars on legitimate cancellations regardless of the rest of this branch's work.

**What we lose by closing the previous branch** (intentional):

- `PlayerRepository.resolvingTrackVideoId` interface + impl — replaced by VM-local `tappedTrackId`.
- `setQueue` gate + `foregroundResolveJob` removal bundle — the *removal* of `foregroundResolveJob` is still right (coalescing makes cancel-and-replace unnecessary), but it gets redone as a clean standalone commit on the new branch.
- The Search/Library wiring on the old StateFlow — composable parameters survive; the screen-level comparison changes.

**Standalone `foregroundResolveJob` removal commit** is part of the new branch (not cherry-picked from the closed one):

```kotlin
// In setQueue() — delete these 5 lines:
// foregroundResolveJob?.cancel()
// foregroundResolveJob = null
// (and the field declaration at line 207)
```

Justification: coalescing prevents the cascade that `foregroundResolveJob?.cancel()` was trying to mitigate. The cancel-and-replace pattern was the *cause* of yt-dlp's 3 ms `YoutubeDLException` per the previous branch's investigation. Removing it eliminates that codepath cleanly.

## Tests

JVM unit tests only. On-device smoke is verification, not regression coverage.

### `PreviewUrlExtractorTest.kt` (extended)

- `extractStreamUrl_coalesces_concurrent_calls_to_same_videoId` — two concurrent callers; hold a `CompletableDeferred` open inside `race()`; assert `race()` invoked once, both callers return the same URL.
- `extractStreamUrl_cancelling_caller_does_not_abort_other_callers` — caller A's scope cancels mid-extract; caller B's await still returns the URL. Tests the singleton-scope design.
- `extractStreamUrl_failure_propagates_to_all_callers` — race throws an exception; all awaiters see the same exception.
- `extractStreamUrl_map_entry_clears_on_success` — after success, calling again for the same videoId starts a fresh extract.
- `extractStreamUrl_map_entry_clears_on_failure` — same, after failure.
- `ytdlp_semaphore_caps_concurrency_at_1` — existing test, updated for the new cap value.

### VM-local spinner

Extend `SearchViewModelTest.kt` + at least one Library VM test (e.g. `LikedSongsDetailViewModelTest.kt` if it exists; create one otherwise):

- `tappedTrackId_emits_during_resolve_and_clears_after` — hold the playerRepository call open via fake; assert flow emissions in order.
- `tappedTrackId_clears_on_cancellation` — cancel the VM scope mid-tap; assert flow returns to null via the `finally` block.

### Cherry-picked tests already pass

`YouTubeStreamResolverTest.kt` from the cherry-pick already has 3 tests for the CE-propagation fix; they run as-is on the new branch.

## Out of scope

- **Mini-player loading indicator.** Per-row spinner only.
- **URL caching with TTL.** Existing `PreviewUrlCache` keeps its current "after-success" behaviour. Coalescing handles concurrent collisions; the cache handles repeat lookups.
- **InnerTube TV-variant probe.** Separate spike.
- **Cloudflare Worker cipher solving.** Separate architecture work.
- **Snackbar UX changes in Library VMs.** The existing `"Couldn't play this track right now."` snackbar in `PlayerRepositoryImpl.kt:309` stays; the cherry-picked `YouTubeStreamResolver` CE fix narrows when it fires (no false snackbars on cancellation).
- **Cross-screen spinner persistence.** Acceptable per the dual-state architecture.
- **Reworking the prefetcher's coroutine scope ownership.** The prefetcher already has its own `SupervisorJob + Dispatchers.IO` scope; it stays. Coalescing in `extractStreamUrl` ensures the prefetcher and a user tap converge on a single `Deferred`.

## Branch posture

`feat/extract-coalescing` is intended to ship — not a feasibility spike. After implementation + on-device verification:

- Verify rapid Search retaps on the same row do NOT produce "Couldn't find this track" snackbars.
- Verify rapid Search retaps on DIFFERENT rows do not produce snackbars either (coalescing handles different videoIds via the cap=1 yt-dlp semaphore).
- Verify Library taps on Spotify-imported tracks (where `youtubeId = null` initially) DO show a spinner from the moment of tap.
- Verify single-tap baseline latency stays at ~11 s on YT-fallback tracks (no regression).
- Verify FLAC tracks still play instantly with no spinner.

If all five behaviours hold, this branch lands as a normal feature merge into `feat/yt-fallback-resolver` (or whatever's current).

## Risks

- **`putIfAbsent` race window.** Two callers can each construct a `LAZY` `Deferred`, but only one wins `putIfAbsent` — the loser's `Deferred` is never started and gets GC'd. Verify Kotlin's `async(start = CoroutineStart.LAZY)` truly doesn't perform work until `.start()` is called.
- **`invokeOnCompletion` ordering.** The cleanup callback runs *after* the `Deferred` completes but *before* `await()` returns. There's a tiny window where the map is empty but the URL is still propagating to callers — harmless because new callers will just start a fresh extract that immediately reuses the warmed `PreviewUrlCache`.
- **`extractorScope` lifetime.** It's a singleton field, never explicitly cancelled. If `PreviewUrlExtractor` were ever destroyed (it isn't — `@Singleton`), the scope would leak. Not a real risk for `@Singleton`.
- **Synthetic-id helper drift.** If the helper formula diverges from `PlayerRepositoryImpl`'s, the spinner stops matching. Mitigation: extract the helper to one place; KDoc references `PlayerRepositoryImpl.playFromStream`'s `videoId.hashCode().toLong()` derivation.
