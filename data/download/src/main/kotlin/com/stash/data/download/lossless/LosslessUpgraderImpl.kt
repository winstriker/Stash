package com.stash.data.download.lossless

import android.util.Log
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Now Playing dialog to [DownloadManager.tryLosslessDownload].
 * Maps the nullable result onto the user-facing [UpgradeResult] tri-state.
 *
 * Conservative mapping: any non-Success outcome becomes [NoMatch].
 * The user doesn't need to distinguish "registry returned null" from
 * "lossless URL came back 404" — both are "no FLAC for you right
 * now." Thrown exceptions become [Error] so the snackbar can say
 * "Couldn't check lossless sources" rather than the misleading
 * "no match."
 */
@Singleton
class LosslessUpgraderImpl @Inject constructor(
    private val downloadManager: DownloadManager,
) : LosslessUpgrader {

    override suspend fun upgradeToLossless(track: Track): UpgradeResult = runCatching {
        when (downloadManager.tryLosslessDownload(track, forced = true)) {
            is TrackDownloadResult.Success -> UpgradeResult.Upgraded
            null,
            is TrackDownloadResult.Unmatched,
            is TrackDownloadResult.Failed,
            TrackDownloadResult.Deferred -> UpgradeResult.NoMatch
        }
    }.getOrElse { e ->
        Log.w(TAG, "upgradeToLossless threw for ${track.id}", e)
        UpgradeResult.Error
    }

    private companion object {
        const val TAG = "LosslessUpgrader"
    }
}
