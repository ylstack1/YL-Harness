package com.androidharness.app.data.transfer

import android.content.Context
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.workspace.SshConnections
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

internal data class SyncState(val working: Boolean = false, val phase: String = "", val path: String = "", val plan: SyncPlan? = null, val message: String? = null)
internal class SyncManager(
    keys: KeyStoreManager, private val ssh: SshConnections,
    private val guard: suspend (WorkspaceFs, suspend () -> Unit) -> Unit,
) {
    val profiles = SyncProfiles(keys)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(SyncState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val active = AtomicReference<RemoteTransferFiles?>()
    private data class Pending(val fs: WorkspaceFs, val profile: SyncProfile, val plan: SyncPlan)
    private var pending: Pending? = null
    @Synchronized fun preview(fs: WorkspaceFs, profile: SyncProfile, direction: SyncDirection) {
        if (job?.isCompleted == false) return
        profile.validate(); val folder = requireNotNull(fs.shellRoot) { "Choose an accessible local workspace for sync." }
        pending = null; mutable.value = SyncState(true, "Scanning")
        job = scope.launch {
            try {
                val remote = remoteTransfer(profile, ssh); active.set(remote)
                val plan = remote.use {
                    runInterruptible {
                        ensureActive(); remote.connect(); ensureActive()
                        SyncEngine.preview(LocalTransferFiles(folder), remote, direction) { path -> ensureActive(); mutable.value = SyncState(true, "Scanning", path) }
                    }
                }
                ensureActive()
                pending = Pending(fs, profile, plan)
                mutable.value = SyncState(plan = plan)
            } catch (e: CancellationException) { mutable.value = SyncState(message = "Sync cancelled. Completed files may already have been saved."); throw e }
            catch (_: Exception) { mutable.value = SyncState(message = if (!isActive) "Sync cancelled. Completed files may already have been saved." else "Could not preview this sync. Check the connection, folder access and file limits, then try again.") }
            finally { active.getAndSet(null)?.close() }
        }
    }
    @Synchronized fun apply(replace: Boolean) {
        if (job?.isCompleted == false) return
        val selected = requireNotNull(pending) { "Preview the sync first." }; pending = null
        mutable.value = SyncState(true, "Transferring")
        job = scope.launch {
            try {
                guard(selected.fs) {
                    val local = LocalTransferFiles(requireNotNull(selected.fs.shellRoot))
                    val remote = remoteTransfer(selected.profile, ssh); active.set(remote)
                    val result = remote.use {
                        runInterruptible {
                            ensureActive(); remote.connect(); ensureActive()
                            SyncEngine.apply(local, remote, selected.plan, replace) { path -> ensureActive(); mutable.value = SyncState(true, "Transferring", path) }
                        }
                    }
                    ensureActive()
                    mutable.value = SyncState(message = "Copied ${result.copied} files; skipped ${result.skipped} replacements. No files were deleted.")
                }
            } catch (e: CancellationException) { mutable.value = SyncState(message = "Sync cancelled. Completed files may already have been saved."); throw e }
            catch (e: Exception) {
                val message = e.message.orEmpty().takeIf { it.startsWith("Source changed:") || it.startsWith("Destination changed:") || it.startsWith("Stop running tasks") }
                mutable.value = SyncState(message = if (!isActive) "Sync cancelled. Completed files may already have been saved." else message ?: "Sync stopped. Check the connection and preview again. Completed files may already have been saved.")
            } finally { active.getAndSet(null)?.close() }
        }
    }
    @Synchronized fun cancel() {
        pending = null; job?.cancel(); active.getAndSet(null)?.close()
        if (job?.isCompleted != false) mutable.value = SyncState(message = "Sync cancelled. Completed files may already have been saved.")
    }
}
