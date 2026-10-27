package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.MaterialReader
import me.rerere.rikkahub.feature.material.MaterialWriter
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Native DB -> generated Pigeon -> installed Dart -> actual accessibility and command path. */
@RunWith(AndroidJUnit4::class)
class AgentTaskSurfaceTest {
    @Test fun currentPickerCannotReplaceTaskLockAndStopRequestWaitsForRealReceipt()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val automation=instrumentation.uiAutomation
        val koin=GlobalContext.get()
        val settings=koin.get<SettingsStore>()
        val original=settings.settingsFlow.first { !it.init }
        val materials=koin.get<MaterialFiles>()
        val repo=koin.get<ConversationRepository>()
        val service=koin.get<me.rerere.rikkahub.service.ChatService>()
        val locked=Model(modelId="qa-task-locked",displayName="QA接受时模型")
        val picked=Model(modelId="qa-current-picker",displayName="QA当前选择模型")
        val provider=ProviderSetting.OpenAI(name="QA本机任务提供商",baseUrl="http://127.0.0.1:9/v1",apiKey="synthetic",models=listOf(locked,picked))
        val assistant=Assistant(name="QA真实任务面板",chatModelId=locked.id,reasoningLevel=ReasoningLevel.OFF)
        val accepted=original.copy(providers=listOf(provider),assistants=original.assistants+assistant,assistantId=assistant.id,
            chatModelId=locked.id,enableSuggestion=false,learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) })
        val id=Uuid.random()
        val user=UIMessage.user("创建只含合成材料的文本文件")
        val conversation=Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA任务锁与停止真实表面",newConversation=true,
            messageNodes=listOf(MessageNode(messages=listOf(user))))
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        val oldTheme=prefs.getString("theme",null)
        val oldScale=if(prefs.contains("fontScale")) prefs.getFloat("fontScale",1f) else null
        val outputDir=File(context.cacheDir,"task-surface-${Uuid.random()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        var store: AgentTaskStore?=null
        var lease: AgentLease?=null
        var material: MaterialFile?=null
        fun text(root: AccessibilityNodeInfo?): String {
            if(root==null) return ""
            return buildString {
                root.text?.let { append(it).append('\n') };root.contentDescription?.let { append(it).append('\n') }
                for(index in 0 until root.childCount) append(text(root.getChild(index)))
            }
        }
        fun click(label: String,root: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(root==null) return false
            if(root.text?.toString()?.substringBefore(", ")==label ||
                root.contentDescription?.toString()?.substringBefore(", ")==label) {
                var target: AccessibilityNodeInfo=root
                while(!target.isClickable) target=target.parent ?: return false
                return target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until root.childCount).any { click(label,root.getChild(it)) }
        }
        fun waitFor(label: String,condition: () -> Boolean) {
            val deadline=System.currentTimeMillis()+40_000
            while(System.currentTimeMillis()<deadline) {
                if(condition()) return
                Thread.sleep(100)
            }
            File(outputDir,"failure.txt").writeText(text(automation.rootInActiveWindow))
            fail(label)
        }
        fun capture(name: String) {
            File(outputDir,"$name.txt").writeText(text(automation.rootInActiveWindow))
            File(outputDir,"$name.png").outputStream().use {
                assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        }
        try {
            settings.update(accepted);settings.settingsFlow.first { it.chatModelId==locked.id }
            service.addConversationReference(id);service.saveConversation(id,conversation)
            val scheduler=AgentScheduler.shared(context)
            val task=scheduler.accept(conversation,user.id.toString(),Json.encodeToString(user.parts),accepted)
            store=AgentTaskStore(context)
            lease=store.claim(task.owner,task.id)
            val usageRequest=store.beginRequest(lease,"planning","synthetic surface ledger",
                AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference))
            store.finishRequest(lease,usageRequest,me.rerere.ai.core.TokenUsage(promptTokens=0,completionTokens=7))
            val input=buildJsonObject { put("format","md");put("title","QA长文件名".repeat(12));put("content","synthetic late receipt") }.toString()
            store.beginCall(lease,"surface-write","create_material_file",input)
            material=materials.runTask(MaterialFile(Uuid.random().toString(),task.owner,"QA长文件名".repeat(12)+".md","md",output=true)) { file ->
                MaterialWriter.write(file,"md","synthetic late receipt",emptyList(),emptyList());"真实合成文件已写入"
            }
            settings.update(accepted.copy(chatModelId=picked.id,assistants=accepted.assistants.map {
                if(it.id==assistant.id) it.copy(chatModelId=picked.id) else it
            }))
            settings.settingsFlow.first { it.chatModelId==picked.id }
            for((theme,scale) in listOf("light" to 1f,"dark" to 1.6f)) {
                prefs.edit().putString("theme",theme).putFloat("fontScale",scale).commit()
                scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                waitFor("actual task panel absent") { text(automation.rootInActiveWindow).contains("执行中") }
                waitFor("task panel cannot expand") { click("执行中") }
                waitFor("accepted model lock absent") { text(automation.rootInActiveWindow).contains(locked.modelId) }
                assertFalse(text(automation.rootInActiveWindow).contains(picked.modelId))
                assertTrue(text(automation.rootInActiveWindow).contains(picked.displayName))
                capture("$theme-$scale-lock")
                val beforeUsage=store.snapshot(task.owner,task.id)
                waitFor("actual request ledger cannot open") { click("查看请求用量") }
                waitFor("native ledger did not reach installed Dart") {
                    text(automation.rootInActiveWindow).contains("输入 Token：报告 0") &&
                        text(automation.rootInActiveWindow).contains("总 Token：1 次未知")
                }
                assertTrue(text(automation.rootInActiveWindow).contains("费用未知"))
                assertEquals(beforeUsage,store.snapshot(task.owner,task.id))
                capture("$theme-$scale-request-ledger")
                waitFor("actual request ledger cannot close") { click("关闭用量") }
                scenario.close();scenario=null
            }
            prefs.edit().putString("theme","light").putFloat("fontScale",1f).commit()
            scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
            waitFor("running task missing") { text(automation.rootInActiveWindow).contains("执行中") }
            waitFor("task panel cannot expand") { click("执行中") }
            waitFor("pause command cannot be invoked") { click("暂停") }
            waitFor("stop request falsely displayed as stopped") { text(automation.rootInActiveWindow).contains("已请求停止，尚未确认停止") }
            assertEquals("STOP_REQUESTED",store.get(task.owner,task.id).state)
            assertFalse(text(automation.rootInActiveWindow).contains("已暂停"))
            capture("stop-requested")
            val facade=StudyHostFacade(context,settings,koin.get(),service,koin.get(),materials,koin.get(),pickFiles={ emptyList() })
            try {
                val bootstrap=facade.getBootstrap(task.owner,1L)
                val paused=store.get(task.owner,task.id)
                val result=facade.taskCommand(task.owner,id.toString(),task.id,Uuid.random().toString(),bootstrap.contextEpoch,
                    paused.revision,paused.fence,"supplement","保留原任务模型与已写成果，增加验收说明")
                assertTrue(result.success)
                assertTrue(store.get(task.owner,task.id).manualPaused)
                assertNull(service.getGenerationJobStateFlow(id).first())
            } finally { facade.close() }
            val actual=requireNotNull(material)
            assertEquals("synthetic late receipt",MaterialReader.read(materials.verifiedFile(task.owner,actual.id),"md").text.trim())
            val receipt=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Document(materials.file(actual).toUri().toString(),actual.name,"text/markdown")))
            store.finishCall(lease,"surface-write",AgentTaskStore.hash(receipt),true,receipt)
            store.finish(lease,"COMPLETED_UNVERIFIED")
            waitFor("known late receipt did not settle pause") { text(automation.rootInActiveWindow).contains("已暂停") }
            assertEquals("PAUSED_BY_USER",store.get(task.owner,task.id).state)
            capture("paused-after-real-readback")
            println("TASK_SURFACE_EVIDENCE=${outputDir.name}")
        } finally {
            scenario?.close()
            lease?.let { current -> store?.let { db ->
                if(db.get(current.task.owner,current.task.id).state in setOf("RUNNING","STOP_REQUESTED")) db.finish(current,"INTERRUPTED")
            } }
            store?.close()
            service.removeConversationReference(id)
            repo.getConversationById(id)?.let { repo.deleteConversation(it) }
            material?.let { materials.file(it).delete();File(context.filesDir,"materials/${it.profileId}/${it.id}.meta.json").delete() }
            val editor=prefs.edit()
            if(oldTheme==null) editor.remove("theme") else editor.putString("theme",oldTheme)
            if(oldScale==null) editor.remove("fontScale") else editor.putFloat("fontScale",oldScale)
            editor.commit()
            settings.update(original);settings.settingsFlow.first { it.assistantId==original.assistantId }
        }
    }
}
