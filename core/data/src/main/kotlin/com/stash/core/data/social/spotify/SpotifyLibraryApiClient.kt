package com.stash.core.data.social.spotify

import android.util.Log
import com.stash.core.auth.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

class SpotifyAuthException : Exception("Spotify access token rejected")
class SpotifyRateLimitException(val retryAfterSeconds: Int?) :
    Exception("Spotify rate-limited; retry after $retryAfterSeconds seconds")
class SpotifyApiException(val code: Int, val body: String?) :
    Exception("Spotify API error $code: $body")

/**
 * v0.9.13: Wraps Spotify Web API's "Save Tracks for User" endpoint.
 *
 * Public API: `PUT /v1/me/tracks?ids=<id1>,<id2>,...`
 * Scope: `user-library-modify` — covered by the existing sp_dc-derived
 *   web-player token (Spotify Web's Like button uses the same scope).
 *   No Premium requirement.
 * Idempotent: re-saving an already-saved track is a no-op (no error).
 * Batch limit: 50 IDs per request.
 *
 * Auth: routes through TokenManager.getSpotifyAccessToken (auto-refresh)
 * and TokenManager.forceRefreshSpotifyAccessToken (401 invalidate path).
 * NO direct dependency on SpotifyAuthManager.
 */
@Singleton
class SpotifyLibraryApiClient @Inject constructor(
    private val tokenManager: TokenManager,
    private val httpClient: OkHttpClient,
) {
    suspend fun saveTracks(spotifyUris: List<String>) = withContext(Dispatchers.IO) {
        require(spotifyUris.isNotEmpty()) { "saveTracks: empty list" }
        require(spotifyUris.size <= 50) { "Spotify caps at 50 IDs per call (got ${spotifyUris.size})" }

        val token = tokenManager.getSpotifyAccessToken()
            ?: throw SpotifyAuthException()

        // Strip "spotify:track:" prefix; Spotify accepts bare IDs.
        val ids = spotifyUris.joinToString(",") { it.removePrefix("spotify:track:") }
        val url = "https://api.spotify.com/v1/me/tracks?ids=$ids"

        // PUT /v1/me/tracks expects an empty body (IDs are in the query).
        val emptyBody = ByteArray(0).toRequestBody(null)
        val request = Request.Builder()
            .url(url)
            .put(emptyBody)
            .header("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                200, 201, 204 -> {
                    Log.d(TAG, "saved ${spotifyUris.size} track(s) to Spotify")
                }
                401 -> {
                    Log.w(TAG, "401 — invalidating Spotify token")
                    tokenManager.forceRefreshSpotifyAccessToken()
                    throw SpotifyAuthException()
                }
                429 -> {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull()
                    throw SpotifyRateLimitException(retryAfter)
                }
                else -> {
                    val body = response.body?.string()
                    throw SpotifyApiException(response.code, body)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SpotifyLibraryApiClient"
    }
}
