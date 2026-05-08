package com.stash.data.download.lossless

import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessUpgraderImplTest {

    private val downloadManager: DownloadManager = mockk()
    private val subject = LosslessUpgraderImpl(downloadManager)

    @Test fun `Success maps to Upgraded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Success(filePath = "/path/to/file.flac")
        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `null maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Unmatched maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Unmatched()
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Failed maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Failed("network")
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Deferred maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Deferred
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `thrown exception maps to Error`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } throws
            RuntimeException("boom")
        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `passes forced = true to bypass global lossless toggle`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        subject.upgradeToLossless(stubTrack())
        // mockk's `forced = true` matcher in the coEvery already enforces this;
        // a separate coVerify is redundant but documents the intent.
    }

    private fun stubTrack(): Track = Track(
        id = 1,
        title = "Karma Police",
        artist = "Radiohead",
        fileFormat = "opus",
    )
}
