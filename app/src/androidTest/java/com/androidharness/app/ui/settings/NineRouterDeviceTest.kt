package com.androidharness.app.ui.settings

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.router.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.URL
import java.io.File

@RunWith(AndroidJUnit4::class)
class NineRouterDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun installsStartsServesDashboardStopsAndRestarts() = runBlocking {
        val manager = c.nineRouter
        suspend fun settled(): NineRouterState = withTimeout(1_000_000) { manager.state.first { !it.busy } }
        manager.refresh()
        if (manager.state.value.phase == NineRouterPhase.SETUP_LINUX) {
            manager.installLinux(); val result = settled(); assertNull(result.error)
        }
        if (manager.state.value.phase == NineRouterPhase.INSTALL) {
            manager.install(); val result = settled(); assertNull(result.error)
        }
        assertEquals(NineRouterPhase.READY, manager.state.value.phase)
        // A conflicting service must be left alone, and the failure must be retryable.
        ServerSocket(NineRouterManager.PORT).use {
            manager.start(); val result = settled()
            assertEquals(NineRouterPhase.READY, result.phase)
            assertTrue(result.error.orEmpty().contains("already in use"))
            assertFalse(it.isClosed)
        }
        try {
            repeat(2) {
                manager.start(); val result = settled()
                assertNull(result.error); assertEquals(NineRouterPhase.RUNNING, result.phase)
                val response = URL(NineRouterManager.DASHBOARD).openConnection() as java.net.HttpURLConnection
                response.connectTimeout = 10_000; response.readTimeout = 60_000
                try { assertEquals(200, response.responseCode) } finally { response.disconnect() }
                manager.stop(); assertEquals(NineRouterPhase.READY, settled().phase)
            }
        } finally { manager.stop(); settled() }
    }

    @Test fun providerSetupUpdateAutoStartAndCompleteUninstall() = runBlocking {
        val manager = c.nineRouter
        suspend fun settled() = withTimeout(1_000_000) { manager.state.first { !it.busy } }.also { assertNull(it.error) }
        manager.refresh()
        if (manager.state.value.phase == NineRouterPhase.SETUP_LINUX) { manager.installLinux(); settled() }
        if (manager.state.value.phase == NineRouterPhase.INSTALL) { manager.install(); settled() }
        try {
            manager.addProvider(); settled()
            val first = c.providers.providers.first().single { it.baseUrl == NineRouterManager.API }
            val key = c.providers.apiKey(first.id)
            assertFalse(key.isNullOrBlank())
            assertTrue(c.providers.catalog(first.id).isNotEmpty())
            manager.addProvider(); settled()
            assertEquals(first.id, c.providers.providers.first().single { it.baseUrl == NineRouterManager.API }.id)
            assertEquals(key, c.providers.apiKey(first.id))
            manager.setAutoStart(true)
            assertTrue(c.appContext.getSharedPreferences("nine_router", 0).getBoolean("autoStart", false))
            manager.stop(); settled(); manager.onAppOpen(); assertEquals(NineRouterPhase.RUNNING, settled().phase)
            val root = File(c.appContext.filesDir, "9router")
            val sentinel = File(root, "data/preserve-fixture").apply { writeText("keep my configuration") }
            // Seed an older version marker to exercise the real staging/replacement path using the published release.
            File(root, ".installed").writeText("0.5.111"); manager.refresh()
            manager.checkUpdates()
            withTimeout(60_000) { manager.state.first { !it.checkingUpdate } }
            assertTrue(manager.state.value.updateAvailable)
            val targetVersion = manager.state.value.latestVersion
            assertEquals(targetVersion, c.appContext.getSharedPreferences("nine_router", 0).getString("latestVersion", null))
            manager.update(); assertEquals(NineRouterPhase.RUNNING, settled().phase)
            assertEquals(targetVersion, manager.installedVersion())
            assertEquals("keep my configuration", sentinel.readText())
            assertEquals(key, c.providers.apiKey(first.id))
            manager.removeProvider(); settled()
            assertFalse(c.providers.providers.first().any { it.baseUrl == NineRouterManager.API })
            manager.addProvider(); settled()
            manager.uninstall(); settled()
            assertFalse(root.exists())
            assertFalse(manager.state.value.autoStart)
            assertFalse(manager.state.value.providerAdded)
            assertFalse(c.providers.providers.first().any { it.baseUrl == NineRouterManager.API })
            assertNull(c.providers.apiKey(first.id))
            assertFalse(c.linuxEnv.managedTools().values.any { "9Router" in it.requirements })
            assertTrue(c.linuxEnv.isReady)
            assertTrue(c.appContext.getSharedPreferences("nine_router", 0).all.isEmpty())
            manager.onAppOpen(); delay(500)
            assertEquals(NineRouterPhase.INSTALL, manager.state.value.phase)
            // A fresh install must support automatic key setup without opening/signing into the dashboard first.
            manager.install(); settled(); manager.addProvider(); settled()
            assertTrue(c.providers.providers.first().any { it.baseUrl == NineRouterManager.API })
            manager.uninstall(); settled(); assertFalse(root.exists())
        } finally { manager.setAutoStart(false); manager.stop(); settled() }
    }

    @Test fun controlsFitPhoneInLightAndDarkThemes() {
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var snapshot by mutableStateOf(NineRouterState(NineRouterPhase.SETUP_LINUX))
        var dark by mutableStateOf(false)
        try {
            instrumentation.runOnMainSync { activity.setContent {
                com.androidharness.app.ui.theme.HarnessTheme(themeMode = if (dark) com.androidharness.app.data.ThemeMode.DARK else com.androidharness.app.data.ThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            NineRouterPanel(snapshot, {}, {}, {}, {}, {})
                        }
                    }
                }
            } }
            for (mode in listOf(false, true)) for (phase in listOf(NineRouterPhase.SETUP_LINUX, NineRouterPhase.INSTALLING, NineRouterPhase.RUNNING)) {
                instrumentation.runOnMainSync { dark = mode; snapshot = NineRouterState(phase, installedVersion = if (phase == NineRouterPhase.RUNNING) NineRouterManager.VERSION else null, hasFiles = phase != NineRouterPhase.SETUP_LINUX) }
                instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(800)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                val out = File(c.appContext.getExternalFilesDir(null), "9router-${if (mode) "dark" else "light"}-${phase.name}.png")
                out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
