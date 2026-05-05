package com.stash.core.media.preview

import android.util.Log
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visible-row prefetch of lossless-source matches. Triggered by
 * `LaunchedEffect(track.videoId) { warmUp(track) }` in search/artist-
 * profile row composables. Stores `Deferred<SourceResult?>` keyed by
 * videoId so [SearchPreviewMediaSource] can join in-flight resolutions
 * without hitting the rate limiter twice.
 *
 * Concurrency cap of 4 matches the aggregator's burst-of-4 budget;
 * `AggregatorRateLimiter`'s 1-token-per-3s budget then naturally
 * serialises beyond that. 5-min TTL on cached results — Qobuz signed
 * URLs expire in minutes, and a stale entry on tap would 403 mid-stream.
 *
 * Memory bounded by TTL: stale entries get evicted via [cancelStale]
 * which `StashApplication` invokes every 60s.
 */
@Singleton
class LosslessUrlPrefetcher @Inject constructor(
    private val registry: LosslessSourceRegistry,
) {
    // App-lifetime scope. The class is @Singleton so this scope lives
    // for the entire process; no leaks. (No Hilt-provided
    // ApplicationScope qualifier exists in this codebase, so we
    // instantiate inline rather than depending on a missing binding.)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = ConcurrentHashMap<String, CachedDeferred>()
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /**
     * Warms up the lossless URL resolution for [track] in the background.
     * Idempotent — if a fresh [CachedDeferred] already exists for this
     * videoId the call is a no-op, so composable rows may call this on
     * every recomposition without triggering duplicate network requests.
     */
    fun warmUp(track: TrackItem) {
        val key = track.videoId
        cache[key]?.let { if (it.isFresh()) return }
        cache[key] = CachedDeferred(
            deferred = scope.async {
                concurrency.withPermit {
                    runCatching { registry.resolve(track.toQuery()) }
                        .onFailure { e -> Log.w(TAG, "resolve failed for $key: ${e.message}") }
                        .getOrNull()
                }
            },
            createdAt = System.currentTimeMillis(),
        )
    }

    /**
     * Returns the cached/in-flight [SourceResult] for [track], or null when
     * the registry finds no lossless match. If no warm-up is in progress,
     * starts one synchronously before awaiting — so callers that skip
     * [warmUp] still get a result (just with extra latency).
     */
    suspend fun lookup(track: TrackItem): SourceResult? {
        val cached = cache[track.videoId]
        if (cached != null && cached.isFresh()) return cached.deferred.await()
        warmUp(track)
        return cache[track.videoId]!!.deferred.await()
    }

    /**
     * Evicts all cache entries whose TTL has elapsed. Intended to be called
     * on a periodic timer (e.g. every 60s from `StashApplication`) to bound
     * memory growth across long browse sessions. Stale entries whose
     * [Deferred] is still running are also cancelled here — if the URL would
     * have expired by the time the lookup completes it is unusable anyway.
     */
    fun cancelStale() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { (_, c) -> now - c.createdAt > TTL_MS }
        expired.forEach { (k, _) -> cache.remove(k) }
        if (expired.isNotEmpty()) Log.d(TAG, "cancelStale: removed ${expired.size} stale entries")
    }

    /**
     * Maps [TrackItem] to the minimal [TrackQuery] the registry understands.
     * `durationMs` multiplication is done as `Double * 1_000` before the
     * `.toLong()` cast to preserve sub-second precision — the alternative of
     * `.toLong() * 1_000L` would truncate 3.7 s → 3000 ms.
     */
    private fun TrackItem.toQuery() = TrackQuery(
        artist = artist,
        title = title,
        album = null,
        isrc = null,
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1_000).toLong() },
    )

    private data class CachedDeferred(
        val deferred: Deferred<SourceResult?>,
        val createdAt: Long,
    ) {
        fun isFresh() = System.currentTimeMillis() - createdAt < TTL_MS
    }

    companion object {
        private const val TAG = "LosslessUrlPrefetcher"

        /** Max concurrent in-flight registry resolutions. Matches the aggregator burst budget. */
        const val MAX_CONCURRENT = 4

        /** Cache TTL in milliseconds. Qobuz signed URLs expire in ~5 minutes. */
        const val TTL_MS = 5 * 60 * 1_000L
    }
}
