package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.env.PackageInstallPhase
import com.androidharness.app.data.env.PackageInstallStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal data class PackageInstallPresentation(
    val title: String,
    val phase: String?,
    val count: String?,
    val bytes: String?,
    val fraction: Float?,
    val percentage: Int?,
    val recent: List<String>,
    val reason: String?,
)

internal fun packageInstallPresentation(call: ToolCallData, result: ChatMessage?, running: Boolean,
    awaitingApproval: Boolean, progress: PackageInstallStatus?): PackageInstallPresentation {
    val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull()
    val reason = (args?.get("reason") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(400)?.takeIf { it.isNotEmpty() }
    val base = toolPresentation(call, result).title
    val active = running && result == null && !awaitingApproval
    val state = progress.takeIf { active }
    val phase = when {
        result != null -> null
        awaitingApproval -> "Waiting for approval"
        !running -> "Not completed"
        state == null -> "Starting installation…"
        else -> when (state.phase) {
            PackageInstallPhase.WAITING -> "Waiting for the package installer…"
            PackageInstallPhase.RESOLVING -> "Resolving packages and dependencies…"
            PackageInstallPhase.DOWNLOADING -> "Downloading ${state.currentPackage.orEmpty()}"
            PackageInstallPhase.VERIFYING -> "Verifying ${state.currentPackage.orEmpty()}"
            PackageInstallPhase.UNPACKING -> "Unpacking ${state.currentPackage.orEmpty()}"
            PackageInstallPhase.CONFIGURING -> state.currentPackage?.let { "Setting up $it" } ?: "Finalizing tools…"
        }
    }
    val downloading = state?.takeIf { it.phase == PackageInstallPhase.DOWNLOADING }
    val fraction = downloading?.totalBytes?.takeIf { it > 0 }?.let {
        (downloading.downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
    }
    return PackageInstallPresentation(
        title = if (active) base.replaceFirst("Install ", "Installing ") else base,
        phase = phase,
        count = state?.takeIf { it.packages.isNotEmpty() }?.let {
            "${it.completed.size.coerceAtMost(it.packages.size)} of ${it.packages.size} packages unpacked"
        },
        bytes = downloading?.let { buildString {
            append(packageBytes(it.downloadedBytes))
            it.totalBytes?.takeIf { size -> size > 0 }?.let { size -> append(" / ${packageBytes(size)}") }
        } },
        fraction = fraction,
        percentage = fraction?.let { (it * 100).toInt() },
        recent = state?.completed.orEmpty().takeLast(3),
        reason = reason,
    )
}

private fun packageBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}
