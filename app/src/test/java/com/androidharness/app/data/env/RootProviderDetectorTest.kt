package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test

class RootProviderDetectorTest {
    private fun detect(packages: Set<String> = emptySet(), kernel: String = "", markers: Set<String> = emptySet()) =
        RootProviderDetector.classify(packages, kernel, markers)

    @Test fun kernelSuMarkersIdentifyTheSuFamilyAndTheManagerRefinesTheFork() {
        assertEquals(RootProvider.KERNELSU, detect(markers = setOf(RootProviderDetector.KSU)).provider)
        assertEquals(RootProvider.KERNELSU_NEXT,
            detect(setOf("com.rifsxd.ksunext"), markers = setOf(RootProviderDetector.KSU)).provider)
        assertEquals(RootProvider.SUKISU,
            detect(setOf("org.sukisu.manager"), markers = setOf(RootProviderDetector.KSU)).provider)
        assertEquals(RootProvider.SUKISU,
            detect(markers = setOf(RootProviderDetector.KSU, RootProviderDetector.SUKISU)).provider)
    }

    @Test fun apatchAndMagiskAreIdentifiedFromMarkersAndManagerPackages() {
        assertEquals(RootProvider.APATCH,
            detect(setOf("me.bmax.apatch"), markers = setOf(RootProviderDetector.APATCH)).provider)
        assertEquals(RootProvider.MAGISK,
            detect(setOf("com.topjohnwu.magisk"), markers = setOf(RootProviderDetector.MAGISK)).provider)
        assertEquals("root markers and manager app",
            detect(setOf("com.topjohnwu.magisk"), markers = setOf(RootProviderDetector.MAGISK)).evidence)
    }

    @Test fun aLeftoverManagerAppDoesNotOverrideTheLiveRootMarkers() {
        assertEquals(RootProvider.APATCH,
            detect(setOf("com.topjohnwu.magisk"), markers = setOf(RootProviderDetector.APATCH)).provider)
        assertEquals(RootProvider.KERNELSU,
            detect(setOf("com.topjohnwu.magisk"), markers = setOf(RootProviderDetector.KSU)).provider)
    }

    @Test fun managerPackageAndKernelHintCoverHiddenMarkers() {
        assertEquals(RootProvider.KERNELSU, detect(setOf("me.weishu.kernelsu")).provider)
        assertEquals(RootProvider.KERNELSU,
            detect(kernel = "5.15.153-android14-11-g1234-KernelSU").provider)
        assertEquals("kernel version",
            detect(kernel = "5.15.153-android14-11-g1234-KernelSU").evidence)
    }

    @Test fun unknownOrUnrootedDevicesReportNoProvider() {
        val detection = detect(kernel = "5.15.153-android14-11-g1234-abcd")
        assertNull(detection.provider)
        assertTrue(detection.evidence.contains("No known root manager"))
    }

    @Test fun markerScriptChecksTheDocumentedPathsWithoutPrompting() {
        val script = RootProviderDetector.markerScript
        listOf("/data/adb/ksu", "/data/adb/sukisu", "/data/adb/ap", "/data/adb/magisk").forEach {
            assertTrue("Missing $it in marker script", script.contains(it))
        }
        listOf(RootProviderDetector.KSU, RootProviderDetector.SUKISU,
            RootProviderDetector.APATCH, RootProviderDetector.MAGISK).forEach {
            assertTrue("Missing $it in marker script", script.contains(it))
        }
    }
}
