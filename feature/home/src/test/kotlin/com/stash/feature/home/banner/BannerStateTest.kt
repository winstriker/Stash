package com.stash.feature.home.banner

import org.junit.Assert.assertEquals
import org.junit.Test

class BannerStateTest {

    @Test fun `count zero hides banner regardless of source state`() {
        assertEquals(
            WaitingForLosslessBannerState.Hidden,
            bannerStateFor(count = 0, currentCookie = "", lastBadCookie = null, kennyyBroken = false),
        )
    }

    @Test fun `cookie matches lastBad shows ExpiredCaptcha`() {
        assertEquals(
            WaitingForLosslessBannerState.ExpiredCaptcha(count = 3),
            bannerStateFor(count = 3, currentCookie = "abc", lastBadCookie = "abc", kennyyBroken = false),
        )
    }

    @Test fun `empty cookie + kennyy down shows NoSourceConfigured`() {
        assertEquals(
            WaitingForLosslessBannerState.NoSourceConfigured(count = 5),
            bannerStateFor(count = 5, currentCookie = "", lastBadCookie = null, kennyyBroken = true),
        )
    }

    @Test fun `active cookie + kennyy down shows KennyyDown`() {
        assertEquals(
            WaitingForLosslessBannerState.KennyyDown(count = 2),
            bannerStateFor(count = 2, currentCookie = "abc", lastBadCookie = null, kennyyBroken = true),
        )
    }

    @Test fun `active cookie + kennyy up + nonzero count shows DefensiveRetry`() {
        assertEquals(
            WaitingForLosslessBannerState.DefensiveRetry(count = 1),
            bannerStateFor(count = 1, currentCookie = "abc", lastBadCookie = null, kennyyBroken = false),
        )
    }
}
