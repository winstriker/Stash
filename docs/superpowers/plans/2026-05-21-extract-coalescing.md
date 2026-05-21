# Extract Coalescing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the yt-dlp cascade on rapid Search retaps (each tap currently fires a parallel `extract` that fails fast with `YoutubeDLException` because yt-dlp-android can't handle concurrent JNI calls) and make the per-row spinner work for Library tracks whose YouTube videoId is unknown until resolve-time.

**Architecture:** Two independent layers. (1) **Coalescing** — `PreviewUrlExtractor` gets a `ConcurrentHashMap<videoId, Deferred<String>>` and a singleton-scoped `extractorScope`, so the prefetcher + every other caller converges on a single in-flight `Deferred` per videoId. `YTDLP_CONCURRENCY` drops from 2 to 1 because yt-dlp-android serializes badly. (2) **Spinner UI state** lives VM-local as `tappedTrackId: StateFlow<Long?>`, keyed on `track.id` (always non-null), so Library tracks where `youtubeId == null` at tap-time still spin.

**Tech Stack:** Kotlin, Coroutines (`runTest` + virtual time), Hilt, Jetpack Compose, Material 3, JUnit4 + MockK + Truth + Robolectric.

**Spec:** `docs/superpowers/specs/2026-05-21-extract-coalescing-design.md`

**Branch:** `feat/extract-coalescing` (already cut from `feat/yt-fallback-resolver`, spec already committed at `dc46dae` + `ec24865` + `0f7d89f`)

---

## File Structure

**Modify:**

- `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` — add `extractorScope` + `inFlightExtracts` map, rename existing body to `doExtract`, wrap `extractStreamUrl` with single-flight coalescing, drop `YTDLP_CONCURRENCY` from 2 to 1.
- `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` — remove the `foregroundResolveJob` field (line ~207) and its 2 references (lines ~254-255 in `setQueue`).
- `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt` — add `isResolving: Boolean = false` param + spinner branch.
- `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackListItem.kt` — add `isResolving: Boolean = false` param + spinner branch.
- `feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt` — add `isResolving: Boolean = false` param; OR with existing `isPreviewLoading` in the spinner branch.
- `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` — add `isResolving: Boolean = false` to private `TopResultCard`; OR with `isPreviewLoading`; collect `tappedTrackId` flow + thread through to row call sites.
- `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt` — add `tappedTrackId` StateFlow + wrap `onResultTap`'s body in try/finally.
- `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt` — add `tappedTrackId` StateFlow + wrap `playTrack`.
- `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt` — same.
- `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailViewModel.kt` — same.
- `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailViewModel.kt` — same.
- `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt` — collect flow, pass per-row state to `DetailTrackRow`.
- `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt` — same.
- `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt` — same.
- `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt` — same.

**Create:**

- `feature/search/src/main/kotlin/com/stash/feature/search/TrackItemExt.kt` — one-line `internal fun TrackItem.syntheticId(): Long = videoId.hashCode().toLong()` extension so the SearchViewModel + SearchScreen comparisons compute the same value from one place.

**Tests:**

- `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` — extend with coalescing + failure-propagation + map-cleanup + cap=1 cases.
- `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt` — extend with `tappedTrackId` emission test.
- `feature/library/src/test/kotlin/com/stash/feature/library/LikedSongsDetailViewModelTest.kt` (create if missing) — `tappedTrackId` emission test.

**Untouched (intentionally):**

- `LibraryViewModel.kt` and `LibraryScreen.kt` — the Library tab only plays downloaded tracks; no streaming, no spinner needed.
- `BlockedSongsScreen.kt`, `FailedMatchesScreen.kt` — use private composables, not the shared `TrackListItem`/`DetailTrackRow`. Default `isResolving = false` keeps them compiling.

---

## Pre-flight (do once before starting)

**Branch is already cut** at `feat/extract-coalescing` from `feat/yt-fallback-resolver`. Spec committed at commits `dc46dae` / `ec24865` / `0f7d89f`. Verify state:

```bash
cd C:/Users/theno/Projects/MP3APK
git status               # should show clean working tree
git log --oneline -5     # most recent should be 0f7d89f "docs: fix stale..."
git rev-parse --abbrev-ref HEAD   # should show feat/extract-coalescing
```

If anything's off, stash or commit before proceeding. Tasks below assume a clean tree on this branch.

---

## Task 1: Cherry-pick test-module type-mismatch repair

**Files:**
- Modify: `core/media/src/test/kotlin/com/stash/core/media/service/StreamingPrefetchTest.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt`

These three test files were silently broken since commit `5583e29` (YT-fallback resolver) — they declared their `streamResolver` mocks as `KennyyStreamResolver` while the production code expected `StreamSourceRegistry`. The previous branch's Task 2 implementer fixed this as a prerequisite. We cherry-pick *only* the test-file edits, not the gate-widening prod-code edit that was bundled in the same commit.

- [ ] **Step 1: Extract the test fixes from `8c892e0`**

Run:
```bash
git show 8c892e0 -- \
    core/media/src/test/kotlin/com/stash/core/media/service/StreamingPrefetchTest.kt \
    core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt \
    core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt \
    | git apply
```

