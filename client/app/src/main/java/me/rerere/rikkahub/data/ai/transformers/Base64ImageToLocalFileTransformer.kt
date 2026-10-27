package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer {
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val filesManager = getKoin().get<FilesManager>()
        val materials = getKoin().get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        return messages.map { message ->
            filesManager.convertBase64ImagePartToLocalFile(message, materials, ctx.settings.activeProfileId)
        }
    }
}
