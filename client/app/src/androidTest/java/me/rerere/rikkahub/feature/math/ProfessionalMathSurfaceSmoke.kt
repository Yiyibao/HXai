package me.rerere.rikkahub.feature.math

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.feature.interactive.*
import me.rerere.rikkahub.feature.interactive.InteractiveSurface
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.ChatService
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Throwaway driver: actual Flutter/host/tool/file paths, no real provider or private data. */
class ProfessionalMathSurfaceSmoke {
    @Test fun operateRealSurface(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val koin=GlobalContext.get();val settingsStore=koin.get<SettingsStore>();val settings=settingsStore.settingsFlow.first {!it.init}
        val owner=settings.activeProfileId;val materials=koin.get<MaterialFiles>();val service=koin.get<ChatService>()
        val pdfFile=File(context.cacheDir,"professional-math-original.pdf");val pdf=PdfDocument()
        try {for(i in 1..2) {val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,800,i).create());page.canvas.drawText(if(i==1) "x+x=2*x" else "(x+1)^2=x^2+2*x+1",30f,160f,Paint().apply {textSize=32f});pdf.finishPage(page)};pdfFile.outputStream().use(pdf::writeTo)} finally {pdf.close()}
        val source=materials.import(owner,pdfFile.toUri(),"QA专业数学两页原稿.pdf")
        val input=buildJsonObject {put("title","数学多页草稿QA");put("pages",buildJsonArray {for(i in 1..2) add(buildJsonObject {put("id",source.id);put("sha256",source.sha256);put("number",i);put("transcription",if(i==1) "x+x=2*x" else "(x+1)^2=x^2+2*x+1");put("ambiguities",buildJsonArray {if(i==1) add("1/l")})})})}
        val draft=materials.mathWorkInputs.create(owner,input.toString(),false,{})
        val definition="""{"version":4,"title":"三维鞍面参数探索QA","presentation":"panel","template":"exploration","components":[{"id":"plot","type":"math_plot_3d","xMin":-2,"xMax":2,"yMin":-2,"yMax":2,"zMin":-4,"zMax":4,"azimuth":35,"elevation":25,"curves":[{"kind":"surface","label":"z=a*x^2-y^2","expression":"a*x^2-y^2"}]},{"id":"a","type":"slider","label":"曲面系数a","min":-2,"max":2,"value":1}]}"""
        val plotArgs=buildJsonObject {put("definition",definition);put("formats",buildJsonArray {add("png");add("svg");add("pdf");add("pptx")})}
        val output=mathPlotTool(materials,owner).execute(plotArgs)
        check(verifyMathPlotDelivery(materials,owner,plotArgs,output))
        val tool=UIMessagePart.Tool("professional-math-ui",InteractiveSurface.MATH_TOOL,plotArgs.toString(),output,executionState=ToolExecutionState.COMPLETED)
        val answer=UIMessage.assistant("").copy(parts=listOf(tool),generationState="COMPLETED")
        val conversation=Conversation(assistantId=settings.assistantId,title="QA 专业数学真实表面",profileId=owner,newConversation=true,messageNodes=listOf(UIMessage.user("探索鞍面并逐页确认数学原稿").toMessageNode(),answer.toMessageNode()))
        service.addConversationReference(conversation.id);service.initializeConversation(conversation.id);service.saveConversation(conversation.id,conversation)
        File(context.cacheDir,"professional-math-ui-setup.json").writeText(buildJsonObject {put("owner",owner);put("source_id",source.id);put("draft",draft);put("conversation_id",conversation.id.toString());put("plot_args",plotArgs);put("plot_output",JsonArray(output.filterIsInstance<UIMessagePart.Text>().map {Json.parseToJsonElement(it.text)}))}.toString())
        instrumentation.uiAutomation.rootInActiveWindow
        val scenario=ActivityScenario.launch(StudyFlutterActivity::class.java)
        val command=File(context.cacheDir,"professional-math-ui-command.json");command.delete()
        var last="";val end=System.currentTimeMillis()+900000
        instrumentation.sendStatus(0,Bundle().apply {putString("stream","MATH_UI_READY\n")})
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.isEditable && (node.text?.toString()==text || node.hintText?.toString()?.contains(text)==true)) return node
            for(i in 0 until node.childCount) find(node.getChild(i),text)?.let {return it}
            return null
        }
        try {
            while(System.currentTimeMillis()<end) {
                val args=runCatching {Json.parseToJsonElement(command.readText()).jsonObject}.getOrNull()
                val id=args?.get("id")?.jsonPrimitive?.content
                if(args!=null && id!=null && id!=last) {
                    last=id;var success=false;var message=""
                    try {
                        when(args.getValue("operation").jsonPrimitive.content) {
                            "set_text" -> {
                                val node=find(instrumentation.uiAutomation.rootInActiveWindow,args.getValue("selector").jsonPrimitive.content) ?: error("editable semantics not found")
                                success=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getValue("value").jsonPrimitive.content)})
                            }
                            "appearance" -> {context.getSharedPreferences("study_appearance",0).edit().putString("theme",args.getValue("theme").jsonPrimitive.content).putFloat("fontScale",args.getValue("scale").jsonPrimitive.float).commit();scenario.recreate();success=true}
                            "switch_profile" -> {val profile=args["profile"]?.jsonPrimitive?.content ?: settingsStore.createLearnerProfile("QA隔离学习者");service.switchLearnerProfile(profile);success=true;message=profile}
                            "done" -> {success=true;File(context.cacheDir,"professional-math-ui-response.json").writeText(buildJsonObject {put("id",id);put("success",true)}.toString());break}
                        }
                    } catch(error: Exception) {message=error.toString()}
                    File(context.cacheDir,"professional-math-ui-response.json").writeText(buildJsonObject {put("id",id);put("success",success);put("message",message)}.toString())
                }
                delay(150)
            }
        } finally {scenario.close();service.removeConversationReference(conversation.id)}
    }
}
