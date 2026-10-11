package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import java.io.File

internal object BackupPaths {
    fun rebase(value: JsonElement, oldRoot: String, newRoot: File): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (name, child) ->
            if (name in setOf("url", "uri", "imageUrl", "fileUrl") || child is JsonObject || child is JsonArray) rebase(child, oldRoot, newRoot) else child
        })
        is JsonArray -> JsonArray(value.map { if (it is JsonObject || it is JsonArray) rebase(it, oldRoot, newRoot) else it })
        is JsonPrimitive -> if (value.isString && oldRoot.isNotBlank() && value.content.startsWith("file://$oldRoot/")) {
            val relative = value.content.removePrefix("file://$oldRoot/")
            // Reject traversal even when encoded by Uri.
            val decoded = java.net.URI(value.content).path.removePrefix("$oldRoot/")
            PendingRestore.resolveInside(newRoot, decoded)
            JsonPrimitive("file://${newRoot.absolutePath}/$relative")
        } else value
    }
}
