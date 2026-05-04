package com.stash.data.download.lossless

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-source token bucket with exponential backoff and circuit breaker.
 *
 * One shared instance gates every aggregator-bound network call across
 * Stash. This is the dam that stops Stash from accidentally DDoSing
 * community-run services that are themselves running on a single
 * paid Tidal/Qobuz account. The economics: a typical aggregator operator
 * pays ~$10-20/mo for one premium account and serves it to thousands of
 * users via a web frontend. A burst of automated traffic from a third-
 * party app trips the upstream provider's abuse heuristics, the operator's
 * account gets suspended, the service goes down for everyone — and Stash
 * is the proximate cause.
 *
 * Defaults are deliberately conservative: 1 token / 8 seconds per source
 * (~7 requests/minute), burst capacity 3. Tuneable per-source via
 * [configure] for sources that publish higher official rate budgets.
 *
 * Thread-safe via [Mutex].
 */
@Singleton
class AggregatorRateLimiter @Inject constructor() {

    /**
     * Test seam: tests overwrite this with a virtual clock before any
     * [acquire]/[reportSuccess] calls. Production paths leave it on
     * [SystemClock]. Kept off the constructor signature because mixing
     * `@Inject` with a defaulted parameter generates two JVM
     * constructors and Hilt rejects multiple injectable constructors.
     */
    internal var clock: Clock = SystemClock

