package com.androidharness.app.data.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

internal data class FtpShareState(val starting: Boolean = false, val running: Boolean = false, val project: String = "", val username: String = "",
    val addresses: List<String> = emptyList(), val readOnly: Boolean = true, val clients: Int = 0, val error: String? = null)
internal class FtpShareManager(private val context: Context, private val guard: suspend (WorkspaceFs, suspend () -> Unit) -> Unit) {
    private class Request(val fs: WorkspaceFs, val name: String, val port: Int, val username: String, val password: String, val lan: Boolean, val readOnly: Boolean)
    private var request: Request? = null
    private var server: FtpShareServer? = null
    private var activePassword: String? = null
    @Synchronized fun passwordForActiveShare(): String? = activePassword
    private val mutable = MutableStateFlow(FtpShareState())
    val state = mutable.asStateFlow()
    @Synchronized fun start(fs: WorkspaceFs, name: String, port: Int, username: String, password: String, lan: Boolean, readOnly: Boolean) {
        check((context.applicationContext as? com.androidharness.app.HarnessApp)?.container?.runtimePolicy?.state?.value?.canRun != false) { context.getString(com.androidharness.app.R.string.background_run_blocked) }
        check(!mutable.value.running && !mutable.value.starting) { "Stop the existing FTP server first." }
        require(fs.shellRoot != null) { "Choose an accessible local workspace to share." }
        require(port in 1024..65535 && username.isNotBlank() && password.length >= 8) { "Check the port, username and password." }
        request = Request(fs, name, port, username, password, lan, readOnly)
        mutable.value = FtpShareState(starting = true, project = name)
        try { ContextCompat.startForegroundService(context, Intent(context, FtpShareService::class.java)) }
        catch (e: Exception) { request = null; mutable.value = FtpShareState(error = "Could not start FTP sharing. Open the app and try again."); throw e }
    }
    @Synchronized fun open(): Boolean {
        if (server != null) return true
        val selected = request ?: return false
        request = null
        return try {
            val files = LocalTransferFiles(requireNotNull(selected.fs.shellRoot))
            val instance = FtpShareServer(files, selected.username, selected.password, selected.readOnly,
                writeGuard = { action -> runBlocking { guard(selected.fs) { action() } } },
                clientsChanged = { count -> mutable.update { if (it.running) it.copy(clients = count) else it } })
            try { instance.start(selected.port, selected.lan) } catch (e: Exception) { instance.close(); throw e }
            server = instance
            activePassword = selected.password
            val hosts = if (!selected.lan) listOf("127.0.0.1") else runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct()
            }.getOrDefault(emptyList())
            mutable.value = FtpShareState(running = true, project = selected.name, username = selected.username,
                addresses = (hosts + "127.0.0.1").distinct().map { "ftp://$it:${instance.port}" }, readOnly = selected.readOnly)
            true
        } catch (_: Exception) { mutable.value = FtpShareState(error = "Could not start FTP sharing. Check folder access or choose a different port."); false }
    }
    @Synchronized fun stop(error: String? = null) {
        request = null; server?.close(); server = null; activePassword = null; mutable.value = FtpShareState(error = error)
        context.stopService(Intent(context, FtpShareService::class.java))
    }
}
