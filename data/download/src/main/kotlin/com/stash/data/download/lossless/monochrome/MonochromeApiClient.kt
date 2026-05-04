package com.stash.data.download.lossless.monochrome

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HTTP client for `https://api.monochrome.tf` (uimaxbai/hifi-api, MIT,
 * fork of binimum/hifi-api). Serves Tidal's catalog with the operator-
 * funded model — no auth required from callers.
 *
 * The API is unauthenticated for Stash's purposes, but some hosted
 * instances may filter by Origin / User-Agent. We send a stable
 * User-Agent to avoid being misidentified as bot traffic.
 *
 * Every response uses a `{"data": ...}` envelope; the client unwraps it
 * and throws [MonochromeApiException] on non-2xx, empty `data`, or parse
 * failure so callers can branch on a single signal.
 */
@Singleton
class MonochromeApiClient @Inject constructor(
    sharedClient: OkHttpClient,
) {

    /**
     * Test seam (test-only assignment). Derived from the shared client to
     * reuse its connection pool, dispatcher, TLS config. No interceptors
     * needed — the API is unauthenticated for callers.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder().build()

    /**
     * Test seam: tests assign a MockWebServer URL before calling any
     * endpoint. Production paths leave this on [DEFAULT_BASE_URL].
     * Kept off the constructor signature so mixing `@Inject` with a
     * default-valued parameter doesn't generate two JVM constructors
     * (which Hilt would reject as ambiguous injection sites).
     */
    internal var baseUrl: String = DEFAULT_BASE_URL

    /** Test seam — override for parsing strictness checks. */
    internal var json: Json = DEFAULT_JSON

    /**
     * Search the Monochrome-proxied Tidal catalog. [query] is free text
     * (artist + title); the API returns up to ~25 candidates.
     *
     * Throws [MonochromeApiException] on network/parse failure.
     */
    suspend fun search(query: String): TidalSearchResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        executeAndParse(request, TidalSearchResponse.serializer())
    }

    /**
     * Resolve a Tidal track ID to a stream manifest. Quality is hardcoded
     * to `"LOSSLESS"` — Stash never wants HI_RES_LOSSLESS (Widevine) or
     * HIGH (lossy AAC).
     *
     * Throws [MonochromeApiException] on network/parse failure. Returns
     * a [TidalTrackResponse] whose `data.manifest` must be base64-decoded
     * via [decodeManifest] to get the actual download URLs.
     */
    suspend fun track(id: Long, quality: String = "LOSSLESS"): TidalTrackResponse =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/track/".toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("quality", quality)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            executeAndParse(request, TidalTrackResponse.serializer())
        }

    // ── Internals ───────────────────────────────────────────────────────

    private fun <T> executeAndParse(
        request: Request,
        serializer: KSerializer<T>,
    ): T {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw MonochromeApiException(
                    status = response.code,
                    message = "HTTP ${response.code} from ${request.url}: ${body.take(200)}",
                )
            }

            return try {
                json.decodeFromString(serializer, body)
            } catch (e: Exception) {
                throw MonochromeApiException(
                    status = response.code,
                    message = "Parse failed for ${request.url}: ${e.message}",
                    cause = e,
                )
            }
        }
    }

    companion object {
        /**
         * Default origin. The public instance at api.monochrome.tf runs
         * uimaxbai/hifi-api; self-hosted instances can be targeted by
         * overriding [baseUrl] in tests or via future settings plumbing.
         */
        const val DEFAULT_BASE_URL: String = "https://api.monochrome.tf"

        private const val USER_AGENT: String =
            "Stash-Android/1.0 (+https://github.com/rawnaldclark/Stash)"

        /**
         * Tolerant Json instance — the API passes Tidal responses through,
         * and Tidal includes many fields we don't model. Without
         * `ignoreUnknownKeys` deserialisation would fail on every call.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

/**
 * Thrown by [MonochromeApiClient] on any non-2xx or parse failure. The
 * [status] field lets callers branch on 429 vs 401/403 vs 5xx for
 * rate-limiter signaling.
 *
 * 429 keeps its specific meaning so the rate limiter can distinguish
 * "you're calling too fast" from "the source is broken".
 */
class MonochromeApiException(
    val status: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
