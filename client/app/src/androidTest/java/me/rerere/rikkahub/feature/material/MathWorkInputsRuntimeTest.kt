package me.rerere.rikkahub.feature.material

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.security.MessageDigest

class MathWorkInputsRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {check(it.packageName=="org.hxsi.agent.nativeqa")}
    private fun pdf(count: Int): File {
        val file=File.createTempFile("math-work-", ".pdf",context.cacheDir);val document=PdfDocument()
        try {for(i in 1..count) {val page=document.startPage(PdfDocument.PageInfo.Builder(300,400,i).create());page.canvas.drawText("Page $i: x+x=2*x",20f,100f,Paint().apply {textSize=16f});document.finishPage(page)};file.outputStream().use(document::writeTo)} finally {document.close()}
        return file
    }
    private fun request(item: MaterialFile, texts: List<String>, ambiguous: Boolean=false) = buildJsonObject {
        put("title","用户原稿页序");put("pages",JsonArray(texts.mapIndexed {i,text -> buildJsonObject {
            put("id",item.id);put("sha256",item.sha256);put("number",i+1);put("transcription",text)
            put("ambiguities",buildJsonArray {if(ambiguous) add("1/l 尚不确定")})
        } }))
    }
    private fun cleanup(owner: String) {
        File(context.filesDir,"materials/$owner").deleteRecursively()
        val hash=MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") {"%02x".format(it)}
        File(context.noBackupFilesDir,"math-work-inputs/$hash").deleteRecursively()
    }
    @Test fun invisiblePageCannotBeConfirmedAndValidUnicodeStillSurvivesReadback(): Unit = runBlocking {
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val original=pdf(1)
        try {
            val item=store.import(owner,original.toUri(),"meaningful.pdf")
            for(text in listOf(" \t\n", "\u200b\u2060\u00a0", "\uD800")) {
                assertTrue(runCatching { store.mathWorkInputs.create(owner,request(item,listOf(text)).toString(),true,{}) }.isFailure)
            }
            val text="x² − 1 = 0\n另一种解法🙂"
            val work=store.mathWorkInputs.create(owner,request(item,listOf(text)).toString(),true,{})
            assertEquals(JsonPrimitive("SOURCE_READY"),work["status"])
            val fresh=MaterialFiles(context).mathWorkInputs.read(owner,work.getValue("work_id").jsonPrimitive.content)
            assertEquals(text,fresh.getValue("pages").jsonArray.single().jsonObject.getValue("transcription").jsonPrimitive.content)
        } finally {original.delete();cleanup(owner)}
    }
    @Test fun unknownPrefixCannotBeOmittedOrTurnLaterErrorIntoFirstError(): Unit = runBlocking {
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val original=pdf(2)
        try {
            val item=store.import(owner,original.toUri(),"two-pages.pdf")
            val work=store.mathWorkInputs.create(owner,request(item,listOf("题设：符号待核","x+x=3*x")).toString(),true,{})
            val id=work.getValue("work_id").jsonPrimitive.content
            val badFact=buildJsonObject {put("operation","identity");put("left","x+x");put("right","3*x")}
            fun review(omit: Boolean)=buildJsonObject {put("work_id",id);put("review",buildJsonObject {
                put("steps",buildJsonArray {
                    if(!omit) add(buildJsonObject {put("original","题设：符号待核");put("locator","1:1");put("check",JsonNull)})
                    add(buildJsonObject {put("original","x+x=3*x");put("locator","2:1");put("check",badFact)})
                });put("reference_note","参考方法并非唯一")
            }.toString())}
            assertTrue(runCatching {store.mathWorkInputs.review(owner,review(true))}.isFailure)
            val result=store.mathWorkInputs.review(owner,review(false))
            assertEquals(JsonPrimitive("UNSUPPORTED"),result["mathematics_status"])
            assertEquals(JsonNull,result.getValue("review").jsonObject["first_error_step"])
            assertEquals(JsonPrimitive(1),result.getValue("review").jsonObject["first_unverified_step"])
            val restored=MaterialFiles(context).mathWorkInputs.read(owner,id)
            assertEquals(work,restored)
            assertTrue(runCatching {store.mathWorkInputs.read(UUID.randomUUID().toString(),id)}.isFailure)
            File(context.cacheDir,"professional-math-work-source.json").writeText(store.mathWorkInputs.readForTool(owner,buildJsonObject {put("work_id",id)}).toString())
        } finally {original.delete();cleanup(owner)}
    }
    @Test fun ambiguityAndSourceDeletionCannotBeOverriddenByOldConfirmation(): Unit = runBlocking {
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val original=pdf(1)
        try {
            val item=store.import(owner,original.toUri(),"ambiguous.pdf")
            val draft=store.mathWorkInputs.create(owner,request(item,listOf("1/l"),true).toString(),false,{})
            assertEquals(JsonPrimitive("WAITING_INPUT"),draft["status"])
            assertTrue(runCatching {store.mathWorkInputs.create(owner,request(item,listOf("1/l"),true).toString(),true,{})}.isFailure)
            val work=store.mathWorkInputs.create(owner,request(item,listOf("1/x")).toString(),true,{})
            val id=work.getValue("work_id").jsonPrimitive.content
            assertEquals(JsonPrimitive("NOT_EVALUATED"),work["mathematics_status"])
            store.delete(owner,item.id,item.sha256)
            assertTrue(runCatching {store.mathWorkInputs.read(owner,id)}.isFailure)
            store.restore(owner,item.id,{})
            assertTrue(runCatching {store.mathWorkInputs.read(owner,id)}.isFailure)
            assertTrue(runCatching {store.mathWorkInputs.create(owner,request(item,listOf("1/x")).toString(),true,{})}.isFailure)
        } finally {original.delete();cleanup(owner)}
    }
    @Test fun correctAlternativeIsCheckedOnItsOwnAndOriginalCannotBeRewritten(): Unit = runBlocking {
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val original=pdf(1)
        try {
            val item=store.import(owner,original.toUri(),"alternative.pdf")
            val text="(x+1)^2=x^2+2*x+1"
            val work=store.mathWorkInputs.create(owner,request(item,listOf(text)).toString(),true,{})
            val id=work.getValue("work_id").jsonPrimitive.content
            fun args(originalLine: String)=buildJsonObject {put("work_id",id);put("review",buildJsonObject {
                put("reference_note","参考先求导；学生直接展开也是正确解法")
                put("steps",buildJsonArray {add(buildJsonObject {put("original",originalLine);put("locator","1:1");put("check",buildJsonObject {put("operation","identity");put("left","(x+1)^2");put("right","x^2+2*x+1")})})})
            }.toString())}
            assertEquals(JsonPrimitive("VERIFIED"),store.mathWorkInputs.review(owner,args(text))["mathematics_status"])
            assertTrue(runCatching {store.mathWorkInputs.review(owner,args("改写用户原式"))}.isFailure)
            store.file(item).appendText("tampered")
            assertTrue(runCatching {store.mathWorkInputs.read(owner,id)}.isFailure)
        } finally {original.delete();cleanup(owner)}
    }
    @Test fun twelveUnicodePagesSurviveActualDiskReadbackAndPhysicalPageBounds(): Unit = runBlocking {
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val original=pdf(12)
        try {
            val item=store.import(owner,original.toUri(),"unicode.pdf")
            val text="数学原稿".repeat(1000)
            val input=request(item,List(12){text})
            val work=store.mathWorkInputs.create(owner,input.toString(),true,{})
            val id=work.getValue("work_id").jsonPrimitive.content
            val fresh=MaterialFiles(context).mathWorkInputs.read(owner,id)
            assertEquals(text,fresh.getValue("pages").jsonArray[11].jsonObject.getValue("transcription").jsonPrimitive.content)
            val page=input.getValue("pages").jsonArray[0].jsonObject
            val outOfRange=JsonObject(input+("pages" to buildJsonArray {add(JsonObject(page+("number" to JsonPrimitive(13))))}))
            assertTrue(runCatching {store.mathWorkInputs.create(owner,outOfRange.toString(),true,{})}.isFailure)
            val changed=JsonObject(input+("title" to JsonPrimitive("拒绝过期档案")))
            assertTrue(runCatching {store.mathWorkInputs.create(owner,changed.toString(),true,{error("context epoch changed")})}.isFailure)
        } finally {original.delete();cleanup(owner)}
    }
}
