package com.stash.feature.home.banner

import com.stash.data.lyrics.backfill.LyricsBackfillSnapshot
import com.stash.data.lyrics.backfill.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBackfillBannerStateTest {

    @Test fun `IDLE maps to Hidden`() {
        val snap = LyricsBackfillSnapshot(State.IDLE, 0, 0, null)
        assertEquals(LyricsBackfillBannerState.Hidden, lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `RUNNING with total maps to Running`() {
        val snap = LyricsBackfillSnapshot(State.RUNNING, 10, 100, null)
        assertEquals(LyricsBackfillBannerState.Running(10, 100), lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `RUNNING with zero total maps to Hidden`() {
        // No tracks need lyrics = no banner; do not render "Fetching lyrics 0/0".
        val snap = LyricsBackfillSnapshot(State.RUNNING, 0, 0, null)
        assertEquals(LyricsBackfillBannerState.Hidden, lyricsBackfillBannerStateFor(snap))
    }

    @Test fun `FINISHED with total maps to Finished`() {
        val snap = LyricsBackfillSnapshot(State.FINISHED, 100, 100, 1L)
        assertEquals(LyricsBackfillBannerState.Finished(100), lyricsBackfillBannerStateFor(snap))
    }
}
