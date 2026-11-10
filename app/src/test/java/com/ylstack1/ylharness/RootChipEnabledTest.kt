package com.ylstack1.ylharness

import com.ylstack1.ylharness.data.env.RootStatus
import com.ylstack1.ylharness.ui.settings.rootChipEnabled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Root enabled" must mirror the direct root service. A root Shizuku is
 * reported on its own line and used to make a denied direct grant succeed, so
 * it must not turn a denied/disconnected status into a green chip.
 */
class RootChipEnabledTest {
    @Test
    fun verifiedRootWithActiveUseShowsTheChip() {
        assertTrue(rootChipEnabled(RootStatus.READY, useActive = true))
    }

    @Test
    fun deniedOrUnavailableRootNeverShowsTheChip() {
        assertFalse(rootChipEnabled(RootStatus.DENIED, useActive = true))
        assertFalse(rootChipEnabled(RootStatus.UNAVAILABLE, useActive = true))
        assertFalse(rootChipEnabled(RootStatus.NOT_CHECKED, useActive = true))
        assertFalse(rootChipEnabled(RootStatus.REQUESTING, useActive = true))
    }

    @Test
    fun verifiedRootWithRevokedUseShowsTheEnableAction() {
        assertFalse(rootChipEnabled(RootStatus.READY, useActive = false))
    }
}
