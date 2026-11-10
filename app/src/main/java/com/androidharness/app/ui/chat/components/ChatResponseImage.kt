package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ChatResponseImages
import com.androidharness.app.core.ImageRef

/** Null outside normal Chat: a model cannot use this to read a device path. */
internal val LocalChatResponseImages = compositionLocalOf<Map<String, ImageRef>?> { null }

@Composable
internal fun ChatResponseImage(url: String, caption: String) {
    val ref = ChatResponseImages.imageId(url)?.let { LocalChatResponseImages.current?.get(it) }
    if (ref == null) {
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolResultImage(ref.copy(caption = caption.ifBlank { ref.caption }), previewHeight = 360.dp, fillPreviewWidth = true)
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