Expected: applies cleanly (the test files at baseline match the pre-`8c892e0` state for these specific lines — just a type rename). If `git apply` reports a hunk conflict, the file has drifted since `8c892e0`; inspect manually with `git show 8c892e0 -- <path>` and apply the rename by hand.

The actual change in each file is a one-line type rename on the mock declaration: `mockk<KennyyStreamResolver>()` → `mockk<StreamSourceRegistry>()` (or the equivalent property-level declaration). Verify each diff is a single-line rename with no behavior changes by running `git diff` after applying.

- [ ] **Step 2: Verify the test module compiles**

Run: `./gradlew :core:media:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. The pre-existing breakage is gone.

- [ ] **Step 3: Verify tests pass**

Run: `./gradlew :core:media:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. The number of tests should match `feat/yt-fallback-resolver`'s baseline count (these are pre-existing tests that just needed the type fix to run again).

- [ ] **Step 4: Commit**

```bash
git add core/media/src/test/kotlin/com/stash/core/media/service/StreamingPrefetchTest.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt
git commit -m "test(media): repair StreamSourceRegistry type-mismatch in test mocks"
```

---

## Task 2: Cherry-pick `YouTubeStreamResolver` CancellationException propagation

**Files:**
- Modify (via cherry-pick): `core/media/src/main/kotlin/com/stash/core/media/streaming/YouTubeStreamResolver.kt`
- Create (via cherry-pick): `core/media/src/test/kotlin/com/stash/core/media/streaming/YouTubeStreamResolverTest.kt`

The CE-propagation fix from the closed branch — independently correct, stops false `NotAvailable` snackbars on legitimate cancellations.

- [ ] **Step 1: Cherry-pick the two commits**

Run:
```bash
git cherry-pick 0a0d165 1551a11
```

Expected: both commits apply cleanly. If either reports a conflict, drop the cherry-pick (`git cherry-pick --abort`) and apply by hand from `git show <sha>` — there are only 2 production-file edits + 1 new test file.

- [ ] **Step 2: Verify tests pass**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.YouTubeStreamResolverTest"`
Expected: BUILD SUCCESSFUL, 3 tests pass:
- `resolve_propagatesCancellationException_notSwallowAsNull`
- `searchYouTubeForVideoId_propagatesCancellationException`
- `resolve_returnsNull_onGenuineExtractionTimeout`

- [ ] **Step 3: Verify full module still passes**

Run: `./gradlew :core:media:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

No additional commit needed — the cherry-picks brought their own commits.

---

## Task 3: Remove `foregroundResolveJob` cancel-and-replace from `setQueue`

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`

Standalone removal of the cancel-and-replace pattern that was the cascade's *cause* on the old branch. Coalescing in Task 4 will prevent the cascade from ever needing this defense. The removal is justified because: (a) cancel-and-replace was killing yt-dlp's JNI subprocess mid-flight, leaving its state in a bad place; (b) coalescing means a second tap on the same videoId joins the in-flight Deferred instead of needing to cancel anything.

- [ ] **Step 1: Delete the field declaration**

In `PlayerRepositoryImpl.kt`, find line ~207:
```kotlin
private var foregroundResolveJob: Job? = null
```
Delete the entire line.

- [ ] **Step 2: Delete the two references in `setQueue`**

In `setQueue` (around lines 245-260), find:
```kotlin
foregroundResolveJob?.cancel()
foregroundResolveJob = null
```
Delete both lines. Keep the immediately-preceding `queueBuildJob?.cancel(); queueBuildJob = null` — that's a separate job for background-fill cancellation and is still needed.

- [ ] **Step 3: Verify with grep**

Run: `grep -rn foregroundResolveJob core/media/src/`
Expected: zero matches. If any remain, delete them too.

- [ ] **Step 4: Compile + test**

Run:
```bash
./gradlew :core:media:compileDebugKotlin
./gradlew :core:media:testDebugUnitTest
```
Both expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt
git commit -m "refactor(media): remove foregroundResolveJob cancel-and-replace"
```

---

## Task 4: `PreviewUrlExtractor` coalescing + `YTDLP_CONCURRENCY = 1` (TDD)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt`

The core of the spike — coalesce concurrent calls + serialize yt-dlp. This task is TDD: tests first, then the wrapper.

- [ ] **Step 1: Write the first failing test — concurrent same-videoId coalescing**

Append to `PreviewUrlExtractorTest.kt`:

```kotlin
@Test
fun extractStreamUrl_coalesces_concurrent_calls_to_same_videoId() = runTest {
    // Hold the underlying race open with a CompletableDeferred so we can
    // observe coalescing while the extract is in-flight.
    val gate = CompletableDeferred<String>()
    val raceInvocations = AtomicInteger(0)
    val extractor = PreviewUrlExtractor(
        context = mockk(relaxed = true),
        ytDlpManager = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        innerTubeClient = mockk(relaxed = true),
    )
    // Use the existing raceForTest hook with a TestHooks impl that
    // counts invocations and blocks on the gate.
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            raceInvocations.incrementAndGet()
            return gate.await()
        }
        override suspend fun ytDlpExtract(id: String): String = error("unreached")
    }

    // Two concurrent callers for the same videoId.
    val a = async { extractor.extractStreamUrlForTest(hooks, "videoA") }
    val b = async { extractor.extractStreamUrlForTest(hooks, "videoA") }
    runCurrent()

    gate.complete("https://result/A")
    val urlA = a.await()
    val urlB = b.await()

    assertThat(urlA).isEqualTo("https://result/A")
    assertThat(urlB).isEqualTo("https://result/A")
    assertThat(raceInvocations.get()).isEqualTo(1)  // coalesced!
}
```

