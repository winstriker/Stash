package com.stash.data.lyrics.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.backfill.LyricsBackfillState
import com.stash.data.lyrics.source.LyricsQuery
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * v0.9.36 once-per-version backfill worker that drains the un-fetched
 * lyrics library — every track whose `lyrics_fetched_at` is NULL gets a
 * fetch attempt via [LyricsRepository.resolveAndStore].
 *
 * ## Loop shape
 *
 * `getTracksNeedingLyrics(limit=50)` is called repeatedly without an
 * offset. The offset is always 0 — each processed row leaves the
 * `WHERE lyrics_fetched_at IS NULL` result set as soon as the repository
 * stamps `lyrics_fetched_at` (with the success epoch on a hit, with the
 * `0L` confirmed-miss sentinel on a complete source-chain miss) OR as
 * soon as the [runCatching] fallback below stamps `0L` on an infra
 * failure. So the next batch query naturally returns the next 50
 * unprocessed rows, and the loop terminates when a batch comes back
 * empty. Mirror of v0.9.35's [com.stash.data.download.backfill.MetadataBackfillWorker].
 *
 * ## Failure handling
 *
 * Two outcomes both leave the row out of the result set:
 *  - `resolveAndStore` returned (success OR confirmed miss) — the
 *    repository already wrote the timestamp / `0L` sentinel.
 *  - `resolveAndStore` threw (network outage, transient I/O, etc.) —
 *    we stamp `0L` directly here so the row drops out of the WHERE
 *    predicate. This is an infra failure: we'd rather mark it as
 *    "tried and failed" and move on than burn the whole backfill on
 *    a single misbehaving row. Per-track retry is the post-download
 *    [LyricsFetchWorker]'s job; backfill is a single-pass sweep.
 *
 * ## `total` is a snapshot at worker start
 *
 * Captured once via `observeTracksNeedingLyricsCount().first()` before
 * the loop. New tracks that arrive in the NULL set mid-pass (e.g. the
 * user starts syncing during backfill) may make the banner render
 * `processed > total` (overshoot) or stall short; this is accepted per
 * spec §10 — re-reading the count each iteration would cause the banner
 * to "jump backward" when new work arrives, which is worse UX. The
 * worker still drains everything to completion because the loop's exit
 * condition is `batch.isEmpty()`, not `processed >= total`.
 *
 * ## Empty-library fast path
 *
 * When `total == 0` we call [LyricsBackfillState.markFinished] directly
 * (skipping [LyricsBackfillState.markStarted]) so the snapshot lands at
 * `FINISHED` instead of bouncing through `RUNNING`. The Home banner
 * treats `FINISHED` with `total == 0` as nothing to surface, so this
 * path produces no UI flash on installs that already have lyrics for
 * every track (e.g. a re-install after a successful previous backfill).
 */
@HiltWorker
class LyricsBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val lyricsRepository: LyricsRepository,
    private val backfillState: LyricsBackfillState,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val total = trackDao.observeTracksNeedingLyricsCount().first()
        if (total == 0) {
            backfillState.markFinished()
            return Result.success()
        }
        backfillState.markStarted(total)

        var processed = 0
        while (true) {
            // No OFFSET: every processed row leaves the
            // `lyrics_fetched_at IS NULL` set, so the next LIMIT 50
            // returns the next 50 unprocessed rows. See class KDoc.
            val batch = trackDao.getTracksNeedingLyrics(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                val query = LyricsQuery(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album.ifBlank { null },
                    albumArtist = track.albumArtist.ifBlank { null },
                    durationMs = track.durationMs.takeIf { it > 0 },
                    // `youtubeId` is already nullable on TrackEntity, so
                    // we propagate it as-is without a blank-check guard.
                    youtubeVideoId = track.youtubeId,
                )
                runCatching { lyricsRepository.resolveAndStore(query) }
                    .onFailure { e ->
                        Log.w(TAG, "resolveAndStore threw for trackId=${track.id}: ${e.message}")
                        runCatching { trackDao.setLyricsFetchedAt(track.id, 0L) }
                            .onFailure { dbErr ->
                                Log.w(
                                    TAG,
                                    "setLyricsFetchedAt(0L) failed for ${track.id}: ${dbErr.message}",
                                )
                            }
                    }
                processed++
                backfillState.publishProgress(processed, total)
            }
        }

        backfillState.markFinished()
        return Result.success()
    }

    companion object {
        private const val TAG = "LyricsBackfillWorker"
        // Memory per batch ≈ 50 × TrackEntity. Matches the v0.9.35
        // metadata-backfill budget; raise only after re-profiling.
        private const val BATCH_SIZE = 50
        const val UNIQUE_WORK_NAME = "lyrics_backfill"
    }
}
