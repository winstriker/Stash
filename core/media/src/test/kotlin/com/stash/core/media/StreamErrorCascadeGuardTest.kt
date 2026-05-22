package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamErrorCascadeGuardTest {

    @Test
    fun firstError_allowsRecovery() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun thirdConsecutiveError_haltsCascade() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Halt)
    }

    @Test
    fun successfulPlaybackResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        guard.onPlaybackStarted()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun userTransportResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError()
        guard.onUserTransport()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun haltVerdictStaysHaltedUntilReset() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError(); guard.onError()  // Halt
        val verdict = guard.onError()
        // Still halted — 4th error after a halt also halts.
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Halt)
    }
}
