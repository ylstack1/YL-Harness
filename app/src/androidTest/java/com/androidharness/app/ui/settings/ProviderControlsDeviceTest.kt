package com.androidharness.app.ui.settings

import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ProviderControlsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun eyeToggleRevealsTheActualValueAndHidesItAgain() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                val pool = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ProviderKeyPool(listOf(ProviderApiKey("eye-fixture", value = "VISIBLE-EYE-FIXTURE")))) }
                HarnessTheme(ThemeMode.DARK, dynamicColor = false) { Surface(Modifier.fillMaxSize()) {
                    ProviderKeysEditor(pool.value, onChange = { pool.value = it })
                } }
            } }
            await { find("Show API key 1") != null }
            tap("Show API key 1")
            await { find("Hide API key 1") != null }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "provider-key-eye-fixture.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertTrue("Revealed field must contain visible text", input("API key 1").text.toString().contains("VISIBLE-EYE-FIXTURE"))
            tap("Hide API key 1")
            await { find("Show API key 1") != null }
            assertFalse("Hidden field must mask the text", input("API key 1").text.toString().contains("VISIBLE-EYE-FIXTURE"))
        }
    }

    @Test fun settingsControlsEditKeysAndProxyAtLargeTextAndCancelPreservesSavedValues() = runBlocking {
        val settings = c.settings.settings.first()
        val global = c.keys.providerProxy(null)
        val p = c.providers.add("Controls fixture", ProviderType.OPENAI_COMPAT, "https://example.test/v1", "fixture", "controls-alpha-key")
        try {
            c.settings.setAllowScreenshots(true)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        HarnessTheme(ThemeMode.DARK, dynamicColor = false) { Surface(Modifier.fillMaxSize()) { ProvidersScreen(c, {}) } }
                    }
                } }
                await { find("Search providers or models") != null }
                edit("Search providers or models", p.name)
                tap("Actions for ${p.name}"); tap("Connection controls")
                await { find("API key 1") != null }
                assertSecureWindow()
                tap("Rotate API keys"); tap("Use key 1")
                scrollTo("Add API key"); tap("Add API key")
                edit("Key name 2", "Spare"); edit("API key 2", "controls-beta-key")
                assertOnScreen("Save"); tap("Save")
                await { find("Connection controls") == null && c.keys.providerKeyPool(p.id).keys.size == 2 }
                val saved = KeyStoreManager(context).providerKeyPool(p.id)
                assertFalse(saved.rotate); assertFalse(saved.keys.first().enabled)
                assertEquals("Spare", saved.keys.last().label)
                assertEquals("controls-beta-key", c.keys.getKey(p.id))

                tap("Actions for ${p.name}"); tap("Connection controls")
                scrollTo("Custom proxy"); tap("Custom proxy")
                await { find("HTTP") != null }
                edit("Proxy host", "127.0.0.1"); edit("Proxy port", "8088")
                edit("Proxy username (optional)", "fixture-user"); edit("Proxy password (optional)", "fixture-password")
                assertOnScreen("Save"); tap("Save")
                await { c.keys.providerProxy(p.id).mode == ProviderProxyMode.CUSTOM }
                val proxy = KeyStoreManager(context).providerProxy(p.id)
                assertEquals(8088, proxy.port); assertEquals("fixture-password", proxy.password)
                tap("Actions for ${p.name}"); tap("Connection controls")
                edit("Key name 2", "Unsaved"); tap("Cancel")
                await { find("Search providers or models") != null }
                assertEquals(saved, c.keys.providerKeyPool(p.id)); assertEquals(proxy, c.keys.providerProxy(p.id))

                scrollTo("Global proxy"); tap("Global proxy")
                await { find("Global provider proxy") != null }
                assertNull(find("Add API key")); assertSecureWindow()
                tap("Direct connection"); tap("Save")
                await { c.keys.providerProxy(null).mode == ProviderProxyMode.DIRECT }
                assertEquals(proxy, ProviderNetwork.selected(p.id))
                assertEquals(ProviderProxyMode.DIRECT, ProviderNetwork.selected("inherited-fixture").mode)
            }
        } finally {
            c.providers.delete(p.id); c.providers.saveGlobalProxy(global); c.settings.restorePortable(settings)
        }
    }

    @Test fun normalEditorShowsTwentyKeysRevealsOneAndPreservesAllDisabledChoices() = runBlocking {
        val global = c.keys.providerProxy(null)
        val server = ModelServer()
        val p = c.providers.add("Full key pool fixture", ProviderType.OPENAI_COMPAT, server.url, "fixture", "")
        val pool = ProviderKeyPool(List(ProviderKeyPool.MAX_KEYS) { ProviderApiKey("$it", value = "disabled-fixture-$it", enabled = false) })
        try {
            c.providers.saveGlobalProxy(ProviderProxy.DIRECT); c.providers.saveControls(p, pool, ProviderProxy())
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme(ThemeMode.DARK, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) { ProvidersScreen(c, {}) }
                } } }
                await { find("Search providers or models") != null }
                edit("Search providers or models", p.name)
                tap("Actions for ${p.name}"); tap("Edit")
                await { find("20 / 20") != null }
                scrollTo("API key 1"); assertSecureWindow()
                assertTrue(input("API key 1").isPassword)
                tap("Show API key 1")
                await { find("Hide API key 1") != null }
                await { input("API key 1").text.toString().contains("disabled-fixture-0") }
                tap("Hide API key 1")
                await { !input("API key 1").text.toString().contains("disabled-fixture-0") }
                edit("API key 1", "edited-disabled-fixture")
                scrollTo("Add API key")
                val add = generateSequence(requireNotNull(find("Add API key"))) { it.parent }.first { it.isClickable }
                assertFalse("A full pool cannot add a twenty-first key", add.isEnabled)
                tap("Continue")
                await { find(context.getString(com.androidharness.app.R.string.settings_provider_choose_model_title)) != null }
                tap("Save")
                val expected = pool.copy(keys = pool.keys.mapIndexed { i, key -> if (i == 0) key.copy(value = "edited-disabled-fixture") else key })
                await { c.providers.apiKeys(p.id) == expected }
                assertEquals(expected, KeyStoreManager(context).providerKeyPool(p.id))
                assertNull(c.keys.getKey(p.id))
                assertTrue("Disabled keys must not load models", server.requests.isEmpty())
            }
        } finally { c.providers.delete(p.id); c.providers.saveGlobalProxy(global); server.close() }
    }

    @Test fun addInsideProviderManagerSavesMultipleKeysAndOnlyUsesTheEnabledOne() = runBlocking {
        val global = c.keys.providerProxy(null)
        val server = ModelServer()
        val created = java.util.concurrent.atomic.AtomicReference<ProviderConfig?>()
        try {
            c.providers.saveGlobalProxy(ProviderProxy.DIRECT)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    val scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        HarnessTheme(ThemeMode.DARK, dynamicColor = false) {
                            ProviderManagerSheet(emptyList(), null, c.providers::apiKeys, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
                                onSave = { _, name, type, url, model, pool, headers -> scope.launch {
                                    created.set(c.providers.add(name, type, url, model, pool, headers))
                                } }, addProviderInitially = true)
                        }
                    }
                } }
                await { find("Custom endpoint") != null }
                tap("Custom endpoint")
                edit("Server address", server.url)
                edit("API key 1", "add-disabled-alpha")
                tap("Use key 1")
                tap("Add API key")
                edit("Key name 2", "Spare")
                edit("API key 2", "add-enabled-beta")
                tap("Show API key 2")
                await { find("Hide API key 2") != null }
                await { input("API key 2").text.toString().contains("add-enabled-beta") }
                // Leaving and returning hides revealed credentials without changing the draft.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scrollTo("API key 2")
                await { find("Show API key 2") != null }
                assertTrue(input("API key 2").isPassword)
                assertSecureWindow(); assertOnScreen("Continue")
                tap("Continue")
                await { find(context.getString(com.androidharness.app.R.string.settings_provider_choose_model_title)) != null }
                tap("Save")
                await { created.get() != null }
                val saved = c.providers.apiKeys(created.get()!!.id)
                assertEquals(listOf("add-disabled-alpha", "add-enabled-beta"), saved.keys.map { it.value })
                assertEquals(listOf(false, true), saved.keys.map { it.enabled })
                assertEquals("Spare", saved.keys.last().label)
                assertTrue(server.requests.single().contains("Authorization: Bearer add-enabled-beta"))
                assertFalse(server.requests.any { it.contains("add-disabled-alpha") })
            }
        } finally { created.get()?.let { c.providers.delete(it.id) }; c.providers.saveGlobalProxy(global); server.close() }
    }

    @Test fun legacyKeyMigratesWithoutLosingAdditionalKeysAndCredentialsStayEncrypted() {
        val id = "controls-legacy-${System.nanoTime()}"
        val vault = KeyStoreManager(context)
        val prefs = KeyStoreManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.get(vault) as SharedPreferences
        try {
            assertTrue(prefs.edit().putString(id, "legacy-controls-key").commit())
            assertEquals("legacy-controls-key", vault.getKey(id))
            val migrated = vault.providerKeyPool(id)
            assertEquals("primary", migrated.keys.single().id)
            val pool = migrated.copy(keys = migrated.keys + ProviderApiKey("spare", "Spare", "additional-controls-key"), rotate = false)
            val proxy = ProviderProxy(ProviderProxyMode.CUSTOM, host = "127.0.0.1", port = 3128, username = "fixture", password = "encrypted-controls-password")
            vault.putProviderControls(id, pool, proxy)
            val reopened = KeyStoreManager(context)
            assertEquals(pool, reopened.providerKeyPool(id)); assertEquals(proxy, reopened.providerProxy(id))
            reopened.putKey(id, "changed-controls-key")
            assertEquals(listOf("changed-controls-key", "additional-controls-key"), reopened.providerKeyPool(id).keys.map { it.value })
            val raw = File(context.applicationInfo.dataDir, "shared_prefs/api_keys.xml").readText()
            listOf("legacy-controls-key", "additional-controls-key", "changed-controls-key", "encrypted-controls-password").forEach {
                assertFalse("Credentials must be encrypted on disk", raw.contains(it))
            }
            reopened.removeKey(id)
            assertTrue(reopened.providerKeyPool(id).keys.isEmpty()); assertEquals(ProviderProxy(), reopened.providerProxy(id))
        } finally { vault.removeKey(id) }
    }

    @Test fun backupsIncludeOptionalKeyPoolsAndProxyCredentialsAndRestoreOldSingleKeys() = runBlocking {
        val settings = c.settings.settings.first(); val global = c.keys.providerProxy(null)
        val p = c.providers.add("Controls backup fixture", ProviderType.OPENAI_COMPAT, "https://example.test/v1", "fixture", "backup-controls-alpha")
        val copies = mutableListOf<String>(); val file = File(context.cacheDir, "provider-controls-test.hbackup")
        val pool = ProviderKeyPool(listOf(ProviderApiKey("a", "First", "backup-controls-alpha"), ProviderApiKey("b", "Spare", "backup-controls-beta", false)), false)
        val proxy = ProviderProxy(ProviderProxyMode.CUSTOM, host = "proxy.example", port = 3128, username = "user", password = "backup-proxy-password")
        try {
            c.providers.saveControls(p, pool, proxy); c.providers.saveGlobalProxy(proxy.copy(port = 3129))
            val backup = SettingsBackup(c)
            for (includeKeys in listOf(false, true)) {
                backup.exportTo(Uri.fromFile(file), "fixture backup password".toCharArray(), includeKeys)
                val decoded = backup.read(Uri.fromFile(file), "fixture backup password".toCharArray())
                val entry = decoded.providers.single { it.config.id == p.id }
                assertEquals(if (includeKeys) pool else pool.copy(keys = emptyList()), entry.keyPool)
                assertEquals(if (includeKeys) proxy else proxy.withoutCredentials(), entry.proxy)
                assertEquals(if (includeKeys) proxy.copy(port = 3129) else proxy.copy(port = 3129).withoutCredentials(), decoded.providerProxy)
                assertFalse(file.readText(Charsets.ISO_8859_1).contains("backup-controls-alpha"))
                if (includeKeys) {
                    backup.restore(SettingsBackupFile(settings = AppSettings(), providers = listOf(entry), providerProxy = decoded.providerProxy))
                    val copy = c.providers.providers.first().single { it.name == p.name + " (restored)" }
                    copies += copy.id
                    assertNotEquals(p.id, copy.id); assertEquals(pool, c.keys.providerKeyPool(copy.id)); assertEquals(proxy, c.keys.providerProxy(copy.id))
                }
            }
            c.providers.saveGlobalProxy(ProviderProxy.DIRECT)
            backup.restore(SettingsBackupFile(version = 3, settings = AppSettings(), providers = listOf(BackupProvider(p.copy(name = "Old controls backup"), apiKey = "old-single-key"))))
            val old = c.providers.providers.first().single { it.name == "Old controls backup (restored)" }
            copies += old.id
            assertEquals("old-single-key", c.keys.getKey(old.id))
            assertEquals(ProviderProxy.DIRECT, c.keys.providerProxy(null))
        } finally {
            copies.forEach { c.providers.delete(it) }; c.providers.delete(p.id)
            c.providers.saveGlobalProxy(global); c.settings.restorePortable(settings); file.delete()
        }
    }

    @Test fun catalogRefreshRetriesARejectedKeyThroughTheSavedProxy() = runBlocking {
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<String>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                val request = buildString { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) } }
                requests += request
                val rejected = requests.size == 1
                val body = if (rejected) """{"error":{"message":"Rejected"}}""" else """{"data":[{"id":"recovered-model"}]}"""
                socket.getOutputStream().write(("HTTP/1.1 ${if (rejected) "401 Unauthorized" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
            } } catch (_: java.net.SocketException) { }
        }
        val p = c.providers.add("Controls catalog fixture", ProviderType.OPENAI_COMPAT, "http://provider.invalid/v1", "fixture", "catalog-alpha")
        try {
            c.providers.saveControls(p, ProviderKeyPool(listOf(ProviderApiKey("a", value = "catalog-alpha"), ProviderApiKey("b", value = "catalog-beta")), false),
                ProviderProxy(ProviderProxyMode.CUSTOM, host = "127.0.0.1", port = server.localPort))
            assertNull(c.providers.refreshCatalog(p.id))
            assertEquals(2, requests.size)
            assertTrue(requests[0].startsWith("GET http://provider.invalid/v1/models"))
            assertTrue(requests[0].contains("Authorization: Bearer catalog-alpha"))
            assertTrue(requests[1].contains("Authorization: Bearer catalog-beta"))
            assertEquals("recovered-model", c.providers.catalog(p.id).single().id)
        } finally { c.providers.delete(p.id); server.close(); worker.join(1000) }
    }

    private class ModelServer : AutoCloseable {
        private val server = java.net.ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${server.localPort}/v1"
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                val lines = mutableListOf<String>()
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                requests += lines.joinToString("\n")
                val body = """{"data":[{"id":"fixture"}]}"""
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
            } } catch (_: java.net.SocketException) { }
        }
        override fun close() { server.close(); worker.join(1000) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun input(label: String) = generateSequence(requireNotNull(find(label))) { it.parent }.first { it.isEditable }
    private fun edit(label: String, value: String) {
        scrollTo(label)
        val input = input(label)
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        SystemClock.sleep(180)
        if (instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun tap(label: String) {
        scrollTo(label)
        val node = requireNotNull(find(label))
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        if (clickable != null) {
            assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(350)
            return
        }
        val rect = bounds(node); val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        SystemClock.sleep(250)
    }
    private fun scrollTo(label: String) {
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(25) {
                if (find(label) != null) return
                val list = nodes().firstOrNull { it.isScrollable } ?: return@repeat
                list.performAction(action); SystemClock.sleep(180)
            }
        }
        error("Provider control unavailable: $label")
    }
    private fun assertOnScreen(label: String) {
        val rect = bounds(requireNotNull(find(label))); val display = context.resources.displayMetrics
        assertTrue(rect.width() > 0 && rect.height() > 0 && rect.left >= 0 && rect.right <= display.widthPixels && rect.top >= 0 && rect.bottom <= display.heightPixels)
    }
    private fun assertSecureWindow() {
        var secure = false
        instrumentation.runOnMainSync {
            secure = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                ((it.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
        }
        assertTrue("Provider credentials sheet must protect its window", secure)
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        error("Provider controls condition timed out")
    }
}
