package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository

// Web search is available to every conversation; legacy flags do not gate this capability.
@Suppress("UNUSED_PARAMETER")
internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean = true

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val learningMemories: me.rerere.rikkahub.feature.memory.LearningMemoryStore,
    private val materialFiles: me.rerere.rikkahub.feature.material.MaterialFiles,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
    private val documentService: me.rerere.rikkahub.feature.material.DocumentServiceRepository? = null,
    private val decorateTool: (Tool) -> Tool = { it },
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        profileId: String,
        allowRemoteDocuments: Boolean = false,
        sourceConversation: me.rerere.rikkahub.data.model.Conversation? = null,
    ): List<Tool> = buildList {
        // 智能交互界面工具 - P0核心功能
        sourceConversation?.let { source -> add(me.rerere.rikkahub.feature.interactive.surfaceRevisionTool {
            conversationRepository.getConversationById(source.id, profileId = profileId) ?: error("会话已不存在")
        }) }
        add(displayImageTool())
        addAll(createWebImageTools())
        add(me.rerere.rikkahub.feature.interactive.interactiveUiTool())
        add(me.rerere.rikkahub.feature.interactive.mathPlotTool(materialFiles, profileId))
        addAll(me.rerere.rikkahub.feature.material.materialEditTools(materialFiles, profileId, documentService, allowRemoteDocuments))
        addAll(me.rerere.rikkahub.feature.material.materialTools(materialFiles, profileId, settings, providerManager, model,
            onImageCreated = sourceConversation?.let { source -> { artifact, prompt, imageModel ->
                val koin = org.koin.core.context.GlobalContext.get()
                me.rerere.rikkahub.feature.imagecreation.ImageCreation.get(koin.get<android.content.Context>(), koin.get(), materialFiles)
                    .registerToolImage(profileId, source.id.toString(), artifact, prompt, imageModel)
            } }))
        documentService?.let { addAll(me.rerere.rikkahub.feature.material.documentServiceTools(it, profileId, me.rerere.ai.provider.Modality.IMAGE in model.inputModalities, allowRemote = allowRemoteDocuments)) }
        // Legacy memory tools are disabled until memory rows carry profile ownership.
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createEnhancedSearchTools(settings))
        }
        addAll(localTools.getTools(assistant.localTools.filter {
            it in listOf(
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.AskUser,
                LocalToolOption.ChartDisplay,
            )
        }))
        if (assistant.enableRecentChatsReference && learningMemories.read(profileId).suppressedSources.isEmpty()) {
            addAll(createConversationTools(conversationRepository, assistant.id, profileId))
        }
        // Shared workspaces have no profile ownership; do not expose them to study runs.
        val availableSkills = skillManager.listSkills()
        // Ship mathematical visualization as a discoverable chat capability, without editing
        // the user's assistant settings or enabling arbitrary imported skills automatically.
        val mathBuiltin = availableSkills.firstOrNull {
            it.name == me.rerere.rikkahub.feature.interactive.MATH_VISUAL_SKILL && it.builtin
        }
        val enabledSkills = assistant.enabledSkills + listOfNotNull(mathBuiltin?.name)
        if (enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = enabledSkills,
                    allSkills = availableSkills,
                    materials = materialFiles,
                    profileId = profileId,
                )
            )
        }

        // Shared MCP tools have no profile authorization contract yet.

    }.map(decorateTool)

}
