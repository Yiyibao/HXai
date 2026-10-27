package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageRecoveryRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: suspend (AgentTaskStore,MaterialFiles,AgentUsageExportHistory,String) -> Unit)=runBlocking {
        val owner=UUID.randomUUID().toString();val name="qa-recovery-$owner.db"
        try { AgentTaskStore(context,name).use { test(it,MaterialFiles(context),AgentUsageExportHistory(context),owner) } }
        finally {
            SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
            context.filesDir.resolve("materials/$owner").deleteRecursively()
            context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$owner").deleteRecursively()
        }
    }
    private suspend fun export(s: AgentTaskStore,f: MaterialFiles,h: AgentUsageExportHistory,o: String,request: String=UUID.randomUUID().toString(),
        task: String?=null,from: Long?=null,until: Long?=null,group: String="requests",point: suspend (String,me.rerere.rikkahub.feature.material.MaterialFile)->Unit={_,_->})=
        AgentUsageExport.export(f,o,task,from,until,group,request,{s.captureUsage(o,task,from,until,group,request)},{},point,h)

    @Test fun reopenedHistoryIsAnOwnedIntentAndManualReadbackPreservesActualBytes()=fixture {s,f,h,o ->
        val receipt=export(s,f,h,o);val before=f.verifiedFile(o,receipt.artifactId).readBytes()
        val fresh=AgentUsageExportHistory(context);val item=fresh.last(o,null,null,null,"requests")!!
        assertEquals(receipt.requestId,item.requestId);assertEquals(receipt.sha256,item.sha256)
        val result=AgentUsageExport.verifyExisting(MaterialFiles(context),item,{})
        assertTrue(result.reused);assertArrayEquals(before,f.verifiedFile(o,result.artifactId).readBytes())
        assertEquals(1,f.list(o).size);assertEquals(item,fresh.last(o,null,null,null,"requests"))
    }
    @Test fun historyBeforeMaterialIntentDoesNotCreateFileOrRecaptureWhenVerified()=fixture {s,f,h,o ->
        assertTrue(runCatching {export(s,f,h,o,point={p,_ ->if(p=="after_history") error("interrupt")})}.isFailure)
        val item=AgentUsageExportHistory(context).last(o,null,null,null,"requests")!!
        assertTrue(f.list(o).isEmpty());assertTrue(runCatching {AgentUsageExport.verifyExisting(f,item,{})}.isFailure)
        assertTrue(f.list(o).isEmpty());assertFalse(context.filesDir.resolve("materials/$o/${item.artifactId}.json").exists())
    }
    @Test fun lostAckAfterMoveRecoversOriginalHashButPartialAndMissingCannotBeRewritten()=fixture {s,f,h,o ->
        for(point in listOf("after_intent","after_partial","before_commit","after_move","after_receipt")) {
            val req=UUID.randomUUID().toString()
            assertTrue(runCatching {export(s,f,h,o,req,point={p,_ ->if(p==point) error("synthetic stop")})}.isFailure)
            val intent=h.get(o,null,null,null,"requests",req);val item=f.get(o,intent.artifactId)
            if(point in setOf("after_move","after_receipt")) {
                val bytes=f.file(item).readBytes();val result=AgentUsageExport.verifyExisting(f,intent,{})
                assertTrue(result.reused);assertEquals(intent.sha256,result.sha256);assertArrayEquals(bytes,f.verifiedFile(o,result.artifactId).readBytes())
            }else{
                assertFalse(f.file(item).exists());assertTrue(runCatching {AgentUsageExport.verifyExisting(f,intent,{})}.isFailure);assertFalse(f.file(item).exists())
            }
        }
    }
    @Test fun scopeOwnerDateAndGroupAreExactAndRepeatedOldRequestDoesNotBecomeLatest()=fixture {s,f,h,o ->
        val task=s.accept(o,UUID.randomUUID().toString(),"seed","goal","missing","p","m","OFF","config")
        val first=export(s,f,h,o);Thread.sleep(2);val second=export(s,f,h,o)
        export(s,f,h,o,first.requestId)
        assertEquals(second.requestId,h.last(o,null,null,null,"requests")!!.requestId)
        assertNull(h.last(o,task.id,null,null,"requests"));assertNull(h.last(o,null,100,200,"requests"));assertNull(h.last(o,null,null,null,"role"))
        assertNull(h.last(UUID.randomUUID().toString(),null,null,null,"requests"))
        assertTrue(runCatching {h.get(o,task.id,null,null,"requests",first.requestId)}.isFailure)
        val scoped=export(s,f,h,o,task=task.id,from=100,until=200,group="role")
        assertEquals(scoped.requestId,h.last(o,task.id,100,200,"role")!!.requestId)
    }
    @Test fun corruptFutureAndForeignIntentAreRejectedAndPreserved()=fixture {s,f,h,o ->
        val result=export(s,f,h,o)
        val path=context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$o").walkTopDown().single {it.name=="${result.requestId}.json"}
        val original=path.readText()
        for(bad in listOf("corrupt sentinel",original.replace("\"schemaVersion\":1","\"schemaVersion\":99"),original.replace(o,UUID.randomUUID().toString()))) {
            // schemaVersion defaults may be omitted; inject an explicit future version instead.
            val value=if(bad==original) original.replaceFirst("{","{\"schemaVersion\":99,") else bad
            path.writeText(value)
            assertTrue(runCatching {h.last(o,null,null,null,"requests")}.isFailure);assertEquals(value,path.readText())
        }
        path.writeText(original);assertEquals(result.requestId,h.last(o,null,null,null,"requests")!!.requestId)
    }
    @Test fun changedOrDeletedArtifactNeverPublishesAndLegacyExportRemainsUsable()=fixture {s,f,h,o ->
        val legacyRequest=UUID.randomUUID().toString()
        val legacy=AgentUsageExport.export(f,o,null,null,null,"requests",legacyRequest,
            {s.captureUsage(o,null,null,null,"requests",legacyRequest)},{})
        assertNull(h.last(o,null,null,null,"requests"))
        assertTrue(AgentUsageExport.export(f,o,null,null,null,"requests",legacy.requestId,{error("must not recapture")},{}).reused)
        val result=export(s,f,h,o);val intent=h.last(o,null,null,null,"requests")!!;val item=f.get(o,result.artifactId)
        f.file(item).appendText("damage");val bad=f.file(item).readBytes()
        assertTrue(runCatching {AgentUsageExport.verifyExisting(f,intent,{})}.isFailure);assertArrayEquals(bad,f.file(item).readBytes())
        f.file(item).delete();assertTrue(runCatching {AgentUsageExport.verifyExisting(f,intent,{})}.isFailure);assertFalse(f.file(item).exists())
        assertEquals(2,f.list(o).size)
    }
    @Test fun realHostChecksEpochOwnerForegroundAndExpectedHashForManualVerification(): Unit=runBlocking {
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();val owner=settings.settingsFlow.first{!it.init}.activeProfileId
        var foreground=true
        val host=StudyHostFacade(context,settings,k.get(),k.get(),k.get(),k.get(),k.get(),pickFiles={emptyList()},foregroundConfirmationAllowed={foreground})
        try {
            val epoch=host.getBootstrap(owner,1).contextEpoch
            val receipt=host.exportAgentUsage(owner,null,null,null,"requests",UUID.randomUUID().toString(),epoch)
            val intent=host.getAgentUsageLastExport(owner,null,null,null,"requests",epoch)!!
            assertEquals(receipt.requestId,intent.requestId)
            assertTrue(runCatching {host.getAgentUsageLastExport(owner,null,null,null,"requests",epoch-1)}.isFailure)
            assertTrue(runCatching {host.getAgentUsageLastExport(UUID.randomUUID().toString(),null,null,null,"requests",epoch)}.isFailure)
            suspend fun verify(hash: String=intent.sha256)=host.verifyAgentUsageExport(owner,null,null,null,"requests",intent.requestId,hash,epoch)
            foreground=false;assertTrue(runCatching{verify()}.isFailure)
            foreground=true;assertTrue(runCatching{verify("0".repeat(64))}.isFailure)
            assertTrue(verify().reused)
        }finally{host.close()}
    }
    @Test fun linkedOrChangedMetadataCannotSubstituteAnOriginalExport()=fixture {s,f,h,o ->
        val result=export(s,f,h,o);val intent=h.last(o,null,null,null,"requests")!!;val item=f.get(o,result.artifactId)
        val target=f.file(item);val backup=target.parentFile!!.resolve("synthetic-link-source.json")
        target.copyTo(backup);target.delete();java.nio.file.Files.createSymbolicLink(target.toPath(),backup.toPath())
        assertTrue(runCatching {AgentUsageExport.verifyExisting(f,intent,{})}.isFailure)
        assertEquals(result.sha256,MaterialFiles.digest(backup));target.delete();backup.copyTo(target)
        f.record(item.copy(sha256="0".repeat(64)))
        assertTrue(runCatching {AgentUsageExport.verifyExisting(f,intent,{})}.isFailure)
        assertEquals(result.sha256,MaterialFiles.digest(target));assertEquals("0".repeat(64),f.get(o,item.id).sha256)
    }
}
