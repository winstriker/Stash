package com.stash.core.data.social

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.spotify.SpotifyLibraryApiClient
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.social.ytmusic.YtMusicLibraryApiClient
import com.stash.core.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

class NoSpotifyUriException : Exception("Track has no spotifyUri")
class NoYouTubeIdException : Exception("Track has no youtubeId")

/**
 * v0.9.13: Stateless fan-out for Like operations. Both auto-save
 * (single-destination [Destination.SPOTIFY]) and manual heart
 * (caller-configured set) funnel through this single entry point.
 *
 * Per-destination dedup: skips destinations where the corresponding
 * `*_saved_at` timestamp is already set on the track. Spotify's
 * `PUT /v1/me/tracks` is idempotent at the API level too — even a
 * spurious second call would be safe — but the dedup avoids
 * unnecessary network round-trips.
 *
 * Parallelism: three destinations fire concurrently. Total time =
 * slowest single-destination time, not sum.
 */
@Singleton
class LikeDestinationDispatcher @Inject constructor(
    private val spotifyLibraryClient: SpotifyLibraryApiClient,
    private val ytMusicLibraryClient: YtMusicLibraryApiClient,
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val trackDao: TrackDao,
) {
    suspend fun like(
        track: Track,
        destinations: Set<Destination>,
    ): Map<Destination, Result<Unit>> = coroutineScope {
        if (destinations.isEmpty()) return@coroutineScope emptyMap()
        destinations.associateWith { dest ->
            async { fireDestination(track, dest) }
        }.mapValues { it.value.await() }
    }

    private suspend fun fireDestination(track: Track, dest: Destination): Result<Unit> {
        if (alreadySaved(track, dest)) {
            Log.d(TAG, "skip $dest for track ${track.id} — already saved")
            return Result.success(Unit)
        }

        return runCatching {
            when (dest) {
                Destination.STASH -> {
                    stashLikedRepository.add(track.id)
                }
                Destination.SPOTIFY -> {
                    val uri = track.spotifyUri ?: throw NoSpotifyUriException()
                    spotifyLibraryClient.saveTracks(listOf(uri))
                    trackDao.markSpotifySaved(track.id, System.currentTimeMillis())
                }
                Destination.YT_MUSIC -> {
                    val videoId = track.youtubeId ?: throw NoYouTubeIdException()
                    ytMusicLibraryClient.likeVideo(videoId)
                    trackDao.markYtMusicSaved(track.id, System.currentTimeMillis())
                }
            }
        }
    }

    private fun alreadySaved(track: Track, dest: Destination): Boolean = when (dest) {
        Destination.STASH -> track.stashLikedAt != null
        Destination.SPOTIFY -> track.spotifySavedAt != null
        Destination.YT_MUSIC -> track.ytMusicSavedAt != null
    }

    companion object {
        private const val TAG = "LikeDestinationDispatcher"
    }
}
