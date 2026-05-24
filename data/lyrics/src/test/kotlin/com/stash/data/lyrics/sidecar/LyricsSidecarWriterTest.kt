package com.stash.data.lyrics.sidecar

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StoragePreference
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric-backed sidecar-writer tests. Internal-storage paths are
 * exercised against a [TemporaryFolder] so we can assert real file
 * contents; the SAF path is intentionally NOT exercised here (it
 * would require a SAF document-tree provider, which isn't viable in
 * Robolectric without a heavy stub) — coverage of the SAF branch is
 * done on-device in Task 14.
 *
 * The minimum surface verified here:
 *   1. internal-storage write lands `<basename>.lrc` next to the audio
 *      with the canonical header tags + body
 *   2. syncedLrc is preferred when present, plainText is the fallback
 *   3. instrumental (both null) writes nothing
 *   4. a missing track row short-circuits silently
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LyricsSidecarWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `internal storage path writes basename dot lrc next to audio`() = runTest {
        val audio = tmp.newFile("Off The Grid.opus")
        val track = stubTrack(
            filePath = audio.absolutePath,
            title = "Off The Grid",
            artist = "Kanye West",
            album = "DONDA",
        )
        val writer = makeWriter(track)

        writer.write(track.id, lyricsEntity(syncedLrc = "[00:01.00]hello", plainText = "hello"))

        val sidecar = File(audio.parent, "Off The Grid.lrc")
        assertTrue("sidecar must exist", sidecar.exists())
        val body = sidecar.readText(Charsets.UTF_8)
        assertTrue("LRC header carries title tag: $body", body.contains("[ti:Off The Grid]"))
        assertTrue("LRC header carries artist tag: $body", body.contains("[ar:Kanye West]"))
        assertTrue("LRC header carries album tag: $body", body.contains("[al:DONDA]"))
        assertTrue("synced body present: $body", body.contains("[00:01.00]hello"))
        assertTrue("by tag stamped: $body", body.contains("[by:Stash]"))
    }

    @Test fun `prefers syncedLrc, falls back to plain when synced is null`() = runTest {
        val audio = tmp.newFile("track.flac")
        val track = stubTrack(filePath = audio.absolutePath)
        val writer = makeWriter(track)

        writer.write(track.id, lyricsEntity(syncedLrc = null, plainText = "plain text only"))

        val sidecar = File(audio.parent, "track.lrc")
        assertTrue("sidecar exists when plain-text only", sidecar.exists())
        val body = sidecar.readText(Charsets.UTF_8)
        assertTrue("plain text written: $body", body.contains("plain text only"))
    }

    @Test fun `does nothing when both syncedLrc and plainText are null - instrumental`() = runTest {
        val audio = tmp.newFile("inst.opus")
        val track = stubTrack(filePath = audio.absolutePath)
        val writer = makeWriter(track)

        writer.write(track.id, lyricsEntity(syncedLrc = null, plainText = null, instrumental = true))

        val sidecar = File(audio.parent, "inst.lrc")
        assertFalse("sidecar must NOT exist for instrumental", sidecar.exists())
    }

    @Test fun `missing track returns silently without writing`() = runTest {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.getById(99L) } returns null
        val storagePrefs = mockk<StoragePreference>()
        // Should not even touch storage prefs when the track row is gone.
        val writer = LyricsSidecarWriter(trackDao = trackDao, context = context, storagePreference = storagePrefs)

        // Must not throw; must not write anything.
        writer.write(99L, lyricsEntity(syncedLrc = "x", plainText = "x"))
        // No file system assertion possible (no path) — the test passes
        // by virtue of not throwing.
    }

    @Test fun `instrumental short-circuit precedes track lookup`() = runTest {
        // Defensive check: even if some caller bypasses LyricsRepository's
        // own instrumental guard, the writer must skip the DAO + write
        // entirely when both lyric bodies are null.
        val trackDao = mockk<TrackDao>()
        // No `coEvery` stub for getById — if the writer reaches the DAO
        // it will throw, failing the test.
        val storagePrefs = mockk<StoragePreference>()
        val writer = LyricsSidecarWriter(trackDao = trackDao, context = context, storagePreference = storagePrefs)

        writer.write(1L, lyricsEntity(syncedLrc = null, plainText = null, instrumental = true))
        // assertion is the lack of MockKException — we never called getById
    }

    private fun makeWriter(track: TrackEntity): LyricsSidecarWriter {
        val trackDao = mockk<TrackDao>()
        coEvery { trackDao.getById(track.id) } returns track
        val storagePrefs = mockk<StoragePreference>()
        // Internal-storage tests don't reach the SAF path, but the prefs
        // call is read defensively even on the File path in some impl
        // variants — return null (= internal mode) so the writer commits
        // to the File branch.
        coEvery { storagePrefs.externalTreeUri } returns flowOf<Uri?>(null)
        return LyricsSidecarWriter(trackDao = trackDao, context = context, storagePreference = storagePrefs)
    }

    private fun stubTrack(
        filePath: String,
        title: String = "T",
        artist: String = "A",
        album: String = "AL",
        albumArtist: String = "",
        durationMs: Long = 0L,
    ): TrackEntity = TrackEntity(
        id = 1L,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        filePath = filePath,
    )

    private fun lyricsEntity(
        syncedLrc: String? = null,
        plainText: String? = null,
        instrumental: Boolean = false,
    ) = LyricsEntity(
        trackId = 1L,
        plainText = plainText,
        syncedLrc = syncedLrc,
        instrumental = instrumental,
        language = null,
        source = "lrclib",
        sourceLyricsId = "42",
        fetchedAt = 1_700_000_000_000L,
    )
}
