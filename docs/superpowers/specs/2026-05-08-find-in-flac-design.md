# v0.9.18 — Find in FLAC

**Date:** 2026-05-08
**Status:** Design
**Branch:** `feat/find-in-flac` (worktree path: `.worktrees/find-in-flac`)

## Problem

Stash v0.9.17 ships strict-FLAC-by-default with a deferred-track pipeline that re-resolves automatically when sources recover, and a Home banner that surfaces the deferred count. But there's a long-tail case the pipeline doesn't address: a track that already lives in the library as m4a (downloaded under v0.9.16 or earlier, or via the YouTube fallback path on v0.9.17) and is currently playing. The user has no in-context affordance to ask "is there a lossless version of THIS song available right now?" — they'd have to delete the track and re-download via search, which is destructive and breaks playlist memberships.

Today the Now Playing player surfaces a flag icon in the top bar that opens a "What's wrong with this song?" dialog with three actions: *Find a better match* (flag for replacement), *Delete from library*, *Delete and block forever*. All three are destructive or replacement-flavored — there's no non-destructive upgrade path.

This spec adds a fourth option to that dialog: **Find in FLAC**, which checks the lossless source registry for a higher-quality version of the currently-playing track and replaces the file in place if a match is found.

## Goals

- A user with an m4a track currently playing can tap the flag icon, choose "Find in FLAC", and have the track upgraded to FLAC if any lossless source can serve it — without leaving the Now Playing screen, without disrupting current playback, and without losing playlist memberships or like state.
- The action is hidden when the currently-playing track is already FLAC (an upgrade is meaningless in that case).
- Failure modes are surfaced via snackbar copy distinct enough that a user can tell whether the issue is "no FLAC version exists" vs. "couldn't reach the sources right now".
- The upgrade does not interrupt the currently-playing audio. The next time the track is played (next manual tap-to-replay, or the next time the queue lands on it), the FLAC file is loaded.

## Non-goals

- **No mid-playback swap.** The user explicitly chose Option A (next-play swap) over Option B (pause-swap-resume). The complexity of pausing ExoPlayer at a saved position, swapping the MediaItem URI, and resuming — including the lock-screen / Bluetooth / album-art cache invalidation that comes with it — isn't earned by the use case.
- **No progress bar / cancellable operation.** The fire-and-forget snackbar pattern matches the existing flag/delete actions. If the user navigates away during the resolve+download, the upgrade still completes silently and shows up on next play.
- **No retry button on the failure snackbar.** A user who wants to retry can simply tap Find in FLAC again. The existing `AggregatorRateLimiter` per-source backoff prevents abuse.
- **No Library long-press menu integration.** This spec scopes the action to Now Playing only. A v0.9.19+ follow-up could surface it elsewhere if users ask.
- **No persistent in-flight state.** No DataStore key, no Room row tracking "this track is being upgraded." If the process dies mid-upgrade, the user doesn't see a resume — they'd just tap Find in FLAC again.
- **No dialog reframing.** The existing "What's wrong with this song?" title stays. The user explicitly de-prioritized renaming the dialog ("its fine dont worry about that dialogue change is not important"). Find in FLAC sits at the top of the four buttons; the semantic mismatch with the dialog title is acknowledged and accepted.
- **No deferral integration.** A null result from the registry produces a one-shot snackbar — it does NOT write the track into `WAITING_FOR_LOSSLESS`. This is a user-initiated check, not a queued sync download. The deferred-track pipeline from v0.9.17 stays untouched.
- **No changes to existing Now Playing actions.** The three current options (Find a better match, Delete, Delete + block) keep their behavior verbatim.

## Design

### 1. Data model

#### 1.1 New sealed type: `UpgradeResult`

Location: `core/model/src/main/kotlin/com/stash/core/model/UpgradeResult.kt`

