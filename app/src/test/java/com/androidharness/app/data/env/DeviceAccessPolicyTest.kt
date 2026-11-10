package com.androidharness.app.data.env

import com.androidharness.app.core.GenerationMetadata
import org.junit.Assert.*
import org.junit.Test

class DeviceAccessPolicyTest {
    @Test fun explicitShizukuPinsTheTransportEvenForAppWorkspacesAndRootStartedServers() {
        for (mode in listOf(AccessMode.AUTO, AccessMode.SHIZUKU, AccessMode.BOTH, AccessMode.ROOT)) {
            for (uid in listOf(0, 2000)) {
                val chosen = DeviceAccessPolicy.choose(mode, true, true, uid, false,
                    appWorkspace = true, requireShizuku = true)
                assertEquals(DeviceBackend.SHIZUKU, chosen.backend)
                assertEquals(uid, chosen.uid)
            }
            assertNull(DeviceAccessPolicy.choose(mode, true, true, null, true,
                appWorkspace = true, requireShizuku = true).backend)
            assertNull(DeviceAccessPolicy.choose(mode, true, true, 2000, true,
                paused = true, requireShizuku = true).backend)
        }
        assertNull(DeviceAccessPolicy.choose(AccessMode.NORMAL, true, true, 2000, true,
            requireShizuku = true).backend)
    }
    private fun choose(mode: AccessMode = AccessMode.AUTO, fallback: Boolean = true,
        root: Boolean = false, shizuku: Int? = null, allowed: Boolean = false,
        paused: Boolean = false, app: Boolean = false, requireRoot: Boolean = false) =
        DeviceAccessPolicy.choose(mode, fallback, root, shizuku, allowed, paused, app, requireRoot)

