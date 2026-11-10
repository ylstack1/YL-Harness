package com.androidharness.app.router

import org.junit.Assert.*
import org.junit.Test

class NineRouterReleaseTest {
    @Test fun versionsUseNumericOrderingAndStableReleasePrecedence() {
        assertTrue(NineRouterRelease.newer("0.5.112", "0.5.99"))
        assertFalse(NineRouterRelease.newer("0.5.99", "0.5.112"))
        assertFalse(NineRouterRelease.newer("0.5.112", "0.5.112"))
        assertTrue(NineRouterRelease.newer("1.0.0", "1.0.0-rc.2"))
        assertFalse(NineRouterRelease.newer("1.0.0-rc.3", "1.0.0"))
        assertTrue(NineRouterRelease.newer("1.0.0-rc.10", "1.0.0-rc.2"))
    }
    @Test fun releaseMetadataCannotBecomeShellArguments() {
        for (bad in listOf("latest", "1.0.0;touch /tmp/x", "1.0.0\n", "https://other", "1.0.0'", "../payload")) {
            assertThrows(IllegalArgumentException::class.java) { NineRouterRelease.validate(bad) }
        }
    }
    @Test fun updateButtonOnlyAppearsForANewerInstalledRelease() {
        assertTrue(NineRouterState(NineRouterPhase.READY, installedVersion = "0.5.111", latestVersion = "0.5.112").updateAvailable)
        assertFalse(NineRouterState(NineRouterPhase.READY, installedVersion = "0.5.112", latestVersion = "0.5.112").updateAvailable)
        assertFalse(NineRouterState(NineRouterPhase.INSTALL, latestVersion = "0.5.112").updateAvailable)
    }
}
