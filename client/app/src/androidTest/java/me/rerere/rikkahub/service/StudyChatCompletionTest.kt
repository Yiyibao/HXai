package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.studychat.StudyMode
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class StudyChatCompletionTest {
    private lateinit var settings: Settings
    private lateinit var service: ChatService
    private lateinit var repository: ConversationRepository
    private val prefix = "QA-S1-${Uuid.random()}"

    @Test fun richTextVisualFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("visualFixture") == "true")
        val id = Uuid.parse(args.getString("fixtureId")!!)
        if (args.getString("phase") == "cleanup") {
            repository.getConversationById(id)?.let {
                check(it.title == "QA S1 排版样例")
                repository.deleteConversation(it)
            }
            return@runBlocking
        }
        val text = """
            **合成排版样例，不是真实模型回答**

            定积分、分式与矩阵：

            ${'$'}${'$'}\int_0^1 x\,dx=\left[\frac{x^2}{2}\right]_0^1=\frac{1}{2}${'$'}${'$'}

            ${'$'}${'$'}A=\begin{pmatrix}1&2\\3&4\end{pmatrix},\quad A^{-1}=\frac{1}{-2}\begin{pmatrix}4&-2\\-3&1\end{pmatrix}${'$'}${'$'}

            | 步骤 | 依据 | 结果 |
            |---|---|---|
            | 求原函数 | 幂函数积分 | x²/2 |
            | 代入端点 | 微积分基本定理 | 1/2 |

            长公式应能横向查看：

            ${'$'}${'$'}\sum_{k=1}^{n}\frac{k^2+2k+1}{(k+1)(k+2)}=\frac{1^2+2+1}{2\cdot3}+\frac{2^2+4+1}{3\cdot4}+\cdots+\frac{n^2+2n+1}{(n+1)(n+2)}${'$'}${'$'}
        """.trimIndent()
        service.addConversationReference(id)
        service.initializeConversation(id)
        service.saveConversation(id, Conversation(id = id, assistantId = settings.assistantId,
            newConversation = true,
            title = "QA S1 排版样例", profileId = settings.activeProfileId,
            messageNodes = listOf(UIMessage.user("检查中文、长公式、矩阵和表格排版。").toMessageNode(),
                UIMessage.assistant(text).copy(generationState = "COMPLETED").toMessageNode())))
        println("qa_visual_fixture=$id")
    }

    @Before fun setup() = runBlocking {
        val koin = GlobalContext.get()
        settings = withTimeout(20_000) { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        service = koin.get()
        repository = koin.get()
    }

    @After fun cleanup() = runBlocking {
        repository.getConversationsOfAssistant(settings.assistantId, settings.activeProfileId).first().filter { it.title.startsWith("QA-S1-") }.forEach {
            service.stopGeneration(it.id)
            repository.deleteConversation(it)
            service.removeConversationReference(it.id)
        }
    }

    private suspend fun fixture(nodes: List<MessageNode>): Conversation {
        val conversation = Conversation(assistantId = settings.assistantId, title = prefix, messageNodes = nodes,
            newConversation = true,
            profileId = settings.activeProfileId)
        service.addConversationReference(conversation.id)
        service.initializeConversation(conversation.id)
        service.saveConversation(conversation.id, conversation)
        return conversation
    }

    @Test fun modePersistsInRoomAndFork() = runBlocking {
        val conversation = fixture(listOf(UIMessage.user("synthetic mode fixture").toMessageNode()))
        service.updateStudyMode(conversation.id, StudyMode.EXPLAIN)
        val stored = repository.getConversationById(conversation.id)!!
        assertEquals(StudyMode.EXPLAIN, stored.studyMode)
        val fork = service.forkConversationAtMessage(conversation.id, stored.currentMessages.last().id)
        assertEquals(StudyMode.EXPLAIN, fork.studyMode)
        assertEquals(conversation.profileId, fork.profileId)
    }

    @Test fun editDetachesDependentRepliesAndKeepsOldPathInHistory() = runBlocking {
        val question = UIMessage.user("old question")
        val reply = UIMessage.assistant("old answer")
        val followup = UIMessage.user("old followup")
        val conversation = fixture(listOf(question, reply, followup).map { it.toMessageNode() })
        service.editMessage(conversation.id, question.id, listOf(UIMessagePart.Text("new question")))
        val updated = repository.getConversationById(conversation.id)!!
        assertEquals(listOf("new question"), updated.currentMessages.map { it.toText() })
        assertEquals(question.id, updated.messageNodes.first().messages.first().id)
        val archiveId = repository.getConversationsOfAssistant(settings.assistantId, settings.activeProfileId).first().single { it.title.startsWith(prefix) && it.id != conversation.id }.id
        val archive = repository.getConversationById(archiveId)!!
        assertEquals(listOf("old question", "old answer", "old followup"), archive.currentMessages.map { it.toText() })
    }

    @Test fun branchSelectionDetachesRepliesFromTheOtherVersion() = runBlocking {
        val question = UIMessage.user("version one")
        val other = UIMessage.user("version two")
        val node = MessageNode(messages = listOf(question, other), selectIndex = 0)
        val conversation = fixture(listOf(node, UIMessage.assistant("answer to version one").toMessageNode()))
        service.selectMessageNode(conversation.id, node.id, 1)
        val updated = repository.getConversationById(conversation.id)!!
        assertEquals(listOf("version two"), updated.currentMessages.map { it.toText() })
        assertEquals(2, updated.messageNodes.first().messages.size)
    }
}
