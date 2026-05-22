package com.stash.core.media

/**
 * Bounds ExoPlayer's skip-next recovery cascade so one upstream outage
 * can't silently drain the queue. Counts consecutive stream errors;
 * after [threshold] consecutive errors with no successful playback or
 * user transport command in between, returns [Verdict.Halt] instead of
 * the usual [Verdict.Recover].
 *
 * Not thread-safe by itself — caller must invoke from the single
 * MediaController callback thread (which is what we already do).
 */
class StreamErrorCascadeGuard(
    private val threshold: Int = 3,
) {
    private var consecutiveErrors: Int = 0

    enum class Verdict { Recover, Halt }

    /** Increment and return whether to recover or halt. */
    fun onError(): Verdict {
        consecutiveErrors += 1
        return if (consecutiveErrors >= threshold) Verdict.Halt else Verdict.Recover
    }

    /** A track actually started playing — backend is alive. */
    fun onPlaybackStarted() {
        consecutiveErrors = 0
    }

    /** User did something deliberate (next/prev/seek/play) — rearm. */
    fun onUserTransport() {
        consecutiveErrors = 0
    }
}
