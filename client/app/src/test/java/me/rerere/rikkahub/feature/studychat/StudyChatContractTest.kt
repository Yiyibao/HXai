package me.rerere.rikkahub.feature.studychat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 验证交接规格书中规定的：
 * 1. 一次性事件去重（OneShotFeedback）：旋转/重组不重播，归属档案匹配校验
 * 2. 模型身份区分：(providerId, modelId) 唯一确定模型，跨提供商同名模型不混淆
 * 3. 错误恢复状态映射（INTERRUPTED / CANCELLED / FAILED）
 */
class StudyChatContractTest {

    @Before
    fun setUp() {
        OneShotFeedback.clear()
    }

    @After
    fun tearDown() {
        OneShotFeedback.clear()
    }

    @Test
    fun testOneShotFeedbackDeduplication() {
        val event = OneShotFeedbackEvent(
            profileId = "profile_1",
            taskId = "task_100",
            version = 1L,
            eventKey = "memory_commit_100",
            eventType = OneShotFeedbackEvent.EventType.MEMORY_SAVED
        )

        // 首次消费当前活跃档案的事件成功
        assertTrue(OneShotFeedback.tryConsume(event, activeProfileId = "profile_1"))

        // 第二次重复消费（如屏幕旋转、Compose重组）必须被拦截
        assertFalse(OneShotFeedback.tryConsume(event, activeProfileId = "profile_1"))
        assertTrue(OneShotFeedback.isConsumed(event.eventKey, "profile_1", 1L))

        // 新版本事件允许再次消费
        val nextVersionEvent = event.copy(version = 2L)
        assertTrue(OneShotFeedback.tryConsume(nextVersionEvent, activeProfileId = "profile_1"))
    }

    @Test
    fun testOneShotFeedbackProfileIsolation() {
        val event = OneShotFeedbackEvent(
            profileId = "profile_alice",
            taskId = "task_200",
            version = 1L,
            eventKey = "file_registered_200",
            eventType = OneShotFeedbackEvent.EventType.FILE_REGISTERED
        )

        // 非当前活跃档案的事件不可消费
        assertFalse(OneShotFeedback.tryConsume(event, activeProfileId = "profile_bob"))

        // 切换到属于该档案时方可消费
        assertTrue(OneShotFeedback.tryConsume(event, activeProfileId = "profile_alice"))
    }

    @Test
    fun testModelIdentityGroupedByProvider() {
        val providerAId = Uuid.random()
        val providerBId = Uuid.random()

        val modelA = Model(
            id = Uuid.random(),
            modelId = "gpt-4o",
            displayName = "OpenAI 官方 GPT-4o",
            abilities = listOf(ModelAbility.TOOL),
            type = ModelType.CHAT
        )
        val modelB = Model(
            id = Uuid.random(),
            modelId = "gpt-4o",
            displayName = "第三方中转 GPT-4o",
            abilities = listOf(ModelAbility.TOOL),
            type = ModelType.CHAT
        )

        val providerA = ProviderSetting.OpenAI(
            id = providerAId,
            name = "官方接口",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test-1",
            enabled = true,
            models = listOf(modelA)
        )
        val providerB = ProviderSetting.OpenAI(
            id = providerBId,
            name = "备用镜像",
            baseUrl = "https://mirror.example.com/v1",
            apiKey = "sk-test-2",
            enabled = true,
            models = listOf(modelB)
        )

        val providers = listOf(providerA, providerB)

        val groups = studyChatModels(providers, "gpt-4o")
        assertEquals(2, groups.size)
        assertEquals(listOf(providerAId, providerBId), groups.map { it.first.id })
        assertEquals(listOf(modelA.id, modelB.id), groups.flatMap { it.second }.map { it.id })
        assertFalse(StudyModelIdentity(providerAId, modelA.id) == StudyModelIdentity(providerBId, modelA.id))
        assertEquals(listOf(providerAId), studyChatModels(providers, "官方接口").map { it.first.id })
        assertEquals(listOf(providerBId), studyChatModels(listOf(providerA.copy(enabled = false), providerB), "").map { it.first.id })
        assertTrue(studyChatModels(providers, "missing").isEmpty())
    }

