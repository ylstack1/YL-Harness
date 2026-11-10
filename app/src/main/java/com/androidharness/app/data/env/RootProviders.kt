package com.androidharness.app.data.env

/** Root managers this app can identify; everything else reports as unknown root. */
internal enum class RootProvider(val label: String) {
    KERNELSU("KernelSU"),
    KERNELSU_NEXT("KernelSU Next"),
    SUKISU("SukiSU Ultra"),
    APATCH("APatch"),
    MAGISK("Magisk"),
}

internal data class RootDetection(val provider: RootProvider?, val evidence: String)

/**
 * Root managers do not expose a uniform API, so identification uses three
 * independent signals: the manager package (visible through <queries>), the
 * kernel version string, and paths the root service itself can see. SUSFS and
 * similar hiding layers may conceal the paths even from root, which is why
 * the manager package is also consulted.
 */
internal object RootProviderDetector {
    private val managers: Map<String, RootProvider> = linkedMapOf(
        "me.weishu.kernelsu" to RootProvider.KERNELSU,
        "com.rifsxd.ksunext" to RootProvider.KERNELSU_NEXT,
        "org.sukisu.manager" to RootProvider.SUKISU,
        "me.bmax.apatch" to RootProvider.APATCH,
        "com.topjohnwu.magisk" to RootProvider.MAGISK,
    )
    private val ksuFamily = setOf(RootProvider.KERNELSU, RootProvider.KERNELSU_NEXT, RootProvider.SUKISU)

    /** Markers printed by [markerScript] keys. */
    const val KSU = "KSU"
    const val SUKISU = "SUKISU"
    const val APATCH = "APATCH"
    const val MAGISK = "MAGISK"

    /**
     * Runs inside the already-granted root service, so it never prompts.
     * KernelSU, KernelSU Next and SukiSU share the base /data/adb/ksu paths;
     * the manager package distinguishes the forks.
     */
    val markerScript: String = """
        if [ -e /data/adb/ksu ] || [ -e /data/adb/ksud ] || command -v ksud >/dev/null 2>&1; then echo $KSU; fi
        if [ -e /data/adb/sukisu ]; then echo $SUKISU; fi
        if [ -e /data/adb/ap ] || command -v apd >/dev/null 2>&1; then echo $APATCH; fi
        if [ -e /data/adb/magisk ] || [ -e /debug_ramdisk/magisk ] || command -v magisk >/dev/null 2>&1; then echo $MAGISK; fi
    """.trimIndent()

    fun classify(installedPackages: Set<String>, kernelVersion: String, markers: Set<String>): RootDetection {
        val manager = installedPackages.firstNotNullOfOrNull { managers[it] }
        val marker = when {
            APATCH in markers -> RootProvider.APATCH
            MAGISK in markers -> RootProvider.MAGISK
            SUKISU in markers -> RootProvider.SUKISU
            KSU in markers -> RootProvider.KERNELSU
            else -> null
        }
        // A fork manager refines the shared KernelSU markers instead of looking
        // like a conflict. A different manager (for example a leftover Magisk
        // app next to active KernelSU) does not override the live markers.
        val provider = when {
            marker != null && marker in ksuFamily && manager != null && manager in ksuFamily -> manager
            marker != null -> marker
            manager != null -> manager
            kernelVersion.contains("KernelSU", ignoreCase = true) -> RootProvider.KERNELSU
            else -> null
        }
        val evidence = when {
            provider == null -> "No known root manager detected"
            marker != null && provider == manager -> "root markers and manager app"
            marker != null -> "root service markers"
            manager != null -> "manager app installed"
            else -> "kernel version"
        }
        return RootDetection(provider, evidence)
    }
}