```kotlin
package com.stash.core.model

/**
 * Outcome of a user-initiated lossless upgrade attempt
 * (Now Playing → "Find in FLAC"). Drives the snackbar copy in
 * [com.stash.feature.nowplaying.NowPlayingViewModel].
 *
 * Distinct from sync-pipeline outcomes (`TrackDownloadOutcome`) because
 * the user-facing language differs ("Upgraded" vs. "Downloaded") and
 * because no queue row is involved on this path.
 */
sealed interface UpgradeResult {
    /** Lossless source served a match; file was replaced and the row was updated. */
    data object Upgraded : UpgradeResult

    /** Sources are reachable but no candidate cleared the confidence threshold. */
    data object NoMatch : UpgradeResult

    /**
     * Caught exception during resolve/download — network, captcha-required,
     * registry threw, finalize failed. Generic enough to cover all of them
     * without leaking implementation detail to the snackbar.
     */
    data object Error : UpgradeResult
}
```

The enum is in `:core:model` so both `:core:data` (where the `MusicRepository` declaration lives) and `:feature:nowplaying` can reference it without a `:data:download` dependency.

#### 1.2 No schema changes

No new DataStore keys. No Room migration. v0.9.18 schema stays at v22.

### 2. Pipeline

#### 2.1 `DownloadManager` — promote `tryLosslessDownload` to a callable surface

Location: `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt`

Today, `tryLosslessDownload(track: Track, forced: Boolean = false): TrackDownloadResult?` is `private` (line ~274). For v0.9.18, expose a thin public wrapper that returns the new `UpgradeResult`:

```kotlin
/**
 * v0.9.18: user-initiated lossless upgrade for a track that's already
 * in the library. Calls the existing private [tryLosslessDownload] with
 * `forced = true` so it bypasses the global lossless toggle, and maps
 * the nullable result onto a [UpgradeResult] for the ViewModel layer.
 *
 * The underlying [tryLosslessDownload] writes the new FLAC via
 * [TrackFinalizer] which:
 *   - Replaces the file at the track's existing path (or writes a new
 *     path and deletes the old file — implementation detail of the
 *     finalizer).
 *   - Updates `tracks.file_path` + format metadata via [TrackDao].
 *   - Embeds tags via the existing metadata embedder.
 *
 * Currently-playing audio is not affected — ExoPlayer holds its
 * existing MediaItem URI until the user navigates away or skips. Next
 * play of this track loads the FLAC.
 */
suspend fun upgradeToLossless(track: Track): UpgradeResult = runCatching {
    val result = tryLosslessDownload(track, forced = true)
    when (result) {
        is TrackDownloadResult.Success -> UpgradeResult.Upgraded
        null, TrackDownloadResult.Deferred -> UpgradeResult.NoMatch
        else -> UpgradeResult.NoMatch  // Unmatched / Failed — no candidate
    }
}.getOrElse {
    Log.w(TAG, "upgradeToLossless threw for ${track.id}", it)
    UpgradeResult.Error
}
```

The mapping logic is deliberately conservative: anything other than `Success` becomes `NoMatch` (or `Error` for thrown exceptions). The user doesn't need to distinguish between "registry returned null" and "downloaded a lossless URL that came back 404" — both are "no FLAC for you right now, try later."