    @Test
    fun testGenerationRecoveryStateNormalization() {
        assertEquals("CANCELLED", normalizeStudyRecoveryState("CANCELLED"))
        assertEquals("INTERRUPTED", normalizeStudyRecoveryState("INTERRUPTED"))
        assertEquals("INTERRUPTED", normalizeStudyRecoveryState("RUNNING"))
        assertEquals("INTERRUPTED", normalizeStudyRecoveryState("WAITING_TOOL"))
        assertEquals("FAILED", normalizeStudyRecoveryState("FAILED"))
        assertEquals("FAILED", normalizeStudyRecoveryState("UNKNOWN_ERR"))
        assertEquals("FAILED", normalizeStudyRecoveryState(null))
    }

    @Test
    fun feedbackSurvivesLedgerRecreation() {
        val keys = mutableSetOf<String>()
        val storage = object : FeedbackStorage {
            override fun contains(key: String) = key in keys
            override fun record(key: String): Boolean { keys.add(key); return true }
            override fun clear() { keys.clear() }
        }
        val event = OneShotFeedbackEvent("alice", eventKey = "memory-1", version = 1,
            eventType = OneShotFeedbackEvent.EventType.MEMORY_SAVED)
        assertTrue(FeedbackLedger(storage).tryConsume(event, "alice"))
        assertFalse(FeedbackLedger(storage).tryConsume(event, "alice"))
        assertTrue(FeedbackLedger(storage).tryConsume(event.copy(version = 2), "alice"))
        assertFalse(FeedbackLedger(storage).tryConsume(event.copy(profileId = "bob"), "alice"))
    }

    @Test
    fun failedDurableWriteDoesNotPublishFeedback() {
        val storage = object : FeedbackStorage {
            override fun contains(key: String) = false
            override fun record(key: String) = false
            override fun clear() {}
        }
        val event = OneShotFeedbackEvent("alice", eventKey = "memory-1",
            eventType = OneShotFeedbackEvent.EventType.MEMORY_SAVED)
        assertFalse(FeedbackLedger(storage).tryConsume(event, "alice"))
    }

    @Test
    fun modelSelectionWaitsForPersistence() = runBlocking {
        val model = Model(modelId = "math-model")
        val gate = CompletableDeferred<Unit>()
        var saved = false
        val result = async {
            commitStudyModelSelection(model) { gate.await(); saved = true }
        }
        kotlinx.coroutines.yield()
        assertFalse(result.isCompleted)
        assertFalse(saved)
        gate.complete(Unit)
        assertTrue(result.await().isSuccess)
        assertTrue(saved)
    }

    @Test
    fun failedModelSelectionPreservesPreviousSelection() = runBlocking {
        val previous = Model(modelId = "previous")
        val next = Model(modelId = "next")
        var selection = previous
        val result = commitStudyModelSelection(next) { throw java.io.IOException("disk unavailable") }
        if (result.isSuccess) selection = next
        assertTrue(result.isFailure)
        assertEquals(previous, selection)
    }

    @Test
    fun cancelledModelSelectionPropagatesCancellation() = runBlocking {
        try {
            commitStudyModelSelection(Model(modelId = "next")) { throw CancellationException() }
            org.junit.Assert.fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test
    fun providerValidationRejectsInvalidAddresses() {
        assertFalse(isValidStudyProvider(ProviderSetting.OpenAI(name = "", baseUrl = "https://example.com")))
        assertFalse(isValidStudyProvider(ProviderSetting.OpenAI(baseUrl = "http-invalid")))
        assertFalse(isValidStudyProvider(ProviderSetting.OpenAI(baseUrl = "file:///tmp/model")))
        assertFalse(isValidStudyProvider(ProviderSetting.OpenAI(baseUrl = "https://key@example.com")))
        assertTrue(isValidStudyProvider(ProviderSetting.Claude(baseUrl = "https://example.com/v1")))
        assertTrue(isValidStudyProvider(ProviderSetting.Google(baseUrl = "http://127.0.0.1:8080")))
    }
}
