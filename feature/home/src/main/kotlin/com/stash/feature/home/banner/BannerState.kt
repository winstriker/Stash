package com.stash.feature.home.banner

/**
 * Discrete states for the Home "tracks waiting for lossless" banner.
 * Only one is rendered at a time. [Hidden] is the dominant state in
 * the steady-state install (zero deferred tracks).
 */
sealed interface WaitingForLosslessBannerState {
    data object Hidden : WaitingForLosslessBannerState
    data class ExpiredCaptcha(val count: Int) : WaitingForLosslessBannerState
    data class NoSourceConfigured(val count: Int) : WaitingForLosslessBannerState
    data class KennyyDown(val count: Int) : WaitingForLosslessBannerState
    data class DefensiveRetry(val count: Int) : WaitingForLosslessBannerState
}

/**
 * Pure mapping from the four observable inputs into a banner state.
 *
 * Render priority (highest first):
 *   1. count == 0  → Hidden
 *   2. cookie == lastBad (non-empty) → ExpiredCaptcha (squid path is broken; user can fix)
 *   3. cookie blank + kennyy broken → NoSourceConfigured (no path forward; offer setup)
 *   4. cookie active + kennyy broken → KennyyDown (squid works in theory; just wait)
 *   5. fall-through (cookie active + kennyy up + count > 0) → DefensiveRetry (retry sweep lagging)
 */
fun bannerStateFor(
    count: Int,
    currentCookie: String,
    lastBadCookie: String?,
    kennyyBroken: Boolean,
): WaitingForLosslessBannerState {
    if (count <= 0) return WaitingForLosslessBannerState.Hidden

    val cookieExpired = currentCookie.isNotEmpty() && currentCookie == lastBadCookie
    if (cookieExpired) return WaitingForLosslessBannerState.ExpiredCaptcha(count)

    val cookieBlank = currentCookie.isEmpty()
    return when {
        cookieBlank && kennyyBroken -> WaitingForLosslessBannerState.NoSourceConfigured(count)
        kennyyBroken -> WaitingForLosslessBannerState.KennyyDown(count)
        else -> WaitingForLosslessBannerState.DefensiveRetry(count)
    }
}