`extractStreamUrlForTest` is a new internal helper on `PreviewUrlExtractor` that takes `TestHooks` + a `videoId`, threads them through the coalescing wrapper, and calls the existing `race(...)` companion. Add it inside the `companion object` next to `raceForTest`. Sketch:

```kotlin
internal suspend fun extractStreamUrlForTest(hooks: TestHooks, videoId: String): String {
    // Skipping the full real wrapper — for the coalescing test, we
    // emulate the same single-flight pattern around raceForTest.
    return coalesce(videoId) { raceForTest(hooks, it) }
}
```

(Implementation detail: the wrapper is `private suspend fun coalesce(videoId: String, doRace: suspend (String) -> String): String` — landed in Step 3d below. Both production `extractStreamUrl` and the test helper delegate to it. Step 3e covers why `extractStreamUrlForTest` must be an instance member, not in the companion.)

Imports needed:
```kotlin
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.atomic.AtomicInteger
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest.extractStreamUrl_coalesces_concurrent_calls_to_same_videoId"`
Expected: COMPILATION FAIL — `extractStreamUrlForTest` and `coalesce` don't exist yet.

- [ ] **Step 3: Implement the coalescing wrapper**

In `PreviewUrlExtractor.kt`:

**3a.** Add imports at the top:
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
```

**3b.** Add the new singleton fields inside the class (e.g. after the existing `private val` declarations, before the companion):

```kotlin
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

**3c.** Rename the existing `extractStreamUrl` body to `private suspend fun doExtract(videoId: String): String` (keep the body identical — LATDIAG logs, race call, the catch block):

```kotlin
/** Production race body — unchanged logic, just relocated for coalescing. */
private suspend fun doExtract(videoId: String): String {
    val t0 = System.currentTimeMillis()
    Log.d("LATDIAG", "extract-start videoId=$videoId")
    return try {
        val url = race(
            videoId = videoId,
            innerTubeExtract = { id ->
                val it0 = System.currentTimeMillis()
                val result = runCatching { extractViaInnerTube(id) }
                val dt = System.currentTimeMillis() - it0
                val outcome = result.fold(
                    onSuccess = { if (it != null) "url" else "null" },
                    onFailure = { "throw:${it.javaClass.simpleName}" },
                )
                Log.d("LATDIAG", "innertube-end videoId=$id dt=${dt}ms outcome=$outcome")
                result.getOrThrow()
            },
            ytDlpExtract = { id ->
                val yt0 = System.currentTimeMillis()
                val result = runCatching { extractViaYtDlp(id) }
                val dt = System.currentTimeMillis() - yt0
                val outcome = result.fold(
                    onSuccess = { "url" },
                    onFailure = { "throw:${it.javaClass.simpleName}" },
                )
                Log.d("LATDIAG", "ytdlp-end videoId=$id dt=${dt}ms outcome=$outcome")
                result.getOrThrow()
            },
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

**3d.** Replace the public `extractStreamUrl` body with the coalescing wrapper:

```kotlin
suspend fun extractStreamUrl(videoId: String): String =
    coalesce(videoId) { doExtract(it) }

/**
 * Single-flight wrapper. If an extract for [videoId] is already in flight,
 * the new caller awaits its result; otherwise a fresh Deferred is started
 * on [extractorScope] and other callers join it.
 *
 * The Deferred lives on a scope independent of any caller's lifetime —
 * caller cancellation only stops the caller's own await(); the underlying
 * extract continues to completion so any other awaiter still gets a URL.
 */
private suspend fun coalesce(
    videoId: String,
    doRace: suspend (String) -> String,
): String {
    // Fast path — share an in-flight extract if one exists.
    inFlightExtracts[videoId]?.let { return it.await() }

    // Create a LAZY Deferred so a putIfAbsent loser's Deferred never starts.
    val freshlyCreated = extractorScope.async(start = CoroutineStart.LAZY) {
        doRace(videoId)
    }
    val deferred = inFlightExtracts.putIfAbsent(videoId, freshlyCreated)
        ?: freshlyCreated.also {
            it.invokeOnCompletion { inFlightExtracts.remove(videoId) }
            it.start()
        }
    return deferred.await()
}
```

**3e.** Add the `extractStreamUrlForTest` helper as an `internal` **instance member** (NOT in the companion — it needs `this` to access `inFlightExtracts` / `extractorScope` via `coalesce`). Place it next to the public `extractStreamUrl`:

```kotlin
/**
 * Test-only: exercises the coalescing wrapper with the existing
 * TestHooks SPI. The race body is replaced by a hook-driven function
 * so the test can observe coalescing without needing real Android deps.
 *
 * Does NOT exercise the real race(...) semantics — those are tested
 * by raceForTest in the same file. This helper tests only the
 * single-flight / coalescing layer above the race.
 *
 * Instance member (not companion) because `coalesce` needs `this`.
 */
