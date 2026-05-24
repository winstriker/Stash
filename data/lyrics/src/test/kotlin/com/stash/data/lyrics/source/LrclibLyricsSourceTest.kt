package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrclibLyricsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LrclibLyricsSource
    private val appVersion = object : AppVersionProvider {
        override val versionName = "0.9.36"
        override val versionCode = 9036
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        source = LrclibLyricsSource(
            okHttpClient = OkHttpClient(),
            appVersion = appVersion,
            baseUrl = server.url("/").toString(),
        )
    }
    @After  fun tearDown() { server.shutdown() }

    @Test fun `exact-match get returns synced and plain`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 42, "trackName": "Off The Grid", "artistName": "Kanye West",
             "albumName": "DONDA", "duration": 279, "instrumental": false,
             "plainLyrics": "I been off the grid",
             "syncedLyrics": "[00:01.00]I been off the grid"}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 279_000))
        assertNotNull(result)
        assertEquals("lrclib", result!!.sourceId)
        assertEquals("[00:01.00]I been off the grid", result.syncedLrc)
        assertEquals("I been off the grid", result.plainText)
        assertEquals(false, result.instrumental)
        assertEquals("42", result.sourceLyricsId)
        // Verify User-Agent
        val request = server.takeRequest()
        val ua = request.getHeader("User-Agent")
        assertTrue("User-Agent header should mention Stash + version", ua!!.contains("Stash/0.9.36"))
        // Verify exact endpoint
        assertTrue(request.path!!.startsWith("/api/get"))
    }

    @Test fun `instrumental flag preserved`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 7, "trackName": "Linus and Lucy", "artistName": "Vince Guaraldi Trio",
             "albumName": "A Charlie Brown Christmas", "duration": 180, "instrumental": true,
             "plainLyrics": null, "syncedLyrics": null}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 180_000))
        assertNotNull(result)
        assertTrue(result!!.instrumental)
        assertNull(result.plainText)
        assertNull(result.syncedLrc)
    }

    @Test fun `duration ladder — exact misses, minus-one hits`() = runTest {
        // Exact fails (404), -1 succeeds, no further requests
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"id": 1, "trackName": "T", "artistName": "A",
            "albumName": "AL", "duration": 233, "instrumental": false, "plainLyrics": "x",
            "syncedLyrics": null}"""))
        val result = source.resolve(query(durationMs = 234_000))
        assertNotNull(result)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().path
        val second = server.takeRequest().path
        assertTrue("exact duration first", first!!.contains("duration=234"))
        assertTrue("-1 second", second!!.contains("duration=233"))
    }

    @Test fun `all rungs miss — search fallback used`() = runTest {
        // Exact + ±2 + ±5 all 404; then /api/search returns a hit
        repeat(11) { server.enqueue(MockResponse().setResponseCode(404)) }
        server.enqueue(MockResponse().setBody("""
            [{"id": 99, "trackName": "Random", "artistName": "Random",
              "albumName": "?", "duration": 234, "instrumental": false,
              "plainLyrics": "fallback", "syncedLyrics": null}]
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 234_000))
        // Lyrics returned only if similarity + duration within ±5s
        // For this stub artist/title equal query, similarity passes; duration 234 vs 234 passes
        assertNotNull(result)
    }

    @Test fun `complete miss returns null`() = runTest {
        repeat(12) { server.enqueue(MockResponse().setResponseCode(404)) }
        // /api/search returns empty list
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.resolve(query(durationMs = 234_000)))
    }

    @Test fun `network exception returns null`() = runTest {
        server.shutdown()
        assertNull(source.resolve(query(durationMs = 234_000)))
    }

    @Test fun `null duration skips ladder, goes straight to search`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.resolve(query(durationMs = null)))
        // Only one request (search), not 12
        assertEquals(1, server.requestCount)
    }

    @Test fun `regression — LRCLIB returns duration as JSON Number with decimal`() = runTest {
        // Real LRCLIB responses come back as `"duration":265.0` (Number with a decimal),
        // NOT `"duration":265` (bare int). Strict kotlinx.serialization throws when an
        // Int? field tries to deserialize a fractional JSON Number, the runCatching in
        // tryGet swallows the throw, and every successful fetch silently becomes a miss.
        // Pinning here so we never regress: the DTO must accept Double for `duration`.
        server.enqueue(MockResponse().setBody("""
            {"id": 4578472, "trackName": "Carry On", "artistName": "Crosby, Stills, Nash & Young",
             "albumName": "Deja Vu", "duration": 265.0, "instrumental": false,
             "plainLyrics": "One morning I woke up and I knew",
             "syncedLyrics": "[00:13.71] One morning I woke up and I knew"}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 265_000))
        assertNotNull("decimal-duration response must deserialize", result)
        assertEquals("4578472", result!!.sourceLyricsId)
        assertEquals("[00:13.71] One morning I woke up and I knew", result.syncedLrc)
    }

    private fun query(durationMs: Long?) = LyricsQuery(
        trackId = 1L,
        title = "Random",
        artist = "Random",
        album = "?",
        albumArtist = null,
        durationMs = durationMs,
        youtubeVideoId = null,
    )
}