    /**
     * Per-source mutable bucket state. [tokens] is fractional so refill
     * is smooth across short intervals; [blockedUntil] is the absolute
     * timestamp before which acquire returns false.
     */
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        var consecutiveFailures: Int = 0,
        var blockedUntilMs: Long = 0L,
        var totalAcquires: Long = 0L,
        var totalRateLimited: Long = 0L,
        /** Timestamps (ms) of recent 429s. Trimmed to last 60s in reportRateLimited. */
        val rateLimitTimestamps: MutableList<Long> = mutableListOf(),
    )

    private val buckets = mutableMapOf<String, Bucket>()
    private val configs = mutableMapOf<String, Config>()
    private val mutex = Mutex()

    private val defaultConfig = Config()

    init {
        // Kennyy operator handles traffic from Monochrome's web UI users at
        // higher rates than the conservative default. Start at 3x the default
        // (1 token / 3s, burst 4); auto-backoff via reportRateLimited will
        // ratchet down if the operator's actual threshold is lower.
        configs["kennyy_qobuz"] = Config(
            tokensPerSecond = 1.0 / 3.0,   // 1 token / 3 seconds
            burstCapacity = 4.0,
            backoff429Ms = 60_000L,        // 1 min pause on 429
            circuitBreakAfter = 5,
            circuitBreakDurationMs = 30 * 60_000L, // 30 min
        )
    }

    companion object {
        private const val TAG = "AggregatorRateLimiter"
    }

    /**
     * Override defaults for a specific source. Call once at app startup
     * (e.g. from the [LosslessSource] implementation's init or a Hilt
     * module) for sources where the operator publishes higher rate
     * budgets, OR where empirical breakage suggests slower defaults.
     */
    suspend fun configure(sourceId: String, config: Config) {
        mutex.withLock { configs[sourceId] = config }
    }

    /**
     * Block until a token is available for [sourceId], OR return false
     * immediately if the source is currently circuit-broken.
     *
     * Caller pattern:
     * ```
     * if (!rateLimiter.acquire(id)) return null  // circuit-broken, skip
     * try { val response = http.get(url); rateLimiter.reportSuccess(id) }
     * catch (e: Http429) { rateLimiter.reportRateLimited(id); throw e }
     * catch (e: Throwable) { rateLimiter.reportFailure(id); throw e }
     * ```
     *
     * Caller MUST report the outcome — [reportSuccess], [reportRateLimited],
     * or [reportFailure] — or the failure counters won't progress and
     * the circuit breaker won't trip when it should.
     */
    suspend fun acquire(sourceId: String): Boolean {
        // Wait outside the mutex so other sources aren't blocked.
        var waitMs = 0L
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()

            // Circuit-broken? Bail without consuming a token.
            if (now < bucket.blockedUntilMs) return false

            // Refill since last visit.
            refill(bucket, cfg, now)

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                bucket.totalAcquires++
                return true
            }

            // Not enough tokens — compute wait, then loop after sleeping.
            waitMs = msToNextToken(bucket, cfg)
        }

        if (waitMs > 0) delay(waitMs)
        // Re-acquire (same source, fresh state). Recursion is fine here —
        // depth is bounded because tokens accumulate during the delay.
        return acquire(sourceId)
    }

    /** Record a successful response. Resets the failure counter. */
    suspend fun reportSuccess(sourceId: String) {
        mutex.withLock { bucketFor(sourceId).consecutiveFailures = 0 }
    }

    /**
     * Manually clear circuit-breaker + failure state for [sourceId].
     * Used by the Settings UI's "Reset lossless attempts" action when
     * the breaker tripped on a transient outage and the user knows
     * the source is healthy again — skips the 30-min organic timeout.
     *
     * Bucket tokens are also fully refilled so the next call doesn't
     * have to wait on the steady-state refill rate.
     */
    suspend fun reset(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            bucket.blockedUntilMs = 0L
            bucket.consecutiveFailures = 0
            bucket.tokens = cfg.burstCapacity
            bucket.lastRefillMs = clock.nowMs()
        }
    }

    /**
     * Record a 429 / Too Many Requests response. Pauses the source for
     * the configured backoff period — caller-side logic should not retry
     * before then, and [acquire] will return false for that interval.
     */
    suspend fun reportRateLimited(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()
            bucket.blockedUntilMs = now + cfg.backoff429Ms
            bucket.totalRateLimited++

            // Auto-backoff: track 429 timestamps in a rolling 60-second
            // window. If we cross the threshold (5+ in 60s), halve the
            // source's effective rate as a self-tuning measure. The system
            // converges toward the operator's actual threshold rather than
            // hardcoding our guess.
            bucket.rateLimitTimestamps.add(now)
            bucket.rateLimitTimestamps.removeAll { it < now - 60_000L }
            if (bucket.rateLimitTimestamps.size >= 5) {
                val newRate = (cfg.tokensPerSecond / 2.0).coerceAtLeast(1.0 / 60.0) // floor at 1/min
                if (newRate < cfg.tokensPerSecond) {
                    configs[sourceId] = cfg.copy(tokensPerSecond = newRate)
                    Log.i(TAG, "AggregatorRateLimiter: $sourceId 429-rate-limited 5+ times in 60s; halving rate to $newRate tokens/sec")
                    bucket.rateLimitTimestamps.clear()  // reset window after action
                }
            }

            // 429 also counts as a failure for circuit-breaker purposes —
            // if we keep getting rate-limited, something's structurally
            // wrong (wrong API endpoint, banned IP, etc.) and we should
            // fall back harder than just the 5-minute backoff.
            bucket.consecutiveFailures++
            maybeTripCircuitBreaker(bucket, cfg)
        }
    }

    /**
     * Record any non-429 failure (timeout, connection refused, 5xx,
     * unparseable response). Increments the consecutive-failure counter
     * and trips the circuit breaker if it crosses the threshold.
     */
    suspend fun reportFailure(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            bucket.consecutiveFailures++
            maybeTripCircuitBreaker(bucket, cfg)
        }
    }

    /** Inspect current state. Used by the Settings UI for diagnostics. */
    suspend fun stateOf(sourceId: String): RateLimitState {
        return mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()
            refill(bucket, cfg, now)
            RateLimitState(
                tokensAvailable = bucket.tokens,
                msUntilNextToken = if (bucket.tokens >= 1.0) 0L else msToNextToken(bucket, cfg),
                isCircuitBroken = now < bucket.blockedUntilMs,
                msUntilUnblock = (bucket.blockedUntilMs - now).coerceAtLeast(0L),
                recentFailures = bucket.consecutiveFailures,
            )
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun bucketFor(sourceId: String): Bucket =
        buckets.getOrPut(sourceId) {
            Bucket(
                tokens = configFor(sourceId).burstCapacity,
                lastRefillMs = clock.nowMs(),
            )
        }

    private fun configFor(sourceId: String): Config =
        configs[sourceId] ?: defaultConfig

    private fun refill(bucket: Bucket, cfg: Config, now: Long) {
        val elapsedSec = (now - bucket.lastRefillMs) / 1000.0
        if (elapsedSec <= 0) return
        bucket.tokens = (bucket.tokens + elapsedSec * cfg.tokensPerSecond)
            .coerceAtMost(cfg.burstCapacity)
        bucket.lastRefillMs = now
    }

    private fun msToNextToken(bucket: Bucket, cfg: Config): Long {
        val needed = (1.0 - bucket.tokens).coerceAtLeast(0.0)
        return (needed / cfg.tokensPerSecond * 1000.0).toLong().coerceAtLeast(1L)
    }

    private fun maybeTripCircuitBreaker(bucket: Bucket, cfg: Config) {
        if (bucket.consecutiveFailures >= cfg.circuitBreakAfter) {
            bucket.blockedUntilMs = clock.nowMs() + cfg.circuitBreakDurationMs
            // Reset counter so we don't re-trip immediately on the next
            // failure if the source is still down. Counter resets organically
            // on first success; on continued failure, we'll re-cross the
            // threshold and re-trip after another N failures.
            bucket.consecutiveFailures = 0
        }
    }

    /**
     * Per-source rate-limit configuration. All durations in ms.
     *
     * @property tokensPerSecond Steady-state rate. 0.125 = 1 / 8s = ~7/min.
     * @property burstCapacity   Initial bucket size; allows short bursts.
     * @property backoff429Ms    Pause-the-source duration on a 429 reply.
     * @property circuitBreakAfter Consecutive failures before extended block.
     * @property circuitBreakDurationMs Length of the extended block.
     */
    data class Config(
        val tokensPerSecond: Double = 0.125,
        val burstCapacity: Double = 3.0,
        val backoff429Ms: Long = 5 * 60_000L,
        val circuitBreakAfter: Int = 3,
        val circuitBreakDurationMs: Long = 30 * 60_000L,
    )

    /** Indirection so tests can inject a virtual clock. */
    interface Clock {
        fun nowMs(): Long
    }

    object SystemClock : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
}
