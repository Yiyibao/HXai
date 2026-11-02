package me.rerere.rikkahub.feature.agent

import android.content.Intent
import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.rikkahub.service.ChatService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Explicit synthetic process-smoke entry only; ordinary full-suite runs skip without a fixture.
 * Driver owns the HTTP services, QA package, process kill and readback of actual file bytes.
 */
@RunWith(AndroidJUnit4::class)
class CloudDeviceProcessProbe {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val marker get()=File(context.noBackupFilesDir,"r27-cloud-probe.json")
    private val owner=DEFAULT_PROFILE_ID
    private val koin get()=GlobalContext.get()
    private val settings get()=koin.get<SettingsStore>()
    private val scheduler get()=AgentScheduler.shared(context)
    private val materials get()=koin.get<MaterialFiles>()
    private fun save(value: JsonObject) {
        val file=android.util.AtomicFile(marker)
        val stream=file.startWrite()
        try { stream.write(value.toString().toByteArray());file.finishWrite(stream) }
        catch(error: Throwable) { file.failWrite(stream);throw error }
    }
    private fun saved()=Json.parseToJsonElement(marker.readText()).jsonObject
    private fun fixture(): JsonObject {
        val input=File(context.noBackupFilesDir,"r27-test-input.json")
        if(input.exists()) {
            val value=Json.parseToJsonElement(input.readText()).jsonObject
            val ref=CredentialVault(context).protect(value.toString())
            save(buildJsonObject { put("fixtureReference",ref) });check(input.delete());return value
        }
        return Json.parseToJsonElement(CredentialVault(context).resolve(saved().getValue("fixtureReference").jsonPrimitive.content)).jsonObject
    }
    @Before fun isolatedFixtureOnly() {
        Assume.assumeTrue(args.getString("r27")=="1")
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val service=instrumentation.uiAutomation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo=service
    }
    private fun foreground(): Boolean=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private suspend fun openForeground() {
        context.startActivity(Intent(context,me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitSurface("actual foreground activity",60_000) { foreground() }
    }
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    private suspend fun lockOwnedDevice() {
        shell("input keyevent KEYCODE_SLEEP")
        withTimeout(10_000) { while(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            context.getSystemService(android.os.PowerManager::class.java).isInteractive)delay(100) }
    }
    private suspend fun unlockOwnedDevice() {
        check(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        shell("input keyevent KEYCODE_WAKEUP")
        withTimeout(10_000) { while(!context.getSystemService(android.os.PowerManager::class.java).isInteractive)delay(100) }
        val metrics=context.resources.displayMetrics
        fun key(id: String)=instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")?.firstOrNull { it.isVisibleToUser && it.isEnabled }
        waitSurface("real secure PIN keypad") {
            if(key("key7")!=null)true else {
                // A wake ACK does not mean the lockscreen animation accepts gestures yet.
                // Open the secure bouncer normally; only the real PIN below may unlock it.
                shell("input keyevent KEYCODE_MENU")
                shell("input swipe ${metrics.widthPixels/2} ${metrics.heightPixels*19/20} ${metrics.widthPixels/2} ${metrics.heightPixels/10} 500")
                false
            }
        }
        for(digit in "7351")check(requireNotNull(key("key$digit")).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        check(requireNotNull(key("key_enter")).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        withTimeout(10_000) { while(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)delay(100) }
        openForeground()
    }
    private fun releaseFault(config: JsonObject) {
        val endpoint=config.getValue("faultEndpoint").jsonPrimitive.content
        val url=java.net.URI(endpoint)
        check(url.scheme=="http" && url.host=="127.0.0.1")
        val connection=java.net.URL(endpoint+"/qa/release").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        try {
            connection.requestMethod="POST";connection.setRequestProperty("X-QA-Fault-Key",config.getValue("faultKey").jsonPrimitive.content)
            connection.connectTimeout=10_000;connection.readTimeout=10_000;connection.doOutput=true
            connection.outputStream.use { it.write(byteArrayOf()) };check(connection.responseCode==200)
            connection.inputStream.close()
        } finally { connection.disconnect() }
    }
    private fun surfaceText(node: android.view.accessibility.AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow): String {
        if(node==null)return ""
        return buildString {
            node.text?.let { appendLine(it) };node.contentDescription?.let { appendLine(it) }
            for(index in 0 until node.childCount)append(surfaceText(node.getChild(index)))
        }
    }
    private fun click(label: String,node: android.view.accessibility.AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        if(node==null)return false
        if(node.text?.contains(label)==true || node.contentDescription?.contains(label)==true) {
            var target: android.view.accessibility.AccessibilityNodeInfo=node
            while(!target.isClickable)target=target.parent ?: return false
            return target.isEnabled && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
        return (0 until node.childCount).any { click(label,node.getChild(it)) }
    }
    private fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow,back: Boolean=false): Boolean {
        if(node==null)return false
        val action=if(back)android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if(node.isScrollable && node.performAction(action))return true
        return (0 until node.childCount).any { scroll(node.getChild(it),back) }
    }
    private suspend fun waitSurface(label: String,timeout: Long=40_000,condition: () -> Boolean) {
        try { withTimeout(timeout) { while(!condition())delay(500) } }
        catch(error: Exception) {
            File(context.noBackupFilesDir,"r27-surface-failure.txt").writeText("$label\n${surfaceText()}")
            File(context.noBackupFilesDir,"r27-surface-failure.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            throw error
        }
    }
    private suspend fun captureTaskSurface(marker: String,name: String,state: String) {
        waitSurface("drawer") { click("历史对话") }
        waitSurface("task center") { click("任务中心") }
        waitSurface("actual task row") { click(marker) }
        delay(1500) // Let the real cloud.read snapshot replace the initial loading card.
        var moves=0
        waitSurface("actual cloud state") {
            val actual=surfaceText()
            File(context.noBackupFilesDir,"r27-surface-trace.txt").appendText("\n---\n$actual")
            if(actual.contains("权威状态：$state"))true else { scroll(back=(moves++/4)%2==1);false }
        }
        File(context.noBackupFilesDir,"r27-$name.txt").writeText(surfaceText())
        File(context.noBackupFilesDir,"r27-$name.png").outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
        }
    }
    private suspend fun facade(): StudyHostFacade {
        withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        return StudyHostFacade(context,settings,koin.get(),koin.get(),koin.get(),materials,koin.get(),
            pickFiles={ emptyList() },foregroundConfirmationAllowed=::foreground)
    }
    private suspend fun call(facade: StudyHostFacade,method: String,task: String?=null,fields: Map<String,Any?> = emptyMap()): Map<*,*> {
        val epoch=facade.getBootstrap(owner,1).contextEpoch
        val reply=CompletableDeferred<Map<*,*>>()
        facade.handleFrontendFeature(method,mapOf("profileId" to owner,"epoch" to epoch,"taskId" to task)+fields,object: MethodChannel.Result {
            override fun success(result: Any?) { reply.complete(result as? Map<*,*> ?: emptyMap<Any,Any>()) }
            override fun error(code: String,message: String?,details: Any?) { reply.completeExceptionally(IllegalStateException("$method $code: $message")) }
            override fun notImplemented() { reply.completeExceptionally(IllegalStateException("Actual frontend callback not implemented")) }
        })
        return withTimeout(150_000) { reply.await() }
    }
    private fun deviceFields(pending: Map<*,*>)=mapOf(
        "callId" to pending["callId"],"inputHash" to pending["inputHash"],
        "grantEpoch" to pending["grantEpoch"],"taskRevision" to pending["taskRevision"])
    private suspend fun seed(goal: String,config: JsonObject): AgentTask {
        val before=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val model=Model(modelId="synthetic-locked-model",displayName="R27 accepted original",
            abilities=listOf(ModelAbility.TOOL,ModelAbility.REASONING))
        val changed=Model(modelId="must-not-dispatch-current-selection",displayName="R27 later selection")
        val provider=ProviderSetting.OpenAI(name="R27 synthetic localhost",models=listOf(model,changed),
            apiKey=config.getValue("secret").jsonPrimitive.content,baseUrl=config.getValue("providerBase").jsonPrimitive.content)
        val assistant=Assistant(name="R27 accepted assistant",chatModelId=model.id,reasoningLevel=ReasoningLevel.HIGH,
            streamOutput=false,systemPrompt="Preserve the R27 original goal and actual owned file receipts.")
        check(before.activeProfileId==owner)
        settings.update(before.copy(providers=listOf(provider),assistants=before.assistants+assistant,assistantId=assistant.id,chatModelId=model.id,
            fastModelId=Uuid.random(),enableSuggestion=false,
            networkSetting=before.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword="",enableAutoRetry=false)))
        val selected=settings.settingsFlow.first { it.chatModelId==model.id }
        val message=UIMessage.user(goal)
        val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=owner,title="R27 actual hosted task",
            messageNodes=listOf(MessageNode.of(message)))
        val repo=koin.get<ConversationRepository>();repo.insertConversation(conversation)
        val accepted=scheduler.accept(conversation,message.id.toString(),Json.encodeToString(message.parts),selected)
        repo.checkpointMessages(conversation.copy(messageNodes=listOf(MessageNode.of(message.copy(generationTaskId=accepted.id)))))
        save(JsonObject(saved()+mapOf("taskId" to JsonPrimitive(accepted.id),"conversationId" to JsonPrimitive(conversation.id.toString()),
            "changedModel" to JsonPrimitive(changed.id.toString()),"acceptedModel" to JsonPrimitive(model.id.toString()),"acceptedProvider" to JsonPrimitive(provider.id.toString()))))
        return accepted
    }
    @Test fun prepareServerTask(): Unit=runBlocking {
        val config=fixture()
        val source=seed("R27_SERVER_MARKDOWN Create an immutable Markdown file containing Verified after the phone process exited.",config)
        openForeground();val host=facade()
        call(host,"cloud.connect",fields=mapOf("endpoint" to config.getValue("endpoint").jsonPrimitive.content,
            "token" to config.getValue("token").jsonPrimitive.content,"consent" to true))
        val accepted=call(host,"cloud.delegate",source.id,mapOf("consent" to true,"hostingMode" to "UNTIL_REVOKED","includeHistory" to true,"requireEvidence" to true,"sources" to emptyList<String>()))
        assertEquals(source.modelId,(accepted["model"] as Map<*,*>)["model_id"])
        val current=settings.settingsFlow.value
        val next=Uuid.parse(saved().getValue("changedModel").jsonPrimitive.content)
        settings.update(current.copy(chatModelId=next,assistants=current.assistants.map { if(it.id==current.assistantId)it.copy(chatModelId=next,reasoningLevel=ReasoningLevel.LOW) else it }))
        save(JsonObject(saved()+mapOf("remoteId" to JsonPrimitive(accepted["taskId"] as String),"ready" to JsonPrimitive(true))))
        println("R27_PHONE_READY_FOR_ACTUAL_PROCESS_EXIT")
        Thread.sleep(120_000)
        fail("The owning driver must force-stop the actual QA phone process after observing READY")
    }
    @Test fun verifyServerTask(): Unit=runBlocking {
        val config=fixture();openForeground();val host=facade()
        try {
            val task=saved().getValue("taskId").jsonPrimitive.content
            val state=withTimeout(60_000) { var value: Map<*,*>;do { value=call(host,"cloud.read",task);if(value["state"]!="SUCCEEDED" || value["executorActive"]==true)delay(100) }
                while(value["state"]!="SUCCEEDED" || value["executorActive"]==true);value }
            assertEquals(saved().getValue("acceptedModel").jsonPrimitive.content,(state["model"] as Map<*,*>)["model_id"])
            assertEquals("UNTIL_REVOKED",state["grantMode"]);assertNull(state["grantExpiresAt"])
            assertTrue(state["chatApplied"]==true)
            val artifact=(((state["result"] as Map<*,*>)["artifacts"] as List<*>).single() as Map<*,*>)
            val imported=call(host,"cloud.import",task,mapOf("artifactId" to artifact["id"]))
            val file=materials.verifiedFile(owner,imported["id"] as String)
            assertTrue(file.readText().contains("Verified after the phone process exited."))
            assertEquals(artifact["sha256"],materials.get(owner,imported["id"] as String).sha256)
            val importedAgain=call(host,"cloud.import",task,mapOf("artifactId" to artifact["id"]))
            assertEquals(imported["id"],importedAgain["id"])
            call(host,"cloud.read",task)
            val conversation=koin.get<ConversationRepository>().getConversationById(Uuid.parse(saved().getValue("conversationId").jsonPrimitive.content),owner)!!
            assertEquals(1,conversation.currentMessages.count { it.generationTaskId==task && it.role==me.rerere.ai.core.MessageRole.ASSISTANT && it.getTools().isEmpty() })
            captureTaskSurface("R27_SERVER_MARKDOWN","server-success","SUCCEEDED")
            call(host,"cloud.disconnect")
            val forbidden=File(context.cacheDir,"r27-forbidden-replay")
            assertTrue(runCatching { scheduler.run(scheduler.get(owner,task),{settings.settingsFlow.value},{},{"COMPLETED_UNVERIFIED"},{false}) {
                forbidden.writeText("must never execute a delegated source after disable")
            } }.isFailure)
            assertFalse(forbidden.exists())
            assertNotNull(CloudWorkspace.shared(context).delegated(owner,task))
            assertFalse(CloudWorkspace.shared(context).configured())
            save(JsonObject(saved()+mapOf("verified" to JsonPrimitive(true),"nativeArtifactId" to JsonPrimitive(imported["id"] as String))))
            println("R27_PHONE_RESTART_VERIFIED_ACTUAL_MARKDOWN_AND_NO_LOCAL_REPLAY")
        } finally { host.close() }
    }
    @Test fun deviceLifecycle(): Unit=runBlocking {
        val config=fixture()
        val source=seed("R27_NATIVE_DOCX Create a genuine DOCX containing R27 native verified document using the paired phone.",config)
        openForeground();val host=facade()
        try {
            if(!CloudWorkspace.shared(context).configured())call(host,"cloud.connect",fields=mapOf("endpoint" to config.getValue("endpoint").jsonPrimitive.content,
                "token" to config.getValue("token").jsonPrimitive.content,"consent" to true))
            call(host,"cloud.delegate",source.id,mapOf("consent" to true,"requireEvidence" to true,"includeHistory" to true,"sources" to emptyList<String>()))
            val waiting=withTimeout(60_000) { var value: Map<*,*>;do { value=call(host,"cloud.read",source.id);if(value["state"]!="WAITING_DEVICE" || value["executorActive"]==true)delay(100) }
                while(value["state"]!="WAITING_DEVICE" || value["executorActive"]==true);value }
            assertTrue(waiting["deviceBlocked"]==true && waiting["deviceConfirmation"]==true)
            captureTaskSurface("R27_NATIVE_DOCX","device-waiting","WAITING_DEVICE")
            val pending=waiting["device"] as Map<*,*>
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            withTimeout(10_000) { while(foreground())delay(100) }
            assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
            val preserved=call(host,"cloud.read",source.id)
            assertTrue(preserved["deviceConfirmation"]==true)
            openForeground()
            val mode=config["faultMode"]?.jsonPrimitive?.content ?: "none"
            if(mode=="stale_confirmation") {
                call(host,"cloud.hosting",source.id,mapOf("consent" to true,"hostingMode" to "UNTIL_REVOKED",
                    "revision" to waiting["revision"],"commandId" to Uuid.random().toString()))
                val changed=call(host,"cloud.read",source.id)
                val renewed=changed["device"] as Map<*,*>
                assertEquals(pending["inputHash"],renewed["inputHash"])
                assertNotEquals(pending["grantEpoch"],renewed["grantEpoch"])
                assertTrue("A confirmation from the old grant must not authorize the new epoch",
                    runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                call(host,"cloud.control",source.id,mapOf("action" to "pause","revision" to changed["revision"],
                    "commandId" to Uuid.random().toString()))
                val paused=call(host,"cloud.read",source.id)
                call(host,"cloud.control",source.id,mapOf("action" to "resume","revision" to paused["revision"],
                    "commandId" to Uuid.random().toString()))
                val resumed=call(host,"cloud.read",source.id)
                val current=resumed["device"] as Map<*,*>
                assertEquals(renewed["grantEpoch"],current["grantEpoch"])
                assertNotEquals(renewed["taskRevision"],current["taskRevision"])
                assertTrue("Pause/resume cannot refresh an already-open confirmation",
                    runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(renewed)) }.isFailure)
                assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,
                    deviceFields(current)+("callId" to "not-the-displayed-action")) }.isFailure)
                assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,
                    mapOf("inputHash" to current["inputHash"])) }.isFailure)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                val blocked=call(host,"cloud.read",source.id)
                assertTrue(blocked["deviceConfirmation"]==true && blocked["executorActive"]==false)
                save(JsonObject(saved()+("staleConfirmationsRejected" to JsonPrimitive(true))))
            }
            if(mode in setOf("offline_claim","offline_pause","offline_pause_restart","offline_cancel","offline_revoke")) {
                assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
                assertTrue(runCatching { call(host,"cloud.read",source.id) }.isFailure)
                val action=if(mode=="offline_claim" || mode=="offline_pause_restart")"pause" else mode.removePrefix("offline_")
                val command=Uuid.random().toString()
                assertTrue(runCatching {
                    if(mode=="offline_claim")call(host,"cloud.stopDevice",source.id)
                    else call(host,"cloud.control",source.id,mapOf("action" to action,"revision" to waiting["revision"],"commandId" to command))
                }.isFailure)
                val controls=CloudWorkspace.shared(context).delegated(owner,source.id)!!["controls"]?.jsonObject.orEmpty()
                assertTrue("Every offline safety button must persist its intent before network reads",
                    controls.values.any { val saved=it.jsonObject
                        (saved["action"] ?: saved["value"]?.jsonObject?.get("action"))==JsonPrimitive(action) &&
                            saved["acknowledged"]==JsonPrimitive(false) })
                assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                if(mode=="offline_pause_restart") {
                    save(JsonObject(saved()+mapOf("ready" to JsonPrimitive(true),
                        "recoveryInputHash" to JsonPrimitive(pending["inputHash"] as String))))
                    println("R30_PHONE_READY_WITH_UNACKNOWLEDGED_SAFETY_INTENT")
                    Thread.sleep(120_000);fail("Owning driver must terminate the phone with its durable offline pause")
                }
                releaseFault(config)
                call(host,"cloud.executeDevice",source.id,mapOf("inputHash" to pending["inputHash"])) // Original claim settlement only.
                val stopped=call(host,"cloud.read",source.id)
                val settled=CloudWorkspace.shared(context).delegated(owner,source.id)!!["controls"]!!.jsonObject
                assertTrue(settled.values.all { it.jsonObject["acknowledged"]==JsonPrimitive(true) })
                assertTrue(stopped["executorActive"]==false)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                if(action!="pause") {
                    if(action=="cancel")assertEquals("CANCELED",stopped["state"])
                    else assertEquals("REVOKED",stopped["authorization"])
                    assertTrue("Canceled/revoked action cannot dispatch after reconnect",
                        runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
                    call(host,"cloud.control",source.id,mapOf("action" to action,"revision" to waiting["revision"],"commandId" to command))
                    val unchanged=call(host,"cloud.read",source.id)
                    assertEquals(stopped["revision"],unchanged["revision"])
                    save(JsonObject(saved()+mapOf("deviceVerified" to JsonPrimitive(true),
                        "safetyOnly" to JsonPrimitive(action),"controlState" to JsonPrimitive(unchanged["state"] as String),
                        "controlAuthorization" to JsonPrimitive(unchanged["authorization"] as String))))
                    println("R30_OFFLINE_SAFETY_SETTLED_WITHOUT_NATIVE_DISPATCH")
                    return@runBlocking
                }
                assertTrue("Offline pause must remain durable after reconnect",stopped["manualPaused"]==true)
                assertTrue(stopped["deviceConfirmation"]==true)
                assertTrue("Reconnect must not silently refresh a stale resume command",
                    runCatching { call(host,"cloud.control",source.id,mapOf("action" to "resume",
                        "revision" to waiting["revision"],"commandId" to Uuid.random().toString())) }.isFailure)
                call(host,"cloud.control",source.id,mapOf("action" to "resume","revision" to stopped["revision"],
                    "commandId" to Uuid.random().toString()))
                val resumed=call(host,"cloud.read",source.id)
                assertTrue(resumed["manualPaused"]==false && resumed["deviceConfirmation"]==true)
                save(JsonObject(saved()+("offlineStopPreserved" to JsonPrimitive(true))))
            }
            if(mode=="lock_pending") {
                lockOwnedDevice()
                assertTrue(runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) }.isFailure)
                val locked=call(host,"cloud.read",source.id)
                assertTrue(locked["deviceConfirmation"]==true && locked["executorActive"]==false)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                unlockOwnedDevice()
            }
            if(mode in setOf("lock_claim","stop_claim","profile_claim")) {
                val execution=async(Dispatchers.Default) { runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) } }
                withTimeout(30_000) {
                    while((call(host,"cloud.read",source.id)["device"] as? Map<*,*>)?.get("state")!="IN_FLIGHT")delay(100)
                }
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                var otherProfile: String?=null
                when(mode) {
                    "lock_claim" -> { lockOwnedDevice();delay(500) }
                    "stop_claim" -> assertEquals("STOP_REQUESTED",call(host,"cloud.stopDevice",source.id)["state"])
                    "profile_claim" -> {
                        val other=settings.createLearnerProfile("R29 isolated second profile");otherProfile=other
                        withTimeout(10_000) { settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id==other } } }
                        assertTrue(host.switchProfile(other,host.getBootstrap(owner,1).contextEpoch).success)
                        assertEquals("Successful profile command must select the durable active owner",other,settings.settingsFlow.value.activeProfileId)
                        delay(500)
                    }
                }
                releaseFault(config)
                assertTrue(withTimeout(30_000) { execution.await() }.isFailure)
                if(mode=="lock_claim")unlockOwnedDevice()
                if(otherProfile!=null) {
                    assertTrue(host.switchProfile(owner,host.getBootstrap(otherProfile,1).contextEpoch).success)
                    assertEquals(owner,settings.settingsFlow.value.activeProfileId)
                    assertTrue(materials.list(otherProfile).isEmpty())
                }
                val stopped=call(host,"cloud.read",source.id)
                assertTrue(stopped["executorActive"]==false && stopped["deviceConfirmation"]==true)
                assertTrue(materials.list(owner).none { it.output && it.extension=="docx" })
                if(mode in setOf("stop_claim","profile_claim")) {
                    assertTrue(stopped["manualPaused"]==true)
                    call(host,"cloud.control",source.id,mapOf("action" to "resume","payload" to "","revision" to stopped["revision"],"commandId" to Uuid.random().toString()))
                    val resumed=call(host,"cloud.read",source.id)
                    assertTrue(resumed["manualPaused"]==false && resumed["deviceConfirmation"]==true)
                }
                openForeground()
            }
            if(mode=="stale_confirmation") {
                val fresh=call(host,"cloud.read",source.id)["device"] as Map<*,*>
                waitSurface("refreshed task revision before opening the confirmation") {
                    if(surfaceText().contains("远端修订 ${fresh["taskRevision"]} ·"))true
                    else { click("读取权威状态");scroll(back=true);false }
                }
                waitSurface("actual foreground action") { click("核对输入并前台接管") || scroll() && false }
                waitSurface("bound confirmation identity") {
                    surfaceText().contains("${fresh["grantEpoch"]} · 任务修订：${fresh["taskRevision"]}")
                }
                File(context.noBackupFilesDir,"r29-device-confirmation.txt").writeText(surfaceText())
                File(context.noBackupFilesDir,"r29-device-confirmation.png").outputStream().use {
                    check(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                }
                waitSurface("actual foreground confirmation") { click("确认并保持前台") }
            } else call(host,"cloud.executeDevice",source.id,deviceFields(call(host,"cloud.read",source.id)["device"] as Map<*,*>))
            val finished=withTimeout(60_000) { var value: Map<*,*>;do { value=call(host,"cloud.read",source.id);if(value["state"]!="SUCCEEDED" || value["executorActive"]==true)delay(100) }
                while(value["state"]!="SUCCEEDED" || value["executorActive"]==true);value }
            assertTrue(finished["chatApplied"]==true)
            val outputs=materials.list(owner).filter { it.output && it.extension=="docx" && it.name.startsWith("R27 actual phone") }
            assertEquals(1,outputs.size)
            val actual=outputs.single();materials.verifiedFile(owner,actual.id)
            assertTrue(materials.readContent(owner,actual.id).text.contains("R27 native verified document"))
            assertTrue(materials.list(owner).none { it.extension=="png" && it.name.contains("screen",ignoreCase=true) })
            call(host,"cloud.disconnect")
            save(JsonObject(saved()+mapOf("deviceVerified" to JsonPrimitive(true),"nativeDocxId" to JsonPrimitive(actual.id),"nativeDocxSha256" to JsonPrimitive(actual.sha256))))
            println("R27_ACTUAL_NATIVE_DOCX_FOREGROUND_AND_READBACK_VERIFIED")
        } finally { host.close() }
    }
    @Test fun prepareDeviceRecovery(): Unit=runBlocking {
        val config=fixture()
        val source=seed("R27_NATIVE_DOCX Create a genuine DOCX containing R27 native verified document using the paired phone.",config)
        openForeground();val host=facade()
        call(host,"cloud.connect",fields=mapOf("endpoint" to config.getValue("endpoint").jsonPrimitive.content,
            "token" to config.getValue("token").jsonPrimitive.content,"consent" to true))
        call(host,"cloud.delegate",source.id,mapOf("consent" to true,"requireEvidence" to true,"sources" to emptyList<String>()))
        val pending=withTimeout(60_000) {
            var value: Map<*,*>
            do { value=call(host,"cloud.read",source.id);if(value["state"]!="WAITING_DEVICE" || value["executorActive"]==true)delay(100) }
            while(value["state"]!="WAITING_DEVICE" || value["executorActive"]==true)
            value["device"] as Map<*,*>
        }
        launch(Dispatchers.Default) { runCatching { call(host,"cloud.executeDevice",source.id,deviceFields(pending)) } }
        val receipt=config.getValue("faultMode").jsonPrimitive.content=="receipt_restart"
        withTimeout(60_000) {
            while(true) {
                val state=call(host,"cloud.read",source.id)
                val reached=if(receipt)(state["calls"] as? List<*>)?.any { (it as Map<*,*>)["state"]=="SUCCEEDED" && it["tool"]=="create_material_file" }==true
                    else (state["device"] as? Map<*,*>)?.get("state")=="IN_FLIGHT"
                if(reached)break
                delay(100)
            }
        }
        save(JsonObject(saved()+mapOf("ready" to JsonPrimitive(true),"recoveryInputHash" to JsonPrimitive(pending["inputHash"] as String))))
        println("R29_PHONE_READY_AT_DEVICE_RECOVERY_BOUNDARY")
        Thread.sleep(120_000);fail("Owning driver must terminate this real phone process at the recorded boundary")
    }
    @Test fun verifyDeviceRecovery(): Unit=runBlocking {
        val config=fixture();openForeground();val host=facade()
        try {
            val id=saved().getValue("taskId").jsonPrimitive.content
            val input=saved().getValue("recoveryInputHash").jsonPrimitive.content
            val before=materials.list(owner).count { it.output && it.extension=="docx" }
            call(host,"cloud.executeDevice",id,mapOf("inputHash" to input)) // Settlement only, never dispatch.
            assertEquals(before,materials.list(owner).count { it.output && it.extension=="docx" })
            val offlinePause=config.getValue("faultMode").jsonPrimitive.content=="offline_pause_restart"
            if(config.getValue("faultMode").jsonPrimitive.content=="claim_restart" || offlinePause) {
                var stopped=call(host,"cloud.read",id)
                assertTrue(stopped["executorActive"]==false && stopped["deviceConfirmation"]==true)
                assertEquals(0,before)
                if(offlinePause) {
                    assertTrue("Actual process exit must not discard pending pause",stopped["manualPaused"]==true)
                    val controls=CloudWorkspace.shared(context).delegated(owner,id)!!["controls"]!!.jsonObject
                    assertTrue(controls.isNotEmpty() && controls.values.all { it.jsonObject["acknowledged"]==JsonPrimitive(true) })
                    assertTrue(runCatching { call(host,"cloud.executeDevice",id,deviceFields(stopped["device"] as Map<*,*>)) }.isFailure)
                    call(host,"cloud.control",id,mapOf("action" to "resume","revision" to stopped["revision"],"commandId" to Uuid.random().toString()))
                    stopped=call(host,"cloud.read",id)
                    assertTrue(stopped["manualPaused"]==false && stopped["deviceConfirmation"]==true)
                }
                call(host,"cloud.executeDevice",id,deviceFields(stopped["device"] as Map<*,*>)) // New explicit foreground confirmation.
            } else assertEquals(1,before)
            val complete=withTimeout(60_000) {
                var state: Map<*,*>
                do { state=call(host,"cloud.read",id);if(state["state"]!="SUCCEEDED" || state["executorActive"]==true)delay(100) }
                while(state["state"]!="SUCCEEDED" || state["executorActive"]==true);state
            }
            assertTrue(complete["chatApplied"]==true)
            val actual=materials.list(owner).single { it.output && it.extension=="docx" }
            assertTrue(materials.readContent(owner,actual.id).text.contains("R27 native verified document"))
            captureTaskSurface("R27_NATIVE_DOCX","device-recovered","SUCCEEDED")
            call(host,"cloud.disconnect")
            save(JsonObject(saved()+mapOf("deviceVerified" to JsonPrimitive(true),"nativeDocxId" to JsonPrimitive(actual.id),
                "nativeDocxSha256" to JsonPrimitive(actual.sha256))))
            println("R29_DEVICE_PROCESS_RECOVERY_VERIFIED_WITHOUT_REPLAY")
        } finally { host.close() }
    }
    @Test fun prepareCloudDocument(): Unit=runBlocking {
        val config=fixture()
        val bytes=java.util.Base64.getDecoder().decode(config.getValue("sourceBytes").jsonPrimitive.content)
        val sourceHash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(config.getValue("sourceSha256").jsonPrimitive.content,sourceHash)
        val input=materials.runTask(me.rerere.rikkahub.feature.material.MaterialFile(
            id=Uuid.random().toString(),profileId=owner,name="R29 unchanged cloud input.xlsx",extension="xlsx")) { output ->
            output.writeBytes(bytes);"Owned synthetic immutable source"
        }
        me.rerere.rikkahub.feature.material.DocumentServiceRepository(context,materials).configure(owner,
            config.getValue("documentEndpoint").jsonPrimitive.content,config.getValue("documentToken").jsonPrimitive.content,true)
        val source=seed("R29_CLOUD_DOCUMENT Use ${config.getValue("documentTool").jsonPrimitive.content} on the selected version; verify real output.",config)
        openForeground();val host=facade()
        call(host,"cloud.connect",fields=mapOf("endpoint" to config.getValue("endpoint").jsonPrimitive.content,
            "token" to config.getValue("token").jsonPrimitive.content,"consent" to true))
        val accepted=call(host,"cloud.delegate",source.id,mapOf("consent" to true,"documentConsent" to true,"hostingMode" to "UNTIL_REVOKED",
            "requireEvidence" to true,"sources" to listOf(input.id)))
        save(JsonObject(saved()+mapOf("remoteId" to JsonPrimitive(accepted["taskId"] as String),"ready" to JsonPrimitive(true),
            "nativeSourceId" to JsonPrimitive(input.id),"sourceSha256" to JsonPrimitive(sourceHash))))
        println("R29_PHONE_DOCUMENT_HANDED_OFF_BEFORE_ACTUAL_PROCESS_EXIT")
        Thread.sleep(120_000);fail("Owning driver must terminate the actual phone process before releasing the provider")
    }
    @Test fun verifyCloudDocument(): Unit=runBlocking {
        val config=fixture();openForeground();val host=facade()
        try {
            // Changing current settings cannot retarget the already accepted document role.
            me.rerere.rikkahub.feature.material.DocumentServiceRepository(context,materials).configure(owner,
                "https://must-not-receive.invalid/documents","",true)
            val id=saved().getValue("taskId").jsonPrimitive.content
            val state=withTimeout(60_000) {
                var value: Map<*,*>
                do { value=call(host,"cloud.read",id);if(value["state"]!="SUCCEEDED" || value["executorActive"]==true)delay(100) }
                while(value["state"]!="SUCCEEDED" || value["executorActive"]==true);value
            }
            assertEquals(config.getValue("documentEndpoint").jsonPrimitive.content,(state["documentService"] as Map<*,*>)["endpoint"])
            assertEquals(saved().getValue("acceptedModel").jsonPrimitive.content,(state["model"] as Map<*,*>)["model_id"])
            val artifact=(((state["result"] as Map<*,*>)["artifacts"] as List<*>).single() as Map<*,*>)
            val imported=call(host,"cloud.import",id,mapOf("artifactId" to artifact["id"]))
            val again=call(host,"cloud.import",id,mapOf("artifactId" to artifact["id"]))
            assertEquals(imported["id"],again["id"])
            val item=materials.get(owner,imported["id"] as String)
            assertEquals(saved().getValue("nativeSourceId").jsonPrimitive.content,item.sourceId)
            assertTrue(item.sourceReferences.any { it.id==item.sourceId && it.sha256==saved().getValue("sourceSha256").jsonPrimitive.content })
            assertEquals(artifact["sha256"],item.sha256)
            assertTrue(materials.readContent(owner,item.id).text.contains("Count"))
            assertEquals(saved().getValue("sourceSha256").jsonPrimitive.content,materials.get(owner,item.sourceId!!).sha256)
            materials.verifiedFile(owner,item.sourceId);materials.verifiedFile(owner,item.id)
            captureTaskSurface("R29_CLOUD_DOCUMENT","cloud-document-success","SUCCEEDED")
            call(host,"cloud.disconnect")
            save(JsonObject(saved()+mapOf("verified" to JsonPrimitive(true),"nativeArtifactId" to JsonPrimitive(item.id),
                "nativeArtifactSha256" to JsonPrimitive(item.sha256),"outputExtension" to JsonPrimitive(item.extension))))
            println("R29_CLOUD_DOCUMENT_NATIVE_IMPORT_AND_PARENT_VERSION_VERIFIED")
        } finally { host.close() }
    }
}
