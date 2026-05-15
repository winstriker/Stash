package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Task 13 — verifies the search-tab download path persists LUFS + true-peak
 * from [TrackFinalizer.FinalizeResult.Success.loudness] via
 * [TrackDao.updateLoudness] on the same upsert that writes
 * `sample_rate_hz`, `bits_per_sample`, `file_format`, and `quality_kbps`.
 *
 * Drives the yt-dlp branch (registry returns null, fallback on) because it
 * has the smallest mock surface — the lossless cache+HTTP plumbing isn't
 * load-bearing for what we're asserting. The DAO write happens in
 * `upsertSearchTrack`, which both branches funnel through.
 */
class SearchDownloadCoordinatorLoudnessTest {

    private val registry: LosslessSourceRegistry = mockk()
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val trackFinalizer: TrackFinalizer = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)

    private fun newSubject(): SearchDownloadCoordinator = SearchDownloadCoordinator(
        registry = registry,
        previewCache = previewCache,
        httpDataSourceFactory = httpDataSourceFactory,
        cacheKeyFactory = cacheKeyFactory,
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = trackDao,
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
        context = context,
        losslessPrefs = losslessPrefs,
        downloadQueueDao = downloadQueueDao,
    )

    private fun stubTrack(): TrackItem = TrackItem(
        videoId = "vid42",
        title = "Sample",
        artist = "Sample Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    private val tmpCacheDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-loudness-test-${System.nanoTime()}",
    ).also { it.mkdirs() }

    @Before
    fun setUp() {
        // SearchDownloadCoordinator.finalizeFromYtDlp() builds
        // `File(context.cacheDir, "search_ytdlp")` early in the path —
        // a relaxed mockk Context returns null cacheDir, which fails the
        // File ctor. Hand it a real temp dir.
        every { context.cacheDir } returns tmpCacheDir
    }

    private fun stubExistingTrackRow(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Sample",
        artist = "Sample Artist",
        youtubeId = "vid42",
        canonicalTitle = "sample",
        canonicalArtist = "sample artist",
        durationMs = 200_000L,
        source = MusicSource.YOUTUBE,
        albumArtUrl = null,
    )

    @Test
    fun `afterDownload writes loudness LUFS and peak`() = runTest {
        // Set up the yt-dlp fallback branch: lossless registry returns null,
        // fallback ON so we fall through to yt-dlp.
        coEvery { registry.resolve(any()) } returns null
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        // yt-dlp produces a temp file successfully.
        val tempFile = File.createTempFile("search_yt", ".opus")
        tempFile.deleteOnExit()
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)

        // TrackFinalizer returns Success with a populated loudness measurement.
        val committed = CommittedTrack(
            filePath = "/library/Sample Artist/Sample.opus",
            sizeBytes = 4096L,
        )
        val meta = AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 128,
            format = "opus",
            sampleRateHz = 48_000,
            bitsPerSample = 16,
        )
        val loudness = LoudnessMeasurer.Result.Success(lufs = -14.2f, truePeakDbfs = -0.3f)
        coEvery {
            trackFinalizer.finalizeFile(any(), any(), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(committed, meta, loudness)

        // upsertSearchTrack looks up an existing track row by youtubeId.
        coEvery { trackDao.findByYoutubeId("vid42") } returns stubExistingTrackRow()

        val statuses = newSubject().download(stubTrack()).toList()

        assertTrue(
            "expected Completed, got $statuses",
            statuses.any { it is SearchDownloadStatus.Completed },
        )

        // The asserts the loudness columns are populated using the values
        // surfaced by TrackFinalizer. The `any()` for the timestamp matches
        // `System.currentTimeMillis()` at the call site.
        coVerify(exactly = 1) {
            trackDao.updateLoudness(
                id = 7L,
                lufs = -14.2f,
                peak = -0.3f,
                now = any(),
            )
        }
    }

    @Test
    fun `afterDownload skips updateLoudness when measurement absent`() = runTest {
        coEvery { registry.resolve(any()) } returns null
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        val tempFile = File.createTempFile("search_yt", ".opus")
        tempFile.deleteOnExit()
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)

        val committed = CommittedTrack(
            filePath = "/library/Sample Artist/Sample.opus",
            sizeBytes = 4096L,
        )
        val meta = AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 128,
            format = "opus",
            sampleRateHz = 48_000,
            bitsPerSample = 16,
        )
        // Null loudness = ebur128 couldn't parse a Summary block. The DAO
        // call must be skipped so LoudnessBackfillWorker (Task 11) can pick
        // up the row on its next run.
        coEvery {
            trackFinalizer.finalizeFile(any(), any(), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(committed, meta, loudness = null)

        coEvery { trackDao.findByYoutubeId("vid42") } returns stubExistingTrackRow()

        newSubject().download(stubTrack()).toList()

        coVerify(exactly = 0) {
            trackDao.updateLoudness(any(), any(), any(), any())
        }
    }
}
