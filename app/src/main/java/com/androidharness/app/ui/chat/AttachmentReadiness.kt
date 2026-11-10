package com.androidharness.app.ui.chat

import com.androidharness.app.llm.ModelCapabilities
import com.androidharness.app.llm.ModelEntry

enum class AttachmentImportStatus { READING, FAILED, TOO_LARGE, UNSUPPORTED }
data class AttachmentImport(val id: String, val name: String, val status: AttachmentImportStatus = AttachmentImportStatus.READING, val detail: String? = null)

internal fun ChatUiState.imageInputSupport(): Boolean? {
    val provider = activeProvider ?: return null
    val entry = catalogs[provider.id].orEmpty().firstOrNull { it.id == effectiveModel } ?: ModelEntry(effectiveModel.orEmpty())
    val capability = ModelCapabilities.of(entry, provider).images
    return if (capability.source in setOf(ModelCapabilities.Source.PROVIDER, ModelCapabilities.Source.CUSTOM)) capability.supported else null
}
