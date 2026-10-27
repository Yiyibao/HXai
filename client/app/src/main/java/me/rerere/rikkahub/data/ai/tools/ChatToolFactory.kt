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
        add(displayImageTool(materialFiles, profileId))
        addAll(createWebImageTools(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.interactive.interactiveUiTool())
        add(me.rerere.rikkahub.feature.interactive.mathPlotTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.math.mathVerifyTool())
        add(me.rerere.rikkahub.feature.material.mathWorkReadTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.mathWorkReviewTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialChapterIndexTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialChapterReadTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialChapterSearchTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialSourceLocationTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialSourceSearchTool(materialFiles, profileId))
        add(me.rerere.rikkahub.feature.material.materialLibrarySearchTool(materialFiles, profileId))
        addAll(me.rerere.rikkahub.feature.material.materialEditTools(materialFiles, profileId, documentService, allowRemoteDocuments))
        documentService?.let { add(me.rerere.rikkahub.feature.material.xlsxRecalculationTool(materialFiles, profileId, it, allowRemoteDocuments)) }
        addAll(me.rerere.rikkahub.feature.material.materialTools(materialFiles, profileId, settings, providerManager, model,
            onImageCreated = sourceConversation?.let { source -> { artifact, prompt, imageModel ->
                val koin = org.koin.core.context.GlobalContext.get()
                me.rerere.rikkahub.feature.imagecreation.ImageCreation.get(koin.get<android.content.Context>(), koin.get(), materialFiles)
                    .registerToolImage(profileId, source.id.toString(), artifact, prompt, imageModel)
            } }))
        documentService?.let { addAll(me.rerere.rikkahub.feature.material.documentServiceTools(it, profileId, me.rerere.ai.provider.Modality.IMAGE in model.inputModalities, allowRemote = allowRemoteDocuments)) }
        // Legacy memory tools are disabled until memory rows carry profile ownership.
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createEnhancedSearchTools(settings, materialFiles, profileId))
        }
        addAll(localTools.getTools(assistant.localTools.filter {
            it in listOf(
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.AskUser,
                LocalToolOption.ChartDisplay,
            )
        }))
        if (assistant.enableRecentChatsReference && learningMemories.read(profileId).let {
                !it.recoveryBlocked && it.suppressedSources.isEmpty() && it.legacySuppressedSources.isEmpty()
            }) {
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
                    checkEnabled = { name ->
                        val current = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.value
                        require(skillManager.findSkill(name) != null && (name == mathBuiltin?.name ||
                            current.assistants.singleOrNull { it.id == assistant.id }?.enabledSkills?.contains(name) == true)) { "技能已停用或卸载" }
                    },
                )
            )
        }

        // Shared MCP tools have no profile authorization contract yet.
        me.rerere.rikkahub.feature.agent.SyntheticMcpRuntime.toolFor(profileId)?.let { add(it) }
        me.rerere.rikkahub.feature.agent.ExtensionMcpRuntime.toolFor(profileId)?.let { add(it) }

    }.map { original ->
        val trusted=me.rerere.rikkahub.feature.agent.TrustedToolRegistry.require(original.name)
        val decorated=decorateTool(original)
        decorated.copy(needsApproval={ input ->
            trusted.risk==me.rerere.rikkahub.feature.agent.TrustedToolRegistry.Risk.EXTERNAL || decorated.needsApproval(input)
        })
    }

}
