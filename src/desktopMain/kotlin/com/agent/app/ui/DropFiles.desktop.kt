package com.agent.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable  // desktop extension on DragAndDropEvent
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
actual fun Modifier.onDropFiles(
    onFiles: (List<String>) -> Unit,
    onFilesBytes: (List<Triple<String, String?, ByteArray>>) -> Unit,
    onDragging: (Boolean) -> Unit,
): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { event: DragAndDropEvent ->
        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    },
    target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) {
            onDragging(true)
        }

        override fun onExited(event: DragAndDropEvent) {
            onDragging(false)
        }

        override fun onEnded(event: DragAndDropEvent) {
            onDragging(false)
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            onDragging(false)
            val t = event.awtTransferable
            if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
            @Suppress("UNCHECKED_CAST")
            val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return false
            onFilesBytes(files.map { Triple(it.name, null, it.readBytes()) })
            return true
        }
    },
)
