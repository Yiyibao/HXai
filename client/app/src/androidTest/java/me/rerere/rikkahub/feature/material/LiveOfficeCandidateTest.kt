package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Reuse the already verified public OCR candidate; never repeat its OCR upload. */
class LiveOfficeCandidateTest {
    @Test fun existingPublicCandidateRendersThroughOriginalLibreOfficeRepository(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qualityOfficeConsent")=="EXISTING_SERVICE_PUBLIC_CANDIDATE")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val run=requireNotNull(args.getString("qualityRunId"));require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val base=File(context.noBackupFilesDir,"quality-evaluation")
        val prior=File(base,"r27-ocr-01")
        val identity=Json.parseToJsonElement(File(prior,"SOURCE.json").readText()).jsonObject
        val original=Json.parseToJsonElement(File(prior,"RESULT.json").readText()).jsonObject
        check(original.getValue("sourceSha256")==JsonPrimitive("a2c4bf5c1491020b040eff25ecd7fda68980fab0493b76202c8798c39f0b51b0"))
        val owner=identity.getValue("owner").jsonPrimitive.content
        val files=MaterialFiles(context);val docs=DocumentServiceRepository(context,files)
        val source=files.list(owner).single { it.extension=="docx" && it.sha256==original.getValue("wordSha256").jsonPrimitive.content }
        val out=File(base,run);check(!out.exists() && out.mkdirs())
        fun save(name: String,value: JsonElement)=File(out,name).outputStream().use { it.write(value.toString().toByteArray()) }
        files.verifiedFile(owner,source.id).copyTo(File(out,"source-candidate.docx"),overwrite=false)
        val task=docs.officeConversions.submit(owner,source.id,source.sha256,"公开OCR候选LibreOffice读回")
        save("SUBMISSION.json",Json.encodeToJsonElement(task))
        val finished=withTimeout(360000) {
            var current=docs.officeConversions.refresh(owner,task.id)
            while(current.state in setOf("QUEUED","RUNNING")) {delay(2000);current=docs.officeConversions.refresh(owner,task.id)}
            current
        }
        save("TASK.json",Json.encodeToJsonElement(finished))
        check(finished.state=="SUCCEEDED") { "Actual renderer failed; do not resubmit" }
        val result=docs.officeConversions.download(owner,task.id)
        files.verifiedFile(owner,result.id).copyTo(File(out,"libreoffice-candidate.pdf"),overwrite=false)
        check(files.get(owner,source.id).sha256==source.sha256)
        save("RECEIPT.json",Json.parseToJsonElement(result.detail))
        save("RESULT.json",buildJsonObject {
            put("status","PASS_SCOPED");put("scope","ACTUAL_PUBLIC_CANDIDATE_LIBREOFFICE_RENDER_NOT_WPS_OR_PIXEL_IDENTITY")
            put("sourceSha256",source.sha256);put("pdfSha256",result.sha256);put("taskId",task.id)
            put("remoteId",task.remoteId);put("ocrReplayed",false);put("recognitionQuality","NOT_EVALUATED")
        })
    }
}
