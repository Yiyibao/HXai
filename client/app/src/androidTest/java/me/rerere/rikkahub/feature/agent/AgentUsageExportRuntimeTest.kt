package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageExportRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: suspend (AgentTaskStore,MaterialFiles,String) -> Unit)=runBlocking {
        val owner=UUID.randomUUID().toString();val name="qa-export-$owner.db"
        try { AgentTaskStore(context,name).use { test(it,MaterialFiles(context),owner) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name));context.filesDir.resolve("materials/$owner").deleteRecursively() }
    }
    private fun task(s: AgentTaskStore,owner: String)=s.accept(owner,"conversation-${UUID.randomUUID()}",UUID.randomUUID().toString(),
        "private-goal-sentinel","private-vault-reference","task-provider","task-model","HIGH","config")
    private fun row(s: AgentTaskStore,t: AgentTask,index: Int=0,provider: String?="provider",model: String?="model",role: String="main",
        provenance: String="reported",total: Long?=0,at: Long?=100): String {
        val id=UUID.randomUUID().toString()
        s.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",id);put("task_id",t.id);put("role",role);put("provider_id",provider);put("model_id",model)
            put("provenance",provenance);put("total_tokens",total);put("prompt_tokens",index.toLong());put("created_at",at)
        })
        s.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",t.id);put("call_id",id);put("tool","provider:$role");put("input_hash","private-input-hash-sentinel");put("state","FAILED")
        });return id
    }
    private suspend fun export(s: AgentTaskStore,f: MaterialFiles,o: String,mode: String="requests",request: String=UUID.randomUUID().toString(),
        id: String?=null,from: Long?=null,until: Long?=null,check: () -> Unit={},point: suspend (String,me.rerere.rikkahub.feature.material.MaterialFile) -> Unit={_,_->})=
        AgentUsageExport.export(f,o,id,from,until,mode,request,{ s.captureUsage(o,id,from,until,mode,request) },check,point)
    private fun data(f: MaterialFiles,o: String,id: String)=AgentUsageSnapshot.decode(f.verifiedFile(o,id).readBytes()).getValue("data").jsonObject

    @Test fun fullWindowIncludesMoreThanThirtyPhysicalReceiptsWithoutPrivateReferences()=fixture { s,f,o ->
        val task=task(s,o);val foreign=task(s,UUID.randomUUID().toString())
        val ids=(0..74).map { row(s,task,it,role=if(it%2==0) "ocr" else "main",provenance=if(it%3==0) "unknown" else "reported") }.toSet()
        row(s,foreign,total=999)
        val before=s.snapshot(o,task.id);val receipt=export(s,f,o);val body=data(f,o,receipt.artifactId)
        assertEquals(75L,receipt.recordCount);assertEquals(75L,receipt.requestCount)
        assertEquals(ids,body.getValue("records").jsonArray.map { it.jsonObject.getValue("requestId").jsonPrimitive.content }.toSet())
        assertFalse(body.toString().contains("private-goal-sentinel"));assertFalse(body.toString().contains("private-vault-reference"))
        assertFalse(body.toString().contains("private-input-hash-sentinel"));assertEquals(before,s.snapshot(o,task.id))
        assertEquals(AgentUsageSnapshot.digest(f.verifiedFile(o,receipt.artifactId).readBytes()),receipt.sha256)
    }
    @Test fun groupsKeepProviderCompositeKeysZeroUnknownAndHugeDecimalStrings()=fixture { s,f,o ->
        val task=task(s,o);row(s,task,provider="A",model="same",total=Long.MAX_VALUE)
        row(s,task,provider="B",model="same",provenance="estimated",total=0)
        row(s,task,provider=null,model=null,role="ocr",provenance="unknown",total=0)
        for(mode in listOf("provider","model","role")) {
            val receipt=export(s,f,o,mode);val body=data(f,o,receipt.artifactId)
            assertEquals(3L,receipt.requestCount);assertEquals(if(mode=="role") 2L else 3L,receipt.recordCount)
            assertEquals(JsonNull,body.getValue("cost").jsonObject.getValue("amount"))
            assertEquals("decimal_strings",body.getValue("integerEncoding").jsonPrimitive.content)
            assertTrue(body.toString().contains("9223372036854775807"))
            if(mode=="model") assertEquals(setOf("A","B",null),body.getValue("records").jsonArray.map { it.jsonObject["providerId"]!!.jsonPrimitive.contentOrNull }.toSet())
        }
    }
    @Test fun halfOpenDatesUndatedRowsEmptyScopeAndWrongTaskAreDistinct()=fixture { s,f,o ->
        val task=task(s,o);listOf(99L,100L,199L,200L,null).forEach { row(s,task,at=it) }
        val result=export(s,f,o,id=task.id,from=100,until=200)
        assertEquals(2L,result.recordCount);assertEquals("1",data(f,o,result.artifactId).getValue("summary").jsonObject.getValue("undatedRequests").jsonPrimitive.content)
        val empty=export(s,f,o,from=1000,until=2000);assertEquals(0L,empty.recordCount)
        assertTrue(runCatching { export(s,f,o,id=task(s,UUID.randomUUID().toString()).id) }.isFailure)
        assertTrue(runCatching { export(s,f,o,from=0) }.isFailure)
    }
    @Test fun duplicateAckAndConcurrentSameIdReuseOriginalSnapshotAfterLateUsage()=fixture { s,f,o ->
        val task=task(s,o);row(s,task,provenance="unknown",total=0);val req=UUID.randomUUID().toString()
        val one=export(s,f,o,request=req);val bytes=f.verifiedFile(o,one.artifactId).readBytes()
        row(s,task,total=7)
        val results=coroutineScope { (1..3).map { async { export(s,f,o,request=req) } }.awaitAll() }
        assertTrue(results.all { it.reused && it.sha256==one.sha256 && it.recordCount==1L })
        assertEquals(1,f.list(o).size);assertArrayEquals(bytes,f.verifiedFile(o,one.artifactId).readBytes())
        val fresh=export(s,f,o);assertEquals(2L,fresh.recordCount);assertNotEquals(one.artifactId,fresh.artifactId)
        assertArrayEquals(bytes,f.verifiedFile(o,one.artifactId).readBytes())
        assertTrue(runCatching { export(s,f,o,"model",req) }.isFailure)
    }
    @Test fun knownMoveWithoutReceiptIsRecoveredByReadbackWithoutReexecutingCapture()=fixture { s,f,o ->
        val task=task(s,o);row(s,task);val req=UUID.randomUUID().toString()
        assertTrue(runCatching { export(s,f,o,request=req,point={point,_ -> if(point=="after_move") error("synthetic dropped ack")}) }.isFailure)
        val stored=f.get(o,AgentUsageExport.artifactId(o,req));assertEquals("FAILED",stored.state)
        val before=f.file(stored).readBytes()
        val reused=AgentUsageExport.export(f,o,null,null,null,"requests",req,{ error("Must not recapture") },{})
        assertTrue(reused.reused);assertEquals(stored.sha256,reused.sha256);assertArrayEquals(before,f.verifiedFile(o,reused.artifactId).readBytes())
    }
    @Test fun intentOnlyAndCancelledPartialAreNotBlindlyRewritten()=fixture { s,f,o ->
        val task=task(s,o);row(s,task)
        for(stop in listOf("after_intent","after_partial")) {
            val req=UUID.randomUUID().toString()
            assertTrue(runCatching { export(s,f,o,request=req,point={p,_ -> if(p==stop) throw CancellationException("synthetic cancel")}) }.isFailure)
            val item=f.get(o,AgentUsageExport.artifactId(o,req));assertFalse(f.file(item).exists())
            assertTrue(runCatching { export(s,f,o,request=req) }.isFailure);assertFalse(f.file(item).exists())
        }
        assertEquals(1L,export(s,f,o).recordCount)
    }
    @Test fun corruptOutputMetadataAndOrphanArePreservedAndNeverReportedSuccessful()=fixture { s,f,o ->
        val task=task(s,o);row(s,task);val req=UUID.randomUUID().toString();val one=export(s,f,o,request=req)
        val file=f.verifiedFile(o,one.artifactId);file.appendText("corrupt")
        val bad=file.readBytes();assertTrue(runCatching { export(s,f,o,request=req) }.isFailure);assertArrayEquals(bad,file.readBytes())
        val orphanReq=UUID.randomUUID().toString();val id=AgentUsageExport.artifactId(o,orphanReq)
        val orphan=context.filesDir.resolve("materials/$o/$id.json");orphan.writeText("original orphan sentinel")
        assertTrue(runCatching { export(s,f,o,request=orphanReq) }.isFailure);assertEquals("original orphan sentinel",orphan.readText())
        val metaReq=UUID.randomUUID().toString();val meta=context.filesDir.resolve("materials/$o/${AgentUsageExport.artifactId(o,metaReq)}.meta.json")
        meta.writeText("corrupt metadata sentinel");assertTrue(runCatching { export(s,f,o,request=metaReq) }.isFailure);assertEquals("corrupt metadata sentinel",meta.readText())
    }
    @Test fun ownerEpochChangeAfterMoveBlocksPublicationThenOriginalOwnedFileCanBeVerified()=fixture { s,f,o ->
        val task=task(s,o);row(s,task);var current=true;val req=UUID.randomUUID().toString()
        assertTrue(runCatching { export(s,f,o,request=req,check={check(current)},point={p,_ -> if(p=="after_move") current=false}) }.isFailure)
        assertEquals("FAILED",f.get(o,AgentUsageExport.artifactId(o,req)).state)
        current=true;assertTrue(export(s,f,o,request=req,check={check(current)}).reused)
        val denied=UUID.randomUUID().toString();assertTrue(runCatching { export(s,f,o,request=denied,check={error("Denied")}) }.isFailure)
        assertTrue(f.list(o).none { it.id==AgentUsageExport.artifactId(o,denied) })
    }
    @Test fun recordTextAndByteBoundsFailWholeExportBeforeIntent()=fixture { s,f,o ->
        val task=task(s,o)
        s.writableDatabase.beginTransaction()
        try { for(i in 0..10000) row(s,task,i);s.writableDatabase.setTransactionSuccessful() } finally { s.writableDatabase.endTransaction() }
        assertTrue(runCatching { export(s,f,o) }.isFailure);assertTrue(f.list(o).isEmpty())
        assertEquals(1L,export(s,f,o,"model").recordCount)
        s.writableDatabase.execSQL("UPDATE usage SET model_id=? WHERE task_id=?",arrayOf("x".repeat(4097),task.id))
        assertTrue(runCatching { export(s,f,o,"model") }.isFailure)
        s.writableDatabase.execSQL("DELETE FROM usage WHERE rowid>9000")
        s.writableDatabase.execSQL("UPDATE usage SET model_id=? WHERE task_id=?",arrayOf("x".repeat(1000),task.id))
        assertTrue(runCatching { export(s,f,o) }.isFailure);assertEquals(1,f.list(o).size)
    }
    @Test fun unicodeQuotedIdentityRoundTripsAndDecoderRejectsFalseSourceHash()=fixture { s,f,o ->
        val task=task(s,o);val special="=1+1\n\"引文🙂\\"
        row(s,task,provider=special,model=special,role="title")
        val result=export(s,f,o);val body=data(f,o,result.artifactId)
        assertEquals(special,body.getValue("records").jsonArray.single().jsonObject.getValue("modelId").jsonPrimitive.content)
        val source=f.verifiedFile(o,result.artifactId).readText();assertTrue(runCatching { AgentUsageSnapshot.decode(source.replace(result.sourceSha256,"0".repeat(64)).toByteArray()) }.isFailure)
        assertTrue(runCatching { AgentUsageSnapshot.decode(byteArrayOf(0xC3.toByte(),0x28)) }.isFailure)
    }
    @Test fun readOnlyCapturePreservesUnknownSlotAndTaskControl()=fixture { s,f,o ->
        val task=task(s,o);val lease=s.claim(o,task.id)
        s.beginRequest(lease,"main","synthetic",AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference));s.finish(lease,"FAILED_BLOCKED")
        val before=s.snapshot(o,task.id);assertEquals(1L,export(s,f,o).requestCount);assertEquals(before,s.snapshot(o,task.id))
        val other=task(s,UUID.randomUUID().toString());assertTrue(runCatching { s.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
    }
    @Test fun realHostRejectsBackgroundOwnerEpochAndReturnsOwnedArtifactReadback(): Unit=runBlocking {
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();val owner=settings.settingsFlow.first { !it.init }.activeProfileId
        var foreground=false
        val host=StudyHostFacade(context,settings,k.get(),k.get(),k.get(),k.get(),k.get(),pickFiles={ emptyList() },foregroundConfirmationAllowed={foreground})
        try {
            val epoch=host.getBootstrap(owner,1).contextEpoch;val req=UUID.randomUUID().toString()
            assertTrue(runCatching { host.exportAgentUsage(owner,null,null,null,"requests",req,epoch) }.isFailure)
            foreground=true
            assertTrue(runCatching { host.exportAgentUsage(owner,null,null,null,"requests",req,epoch-1) }.isFailure)
            assertTrue(runCatching { host.exportAgentUsage("other",null,null,null,"requests",req,epoch) }.isFailure)
            val result=host.exportAgentUsage(owner,null,null,null,"requests",req,epoch)
            val preview=host.getArtifactPreview(owner,result.artifactId,epoch);assertEquals(result.sha256,preview.sha256);assertEquals("SUCCEEDED",preview.state)
            assertTrue(host.exportAgentUsage(owner,null,null,null,"requests",req,epoch).reused)
        } finally { host.close() }
    }
}