**Phase 1 verification step:** before writing code, the implementer must enumerate the actual `TrackDownloadResult` sealed hierarchy in `DownloadManager.kt`. The pseudocode above lists `Success`, `null`, and `Deferred` explicitly with a fallthrough `else` — but the real hierarchy may include `Unmatched`, `Failed`, or other variants that need explicit branches. The compiler will enforce exhaustiveness; the spec just guarantees that any non-Success outcome maps to `NoMatch`. Confirm `TAG` is accessible in scope inside `DownloadManager` (it's a class-level companion in the existing code, so this should be a no-op — just verify).

Note: `Deferred` is mapped to `NoMatch` even though it has a different semantic in the sync-pipeline context. For this user-initiated path, deferral makes no sense (we'd be writing to `WAITING_FOR_LOSSLESS` for a track the user is actively listening to — confusing). `NoMatch` is the right user-facing outcome.

#### 2.2 `MusicRepository` — expose the upgrade method

Location: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`

Add a new method to the repository interface that the ViewModel calls:

```kotlin
/**
 * v0.9.18: user-initiated "Find in FLAC" upgrade. Delegates to
 * [DownloadManager.upgradeToLossless]. ViewModel layer maps the
 * result onto snackbar copy.
 */
suspend fun upgradeToLossless(track: Track): UpgradeResult =
    downloadManager.upgradeToLossless(track)
```

The repository pattern is already established for download operations in this codebase; the ViewModel doesn't directly touch `DownloadManager`.

#### 2.3 `NowPlayingViewModel` — surface action + result

Location: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`

```kotlin
/**
 * v0.9.18: upgrade the currently-playing track to FLAC if any
 * lossless source can serve it. Fire-and-forget — emits a "looking"
 * snackbar immediately, then a result snackbar when the resolve +
 * download completes.
 *
 * No-op when nothing is playing or when the current track is already
 * FLAC (UI hides the button in that case, but defensive guard here
 * in case state changes mid-tap).
 */
fun findInFlacForCurrentTrack() {
    val track = _uiState.value.currentTrack ?: return
    if (track.isFlac) return
    viewModelScope.launch {
        _userMessages.tryEmit("Looking for FLAC…")
        val result = musicRepository.upgradeToLossless(track)
        _userMessages.tryEmit(snackbarCopyFor(result))
    }
}
```

`snackbarCopyFor` is a pure top-level helper:

```kotlin
internal fun snackbarCopyFor(result: UpgradeResult): String = when (result) {
    UpgradeResult.Upgraded -> "Upgraded to FLAC"
    UpgradeResult.NoMatch -> "No lossless match found"
    UpgradeResult.Error -> "Couldn't check lossless sources"
}
```

The `track.isFlac` check is a property derived from the file format (existing field on the domain `Track` — likely something like `track.format == "flac"` or codec inspection).

**Phase 1 task (lands before the ViewModel work):** check `core/model/src/main/kotlin/com/stash/core/model/Track.kt` for an existing `isFlac` boolean accessor or equivalent. If absent, add it as an extension property:

```kotlin
val Track.isFlac: Boolean
    get() = format.equals("flac", ignoreCase = true)
```

(or the codec-inspection equivalent, depending on which property carries the format on the domain `Track`). Treat this as a discrete plan task so it isn't lost between the ViewModel reference (§2.3) and the UI reference (§3) — both consume the same accessor.

### 3. UI

Location: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`

In the existing AlertDialog at lines 136-198, prepend a fourth `OutlinedButton` above the current "Find a better match" button. The button is conditional on the current track NOT being FLAC:

```kotlin
val track = uiState.currentTrack
if (track != null && !track.isFlac) {
    androidx.compose.material3.OutlinedButton(
        onClick = {
            viewModel.findInFlacForCurrentTrack()
            showWrongMatchDialog = false
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.material3.Text("Find in FLAC")
    }
}
```

The button:
- Sits as the first option in the dialog (above the existing three).
- Uses `OutlinedButton` (matches the non-destructive existing buttons; the destructive "Delete and block forever" stays as the only filled `Button` with `error` color).
- Dismisses the dialog immediately on tap (matches existing behavior — fire-and-forget).

No other changes to `NowPlayingScreen.kt`. The dialog title stays "What's wrong with this song?" per the user's explicit de-prioritization.

### 4. Test surface (TDD)

Before any implementation:

| Test | Module | What it asserts |
|---|---|---|
| `NowPlayingViewModelTest.snackbarCopyFor` (parameterised) | `:feature:nowplaying` | All three `UpgradeResult` variants map to the right snackbar string. |
| `NowPlayingViewModelTest.findInFlacForCurrentTrack happy path` | `:feature:nowplaying` | When repository returns `Upgraded`, `_userMessages` emits "Looking for FLAC…" then "Upgraded to FLAC" in order. |
| `NowPlayingViewModelTest.findInFlacForCurrentTrack no-match path` | `:feature:nowplaying` | When repository returns `NoMatch`, emits "Looking for FLAC…" then "No lossless match found". |
| `NowPlayingViewModelTest.findInFlacForCurrentTrack error path` | `:feature:nowplaying` | When repository returns `Error`, emits "Looking for FLAC…" then "Couldn't check lossless sources". |
| `NowPlayingViewModelTest.findInFlacForCurrentTrack no-op when track is FLAC` | `:feature:nowplaying` | Defensive: if `currentTrack.isFlac == true`, repository is never called and no snackbar fires. |
| `DownloadManagerUpgradeTest.upgradeToLossless maps Success to Upgraded` | `:data:download` | `tryLosslessDownload` Success → `UpgradeResult.Upgraded`. |
| `DownloadManagerUpgradeTest.upgradeToLossless maps null to NoMatch` | `:data:download` | `tryLosslessDownload` null → `UpgradeResult.NoMatch`. |
| `DownloadManagerUpgradeTest.upgradeToLossless catches exceptions to Error` | `:data:download` | Thrown exception (e.g., registry threw) → `UpgradeResult.Error`. No exception escapes. |

No Compose UI test for the conditional button — the visibility logic is too thin to merit one (`!track.isFlac` is a single boolean check). Manual smoke verification on-device covers the rendering.

### 5. Edge cases

#### 5.1 Track changes mid-upgrade

If the user taps Find in FLAC, then immediately skips to the next track, the upgrade continues against the original track id (captured at tap time). The result snackbar fires regardless of what's currently playing — which is fine; the snackbar is informational, not action-bound.

#### 5.2 User toggles `youtubeFallbackEnabled` mid-upgrade

The upgrade path uses `forced = true`, which bypasses the global lossless toggle. The `youtubeFallbackEnabled` flag has no bearing on the upgrade flow — it only governs whether `DownloadManager` falls through to yt-dlp on a sync-pipeline null. Find in FLAC is a one-shot lossless-or-nothing query.

#### 5.3 Track is in `WAITING_FOR_LOSSLESS` when user taps Find in FLAC

This shouldn't happen — `WAITING_FOR_LOSSLESS` rows live in `download_queue` for tracks that are NOT yet downloaded. A track playing in Now Playing is by definition downloaded (has a file on disk). But if a race somehow surfaces this state, the upgrade is idempotent: a successful resolve+download writes a new file and updates the row; the `WAITING_FOR_LOSSLESS` queue entry is independently flipped to `PENDING` by `LosslessRetryWorker` on its next sweep. No conflict.

#### 5.4 Source-level rate limiting trips during an upgrade

If `AggregatorRateLimiter` denies the resolve call (circuit breaker tripped, refill exhausted), `tryLosslessDownload` returns null. The user sees "No lossless match found" — slightly misleading (a rate-limit isn't really "no match"), but acceptable for v0.9.18. Future: introduce `UpgradeResult.RateLimited` if user feedback warrants.

#### 5.5 Multiple Find in FLAC taps in quick succession

Each tap launches a new coroutine in `viewModelScope`. The `tryLosslessDownload` calls run serially against the registry's per-source mutex; a quick double-tap results in two sequential resolves, two snackbars. The second snackbar overwrites the first in Material3's snackbar queue — acceptable. No explicit debounce.

### 6. Versioning + ship

- `versionCode`: 55 → 56
- `versionName`: 0.9.17 → 0.9.18
- Schema: 22 (unchanged)
- New branch: `feat/find-in-flac`, worktree at `.worktrees/find-in-flac`. Per project memory: copy `local.properties` into the worktree.
- After merge to master, tag + push triggers the release workflow.

## Open questions

None blocking implementation. Possible follow-ups deferred to later releases:

- **Library long-press menu** ("Find in FLAC" available outside Now Playing). Worth adding if users ask.
- **Re-check on FLAC tracks** ("Re-check lossless source" — for the rare case where a higher-quality master appeared upstream). Worth adding if users ask.
- **`UpgradeResult.RateLimited`** distinct copy — only revisit if "No lossless match found" turns out to be misleading in practice.
- **Mid-playback swap** — only revisit if users find next-play upgrade unintuitive.
