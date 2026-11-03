package me.rerere.rikkahub.feature.material

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Frozen CC0 five-page corpus, distinct from the historical LiveTest fixture. */
class LiveOcrQualityTest {
    @Test fun authorizedPublicCorpusUsesExistingMinerUAndCreatesSourceBoundWord(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qualityOcrConsent")=="EXISTING_SERVICE_PUBLIC_FIVE_PAGES")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val run=requireNotNull(args.getString("qualityRunId"));require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val base=File(context.noBackupFilesDir,"quality-evaluation")
        val source=File(base,"source.pdf")
        check(MaterialFiles.digest(source)=="a2c4bf5c1491020b040eff25ecd7fda68980fab0493b76202c8798c39f0b51b0")
        val out=File(base,run);check(!out.exists() && out.mkdirs())
        val uri=URI(requireNotNull(args.getString("qualityProvisionUrl")))
        require(uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port in 1024..65535 &&
            uri.userInfo==null && uri.rawQuery==null && uri.path.matches(Regex("/[a-f0-9]{64}")))
        val connection=uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=15000
        val credential=try {
            check(connection.responseCode==200)
            connection.inputStream.bufferedReader().use { input ->
                val result=StringBuilder();val buffer=CharArray(1024)
                while(true) { val count=input.read(buffer);if(count<0)break;result.append(buffer,0,count);check(result.length<=4096) }
                Json.parseToJsonElement(result.toString()).jsonObject
            }
        } finally {connection.disconnect()}
        check(credential.getValue("baseUrl")==JsonPrimitive("https://hxnf.top/documents"))
        val owner=UUID.randomUUID().toString();val files=MaterialFiles(context);val repository=DocumentServiceRepository(context,files)
        repository.configure(owner,"https://hxnf.top/documents",credential.getValue("token").jsonPrimitive.content,true)
        fun save(name: String,value: JsonElement) { File(out,name).outputStream().use {it.write(value.toString().toByteArray())} }
        val capabilities=repository.capabilities(owner);save("CAPABILITIES.json",capabilities)
        check(capabilities.getValue("ready")==JsonPrimitive(true) && capabilities.getValue("parser")==JsonPrimitive("MinerU"))
        val material=files.import(owner,source.toUri(),"public-fixed-ocr-corpus.pdf")
        save("SOURCE.json",buildJsonObject {put("id",material.id);put("sha256",material.sha256);put("owner",owner)})
        // submit persists its own idempotency journal before upload. Never resubmit on a timeout.
        val task=repository.submit(owner,material.id,1,5)
        save("SUBMISSION.json",buildJsonObject {put("id",task.id);put("remoteId",task.remoteId);put("sourceSha256",task.sourceSha256)})
        val finished=withTimeout(960000) {
            var current=repository.refresh(owner,task.id)
            while(current.state in setOf("QUEUED","RUNNING")) {delay(2000);current=repository.refresh(owner,task.id)}
            current
        }
        save("TASK.json",Json.encodeToJsonElement(finished))
        check(finished.state=="SUCCEEDED") { "Actual parser task did not succeed; evidence retained" }
        val document=repository.download(owner,task.id)
        save("document.json",Json.encodeToJsonElement(document))
        check(document.sourceSha256==material.sha256 && document.totalPages==5)
        // This is an unconfirmed editable candidate. No artificial human/OCR/math approval.
        val draft=MaterialPdfOcrDraft.edit(files,repository,owner,material,buildJsonObject {
            put("task_ids",buildJsonArray {add(task.id)});put("start_page",1);put("end_page",5)
        },"实际MinerU公开语料候选")
        files.verifiedFile(owner,draft.id).copyTo(File(out,"ocr-candidate.docx"),overwrite=false)
        save("WORD_RECEIPT.json",Json.parseToJsonElement(draft.detail))
        save("RESULT.json",buildJsonObject {
            put("execution","ACTUAL_ORIGINAL_ANDROID_DOCUMENT_SERVICE");put("taskId",task.id);put("remoteId",task.remoteId)
            put("sourceSha256",material.sha256);put("documentSha256",MaterialFiles.digest(File(out,"document.json")))
            put("wordSha256",draft.sha256);put("recognitionQuality","NOT_SCORED");put("mathQuality","NOT_EVALUATED")
            put("userConfirmation","NOT_PERFORMED");put("remoteCleanup","NOT_REQUESTED_RETENTION_POLICY_APPLIES")
        })
        // Preserve remote/local receipts and encrypted configuration for readback; no deletion of other jobs.
    }
}
