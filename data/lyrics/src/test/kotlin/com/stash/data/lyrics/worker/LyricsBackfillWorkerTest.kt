package com.stash.data.lyrics.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.backfill.LyricsBackfillState
import com.stash.data.lyrics.source.LyricsQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LyricsBackfillWorker].
 *
 * Mirror of `MetadataBackfillWorkerTest`. Both assisted constructor
 * params (`Context`, `WorkerParameters`) are positional so we instantiate
 * the worker directly with relaxed mocks — no Hilt graph or
 * `TestListenableWorkerBuilder` required (the worker doesn't read input
 * data or `runAttemptCount`, so the builder buys us nothing here).
 *
 * Behaviour under test:
 *  - Empty result set short-circuits to `Result.success()` after a
 *    direct `markFinished()` (skipping `markStarted` for the no-flash
 *    fast path described in the worker's KDoc).
 *  - Each row in a multi-row batch invokes `resolveAndStore` exactly
 *    once; the worker terminates after the second (empty) batch.
 *  - `resolveAndStore` throws -> the worker stamps `0L` via
 *    `setLyricsFetchedAt(trackId, 0L)` and continues with the next row
 *    instead of aborting the whole backfill.
 *  - Banner snapshot transitions via `markStarted` -> `publishProgress`
 *    (one per row) -> `markFinished` in order, so the Home banner reads
 *    the expected IDLE -> RUNNING -> FINISHED arc.
 */
class LyricsBackfillWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val backfillState: LyricsBackfillState = mockk(relaxUnitFun = true)

    private fun buildSubject(): LyricsBackfillWorker = LyricsBackfillWorker(
        context = context,
        params = workerParams,
        trackDao = trackDao,
        lyricsRepository = lyricsRepository,
        backfillState = backfillState,
    )

    private fun stubEntity(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        albumArtist = "AlbumArtist $id",
        filePath = "/music/track$id.opus",
        durationMs = 200_000L,
        youtubeId = "vid$id",
        isDownloaded = true,
    )

    @Test
    fun `empty library returns success without invoking resolveAndStore`() = runTest {
        every { trackDao.observeTracksNeedingLyricsCount() } returns flowOf(0)

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Empty-library fast path: skip markStarted, jump straight to FINISHED.
        coVerify(exactly = 0) { backfillState.markStarted(any()) }
        coVerify(exactly = 1) { backfillState.markFinished() }
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any()) }
        coVerify(exactly = 0) { trackDao.getTracksNeedingLyrics(any()) }
    }

    @Test
    fun `multi-row library drains every track and transitions banner state`() = runTest {
        val rows = listOf(stubEntity(1L), stubEntity(2L), stubEntity(3L))
        every { trackDao.observeTracksNeedingLyricsCount() } returns flowOf(3)
        coEvery { trackDao.getTracksNeedingLyrics(any()) } returnsMany listOf(rows, emptyList())
        coEvery { lyricsRepository.resolveAndStore(any()) } returns null

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { backfillState.markStarted(3) }
        coVerify(exactly = 1) { lyricsRepository.resolveAndStore(match { it.trackId == 1L }) }
        coVerify(exactly = 1) { lyricsRepository.resolveAndStore(match { it.trackId == 2L }) }
        coVerify(exactly = 1) { lyricsRepository.resolveAndStore(match { it.trackId == 3L }) }
        coVerify(exactly = 1) { backfillState.publishProgress(1, 3) }
        coVerify(exactly = 1) { backfillState.publishProgress(2, 3) }
        coVerify(exactly = 1) { backfillState.publishProgress(3, 3) }
        coVerify(exactly = 1) { backfillState.markFinished() }
        // Repository owns the success/miss stamp; the worker only stamps
        // 0L on the runCatching-onFailure path, which doesn't fire here.
        coVerify(exactly = 0) { trackDao.setLyricsFetchedAt(any(), any()) }
    }

    @Test
    fun `resolveAndStore failure stamps 0L and loop continues`() = runTest {
        val rows = listOf(stubEntity(7L), stubEntity(8L))
        every { trackDao.observeTracksNeedingLyricsCount() } returns flowOf(2)
        coEvery { trackDao.getTracksNeedingLyrics(any()) } returnsMany listOf(rows, emptyList())
        coEvery {
            lyricsRepository.resolveAndStore(match { it.trackId == 7L })
        } throws RuntimeException("network blip")
        coEvery {
            lyricsRepository.resolveAndStore(match { it.trackId == 8L })
        } returns null

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Failing row: worker stamps the 0L sentinel directly so the
        // row leaves the WHERE-NULL set.
        coVerify(exactly = 1) { trackDao.setLyricsFetchedAt(7L, 0L) }
        // Healthy row: repository owns the stamp; the worker doesn't
        // touch setLyricsFetchedAt for it.
        coVerify(exactly = 0) { trackDao.setLyricsFetchedAt(8L, any()) }
        // Banner still transitions cleanly through the failure.
        coVerify(exactly = 1) { backfillState.markFinished() }
    }

    @Test
    fun `LyricsQuery propagates albumArtist + duration + youtubeId from TrackEntity`() = runTest {
        val row = stubEntity(42L)
        every { trackDao.observeTracksNeedingLyricsCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingLyrics(any()) } returnsMany listOf(listOf(row), emptyList())
        val captured = slot<LyricsQuery>()
        coEvery { lyricsRepository.resolveAndStore(capture(captured)) } returns null

        buildSubject().doWork()

        assertEquals(42L, captured.captured.trackId)
        assertEquals("Track 42", captured.captured.title)
        assertEquals("Artist 42", captured.captured.artist)
        assertEquals("Album 42", captured.captured.album)
        assertEquals("AlbumArtist 42", captured.captured.albumArtist)
        assertEquals(200_000L, captured.captured.durationMs)
        assertEquals("vid42", captured.captured.youtubeVideoId)
    }
}