    @Test fun autoIsTheDefaultAndChoosesReadyPermittedAccessInOrder() {
        assertEquals(AccessMode.AUTO, AccessPreferences().mode)
        assertEquals(DeviceBackend.ROOT, choose(root = true, shizuku = 2000, allowed = true).backend)
        assertEquals(DeviceBackend.SHIZUKU, choose(root = false, shizuku = 2000, allowed = true).backend)
        assertEquals(DeviceBackend.NORMAL, choose(root = false, shizuku = null, allowed = true).backend)
    }
    @Test fun autoDoesNotRequestOrUseRootWithoutAuthorization() {
        val decision = choose(root = true, shizuku = 2000)
        assertEquals(DeviceBackend.SHIZUKU, decision.backend)
        assertNull(decision.reason)
        assertEquals(DeviceBackend.NORMAL, choose(root = true).backend)
    }
    @Test fun autoStillSelectsAvailableAccessWhenPinnedFallbackIsOff() {
        assertEquals(DeviceBackend.SHIZUKU, choose(fallback = false, shizuku = 2000).backend)
        assertEquals(DeviceBackend.NORMAL, choose(fallback = false).backend)
    }
    @Test fun rootModeFallsBackThroughShizukuThenNormal() {
        assertEquals(DeviceBackend.SHIZUKU, choose(AccessMode.ROOT, shizuku = 2000, allowed = true).backend)
        assertEquals(DeviceBackend.NORMAL, choose(AccessMode.ROOT, allowed = true).backend)
        assertTrue(choose(AccessMode.ROOT, allowed = true).reason!!.contains("Root is unavailable"))
    }
    @Test fun strictRootNeverSilentlyUsesAnotherBackend() {
        assertNull(choose(AccessMode.ROOT, fallback = false, shizuku = 2000, allowed = true).backend)
        assertNull(choose(AccessMode.ROOT, fallback = false, root = true, allowed = false).backend)
    }
    @Test fun strictShizukuDoesNotUseRootOrNormal() {
        assertNull(choose(AccessMode.SHIZUKU, fallback = false, root = true, allowed = true).backend)
        assertEquals(DeviceBackend.SHIZUKU, choose(AccessMode.SHIZUKU, fallback = false, shizuku = 2000).backend)
    }
    @Test fun normalModeNeverEscalates() {
        assertEquals(DeviceBackend.NORMAL, choose(AccessMode.NORMAL, root = true, shizuku = 0, allowed = true).backend)
        assertNull(choose(AccessMode.NORMAL, root = true, allowed = true, requireRoot = true).backend)
    }
    @Test fun rootStartedShizukuUsesItsOwnAuthorizationAndPreservesItsIdentity() {
        for (mode in listOf(AccessMode.AUTO, AccessMode.SHIZUKU, AccessMode.BOTH)) {
            for (fallback in listOf(false, true)) {
                val decision = choose(mode, fallback = fallback, root = true, shizuku = 0)
                assertEquals(DeviceBackend.SHIZUKU, decision.backend)
                assertEquals(0, decision.uid)
                assertNull(decision.reason)
            }
        }
    }
    @Test fun shizukuOnlyNeverSelectsTheDirectRootRunner() {
        for (uid in listOf(0, 2000)) {
            val decision = choose(AccessMode.SHIZUKU, root = true, shizuku = uid, allowed = true)
            assertEquals(DeviceBackend.SHIZUKU, decision.backend)
            assertEquals(uid, decision.uid)
        }
        assertEquals(DeviceBackend.NORMAL, choose(AccessMode.SHIZUKU, root = true, allowed = true).backend)
    }
    @Test fun explicitRootThroughShizukuStillRequiresRootAuthorization() {
        assertNull(choose(AccessMode.SHIZUKU, shizuku = 0, requireRoot = true).backend)
        assertEquals(DeviceBackend.SHIZUKU,
            choose(AccessMode.SHIZUKU, shizuku = 0, allowed = true, requireRoot = true).backend)
    }
    @Test fun explicitRootCannotDowngradeToAdbOrNormal() {
        assertNull(choose(shizuku = 2000, allowed = true, requireRoot = true).backend)
        assertEquals(DeviceBackend.SHIZUKU, choose(shizuku = 0, allowed = true, requireRoot = true).backend)
    }
    @Test fun stopBlocksEveryPrivilegedBackendButNormalStillWorks() {
        assertEquals(DeviceBackend.NORMAL, choose(root = true, shizuku = 0, allowed = true, paused = true).backend)
        assertNull(choose(root = true, shizuku = 0, allowed = true, paused = true, requireRoot = true).backend)
    }
    @Test fun ordinaryAppWorkspaceCommandsStayOnAppUid() {
        assertEquals(DeviceBackend.NORMAL, choose(AccessMode.ROOT, root = true, allowed = true, app = true).backend)
        assertEquals(DeviceBackend.ROOT, choose(root = true, allowed = true, app = true, requireRoot = true).backend)
    }
    @Test fun bothKeepsShizukuForOrdinaryWorkAndRootForRootRequests() {
        assertEquals(DeviceBackend.SHIZUKU,
            choose(AccessMode.BOTH, root = true, shizuku = 2000, allowed = true).backend)
        assertEquals(DeviceBackend.ROOT,
            choose(AccessMode.BOTH, root = true, shizuku = 2000, allowed = true, requireRoot = true).backend)
        assertEquals(DeviceBackend.ROOT,
            choose(AccessMode.BOTH, root = true, shizuku = null, allowed = true).backend)
    }
    @Test fun bothWithoutFallbackNeverUsesNormalOrAnotherBackendSilently() {
        assertNull(choose(AccessMode.BOTH, fallback = false, root = false, shizuku = null).backend)
        assertEquals(DeviceBackend.NORMAL, choose(AccessMode.BOTH, fallback = true, root = false, shizuku = null).backend)
        assertEquals(DeviceBackend.SHIZUKU,
            choose(AccessMode.BOTH, fallback = false, root = true, shizuku = 2000, allowed = true).backend)
    }
    @Test fun temporaryRootModeGatesRootWithoutTouchingTheConnectionRules() {
        assertFalse(DeviceAccessPolicy.rootPermitted(temporaryMode = true, manual = false, projectAllowed = false, allowanceActive = false))
        assertFalse(DeviceAccessPolicy.rootPermitted(temporaryMode = true, manual = false, projectAllowed = true, allowanceActive = false))
        assertTrue(DeviceAccessPolicy.rootPermitted(temporaryMode = true, manual = false, projectAllowed = true, allowanceActive = true))
        assertTrue(DeviceAccessPolicy.rootPermitted(temporaryMode = true, manual = true, projectAllowed = false, allowanceActive = true))
        assertTrue(DeviceAccessPolicy.rootPermitted(temporaryMode = false, manual = false, projectAllowed = false, allowanceActive = false))
    }
    @Test fun receiptsSurviveSavedHistoryAndLabelOnlyTheActualBackend() {
        val metadata = GenerationMetadata(execution = listOf(
            ExecutionReceipt(DeviceBackend.SHIZUKU, 2000, "Root is unavailable"),
            ExecutionReceipt(DeviceBackend.SHIZUKU, 0), ExecutionReceipt(DeviceBackend.ROOT, 0)))
        assertEquals(metadata, GenerationMetadata.decode(GenerationMetadata.encode(metadata)))
        assertEquals("Shizuku", metadata.execution[0].label)
        assertEquals("Shizuku", metadata.execution[1].label)
        assertEquals("Root", metadata.execution[2].label)
        assertEquals("Normal", ExecutionReceipt(DeviceBackend.NORMAL).label)
        assertEquals("Wireless ADB", ExecutionReceipt(DeviceBackend.SHIZUKU, 2000, transport = "Wireless ADB").label)
        assertTrue(GenerationMetadata.decode("{\"reasoningTokens\":42}").execution.isEmpty())
    }
}
