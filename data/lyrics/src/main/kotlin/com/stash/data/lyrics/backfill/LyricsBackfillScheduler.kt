package com.stash.data.lyrics.backfill

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.stash.data.download.backfill.BackfillVersionTracker
import com.stash.data.lyrics.worker.LyricsBackfillWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-enqueues [LyricsBackfillWorker] exactly once per `versionCode`.
 *
 * Called from [com.stash.app.StashApplication.onCreate] on a background
 * coroutine, immediately after the v0.9.35 metadata scheduler. Idempotent:
 * if the version has already been enqueued, the gate short-circuits
 * before WorkManager is touched.
 *
 * ## WorkManager DI shape
 * WorkManager is not Hilt-injectable in this project — every existing
 * usage (see `MetadataBackfillScheduler`, `LosslessRetryScheduler`) goes
 * through `WorkManager.getInstance(context)`. We follow the same pattern
 * here so the lyrics scheduler doesn't introduce a one-off binding the
 * rest of the codebase would have to follow.
 *
 * ## Crash-resilience ordering
 * Identical to the metadata scheduler:
 *  1. `shouldRunForCurrentVersion(LYRICS_BACKFILL_KEY)` returns true on
 *     the first launch after each version bump.
 *  2. `enqueueUniqueWork(KEEP)` is idempotent — if a previous launch
 *     enqueued and the process died before marking, this is a no-op.
 *  3. `markEnqueuedForCurrentVersion(LYRICS_BACKFILL_KEY)` flips the
 *     gate so subsequent launches at the same version skip the enqueue.
 *
 * The two backfills share the `metadata_backfill_state.preferences_pb`
 * DataStore file under disjoint preference keys — see
 * [BackfillVersionTracker]'s KDoc — so a single read/write per launch
 * covers both.
 *
 * ## Constraints
 * Only [NetworkType.CONNECTED] is required — both LRCLIB (HTTP) and the
 * InnerTube fallback need the network, but neither is heavy enough to
 * warrant charging or unmetered. Expedited with non-expedited fallback
 * matches the metadata scheduler so the two backfills are scheduled with
 * comparable priority on the first post-upgrade launch.
 */
@Singleton
class LyricsBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versionTracker: BackfillVersionTracker,
) {
    suspend fun scheduleIfNeeded() {
        if (!versionTracker.shouldRunForCurrentVersion(LYRICS_BACKFILL_KEY)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            LyricsBackfillWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LyricsBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
        versionTracker.markEnqueuedForCurrentVersion(LYRICS_BACKFILL_KEY)
    }

    private companion object {
        /**
         * Stable preference-key string for the lyrics-backfill enqueue gate.
         *
         * MUST remain `"lyrics_backfill_enqueued_for_version"` — renaming
         * it would re-fire the lyrics backfill on every existing install
         * the next time they launch a new binary. Disjoint from the
         * metadata key (`"backfill_enqueued_for_version"`) so the two
         * backfills can coexist in the shared DataStore file without
         * tripping each other.
         */
        const val LYRICS_BACKFILL_KEY = "lyrics_backfill_enqueued_for_version"
    }
}