internal suspend fun extractStreamUrlForTest(
    hooks: TestHooks,
    videoId: String,
): String = coalesce(videoId) { id ->
    hooks.innerTubeExtract(id) ?: hooks.ytDlpExtract(id)
}
```

`coalesce` stays `private` (instance member). The test calls `extractStreamUrlForTest` on a `PreviewUrlExtractor` instance — already the shape of the Step 1 test code (`extractor.extractStreamUrlForTest(hooks, "videoA")`).

**Test-scheduler caveat**: `extractorScope` uses `Dispatchers.IO`, which is real threads, not `runTest`'s virtual scheduler. The new tests rely entirely on `CompletableDeferred` gates for synchronization — do NOT assume `runCurrent()` will advance work running on `extractorScope`. If a test starts flaking, the cause is almost certainly a missing `CompletableDeferred.complete(...)` or `runCurrent()` call letting unsynchronized IO work proceed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest.extractStreamUrl_coalesces_concurrent_calls_to_same_videoId"`
Expected: PASS.

- [ ] **Step 5: Write the cancellation-isolation test**

Append:
```kotlin
@Test
fun extractStreamUrl_cancelling_caller_does_not_abort_other_callers() = runTest {
    val gate = CompletableDeferred<String>()
    val extractor = PreviewUrlExtractor(
        context = mockk(relaxed = true),
        ytDlpManager = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        innerTubeClient = mockk(relaxed = true),
    )
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? = gate.await()
        override suspend fun ytDlpExtract(id: String): String = error("unreached")
    }

    val cancellable = async { extractor.extractStreamUrlForTest(hooks, "X") }
    val persistent  = async { extractor.extractStreamUrlForTest(hooks, "X") }
    runCurrent()

    cancellable.cancel(CancellationException("caller A bails"))
    gate.complete("https://result/X")

    // persistent's await() should still complete with the URL even though
    // the OTHER caller cancelled. The race ran on extractorScope, not on
    // either caller's scope, so caller A's death cannot kill the work.
    val result = persistent.await()
    assertThat(result).isEqualTo("https://result/X")
}
```

- [ ] **Step 6: Run + commit so far**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"`
Expected: PASS.

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
git commit -m "feat(preview): coalesce concurrent extractStreamUrl calls"
```

- [ ] **Step 7: Add failure-propagation + map-cleanup tests**

Append three more tests:

```kotlin
@Test
fun extractStreamUrl_failure_propagates_to_all_callers() = runTest {
    val gate = CompletableDeferred<String>()
    val extractor = PreviewUrlExtractor(
        context = mockk(relaxed = true),
        ytDlpManager = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        innerTubeClient = mockk(relaxed = true),
    )
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? = null
        override suspend fun ytDlpExtract(id: String): String {
            gate.await()
            throw IllegalStateException("simulated yt-dlp failure")
        }
    }

    val a = async { extractor.extractStreamUrlForTest(hooks, "X") }
    val b = async { extractor.extractStreamUrlForTest(hooks, "X") }
    runCurrent()

    gate.complete("ignored")

    // Both callers see the same exception.
    val errA = runCatching { a.await() }.exceptionOrNull()
    val errB = runCatching { b.await() }.exceptionOrNull()
    assertThat(errA).isInstanceOf(IllegalStateException::class.java)
    assertThat(errB).isInstanceOf(IllegalStateException::class.java)
    assertThat(errA?.message).isEqualTo("simulated yt-dlp failure")
}

@Test
fun extractStreamUrl_map_entry_clears_on_success() = runTest {
    val extractor = PreviewUrlExtractor(
        context = mockk(relaxed = true),
        ytDlpManager = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        innerTubeClient = mockk(relaxed = true),
    )
    val invocations = AtomicInteger(0)
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? {
            invocations.incrementAndGet()
            return "https://x/${invocations.get()}"
        }
        override suspend fun ytDlpExtract(id: String): String = error("unreached")
    }

    val first = extractor.extractStreamUrlForTest(hooks, "X")
    val second = extractor.extractStreamUrlForTest(hooks, "X")

    // Second call started a fresh extract — different result, two invocations.
    assertThat(first).isEqualTo("https://x/1")
    assertThat(second).isEqualTo("https://x/2")
    assertThat(invocations.get()).isEqualTo(2)
}

@Test
fun extractStreamUrl_map_entry_clears_on_failure() = runTest {
    val extractor = PreviewUrlExtractor(
        context = mockk(relaxed = true),
        ytDlpManager = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        innerTubeClient = mockk(relaxed = true),
    )
    val invocations = AtomicInteger(0)
    val hooks = object : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String): String? = null
        override suspend fun ytDlpExtract(id: String): String {
            invocations.incrementAndGet()
            throw IllegalStateException("boom $${invocations.get()}")
        }
    }

    val firstErr = runCatching { extractor.extractStreamUrlForTest(hooks, "X") }.exceptionOrNull()
    val secondErr = runCatching { extractor.extractStreamUrlForTest(hooks, "X") }.exceptionOrNull()

    // Both calls produced an exception, but the second one ran a fresh
    // extract (not a cached failure replay).
    assertThat(firstErr?.message).isEqualTo("boom \$1")
    assertThat(secondErr?.message).isEqualTo("boom \$2")
    assertThat(invocations.get()).isEqualTo(2)
}
```

- [ ] **Step 8: Run all four new tests**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"`
Expected: PASS.

