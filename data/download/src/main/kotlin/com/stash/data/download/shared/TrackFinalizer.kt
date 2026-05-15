package com.stash.data.download.shared

import android.util.Log
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lossless.AudioFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared file-side finalisation for downloaded audio. Used by sync
 * ([com.stash.data.download.DownloadManager.tryLosslessDownload])
 * and search ([com.stash.data.download.search.SearchDownloadCoordinator]).
 *
 * Steps:
 *  1. Embed title/artist/album metadata into the file via ffmpeg.
 *  2. Move the temp file to the canonical library path.
 *  3. Probe the on-disk file for codec/bitrate/sample-rate/bit-depth.
 *  4. Measure integrated loudness (LUFS) + true peak via ffmpeg's
 *     `ebur128` filter so the player can apply per-track makeup gain.
 *
 * Returns [FinalizeResult]. **No DB writes** — caller does its own
 * row insert/update so sync preserves spotifyUri/isrc/album/explicit
 * and search runs `linkTrackToDownloadsMix` on its own path. The
 * loudness measurement is surfaced via [FinalizeResult.Success.loudness]
 * so callers can persist it on the same row write they were already
 * doing.
 */
@Singleton
class TrackFinalizer @Inject constructor(
    private val metadataEmbedder: MetadataEmbedder,
    private val fileOrganizer: FileOrganizer,
    private val audioExtractor: AudioDurationExtractor,
    private val loudnessMeasurer: LoudnessMeasurer,
) {
    /**
     * Embed metadata, commit to library, probe. Caller passes a [Track]
     * built from whatever shape they have ([com.stash.core.model.Track]
     * for sync; a stub built from a TrackItem for search).
     *
     * Embedding is non-fatal — file remains playable on failure.
     * The probe in [FinalizeResult.Success.meta] may be null if the
     * committed file cannot be opened by [MediaMetadataRetriever].
     */
    suspend fun finalizeFile(
        sourceFile: File,
        track: Track,
        format: AudioFormat,
        embedMetadata: Boolean = true,
    ): FinalizeResult = runCatching {
        if (embedMetadata) {
            runCatching { metadataEmbedder.embedMetadata(sourceFile, track) }
                .onFailure { e -> Log.w(TAG, "metadata embed failed: ${e.message}") }
        }
        val committed: CommittedTrack = fileOrganizer.commitDownload(
            tempFile = sourceFile,
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            title = track.title,
            format = format.codec.ifBlank { "flac" },
        )
        val meta: AudioMetadata? = audioExtractor.extract(committed.filePath)
        // Loudness measurement is non-fatal: if ffmpeg's ebur128 filter
        // crashes or the Summary block is unparseable, the file is still
        // playable, the caller just stores NULL LUFS and the backfill worker
        // picks the row up later.
        val loudness = when (
            val r = loudnessMeasurer.measure(File(committed.filePath))
        ) {
            is LoudnessMeasurer.Result.Success -> r
            is LoudnessMeasurer.Result.Failed -> {
                Log.w(
                    TAG,
                    "loudness measurement failed for ${committed.filePath}: ${r.reason}",
                )
                null
            }
        }
        FinalizeResult.Success(committed, meta, loudness)
    }.getOrElse { e ->
        FinalizeResult.Failed(e.message ?: "finalize failed")
    }

    sealed interface FinalizeResult {
        /**
         * File was successfully embedded, committed, and probed.
         * [meta] is null only if [AudioDurationExtractor] could not
         * open the committed file (rare; file is still on disk and playable).
         * [loudness] is null when [LoudnessMeasurer] could not parse a
         * valid LUFS value from the file (too short, all-silence, ffmpeg
         * crash); callers persist NULL so the backfill worker retries later.
         */
        data class Success(
            val committed: CommittedTrack,
            val meta: AudioMetadata?,
            val loudness: LoudnessMeasurer.Result.Success? = null,
        ) : FinalizeResult

        /** A fatal step (commit) failed; the temp file has been deleted by the caller. */
        data class Failed(val message: String) : FinalizeResult
    }

    companion object {
        private const val TAG = "TrackFinalizer"
    }
}
