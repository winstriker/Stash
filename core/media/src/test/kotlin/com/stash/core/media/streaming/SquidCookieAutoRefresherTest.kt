package com.stash.core.media.streaming

import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.squid.NativeSquidCaptchaSolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SquidCookieAutoRefresherTest {

    @Test
    fun doesNotRefresh_whenKennyyHealthy() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk(relaxed = true)
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(System.currentTimeMillis() - 30 * 60_000L)
        }
        val monitor = KennyyHealthMonitor()  // starts healthy

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, this)
        refresher.start()
        advanceTimeBy(60_000L)
        runCurrent()

        coVerify(exactly = 0) { solver.solve() }
        refresher.stop()
    }

    @Test
    fun refreshesImmediately_whenKennyyUnhealthyAndCookieStale() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk(relaxed = true) {
            coEvery { solve() } returns "new-cookie-value"
        }
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(0L)  // never set
        }
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() }  // mark unhealthy

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, this)
        refresher.start()
        runCurrent()

        coVerify(exactly = 1) { solver.solve() }
        coVerify(exactly = 1) { prefs.setCaptchaCookieValue("new-cookie-value") }
        refresher.stop()
    }
}
