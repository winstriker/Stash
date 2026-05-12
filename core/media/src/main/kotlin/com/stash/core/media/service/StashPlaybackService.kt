package com.stash.core.media.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.media.R
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.StashRenderersFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background playback service that hosts an [ExoPlayer] and exposes a [MediaSession]
 * for media-controller clients (e.g. system notification, Bluetooth, Android Auto).
 *
 * Custom session commands:
 * - [COMMAND_TOGGLE_SHUFFLE] -- toggles shuffle mode on/off
 * - [COMMAND_CYCLE_REPEAT]   -- cycles repeat mode: OFF -> ALL -> ONE -> OFF
 * - [COMMAND_TOGGLE_LIKE]    -- toggles Stash Liked Songs membership for the
 *   currently playing track. Surfaced as a heart icon in the system
 *   notification (expanded view) so the user can like/unlike from the
 *   lockscreen without opening Now Playing.
 */
@AndroidEntryPoint
class StashPlaybackService : MediaSessionService() {

    @Inject lateinit var eqController: EqController
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var stashLikedRepository: StashLikedPlaylistRepository

    companion object {
        /** Custom command action for toggling shuffle mode. */
        const val COMMAND_TOGGLE_SHUFFLE = "com.stash.TOGGLE_SHUFFLE"

        /** Custom command action for cycling repeat mode. */
        const val COMMAND_CYCLE_REPEAT = "com.stash.CYCLE_REPEAT"

        /** Custom command action for toggling Stash Liked on the current track. */
        const val COMMAND_TOGGLE_LIKE = "com.stash.TOGGLE_LIKE"
    }

    private var mediaSession: MediaSession? = null

    // Service-scoped CoroutineScope for the like-state observer + toggle
    // suspending calls. Cancelled in onDestroy so the observer doesn't leak
    // when the service stops.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var likeObserverJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Generate an explicit audio session ID BEFORE building the player.
        // ExoPlayer.audioSessionId returns 0 (global mix) by default until playback starts,
        // which causes audio effect creation to fail with Error -3.
        // By generating our own ID and passing it to the builder, the effects can attach immediately.
        // Generate a dedicated audio session ID so audio effects can attach immediately.
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        val audioSessionId = audioManager.generateAudioSessionId()
        android.util.Log.i("StashPlayback", "Generated audio session ID: $audioSessionId")

        // Optimised buffer for local music playback: larger buffers eliminate
        // micro-stutters from storage I/O; lower playback thresholds keep
        // start-up snappy.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(StashRenderersFactory(this, eqController))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Set the pre-generated session ID on the player
        player.audioSessionId = audioSessionId

        // Set session activity so tapping the media notification opens the app.
        // The intent targets the app's launcher activity via the package's launch intent.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                this, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val sessionBuilder = MediaSession.Builder(this, player)
            .setCallback(StashSessionCallback())
        if (sessionActivity != null) {
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        val session = sessionBuilder.build()

        mediaSession = session

        // Heart-button wiring: rebuild the notification's custom layout
        // whenever the playing track changes so the icon (filled vs.
        // outlined) reflects the new track's Stash-Liked state. The
        // per-track observe loop inside [refreshLikeButton] keeps the
        // icon in sync if the user toggles like from elsewhere
        // (Now Playing, Library, etc.) while audio is playing.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshLikeButton()
            }
        })
        refreshLikeButton()
    }

    /**
     * Cancels any existing like-state observer and starts a new one for
     * the player's current media item. Each emission rebuilds the
     * MediaSession's custom layout with the appropriate heart icon.
     * When there's no current track the custom layout is cleared so the
     * notification doesn't render a stale heart.
     */
    @OptIn(UnstableApi::class)
    private fun refreshLikeButton() {
        likeObserverJob?.cancel()
        val session = mediaSession ?: return
        val trackId = session.player.currentMediaItem?.mediaId?.toLongOrNull()
        if (trackId == null) {
            session.setCustomLayout(ImmutableList.of())
            return
        }
        likeObserverJob = serviceScope.launch {
            trackDao.observeLikeState(trackId).collect { state ->
                val isLiked = state?.stashLikedAt != null
                session.setCustomLayout(ImmutableList.of(buildLikeButton(isLiked)))
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildLikeButton(isLiked: Boolean): CommandButton {
        val iconRes = if (isLiked) {
            R.drawable.ic_notification_heart_filled
        } else {
            R.drawable.ic_notification_heart_outlined
        }
        val displayNameRes = if (isLiked) {
            R.string.notification_action_unlike
        } else {
            R.string.notification_action_like
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_TOGGLE_LIKE, android.os.Bundle.EMPTY),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        likeObserverJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ---- MediaSession.Callback ----

    private inner class StashSessionCallback : MediaSession.Callback {

        /**
         * Resolve media items from request metadata URIs so that the controller
         * can set items by URI rather than providing fully-resolved [MediaItem]s.
         *
         * Only allows file://, android.resource://, and content:// URI schemes
         * to prevent external controllers from injecting arbitrary network URIs.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                val uri = item.requestMetadata.mediaUri ?: return@mapNotNull null
                val scheme = uri.scheme
                if (scheme != "file" && scheme != "android.resource" && scheme != "content") {
                    return@mapNotNull null
                }
                item.buildUpon()
                    .setUri(uri)
                    .build()
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands = listOf(
                SessionCommand(COMMAND_TOGGLE_SHUFFLE, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_CYCLE_REPEAT, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_TOGGLE_LIKE, /* extras = */ android.os.Bundle.EMPTY),
            )
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            customCommands.forEach { sessionCommands.add(it) }

            // Default availablePlayerCommands omits COMMAND_CHANGE_MEDIA_ITEMS,
            // which is what addMediaItem / removeMediaItem / moveMediaItem
            // require. Without explicitly granting full player commands here,
            // controller.addMediaItem(...) silently no-ops — the item never
            // reaches the underlying ExoPlayer's timeline. This is what made
            // "Play Next" and "Add to Queue" appear broken when a queue
            // already existed.
            //
            // Granting all commands is safe: this MediaSession is internal-
            // only (no third-party controllers connect to it).
            val playerCommands = Player.Commands.Builder().addAllCommands().build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_TOGGLE_SHUFFLE -> {
                    val player = session.player
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                COMMAND_CYCLE_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                COMMAND_TOGGLE_LIKE -> {
                    // Read the mediaId on the caller thread (Player APIs are
                    // main-thread-only) but do the DAO/repo work in the
                    // service scope so the callback returns immediately.
                    val trackId = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                    if (trackId != null) {
                        serviceScope.launch {
                            val current = trackDao.observeLikeState(trackId).firstOrNull()
                            if (current?.stashLikedAt != null) {
                                stashLikedRepository.remove(trackId)
                            } else {
                                stashLikedRepository.add(trackId)
                            }
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
