package com.stash.data.download.lossless.kennyy

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzApiException
import com.stash.data.download.lossless.qobuz.QobuzQuality
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.qobuz.QobuzTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LosslessSource] backed by the Qobuz catalog via the kennyy.com.br
 * public proxy (`qobuz.kennyy.com.br/api`). Searches for the requested
 * track, scores candidates by ISRC / title / artist / duration agreement,
 * and resolves the best match to a signed FLAC download URL.
 *
 * kennyy.com.br is a near-clone of qobuz.squid.wtf (same Qobuz-DL
 * Next.js codebase, different operator). Critically: **no captcha gate**
 * — the download-music endpoint is openly accessible without any cookie.
 * As a result this source has no captcha-expired branch and no
 * [com.stash.data.download.lossless.squid.CaptchaExpiredNotifier] dependency.
 *
 * Scoring logic is identical to [QobuzSource]; normalization and
 * similarity helpers are shared directly from [QobuzSource] companion
 * (same module, internal visibility). A v0.9.11 refactor will extract
 * them to a shared helpers object; for now the in-package coupling
 * is accepted.
 */
@Singleton
class KennyySource @Inject constructor(
    private val apiClient: KennyyApiClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Qobuz (kennyy.com.br)"

    override suspend fun isEnabled(): Boolean {
        // No credentials gate — the only reason to skip this source is
        // the circuit breaker.
        return !rateLimiter.stateOf(id).isCircuitBroken
    }

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        // 1. Search kennyy.com.br for candidates. ISRC is Qobuz's best
        // index key — when we have one, send it as the query directly.
        val searchTerm = query.isrc ?: "${query.artist} ${query.title}"
        val searchData = callLimited { apiClient.search(searchTerm) }
            ?: return null

        val candidates = searchData.tracks?.items.orEmpty()
        if (candidates.isEmpty()) return null

        // 2. Score and pick the best candidate that crosses the
        // confidence threshold.
        val scored = candidates.map { it to confidence(query, it) }
        val best = scored
            .filter { it.second >= MIN_CONFIDENCE }
            .maxByOrNull { it.second }

        if (best == null) {
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "no candidate above threshold ($MIN_CONFIDENCE) for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.performer?.name}']"
                    },
            )
            return null
        }

        // 3. Resolve to a signed download URL. kennyy.com.br returns 403
        // when the track is non-streamable; callLimited returns null so
        // we fall through to the next source cleanly.
        val requestedQuality = QobuzQuality.FLAC_HIRES_192
        val download = callLimited {
            apiClient.getFileUrl(best.first.id, requestedQuality)
        } ?: return null

        if (download.url.isNullOrEmpty()) {
            Log.d(TAG, "download-music returned empty url for ${best.first.id}")
            return null
        }

        // Album art — prefer `large` (~600px); fall back to `thumbnail`
        // then `small` so a thinly-populated catalog row still produces
        // something.
        val albumImage = best.first.album?.image
        val artUrl = albumImage?.large
            ?: albumImage?.thumbnail
            ?: albumImage?.small

        return SourceResult(
            sourceId = id,
            downloadUrl = download.url,
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = if (requestedQuality == QobuzQuality.MP3_320) "mp3" else "flac",
                bitrateKbps = 0,
                sampleRateHz = (best.first.maximumSamplingRate * 1000f).toInt(),
                bitsPerSample = best.first.maximumBitDepth,
            ),
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
            coverArtUrl = artUrl,
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Wraps an API call with rate-limiter bookkeeping. Returns null on
     * any failure mode (rate-limit denial, exception, circuit-break)
     * so callers can simply `?: return null` to skip cleanly.
     *
     * No captcha-expired branch — kennyy.com.br has no captcha gate.
     * 429 → reportRateLimited; all other exceptions → reportFailure.
     */
    private suspend fun <T> callLimited(block: suspend () -> T): T? {
        if (!rateLimiter.acquire(id)) return null
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: QobuzApiException) {
            when {
                e.status == 429 -> rateLimiter.reportRateLimited(id)
                else -> rateLimiter.reportFailure(id)
            }
            Log.w(TAG, "kennyy.com.br API call failed: $e")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "kennyy.com.br call threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. ISRC equality short-circuits to
     * 0.95 — same recording, same master, by definition. Otherwise
     * combines token-overlap on title and artist with a duration
     * penalty that downweights mismatched cuts (live, extended, edits)
     * without hard-rejecting them.
     *
     * Normalization and similarity helpers are shared from [QobuzSource]
     * companion (internal to the same Gradle module). A v0.9.11 refactor
     * will extract them to a dedicated helpers file.
     */
    private fun confidence(query: TrackQuery, candidate: QobuzTrack): Float {
        if (!candidate.streamable) return 0f

        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = QobuzSource.jaccard(
            QobuzSource.normalize(query.title),
            QobuzSource.normalize(candidate.title),
        )
        val artistSim = QobuzSource.artistSimilarity(
            QobuzSource.normalize(query.artist),
            QobuzSource.normalize(candidate.performer?.name.orEmpty()),
        )

        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f
                drift < 0.10 -> 0.85f
                drift < 0.20 -> 0.6f
                else -> 0.3f
            }
        }

        return (titleSim * artistSim * durationFactor)
    }

    companion object {
        /** Per LosslessSource KDoc convention: `kennyy_<catalog>`. */
        const val SOURCE_ID = "kennyy_qobuz"
        private const val TAG = "KennyySource"

        /** Threshold below which a candidate is rejected outright. */
        private const val MIN_CONFIDENCE = 0.5f
    }
}
