package com.androidharness.app.data.env.adb

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.ui.chat.ChatListTestActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class WirelessAdbLifecycleDeviceTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext

    @Test fun pairingAndConnectionServicesHaveNoShortServiceLifetimeAndSearchStopsCleanly() {
        for (type in listOf(AdbPairingService::class.java, WirelessAdbService::class.java)) {
            val info = context.packageManager.getServiceInfo(ComponentName(context, type), 0)
            assertEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        }
        ActivityScenario.launch(ChatListTestActivity::class.java).use {
            AdbPairingService.start(context)
            val am = context.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (am.getRunningServices(100).none { it.service.className == AdbPairingService::class.java.name && it.foreground } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(am.getRunningServices(100).any { it.service.className == AdbPairingService::class.java.name && it.foreground })
            AdbPairingService.stop(context)
            val stopped = SystemClock.uptimeMillis() + 5_000
            while (am.getRunningServices(100).any { it.service.className == AdbPairingService::class.java.name } && SystemClock.uptimeMillis() < stopped) SystemClock.sleep(50)
            assertTrue(am.getRunningServices(100).none { it.service.className == AdbPairingService::class.java.name })
        }
    }

    @Test fun savedPairingResumesAcrossManagerRecreationAndExplicitDisconnectIsRespected() {
        val name = "wireless-policy-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = context.getSharedPreferences(name, mode)
        }
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("adbkey", "legacy-pairing-fixture").commit()
            val initial = WirelessAdbManager(isolated)
            assertTrue(initial.autoReconnectEnabled)
            assertTrue(WirelessAdbManager(isolated).autoReconnectEnabled)
            initial.disconnect()
            assertTrue(initial.isPaired); assertFalse(initial.autoReconnectEnabled)
            assertFalse(WirelessAdbManager(isolated).autoReconnectEnabled)
            initial.setAutoReconnect(true)
            assertTrue(WirelessAdbManager(isolated).autoReconnectEnabled)
            initial.forget()
            assertFalse(WirelessAdbManager(isolated).autoReconnectEnabled)
            assertFalse(prefs.contains("adbkey"))
        } finally { context.deleteSharedPreferences(name) }
    }
}
