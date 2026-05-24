package com.stash.feature.home.banner

import com.stash.data.lyrics.backfill.LyricsBackfillSnapshot
import com.stash.data.lyrics.backfill.State

/**
 * v0.9.36 counterpart to [MetadataBackfillBannerState]: discrete states
 * for the Home "Fetching lyrics" banner. [Hidden] is the dominant state
 * in the steady-state install — only rendered while the lyrics-backfill
 * worker is actively processing rows, and for a short "Done" pulse after
 * completion.
 *
 * Kept structurally identical to [MetadataBackfillBannerState] (Hidden /
 * Running / Finished) but without the SAF-skipped counter — lyrics
 * sidecars go through [com.stash.data.lyrics.sidecar.LyricsSidecarWriter]
 * which handles SAF tree URIs in place, so the backfill loop has no
 * SAF-skipped subset to surface.
 */
sealed interface LyricsBackfillBannerState {
    data object Hidden : LyricsBackfillBannerState
    data class Running(val processed: Int, val total: Int) : LyricsBackfillBannerState
    data class Finished(val total: Int) : LyricsBackfillBannerState
}

/**
 * Pure mapping from [LyricsBackfillSnapshot] into the banner sealed
 * type. Extracted so [com.stash.feature.home.HomeViewModel] doesn't
 * carry the branching logic inline.
 *
 * Mirrors [metadataBackfillBannerStateFor]'s semantics:
 *  - IDLE → Hidden (steady state)
 *  - RUNNING with `total > 0` → Running(processed, total)
 *  - RUNNING with `total == 0` → Hidden (no work to show)
 *  - FINISHED with `total > 0` → Finished(total) ("Done" pulse)
 *  - FINISHED with `total == 0` → Hidden (no successes to announce)
 */
internal fun lyricsBackfillBannerStateFor(snapshot: LyricsBackfillSnapshot): LyricsBackfillBannerState = when {
    snapshot.state == State.RUNNING && snapshot.total > 0 ->
        LyricsBackfillBannerState.Running(snapshot.processed, snapshot.total)
    snapshot.state == State.FINISHED && snapshot.total > 0 ->
        LyricsBackfillBannerState.Finished(snapshot.total)
    else -> LyricsBackfillBannerState.Hidden
}
