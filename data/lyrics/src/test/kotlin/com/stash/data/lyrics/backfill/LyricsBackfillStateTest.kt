package com.stash.data.lyrics.backfill

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for [LyricsBackfillState].
 *
 * Mirrors the Robolectric + ApplicationProvider convention used by
 * `MetadataBackfillStateTest`. The DataStore is cleared in both `@Before`
 * AND `@After` because the top-level `preferencesDataStore(...)`
 * delegate keeps a per-process cache that survives file deletion — a
 * single end-of-test wipe leaves the next test starting from whatever
 * the previous test wrote. Clearing on both sides guarantees a clean
 * IDLE snapshot.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsBackfillStateTest {

    private lateinit var context: Context
    private lateinit var subject: LyricsBackfillState

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { context.lyricsBackfillDataStore.edit { it.clear() } }
        subject = LyricsBackfillState(context)
    }

    @After
    fun tearDown() {
        runBlocking { context.lyricsBackfillDataStore.edit { it.clear() } }
    }

    @Test
    fun `initial state is IDLE`() = runTest {
        val snap = subject.snapshot.first()
        assertEquals(State.IDLE, snap.state)
        assertEquals(0, snap.processed)
        assertEquals(0, snap.total)
        assertEquals(null, snap.finishedAt)
    }

    @Test
    fun `markStarted publishes RUNNING with total`() = runTest {
        subject.markStarted(50)
        val snap = subject.snapshot.first()
        assertEquals(State.RUNNING, snap.state)
        assertEquals(50, snap.total)
        assertEquals(0, snap.processed)
    }

    @Test
    fun `markFinished publishes FINISHED`() = runTest {
        subject.markStarted(10)
        subject.markFinished()
        val snap = subject.snapshot.first()
        assertEquals(State.FINISHED, snap.state)
    }

    @Test
    fun `markFinishedAcknowledged returns to IDLE`() = runTest {
        subject.markStarted(10)
        subject.markFinished()
        subject.markFinishedAcknowledged()
        assertEquals(State.IDLE, subject.snapshot.first().state)
    }
}
