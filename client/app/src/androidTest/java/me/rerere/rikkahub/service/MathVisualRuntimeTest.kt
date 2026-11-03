package me.rerere.rikkahub.service

import android.graphics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.interactive.*
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Real bitmap/file/bridge tests, plus opt-in requests using the already configured image model. */
class MathVisualRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val koin get() = GlobalContext.get()
    private val spec = """{"version":3,"title":"椭圆与直线","components":[{"id":"source","type":"text","text":"合成题目：x²/9+y²/4=1；探索直线 y=kx+1"},{"id":"k","type":"slider","label":"斜率 k","min":-2,"max":2,"value":1},{"id":"plot","type":"math_plot","xMin":-5,"xMax":5,"yMin":-5,"yMax":5,"curves":[{"kind":"parametric","label":"椭圆","x":"3*cos(t)","y":"2*sin(t)","min":0,"max":6.283185307179586},{"kind":"function","label":"y=kx+1","expression":"k*x+1"}],"points":[{"x":0,"y":1,"label":"固定截距"}]},{"id":"go","type":"button","label":"按当前斜率分析","action":"continue"}],"layout":[["source"],["k"],["plot"],["go"]]}"""
    private fun cleanArtifact(store: MaterialFiles, owner: String, id: String) {
        val item=store.get(owner,id)
        store.file(item).delete(); File(context.filesDir,"materials/$owner/$id.meta.json").delete()
    }
    private fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        if(node==null) return false
        if(node.text?.toString()?.contains(text)==true || node.contentDescription?.toString()?.contains(text)==true) return true
        return (0 until node.childCount).any { contains(node.getChild(it),text) }
    }
    private fun scrollBack(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if(node==null) return false
        if(node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return (0 until node.childCount).any {scrollBack(node.getChild(it))}
    }
    @Test fun shippedSkillRealPngAndPersistedMathSurface() = runBlocking {
        val settings=koin.get<SettingsStore>().settingsFlow.first { !it.init }
        val skills=koin.get<SkillManager>(); val store=koin.get<MaterialFiles>(); val service=koin.get<ChatService>(); val repository=koin.get<ConversationRepository>()
        val skill=skills.findSkill(MATH_VISUAL_SKILL)!!
        assertTrue(skill.builtin); assertTrue(skill.skillFile.readText().contains("render_math_plot"))
        val tool=mathPlotTool(store,settings.activeProfileId)
        val input=buildJsonObject {put("definition",spec)}
        val output=tool.execute(input)
        val result=Json.parseToJsonElement((output.first() as UIMessagePart.Text).text).jsonObject
        assertEquals("interactive_surface",result.getValue("kind").jsonPrimitive.content)
        val artifactId=result.getValue("material_id").jsonPrimitive.content
        val bitmap=BitmapFactory.decodeFile(store.verifiedFile(settings.activeProfileId,artifactId).path)!!
        assertEquals(1000,bitmap.width); assertEquals(1140,bitmap.height)
        var colored=0
        for(x in 100 until 900 step 3) for(y in 100 until 900 step 3) {
            val p=bitmap.getPixel(x,y); if(Color.blue(p)>Color.red(p)+40 || Color.red(p)>Color.blue(p)+40) colored++
        }
        assertTrue("PNG must contain curves",colored>100)
        File(context.cacheDir,"math-visual-default.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}; bitmap.recycle()
        val replay=tool.execute(input)
        assertEquals(artifactId,Json.parseToJsonElement((replay.first() as UIMessagePart.Text).text).jsonObject.getValue("material_id").jsonPrimitive.content)
        val surfaceTool=UIMessagePart.Tool("qa-math",InteractiveSurface.MATH_TOOL,input.toString(),output,executionState=ToolExecutionState.COMPLETED)
        val answer=UIMessage.assistant("").copy(parts=listOf(surfaceTool),generationState="COMPLETED")
        val conversation=Conversation(assistantId=settings.assistantId,title="QA 数学图像",profileId=settings.activeProfileId,newConversation=true,messageNodes=listOf(UIMessage.user("辅助理解这道椭圆题").toMessageNode(),answer.toMessageNode()))
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        try {
            service.addConversationReference(conversation.id); service.initializeConversation(conversation.id); service.saveConversation(conversation.id,conversation)
            // Attach the accessibility service before Flutter creates its semantics tree.
            instrumentation.uiAutomation.rootInActiveWindow
            scenario=ActivityScenario.launch(StudyFlutterActivity::class.java)
            var facade: StudyHostFacade?=null
            scenario.onActivity { activity -> facade=StudyFlutterActivity::class.java.getDeclaredField("facade").apply {isAccessible=true}.get(activity) as StudyHostFacade }
            val activeFacade=facade!!
            assertEquals("QA conversation must be selected at bootstrap",conversation.id.toString(),activeFacade.getBootstrap(settings.activeProfileId,0).defaultConversationId)
            val initial=Json.parseToJsonElement(activeFacade.getSurfaceState(settings.activeProfileId,conversation.id.toString(),answer.id.toString(),"qa-math",result.getValue("revision").jsonPrimitive.content)).jsonObject
            assertEquals(1.0,initial.getValue("k").jsonPrimitive.double,0.0)
            service.saveInteractiveState(conversation.id,answer.id.toString(),"qa-math",InteractiveSurface.hash(spec),buildJsonObject {put("k",-1.5)}) { }
            val loaded=Json.parseToJsonElement(activeFacade.getSurfaceState(settings.activeProfileId,conversation.id.toString(),answer.id.toString(),"qa-math",InteractiveSurface.hash(spec))).jsonObject
            assertEquals(-1.5,loaded.getValue("k").jsonPrimitive.double,0.0)
            val deadline=System.currentTimeMillis()+20_000
            while(System.currentTimeMillis()<deadline && !contains(instrumentation.uiAutomation.rootInActiveWindow,"数学坐标图")) {
                scrollBack(instrumentation.uiAutomation.rootInActiveWindow)
                delay(200)
            }
            File(context.cacheDir,"math-visual-chat-delivery.png").outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            assertTrue("Flutter math plot semantics missing",contains(instrumentation.uiAutomation.rootInActiveWindow,"数学坐标图"))
            delay(500)
            File(context.cacheDir,"math-visual-chat-delivery.png").outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            scrollBack(instrumentation.uiAutomation.rootInActiveWindow); delay(500)
            File(context.cacheDir,"math-visual-chat.png").outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            scenario.close(); scenario=null
            ActivityScenario.launch(StudyFlutterActivity::class.java).use { reopened ->
                var restored:StudyHostFacade?=null
                reopened.onActivity { activity -> restored=StudyFlutterActivity::class.java.getDeclaredField("facade").apply {isAccessible=true}.get(activity) as StudyHostFacade }
                val state=Json.parseToJsonElement(restored!!.getSurfaceState(settings.activeProfileId,conversation.id.toString(),answer.id.toString(),"qa-math",InteractiveSurface.hash(spec))).jsonObject
                assertEquals(-1.5,state.getValue("k").jsonPrimitive.double,0.0)
            }
            val bad=tool.execute(buildJsonObject {put("definition",spec.replace("3*cos(t)","run(t)"))})
            assertEquals(1,bad.size); assertEquals("FAILED",Json.parseToJsonElement((bad.single() as UIMessagePart.Text).text).jsonObject.getValue("status").jsonPrimitive.content)
        } finally {
            scenario?.close(); repository.getConversationById(conversation.id)?.let {service.deleteConversation(it)}; service.removeConversationReference(conversation.id)
            cleanArtifact(store,settings.activeProfileId,artifactId)
        }
    }

    @Test fun selectedQuadraticAndRevisionKeepBothVersions() = runBlocking {
        val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
        val store = koin.get<MaterialFiles>()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val raw = """{"version":4,"title":"二次函数交互演示","presentation":"panel","template":"exploration","components":[{"id":"plot","type":"math_plot","xMin":-5,"xMax":5,"yMin":-80,"yMax":80,"equalAspect":false,"curves":[{"kind":"function","label":"二次函数","expression":"a*x^2"}]},{"id":"a","type":"slider","label":"调整系数 a","min":-3,"max":3,"value":1}]}"""
        val input = buildJsonObject { put("definition", raw) }
        val output = mathPlotTool(store, settings.activeProfileId).execute(input)
        val result = Json.parseToJsonElement((output.first() as UIMessagePart.Text).text).jsonObject
        assertEquals("interactive_surface", result.getValue("kind").jsonPrimitive.content)
        val artifactId = result.getValue("material_id").jsonPrimitive.content
        assertTrue(store.verifiedFile(settings.activeProfileId, artifactId).length() > 1000)
        val sourceTool = UIMessagePart.Tool("qa-selected-plot", InteractiveSurface.MATH_TOOL, input.toString(), output, executionState = ToolExecutionState.COMPLETED)
        val source = UIMessage.assistant("").copy(parts = listOf(sourceTool), generationState = "COMPLETED")
        val initial = Conversation(assistantId = settings.assistantId, title = "QA 所选函数样式与修改", profileId = settings.activeProfileId, newConversation = true, messageNodes = listOf(source.toMessageNode()))
        val revisedRaw = raw.replace("二次函数交互演示", "二次函数修改版").replace("\"value\":1", "\"value\":-3")
        val args = buildJsonObject { put("message_id", source.id.toString()); put("tool_call_id", sourceTool.toolCallId); put("expected_revision", InteractiveSurface.hash(raw)); put("definition", revisedRaw) }
        val revisionOutput = surfaceRevisionTool { initial }.execute(args)
        val revisedTool = UIMessagePart.Tool("qa-selected-revised", "revise_interactive_surface", args.toString(), revisionOutput, executionState = ToolExecutionState.COMPLETED)
        val revised = UIMessage.assistant("").copy(parts = listOf(revisedTool), generationState = "COMPLETED")
        assertEquals(raw, InteractiveSurface.definition(sourceTool))
        val conversation = initial.copy(messageNodes = initial.messageNodes + revised.toMessageNode())
        try {
            service.addConversationReference(conversation.id)
            service.initializeConversation(conversation.id)
            service.saveConversation(conversation.id, conversation)
            ActivityScenario.launch(StudyFlutterActivity::class.java).use { scenario ->
                var facade: StudyHostFacade? = null
                scenario.onActivity { activity -> facade = StudyFlutterActivity::class.java.getDeclaredField("facade").apply { isAccessible = true }.get(activity) as StudyHostFacade }
                val dto = facade!!.getConversationDetails(settings.activeProfileId, conversation.id.toString())
                val surfaces = dto.nodes.flatMap { it.branches }.flatMap { it.parts }.filter { it.type == "tool" }.map { Json.parseToJsonElement(it.toolOutput!!).jsonObject }
                assertEquals(2, surfaces.count { it["kind"]?.jsonPrimitive?.content == "interactive_surface" })
                assertEquals("二次函数修改版", surfaces.last().getValue("definition").jsonObject.getValue("title").jsonPrimitive.content)
                assertEquals("qa-selected-plot", surfaces.last().getValue("parent").jsonObject.getValue("toolCallId").jsonPrimitive.content)
            }
        } finally {
            repository.getConversationById(conversation.id)?.let { service.deleteConversation(it) }
            service.removeConversationReference(conversation.id)
            cleanArtifact(store, settings.activeProfileId, artifactId)
        }
    }

    @Test fun configuredVisionModelAutomaticallyUsesMathSkillForPictureProblems() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMath")=="true")
        val settingsStore=koin.get<SettingsStore>(); val service=koin.get<ChatService>(); val repository=koin.get<ConversationRepository>(); val store=koin.get<MaterialFiles>()
        val original=settingsStore.settingsFlow.first { !it.init }
        val model=original.getCurrentChatModel()?:error("请先配置图片模型")
        require(me.rerere.ai.provider.Modality.IMAGE in model.inputModalities) { "当前模型不支持图片" }
        val assistant=original.getCurrentAssistant().copy(id=Uuid.random(),name="QA 数学图片",enabledSkills=emptySet(),enableMemory=false,localTools=emptyList())
        val evidence=mutableListOf<JsonElement>()
            try {
                settingsStore.update(original.copy(assistants=original.assistants+assistant,assistantId=assistant.id,enableSuggestion=false))
                settingsStore.settingsFlow.first {it.assistantId==assistant.id}
                val examples=listOf(
                    listOf("f(x)=x^2-2*x-3", "Find the vertex and x-intercepts.", "Explain why f(x)<0 between the roots."),
                    listOf("Ellipse: x^2/9+y^2/4=1", "Line: y=k*x+1", "Explore the line as k varies from -2 to 2.")
                )
                examples.forEachIndexed {index,lines ->
                    val requestedCase=InstrumentationRegistry.getArguments().getString("liveMathCase")?.toIntOrNull()
                    if(requestedCase!=null && index!=requestedCase) return@forEachIndexed
                    val picture=File(context.cacheDir,"qa-math-question-$index.png")
                    val b=Bitmap.createBitmap(1200,450,Bitmap.Config.ARGB_8888); val c=Canvas(b); c.drawColor(Color.WHITE)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=42f}
                    lines.forEachIndexed {i,line->c.drawText(line,40f,90f+i*110,paint)}
                    picture.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}; b.recycle()
                    val id=Uuid.random(); val artifactIds=mutableListOf<String>(); val started=System.currentTimeMillis()
                    val sourceImage=store.import(original.activeProfileId,android.net.Uri.fromFile(picture),picture.name)
                    try {
                        service.addConversationReference(id);service.initializeConversation(id)
                        service.saveConversation(id,Conversation(id=id,assistantId=assistant.id,title="QA 图片数学 $index",profileId=original.activeProfileId,newConversation=true,messageNodes=emptyList()))
                        service.sendMessageCommitted(id,listOf(UIMessagePart.Text("请用中文讲解图片里的题目，让我理解解题过程。这是合成测试题，不涉及本人学习表现。"),UIMessagePart.Image(android.net.Uri.fromFile(store.verifiedFile(original.activeProfileId,sourceImage.id)).toString())),Uuid.random())
                        suspend fun awaitGeneration() = withTimeout(180_000) {
                            while(service.getConversationFlow(id).value.currentMessages.size<=1 || service.getGenerationJobStateFlow(id).first()!=null) delay(100)
                        }
                        awaitGeneration()
                        val interrupted=repository.getConversationById(id)!!
                        val failures=service.errors.value.filter {it.conversationId==id}
                        val networkResume=interrupted.currentMessages.last().generationState=="INTERRUPTED" && failures.any {it.error is java.io.IOException} && interrupted.currentMessages.flatMap {it.getTools()}.none {it.isExecutionUncertain}
                        if(networkResume) {
                            println("Math picture case $index: ${failures.map {it.error.javaClass.simpleName}}; verifying one explicit safe resume, not counting interruption as success")
                            failures.forEach {service.dismissError(it.id)}
                            service.resumeInterruptedGeneration(id)
                            awaitGeneration()
                        }
                        val conversation=repository.getConversationById(id)!!
                        val tools=conversation.currentMessages.flatMap {it.parts}.filterIsInstance<UIMessagePart.Tool>()
                        tools.filter {it.toolName==InteractiveSurface.MATH_TOOL}.flatMap {it.output}.filterIsInstance<UIMessagePart.Text>().forEach {part ->
                            runCatching {Json.parseToJsonElement(part.text).jsonObject["material_id"]?.jsonPrimitive?.content}.getOrNull()?.let {artifactIds.add(it)}
                        }
                        assertEquals("Completion required; tools=${tools.map {it.toolName+":"+it.executionState}}; errors=${service.errors.value.filter {it.conversationId==id}.map {it.error.javaClass.simpleName+" at "+it.error.stackTrace.take(2)}}","COMPLETED",conversation.currentMessages.last().generationState)
                        assertTrue("Automatic skill not called",tools.any {it.toolName=="use_skill" && it.input.contains(MATH_VISUAL_SKILL) && it.executionState==ToolExecutionState.COMPLETED})
                        val plot=tools.lastOrNull {it.toolName==InteractiveSurface.MATH_TOOL && runCatching {InteractiveSurface.clientOutput(it)}.isSuccess}?:error("Automatic math plot not produced")
                        val definition=InteractiveSurface.definition(plot)
                        val components=InteractiveSurface.validate(definition).getValue("components").jsonArray.map {it.jsonObject}
                        val graph=components.single {it.getValue("type").jsonPrimitive.content=="math_plot"}
                        val defaults=components.filter {it.getValue("type").jsonPrimitive.content=="slider"}.associate {it.getValue("id").jsonPrimitive.content to it.getValue("value").jsonPrimitive.double}
                        if(index==0) {
                            val functions=graph.getValue("curves").jsonArray.map {it.jsonObject}.filter {it.getValue("kind").jsonPrimitive.content=="function"}
                            assertTrue("Recognized quadratic does not match the image",functions.any {curve ->
                                val expression=MathExpression.parse(curve.getValue("expression").jsonPrimitive.content,defaults.keys+"x")
                                listOf(-1.0 to 0.0,1.0 to -4.0,3.0 to 0.0).all {(x,y)->kotlin.math.abs(expression.value(defaults+mapOf("x" to x))-y)<1e-6}
                            })
                        } else {
                            assertEquals("One slope control expected",1,defaults.size)
                            val changed=defaults+mapOf(defaults.keys.single() to 2.0)
                            val curves=MathPlot.sample(graph,changed)
                            assertTrue("Ellipse equation does not match the image",curves.any {curve ->
                                val points=curve.segments.flatten(); points.size>100 && points.all {kotlin.math.abs(it.x*it.x/9+it.y*it.y/4-1)<.01}
                            })
                            assertTrue("Slope control does not actually update the requested line",curves.any {curve ->
                                val points=curve.segments.flatten(); points.size>100 && points.all {kotlin.math.abs(it.y-2*it.x-1)<1e-6}
                            })
                        }
                        assertTrue(artifactIds.isNotEmpty()); artifactIds.forEach {assertTrue(store.verifiedFile(original.activeProfileId,it).length()>1000)}
                        File(context.cacheDir,"math-visual-live-$index.png").writeBytes(store.verifiedFile(original.activeProfileId,artifactIds.last()).readBytes())
                        evidence.add(buildJsonObject {put("case",index);put("model",model.modelId);put("automatic_skill",true);put("automatic_plot",true);put("network_resumed",networkResume);put("definition",Json.parseToJsonElement(definition));put("elapsed_ms",System.currentTimeMillis()-started)})
                        File(context.cacheDir,"math-visual-live.json").writeText(JsonArray(evidence).toString())
                    } finally {
                        service.stopGeneration(id); repository.getConversationById(id)?.let {service.deleteConversation(it)};service.removeConversationReference(id)
                        artifactIds.distinct().forEach {cleanArtifact(store,original.activeProfileId,it)};cleanArtifact(store,original.activeProfileId,sourceImage.id);picture.delete()
                    }
                }
            } finally {
                settingsStore.update(original)
                withTimeout(10_000) {settingsStore.settingsFlow.first {it.assistantId==original.assistantId && it.assistants.none {entry->entry.id==assistant.id}}}
            }
        File(context.cacheDir,"math-visual-live.json").writeText(JsonArray(evidence).toString())
    }
}
