package com.stash.data.download.shared

import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lossless.AudioFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for Task 12 — loudness measurement integration in
 * [TrackFinalizer].
 *
 * After the existing probe step succeeds, [TrackFinalizer] now invokes
 * [LoudnessMeasurer.measure] on the committed file. Success forwards the
 * parsed LUFS / true-peak into the new [TrackFinalizer.FinalizeResult.Success.loudness]
 * field; failure logs a warning and lands as `loudness = null` (non-fatal —
 * the file is still playable, callers just store NULL LUFS).
 */
class TrackFinalizerLoudnessTest {

    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk()
    private val audioExtractor: AudioDurationExtractor = mockk()
    private val loudnessMeasurer: LoudnessMeasurer = mockk()

    private fun subject(): TrackFinalizer = TrackFinalizer(
        metadataEmbedder = metadataEmbedder,
        fileOrganizer = fileOrganizer,
        audioExtractor = audioExtractor,
        loudnessMeasurer = loudnessMeasurer,
    )

    private fun stubTrack(): Track = Track(
        id = 1L,
        title = "Sample",
        artist = "Artist",
        album = "Album",
    )

    private val format = AudioFormat(codec = "flac", bitrateKbps = 1411)

    private fun stubCommit(path: String): CommittedTrack = CommittedTrack(
        filePath = path,
        sizeBytes = 1024L,
    )

    private fun stubMeta(): AudioMetadata = AudioMetadata(
        durationMs = 200_000L,
        bitrateKbps = 1411,
        format = "flac",
    )

    @Test
    fun successfulFinalize_invokesMeasurerAndReturnsLufs() = runTest {
        val committed = stubCommit("/library/Artist/Album/Sample.flac")
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any())
        } returns committed
        coEvery { audioExtractor.extract(committed.filePath) } returns stubMeta()
        coEvery { loudnessMeasurer.measure(any()) } returns
            LoudnessMeasurer.Result.Success(lufs = -14.2f, truePeakDbfs = -1.3f)

        val temp = File("/tmp/source.flac")
        val result = subject().finalizeFile(
            sourceFile = temp,
            track = stubTrack(),
            format = format,
        )

        assertTrue(
            "expected Success, got $result",
            result is TrackFinalizer.FinalizeResult.Success,
        )
        val success = result as TrackFinalizer.FinalizeResult.Success
        assertEquals(committed, success.committed)
        assertNotNull(success.meta)
        val loudness = success.loudness
        assertNotNull("loudness should be populated on Success", loudness)
        assertEquals(-14.2f, loudness!!.lufs)
        assertEquals(-1.3f, loudness.truePeakDbfs)

        // Measurer must be invoked exactly once with the committed file path,
        // not the temp source — gain is applied at playback against the file
        // that actually lives in the library.
        coVerify(exactly = 1) {
            loudnessMeasurer.measure(match { it.absolutePath.endsWith("Sample.flac") })
        }
    }

    @Test
    fun measurementFailed_finalizeStillReturnsSuccess() = runTest {
        // Non-fatal failure: file is playable, just no loudness data.
        val committed = stubCommit("/library/Artist/Album/Sample.flac")
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any())
        } returns committed
        coEvery { audioExtractor.extract(committed.filePath) } returns stubMeta()
        coEvery { loudnessMeasurer.measure(any()) } returns
            LoudnessMeasurer.Result.Failed("ffmpeg crash")

        val result = subject().finalizeFile(
            sourceFile = File("/tmp/source.flac"),
            track = stubTrack(),
            format = format,
        )

        assertTrue(
            "expected Success even when measurer fails, got $result",
            result is TrackFinalizer.FinalizeResult.Success,
        )
        val success = result as TrackFinalizer.FinalizeResult.Success
        assertEquals(committed, success.committed)
        assertNotNull(success.meta)
        assertNull("loudness must be null on measurer failure", success.loudness)
    }
}
