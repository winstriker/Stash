package com.stash.core.media.streaming

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.squid.HeadlessSquidCaptchaSolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Background refresher for the squid.wtf captcha cookie. Active only
 * when Kennyy is unhealthy (so we don't burn ALTCHA solves we won't
 * use) and the app is in the ProcessLifecycle STARTED window (caller
 * controls via [start] / [stop]).
 *
 * Cadence derived from cookie age: refreshes at age+25min. The
 * 25-min window leaves a 5-min safety margin against squid.wtf's
 * 30-min sliding cookie expiry.
 *
 * Failure ladder:
 *  - Single solve failure -> retry in 60s
 *  - Two consecutive failures -> stop refresh loop, rely on existing
 *    CaptchaExpiredNotifier to nag the user
 *
 * Placement note: lives in :core:media (not :data:download) because
 * it depends on KennyyHealthMonitor (own module) AND
 * LosslessSourcePreferences + HeadlessSquidCaptchaSolver (:data:download).
 * The module graph is :core:media -> :data:download (one-way), so this
 * is the only module that can see all three types.
 */
@Singleton
class SquidCookieAutoRefresher(
    private val solver: HeadlessSquidCaptchaSolver,
    private val healthMonitor: KennyyHealthMonitor,
    private val prefs: LosslessSourcePreferences,
    private val scope: CoroutineScope,
) {
    /**
     * Hilt-injectable constructor. Dagger does NOT honour Kotlin default
     * parameter values on `@Inject` constructors (it sees the parameter
     * as a required binding regardless), so the production scope is
     * constructed explicitly here. Tests use the four-arg primary
     * constructor to pass a `TestScope` directly.
     */
    @Inject
    constructor(
        solver: HeadlessSquidCaptchaSolver,
        healthMonitor: KennyyHealthMonitor,
        prefs: LosslessSourcePreferences,
    ) : this(
        solver = solver,
        healthMonitor = healthMonitor,
        prefs = prefs,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    private var job: Job? = null
    private var consecutiveFailures: Int = 0

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "start")
        job = scope.launch {
            combine(
                healthMonitor.isHealthy,
                prefs.captchaCookieSetAtMs,
            ) { healthy, setAtMs -> healthy to setAtMs }
                .collect { (healthy, setAtMs) ->
                    if (healthy) {
                        Log.d(TAG, "Kennyy healthy - sleeping")
                        return@collect
                    }
                    val age = System.currentTimeMillis() - setAtMs
                    if (setAtMs == 0L || age >= COOKIE_REFRESH_AGE_MS) {
                        refresh()
                    } else {
                        val waitMs = COOKIE_REFRESH_AGE_MS - age
                        Log.d(TAG, "cookie is fresh - sleeping ${waitMs}ms")
                        delay(waitMs)
                        refresh()
                    }
                }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
    }

    private suspend fun refresh() {
        Log.d(TAG, "refresh attempt")
        val newCookie = solver.solve()
        if (newCookie != null) {
            prefs.setCaptchaCookieValue(newCookie)
            consecutiveFailures = 0
            Log.d(TAG, "refresh success")
        } else {
            consecutiveFailures += 1
            Log.w(TAG, "refresh failure ($consecutiveFailures consecutive)")
            if (consecutiveFailures >= MAX_FAILURES) {
                Log.w(TAG, "max failures reached, halting auto-refresh")
                stop()
            } else {
                delay(FAILURE_RETRY_DELAY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "SquidAutoRefresher"
        const val COOKIE_REFRESH_AGE_MS = 25 * 60_000L
        const val FAILURE_RETRY_DELAY_MS = 60_000L
        const val MAX_FAILURES = 2
    }
}
