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
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageHistoryRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: suspend (AgentTaskStore, MaterialFiles, AgentUsageExportHistory, String) -> Unit) = runBlocking {
        val owner=UUID.randomUUID().toString();val name="qa-history-$owner.db"
        try { AgentTaskStore(context,name).use { test(it,MaterialFiles(context),AgentUsageExportHistory(context),owner) } }
        finally {
            SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
            context.filesDir.resolve("materials/$owner").deleteRecursively()
            context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$owner").deleteRecursively()
        }
    }
    private suspend fun export(s: AgentTaskStore,f: MaterialFiles,h: AgentUsageExportHistory,o: String,
        task: String?=null,from: Long?=null,until: Long?=null,group: String="requests") =
        UUID.randomUUID().toString().let { req -> AgentUsageExport.export(f,o,task,from,until,group,req,
            {s.captureUsage(o,task,from,until,group,req)},{},history=h) }

    @Test fun reopenedCrossScopePagesPreserveOriginalRangeAndFiles() = fixture {s,f,h,o ->
        val task=s.accept(o,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"synthetic history","missing","p","m","OFF","config").id
        val receipts=listOf(export(s,f,h,o),export(s,f,h,o,task,100,200,"role"),export(s,f,h,o,null,300,400,"model"))
        val hashes=receipts.associate {it.artifactId to MaterialFiles.digest(f.verifiedFile(o,it.artifactId))}
        val fresh=AgentUsageExportHistory(context);val found=ArrayList<AgentUsageExportIntent>();var cursor: String?=null
        do {val page=fresh.page(o,cursor,1);assertEquals(o,page.owner);found.addAll(page.entries);cursor=page.nextCursor} while(cursor!=null)
        assertEquals(3,found.size);assertEquals(receipts.map {it.requestId}.toSet(),found.map {it.requestId}.toSet())
        assertEquals(found.sortedWith(compareByDescending<AgentUsageExportIntent>{it.createdAt}.thenByDescending{it.requestId}),found)
        val old=found.single {it.taskId==task};assertEquals(100L,old.from);assertEquals(200L,old.until);assertEquals("role",old.groupBy)
        assertTrue(AgentUsageExport.verifyExisting(f,old,{}).reused)
        assertEquals(3,f.list(o).size);hashes.forEach {(id,hash)->assertEquals(hash,MaterialFiles.digest(f.verifiedFile(o,id)))}
        assertTrue(fresh.page(UUID.randomUUID().toString(),null,20).entries.isEmpty())
    }
    @Test fun cursorIsBoundToOwnerAndWholeCollectionAndRefreshPreservesNewValidExport() = fixture {s,f,h,o ->
        export(s,f,h,o);export(s,f,h,o,group="role")
        val cursor=h.page(o,null,1).nextCursor!!
        assertTrue(runCatching {h.page(UUID.randomUUID().toString(),cursor,1)}.isFailure)
        assertTrue(runCatching {h.page(o,"{}",1)}.isFailure)
        export(s,f,h,o,from=100,until=200)
        assertTrue(runCatching {h.page(o,cursor,1)}.isFailure)
        assertEquals(3,h.page(o,null,100).entries.size);assertEquals(3,f.list(o).size)
        for(limit in listOf(0,101,Int.MAX_VALUE)) assertTrue(runCatching{h.page(o,null,limit)}.isFailure)
    }
    @Test fun malformedFutureWrongPathAndLinksAreRejectedWithoutDamagingOldRecords() = fixture {s,f,h,o ->
        val r=export(s,f,h,o)
        val root=context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$o")
        val path=root.walkTopDown().single {it.name=="${r.requestId}.json"};val bytes=path.readBytes()
        for(value in listOf("bad","x".repeat(4097),path.readText().replace("\"schemaVersion\":1","\"schemaVersion\":99"))) {
            path.writeText(value);assertTrue(runCatching{h.page(o,null,20)}.isFailure);assertEquals(value,path.readText())
        }
        path.writeBytes(bytes)
        val wrong=root.resolve("0".repeat(64));wrong.mkdir();val copy=wrong.resolve(path.name);path.copyTo(copy)
        assertTrue(runCatching{h.page(o,null,20)}.isFailure);assertArrayEquals(bytes,copy.readBytes());copy.delete();wrong.delete()
        val link=path.parentFile!!.resolve(UUID.randomUUID().toString()+".json")
        Files.createSymbolicLink(link.toPath(),path.toPath());assertTrue(runCatching{h.page(o,null,20)}.isFailure)
        assertArrayEquals(bytes,path.readBytes());link.delete();assertEquals(1,h.page(o,null,20).entries.size)
    }
    @Test fun incompleteIntentWriteIsNotClaimedButCommittedMissingArtifactRemainsVisible() = fixture {s,f,h,o ->
        val r=export(s,f,h,o);val root=context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$o")
        val dir=root.listFiles()!!.single();val remnant=dir.resolve(UUID.randomUUID().toString()+".tmp");remnant.writeText("unknown")
        f.verifiedFile(o,r.artifactId).delete()
        val i=h.page(o,null,20).entries.single();assertEquals(r.requestId,i.requestId)
        assertTrue(runCatching{AgentUsageExport.verifyExisting(f,i,{})}.isFailure)
        assertEquals("unknown",remnant.readText());assertEquals(1,f.list(o).size);assertFalse(f.file(f.get(o,r.artifactId)).exists())
    }
    @Test fun exactScopeLegacyReadsRemainUsableAndAbsentHistoryDoesNotCreateDirectories() = fixture {s,f,h,o ->
        assertTrue(h.page(o,null,20).entries.isEmpty())
        assertFalse(context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$o").exists())
        val req=UUID.randomUUID().toString()
        val legacy=AgentUsageExport.export(f,o,null,null,null,"requests",req,
            {s.captureUsage(o,null,null,null,"requests",req)},{})
        assertTrue(h.page(o,null,20).entries.isEmpty());assertNotNull(f.verifiedFile(o,legacy.artifactId))
        val r=export(s,f,h,o,from=10,until=20);assertEquals(r.requestId,h.last(o,null,10,20,"requests")!!.requestId)
        assertEquals(1,h.page(o,null,20).entries.size);assertEquals(2,f.list(o).size)
    }
    @Test fun duplicateRequestAcrossValidScopeDirectoriesIsRejectedAndPreserved() = fixture {s,f,h,o ->
        export(s,f,h,o);val first=h.page(o,null,20).entries.single()
        h.record(first.copy(groupBy="role"))
        assertTrue(runCatching{h.page(o,null,20)}.isFailure)
        assertEquals(first,h.get(o,null,null,null,"requests",first.requestId))
        assertEquals("role",h.get(o,null,null,null,"role",first.requestId).groupBy)
        assertEquals(1,f.list(o).size)
    }
    @Test fun corruptionOutsideFirstPageInvalidatesBrowseWithoutChangingAnyExport() = fixture {s,f,h,o ->
        val receipts = listOf(export(s,f,h,o),export(s,f,h,o,group="role"),export(s,f,h,o,from=10,until=20))
        val initial = h.page(o,null,1)
        val hidden = h.page(o,initial.nextCursor,100).entries.last()
        val root = context.noBackupFilesDir.resolve("agent-usage-export-intents/v1/$o")
        val path = root.walkTopDown().single {it.name=="${hidden.requestId}.json"}
        val original = path.readBytes()
        val hashes = receipts.associate {it.artifactId to MaterialFiles.digest(f.verifiedFile(o,it.artifactId))}
        path.writeText("damaged historical intent outside the displayed page")
        assertTrue(runCatching {h.page(o,null,1)}.isFailure)
        assertTrue(runCatching {h.page(o,initial.nextCursor,1)}.isFailure)
        assertEquals("damaged historical intent outside the displayed page",path.readText())
        hashes.forEach {(id,hash) -> assertEquals(hash,MaterialFiles.digest(f.verifiedFile(o,id)))}
        path.writeBytes(original)
        assertEquals(initial,h.page(o,null,1))
        assertEquals(2,h.page(o,initial.nextCursor,100).entries.size)
    }
    @Test fun realHostHistoryRequiresCurrentOwnerEpochAndDoesNotRunRequests()=runBlocking {
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();val o=settings.settingsFlow.first{!it.init}.activeProfileId
        val host=StudyHostFacade(context,settings,k.get(),k.get(),k.get(),k.get(),k.get(),pickFiles={emptyList()},foregroundConfirmationAllowed={true})
        try {
            val epoch=host.getBootstrap(o,1).contextEpoch
            val page=host.getAgentUsageExportHistory(o,null,20,epoch);assertEquals(o,page.owner)
            assertTrue(runCatching{host.getAgentUsageExportHistory(o,null,20,epoch-1)}.isFailure)
            assertTrue(runCatching{host.getAgentUsageExportHistory(UUID.randomUUID().toString(),null,20,epoch)}.isFailure)
            assertTrue(runCatching{host.getAgentUsageExportHistory(o,null,101,epoch)}.isFailure)
        } finally {host.close()}
    }
}