- [ ] **Step 9: Drop `YTDLP_CONCURRENCY` to 1**

In `PreviewUrlExtractor.kt` (around line 79):
```kotlin
private const val YTDLP_CONCURRENCY = 1   // was 2 — see spec
```

Update the existing test `ytdlp_semaphore_caps_concurrency_at_2` (likely around line 120 of the test file): rename to `ytdlp_semaphore_caps_concurrency_at_1` and change the assertion from `assertEquals(... 2, ytMax.get())` to `assertEquals(... 1, ytMax.get())`.

Update the existing KDoc comment near the constant to reflect the new rationale:
```kotlin
/**
 * Concurrency caps for the two extractors. Shared process-wide.
 *
 * yt-dlp-android throws YoutubeDLException at ~120ms when a second
 * execute() runs while one is in flight (or shortly after a cancelled
 * one). On-device LATDIAG from feat/tap-cancel-hardening showed
 * cascading failures with cap=2. Cap=1 forces serialization at the
 * JNI boundary; coalescing (above) ensures redundant calls for the
 * same videoId never reach the semaphore at all.
 */
private const val INNERTUBE_CONCURRENCY = 8
private const val YTDLP_CONCURRENCY = 1   // was 2
```

- [ ] **Step 10: Run all tests + commit**

Run: `./gradlew :data:download:testDebugUnitTest`
Expected: PASS — all 4 new tests + the existing renamed test + all pre-existing tests.

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
git commit -m "feat(preview): drop YTDLP_CONCURRENCY to 1 (serializes JNI)"
```

---

## Task 5: Row composables — re-add `isResolving` parameter

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt`
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackListItem.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` (private `TopResultCard`)

Pure parameter additions with `= false` defaults — every existing caller continues to compile. No new tests (Compose preview screenshots + on-device verification are sufficient for visual state).

- [ ] **Step 1: `DetailTrackRow.kt`**

Add `isResolving: Boolean = false` to the parameter list. Find the trailing duration text in the body (around the `Spacer` near the end of the Row). Wrap it in a `when` block:

```kotlin
when {
    isResolving -> CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 2.dp,
    )
    else -> Text(
        text = formatDuration(track.durationMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

(Use the existing `Text` block in place of the placeholder; don't replace any other styling.)

Add imports if missing:
```kotlin
import androidx.compose.material3.CircularProgressIndicator
```

- [ ] **Step 2: `TrackListItem.kt`**

Same change. Priority order in the `when` block is `isResolving > isPlaying > duration`:

```kotlin
when {
    isResolving -> CircularProgressIndicator(/* same 20.dp/primary/2.dp config */)
    isPlaying -> Icon(/* existing GraphicEq icon */)
    else -> Text(/* existing duration text */)
}
```

- [ ] **Step 3: `PreviewDownloadRow.kt`**

Add `isResolving: Boolean = false` near `isPreviewLoading`. Find the existing `isPreviewLoading -> CircularProgressIndicator(...)` branch (around line 148). Change the condition:

```kotlin
isPreviewLoading || isResolving -> CircularProgressIndicator(/* unchanged */)
```

- [ ] **Step 4: `TopResultCard` in `SearchScreen.kt`**

Find the private `TopResultCard` composable. Add `isResolving: Boolean = false` to its parameter list (near the other Track-only Booleans like `isPreviewLoading`). Find the spinner branch inside the play-button slot (only renders when `item is TopResultItem.TrackTop`). Apply the same OR pattern:

```kotlin
when {
    isPreviewLoading || isResolving -> CircularProgressIndicator(/* unchanged */)
    isPreviewPlaying -> Icon(/* existing */)
    // ... rest unchanged
}
```

- [ ] **Step 5: Verify all four files compile**

Run:
```bash
./gradlew :core:ui:compileDebugKotlin
./gradlew :feature:search:compileDebugKotlin
```
Both expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Build the full debug APK to catch any cross-module surprises**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (BlockedSongsScreen + FailedMatchesScreen use their own private composables; they compile untouched. PopularTracksSection + AlbumDiscoveryScreen call `PreviewDownloadRow` with defaults; they compile too.)

- [ ] **Step 7: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt \
        core/ui/src/main/kotlin/com/stash/core/ui/components/TrackListItem.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt
git commit -m "feat(ui): row composables gain isResolving param + spinner branch"
```

---

## Task 6: `TrackItem.syntheticId` extension

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/TrackItemExt.kt`

One-line helper for the Search-side comparison key derivation. Lives in feature/search because that's where both consumers (SearchViewModel + SearchScreen) live.

- [ ] **Step 1: Create the file**

```kotlin
package com.stash.feature.search

import com.stash.core.model.TrackItem

/**
 * Derives the synthetic track id used by `PlayerRepositoryImpl.playFromStream`
 * (which is `item.videoId.hashCode().toLong()`). Centralised here so the
 * Search VM and SearchScreen compute the same key for the spinner-row
 * comparison. If `PlayerRepositoryImpl`'s derivation ever changes, update
 * this function in lockstep.
 */
internal fun TrackItem.syntheticId(): Long = videoId.hashCode().toLong()
```

- [ ] **Step 2: Compile**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/TrackItemExt.kt
git commit -m "feat(search): TrackItem.syntheticId helper for spinner-key derivation"
```

---

## Task 7: `SearchViewModel.tappedTrackId` + tap-wrap (TDD)

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt`
- Modify: `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

The existing `SearchViewModelTest.kt` uses **Mockito-Kotlin** (`org.mockito.kotlin.*`) and has private helpers `newVm(...)` (line ~252) and `sampleTrack()` (line ~269). Match that style — do not introduce MockK in this file.

Append to `SearchViewModelTest.kt`:

```kotlin
@Test
fun `tappedTrackId emits on tap and clears after playFromStream returns`() = runTest {
    val gate = CompletableDeferred<StreamRoutingResult>()
    val playerRepo = mock<PlayerRepository> {
        onBlocking { playFromStream(any()) } doSuspendableAnswer { gate.await() }
    }
    val streamingPref = mock<StreamingPreference> {
        onBlocking { current() } doReturn true
    }
    val vm = newVm(
        playerRepository = playerRepo,
        streamingPreference = streamingPref,
    )

    val emissions = mutableListOf<Long?>()
    val collectJob = backgroundScope.launch {
        vm.tappedTrackId.collect { emissions.add(it) }
    }

    val item = sampleTrack()  // videoId is set inside the helper
    vm.onResultTap(item)
    runCurrent()

    val expectedId = item.videoId.hashCode().toLong()
    assertTrue(expectedId in emissions)

    gate.complete(StreamRoutingResult.Item(mock()))
    runCurrent()
    assertEquals(null, emissions.last())

    collectJob.cancel()
}
```

The existing `newVm(...)` helper already accepts `playerRepository` and `streamingPreference` as named args (look at its signature around line 252 — adapt the call to whatever args it takes). Imports — all already present in the test file (`mock`, `onBlocking`, `doReturn`, `doSuspendableAnswer`, `any`, `CompletableDeferred`, `runCurrent`); no new imports needed unless `backgroundScope` requires `kotlinx-coroutines-test ≥1.7`, in which case verify the project's version supports it (it does at `1.10.1` per `gradle/libs.versions.toml`).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.SearchViewModelTest"`
Expected: COMPILATION FAIL — `vm.tappedTrackId` doesn't exist.

- [ ] **Step 3: Add the StateFlow + wrap `onResultTap`**

In `SearchViewModel.kt`:

**3a.** Add the field (after the other VM-local properties, e.g. near `uiState`):
```kotlin
private val _tappedTrackId = MutableStateFlow<Long?>(null)
val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()
```

Add imports if missing:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

**3b.** Wrap the existing `onResultTap` body. The current shape (around line 173):

```kotlin
fun onResultTap(item: TrackItem) {
    viewModelScope.launch {
        if (streamingPreference.current()) {
            val result = playerRepository.playFromStream(item)
            // ... existing snackbar handling ...
        } else {
            delegate.previewTrack(item)
        }
    }
}
```

Becomes:

```kotlin
fun onResultTap(item: TrackItem) {
    viewModelScope.launch {
        _tappedTrackId.value = item.syntheticId()
        try {
            if (streamingPreference.current()) {
                val result = playerRepository.playFromStream(item)
                // ... existing snackbar handling — unchanged ...
            } else {
                delegate.previewTrack(item)
            }
        } finally {
            _tappedTrackId.value = null
        }
    }
}
```

The `_tappedTrackId.value = null` in `finally` runs on success, on snackbar-emit branches, on cancellation, on any throw.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.SearchViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelTest.kt
git commit -m "feat(search): tappedTrackId StateFlow + onResultTap wrap"
```

---

## Task 8: `SearchScreen` consumes `tappedTrackId`

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`

Wire the VM's flow through to the row composables.

- [ ] **Step 1: Collect the flow**

In the `SearchScreen` composable (top of the function, with the other `collectAsStateWithLifecycle` calls), add:

```kotlin
val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Pass through to `SectionedResultsList`**

Add `tappedTrackId: Long?` as a parameter to `SectionedResultsList` (its signature is in the same file, around line 247-256). At the call site (around line 137), pass:

```kotlin
SectionedResultsList(
    sections = status.sections,
    // ... existing args ...
    tappedTrackId = tappedTrackId,
    // ... existing args ...
)
```

- [ ] **Step 3: Pass to the row call sites**

Inside `SectionedResultsList`, find the two row call sites:

**`TopResultCard` (around line 301):**
```kotlin
TopResultCard(
    item = top,
    // ... existing args ...
    isResolving = (top.track.videoId.hashCode().toLong() == tappedTrackId),
    // ... existing args ...
)
```

(Use `top.track.videoId.hashCode().toLong()` since `TrackSummary` doesn't have a `TrackItem.syntheticId()` helper — inline the derivation. Alternative: extract a parallel helper on `TrackSummary` if you want one. Keep inline for now; the duplication is one place.)

**`PreviewDownloadRow` (around line 339):** the actual call site uses `item = item` (or the lambda's `t` parameter), not `track = track`. Match the existing parameter name. Use the `TrackItem.syntheticId()` helper from Task 6 for consistency with the SearchViewModel side (which also calls `item.syntheticId()`):

```kotlin
PreviewDownloadRow(
    item = t,
    // ... existing args ...
    isResolving = (t.syntheticId() == tappedTrackId),
)
```

The `TopResultCard` call site stays inline (`top.track.videoId.hashCode().toLong()`) because `top.track` is a `TrackSummary`, not a `TrackItem` — the helper doesn't apply there. Verify by reading the actual call sites before editing.

- [ ] **Step 4: Compile**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt
git commit -m "feat(search): SearchScreen passes tappedTrackId to row composables"
```

---

## Task 9: 4 Library *DetailViewModels — `tappedTrackId` + tap-wrap

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailViewModel.kt`

Each VM gets the same field + same `playTrack` wrap. One write-once test on `LikedSongsDetailViewModel` covers the pattern; the other three are mechanical clones.

- [ ] **Step 1: Add the field to each of the 4 VMs**

For each file, add (after the existing `private val` declarations, before the first `fun`):

```kotlin
private val _tappedTrackId = MutableStateFlow<Long?>(null)
val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()
```

Add imports if missing — same set as Task 7 (`MutableStateFlow`, `StateFlow`, `asStateFlow`).

- [ ] **Step 2: Wrap `playTrack` in each VM**

Each has the signature `fun playTrack(trackId: Long)`. The existing body wraps a `viewModelScope.launch { ... playerRepository.setQueue(...) }`. Add the `tappedTrackId` set/clear:

```kotlin
fun playTrack(trackId: Long) {
    viewModelScope.launch {
        _tappedTrackId.value = trackId
        try {
            // ... existing body — unchanged ...
        } finally {
            _tappedTrackId.value = null
        }
    }
}
```

The existing body is different in each VM (different stream-vs-disk filtering, different snapshot sources). Don't change any of that — just wrap.

- [ ] **Step 3: Prepare the `feature/library` test classpath**

`feature/library/src/` currently has no `test/` directory and `feature/library/build.gradle.kts` declares zero `testImplementation` deps. Before writing a test, set up the classpath.

**3a.** Open `feature/library/build.gradle.kts` and add a `dependencies { }` block (or extend the existing one) with the test deps needed for the new VM tests. The new Library tests use MockK + Truth (matching the spec's testing style), not Mockito-Kotlin — so this list is *canonical*, not copied from `feature/search` (which uses Mockito-Kotlin and would be the wrong template):

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
testImplementation("com.google.truth:truth:1.4.4")
testImplementation(libs.mockk)
```

If the project already declares any of these via a convention plugin (`stash.android.feature`?), you may not need to duplicate — try the test build first, add only what's missing.

**3b.** Create the test directory: `feature/library/src/test/kotlin/com/stash/feature/library/`.

- [ ] **Step 4: Write a test on `LikedSongsDetailViewModel`**

Create `feature/library/src/test/kotlin/com/stash/feature/library/LikedSongsDetailViewModelTest.kt`. Follow the pattern from `SearchViewModelTest.kt`. Append:

```kotlin
@Test
fun `tappedTrackId emits on playTrack and clears after setQueue returns`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val playerRepo = mockk<PlayerRepository>(relaxed = true) {
        coEvery { setQueue(any(), any()) } coAnswers { gate.await() }
    }
    val vm = buildVm(playerRepository = playerRepo)  // adapt to existing test helper

    val emissions = mutableListOf<Long?>()
    val collectJob = backgroundScope.launch {
        vm.tappedTrackId.collect { emissions.add(it) }
    }

    vm.playTrack(trackId = 42L)
    runCurrent()
    assertThat(emissions).contains(42L)

    gate.complete(Unit)
    runCurrent()
    assertThat(emissions.last()).isNull()

    collectJob.cancel()
}
```

If `LikedSongsDetailViewModelTest.kt` doesn't exist, create it using the same setup pattern as `SearchViewModelTest.kt` for VM construction.

- [ ] **Step 5: Run + commit**

Run: `./gradlew :feature:library:testDebugUnitTest`
Expected: PASS, including the new test.

```bash
git add feature/library/build.gradle.kts \
        feature/library/src/main/kotlin/com/stash/feature/library/ \
        feature/library/src/test/kotlin/com/stash/feature/library/
git commit -m "feat(library): 4 *DetailViewModels expose tappedTrackId + wrap playTrack"
```

---

## Task 10: 4 Library *DetailScreens consume `tappedTrackId`

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt`

- [ ] **Step 1: Collect the flow in each screen**

In each `*DetailScreen` composable, add (next to the other `collectAsStateWithLifecycle` calls):

```kotlin
val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Pass `isResolving` to each `DetailTrackRow` call**

In each screen's `DetailTrackRow(...)` call site:

```kotlin
DetailTrackRow(
    track = track,
    // ... existing args ...
    isResolving = (track.id == tappedTrackId),
)
```

`Long == Long?` is null-safe; no explicit guard needed.

- [ ] **Step 3: Compile + assemble**

Run:
```bash
./gradlew :feature:library:compileDebugKotlin
./gradlew :app:assembleDebug
```
Both expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/
git commit -m "feat(library): 4 *DetailScreens pass tappedTrackId to DetailTrackRow"
```

---

## Task 11: On-device smoke verification

**Files:** none (verification-only)

Per project memory, always install on device after code changes — compile-pass isn't enough.

- [ ] **Step 1: Install the debug APK**

```bash
./gradlew :app:installDebug
# If "INSTALL_FAILED_VERSION_DOWNGRADE":
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Start filtered logcat**

```bash
adb logcat -c
adb logcat -s LATDIAG:D StashYT:D PlayerRepository:W YouTubeStreamResolver:D
```

- [ ] **Step 3: Verify the five acceptance behaviours**

For each behaviour: perform the action and note the observed result.

**Behaviour 1: No false snackbar on rapid same-track retaps (Search).**
- Open Search, search for a YT-fallback track (obscure / non-Qobuz).
- Tap the row once. While the spinner is showing, tap 3-4 more times rapidly.
- **Expected:** Zero "Couldn't find this track" snackbars. Single ~11s spinner, then audio plays. Logcat shows ONE `extract-start` event, multiple `extract-end` events with the same `dt` (because the coalesced Deferred completes once but all callers see the URL).

**Behaviour 2: No false snackbar on different-track rapid taps (Search).**
- Same search, different tracks. Tap Track A, then within 500ms tap Track B.
- **Expected:** No snackbars. Track B's spinner overrides Track A's (because the tap handler set `_tappedTrackId.value = B.id` and the row comparison only matches one row at a time). Audio for Track B plays at ~11s. Track A's underlying extract continues on `extractorScope` and runs to completion in the background. (Track A's caller-side `viewModelScope` coroutine was cancelled when Track B's tap fired, but the singleton-scoped Deferred is unaffected — that's the entire point of the caller-independent design.)
- **Cache-fill verification:** does Track A's URL also reach `PreviewUrlCache`? Today `PreviewUrlCache` is written by `PreviewPrefetcher` after a successful `extractStreamUrl` (`PreviewPrefetcher.kt:71`). Direct `extractStreamUrl` callers (including the Search VM via `playerRepository.playFromStream`) DON'T write the cache. So Track A's URL lives only in `inFlightExtracts` until that Deferred completes — and then the map entry is removed. The URL is NOT cached for next time unless a subsequent caller calls `extractStreamUrl(A)` while the Deferred is still in-flight. This is a real gap to flag in the verification notes; addressing it is out of scope for this branch.

**Behaviour 3: Library tap shows a spinner.**
- Open Liked Songs (or a playlist). Tap a streaming-only (non-FLAC) track.
- **Expected:** Spinner appears on the tapped row within one frame, persists for the ~11s resolve, then audio plays. Snackbar fires only if the resolve genuinely failed (extract returned null), never on transient/cancellation paths.

**Behaviour 4: yt-dlp serialization (cap=1).**
- Search for a popular YT-fallback track. The prefetcher should fire for the top 6 hits in the background. Watch logcat.
- **Expected:** Sequential `extract-start` → `extract-end` cycles for each videoId, never two `extract-start` events at the same time before the prior one's `extract-end`. (Different videoIds queue at the cap=1 semaphore; the coalescing layer only dedups SAME videoId.)

**Behaviour 5: FLAC tracks unaffected.**
- Tap a Qobuz catalog track in Library.
- **Expected:** Audio plays instantly with NO spinner. They don't go through `extractStreamUrl`.

- [ ] **Step 4: Document findings in the spec**

Append a `## Verification Results (YYYY-MM-DD)` section to `docs/superpowers/specs/2026-05-21-extract-coalescing-design.md` listing each of the 5 behaviours with pass/fail and a one-line observation. If any fail, also note what was observed and any LATDIAG anomalies.

- [ ] **Step 5: Commit findings**

```bash
git add docs/superpowers/specs/2026-05-21-extract-coalescing-design.md
git commit -m "docs: extract-coalescing on-device verification"
```

---

## Out of scope (do NOT do)

- **Adding streaming support to `LibraryViewModel`.** Library tab is downloaded-only.
- **Mini-player loading state.** Per-row only.
- **Cancellation-replace tap behaviour.** First-tap doesn't get cancelled when user taps something else; both extracts run in the coalesced background pool.
- **URL caching with TTL** — `PreviewUrlCache` keeps current "after-success" behaviour.
- **InnerTube TV-variant probe / Cloudflare Worker cipher** — separate work.
- **Reworking `PreviewPrefetcher`'s scope ownership.** The prefetcher already has its own `SupervisorJob + Dispatchers.IO` scope.
- **Snackbar redesign in Library** — the existing `"Couldn't play this track right now."` at `PlayerRepositoryImpl.kt:309` stays.

If anything on this list feels necessary mid-implementation, push back — the spec deliberately excluded them.
