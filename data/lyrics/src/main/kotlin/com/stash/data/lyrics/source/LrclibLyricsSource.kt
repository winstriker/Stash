package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import com.stash.data.lyrics.di.LrclibBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrclibLyricsSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appVersion: AppVersionProvider,
    // Qualified so Hilt can resolve the SingletonComponent String binding
    // without colliding with other module-level @Provides String. Default
    // value is preserved for unit-test construction (see LrclibLyricsSourceTest)
    // — Hilt overrides it with the `@Provides @LrclibBaseUrl` value in
    // LyricsModule when the class is constructed through the graph.
    @LrclibBaseUrl private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricsSource {

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Duration ladder
        query.durationMs?.let { ms ->
            val baseSec = (ms / 1000).toInt()
            for (delta in DURATION_LADDER) {
                val sec = baseSec + delta
                if (sec <= 0) continue
                tryGet(query, sec)?.let { return@withContext it }
            }
        }
        // 2. Search fallback
        return@withContext trySearch(query)
    }

    private fun tryGet(query: LyricsQuery, durationSec: Int): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .also { if (!query.album.isNullOrBlank()) it.addQueryParameter("album_name", query.album) }
            .addQueryParameter("duration", durationSec.toString())
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val dto = JSON.decodeFromString<LrclibGetResponse>(body)
                LyricsResult(
                    sourceId = id,
                    plainText = dto.plainLyrics,
                    syncedLrc = dto.syncedLyrics,
                    instrumental = dto.instrumental,
                    language = null,
                    sourceLyricsId = dto.id.toString(),
                )
            }
        }.getOrNull()
    }

    private fun trySearch(query: LyricsQuery): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${query.artist} ${query.title}")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                val list = JSON.decodeFromString<List<LrclibGetResponse>>(body)
                if (list.isEmpty()) return@runCatching null
                pickBestSearchHit(query, list)?.let { dto ->
                    LyricsResult(
                        sourceId = id,
                        plainText = dto.plainLyrics,
                        syncedLrc = dto.syncedLyrics,
                        instrumental = dto.instrumental,
                        language = null,
                        sourceLyricsId = dto.id.toString(),
                    )
                }
            }
        }.getOrNull()
    }

    private fun pickBestSearchHit(query: LyricsQuery, hits: List<LrclibGetResponse>): LrclibGetResponse? {
        val target = "${query.artist} ${query.title}".lowercase()
        val baseSec = query.durationMs?.let { (it / 1000).toInt() }
        return hits
            .filter { hit ->
                if (baseSec == null) true
                else hit.duration?.let { kotlin.math.abs(it - baseSec) <= 5 } ?: true
            }
            .maxByOrNull { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate)
            }
            ?.takeIf { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate) >= 0.85
            }
    }

    private fun userAgent(): String =
        "Stash/${appVersion.versionName} (https://github.com/rawnaldclark/Stash)"

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net/"

        // Closer-to-exact first. Index 0 is the exact rung.
        private val DURATION_LADDER: IntArray = intArrayOf(0, -1, +1, -2, +2, -3, +3, -4, +4, -5, +5)

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun jaroWinkler(s1: String, s2: String): Double {
    if (s1 == s2) return 1.0
    if (s1.isEmpty() || s2.isEmpty()) return 0.0
    val matchDistance = (maxOf(s1.length, s2.length) / 2) - 1
    val s1Matches = BooleanArray(s1.length)
    val s2Matches = BooleanArray(s2.length)
    var matches = 0
    for (i in s1.indices) {
        val start = maxOf(0, i - matchDistance)
        val end = minOf(i + matchDistance + 1, s2.length)
        for (j in start until end) {
            if (s2Matches[j]) continue
            if (s1[i] != s2[j]) continue
            s1Matches[i] = true
            s2Matches[j] = true
            matches++
            break
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var k = 0
    for (i in s1.indices) {
        if (!s1Matches[i]) continue
        while (!s2Matches[k]) k++
        if (s1[i] != s2[k]) transpositions++
        k++
    }
    val m = matches.toDouble()
    val jaro = (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    // Winkler boost
    var prefix = 0
    while (prefix < 4 && prefix < s1.length && prefix < s2.length && s1[prefix] == s2[prefix]) prefix++
    return jaro + prefix * 0.1 * (1 - jaro)
}
