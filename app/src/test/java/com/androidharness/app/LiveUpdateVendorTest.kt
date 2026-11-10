package com.androidharness.app

import com.androidharness.app.ui.settings.LiveUpdateVendor
import com.androidharness.app.ui.settings.detectLiveUpdateVendor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live-activity card is an Android 16 platform API, so the only per-OEM
 * decision left is which instructions to print. Keep that mapping pinned.
 */
class LiveUpdateVendorTest {
    @Test
    fun samsungModelsUseOneUiSteps() {
        assertEquals(
            LiveUpdateVendor.SAMSUNG,
            detectLiveUpdateVendor("samsung", "samsung"),
        )
        assertEquals(
            LiveUpdateVendor.SAMSUNG,
            detectLiveUpdateVendor("Samsung", "SM-A356B"),
        )
    }

    @Test
    fun pixelUsesStockSteps() {
        assertEquals(LiveUpdateVendor.GOOGLE, detectLiveUpdateVendor("Google", "google"))
        assertEquals(LiveUpdateVendor.GOOGLE, detectLiveUpdateVendor("Google", "Pixel 8 Pro"))
    }

    @Test
    fun chineseOemsMapToTheirSkin() {
        assertEquals(LiveUpdateVendor.XIAOMI, detectLiveUpdateVendor("Xiaomi", "Redmi"))
        assertEquals(LiveUpdateVendor.XIAOMI, detectLiveUpdateVendor("Xiaomi", "POCO"))
        assertEquals(LiveUpdateVendor.COLOR_OS, detectLiveUpdateVendor("OnePlus", "OnePlus"))
        assertEquals(LiveUpdateVendor.COLOR_OS, detectLiveUpdateVendor("OPPO", "CPH"))
        assertEquals(LiveUpdateVendor.COLOR_OS, detectLiveUpdateVendor("realme", "RMX"))
        assertEquals(LiveUpdateVendor.ORIGIN_OS, detectLiveUpdateVendor("vivo", "V2"))
        assertEquals(LiveUpdateVendor.ORIGIN_OS, detectLiveUpdateVendor("vivo", "iQOO"))
        assertEquals(LiveUpdateVendor.MAGIC_OS, detectLiveUpdateVendor("HONOR", "ANY"))
    }

    @Test
    fun nearStockOemsGetTheGenericStockSteps() {
        assertEquals(LiveUpdateVendor.NEAR_STOCK, detectLiveUpdateVendor("motorola", "moto"))
        assertEquals(LiveUpdateVendor.NEAR_STOCK, detectLiveUpdateVendor("Nothing", "A065"))
        assertEquals(LiveUpdateVendor.NEAR_STOCK, detectLiveUpdateVendor("Sony", "XQ"))
    }

    @Test
    fun unknownAndEmptyManufacturersFallBackToOther() {
        assertEquals(LiveUpdateVendor.OTHER, detectLiveUpdateVendor("HUAWEI", "HUAWEI"))
        assertEquals(LiveUpdateVendor.OTHER, detectLiveUpdateVendor("", ""))
    }
}
