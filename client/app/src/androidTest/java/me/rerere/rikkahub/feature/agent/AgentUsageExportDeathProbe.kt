package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.feature.material.MaterialFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host waits for a durable actual production export checkpoint, then stops the real QA process. */
@RunWith(AndroidJUnit4::class)
class AgentUsageExportDeathProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also { require(UUID.fromString(it).toString()==it) }
    private val point get()=requireNotNull(args.getString("checkpoint"))
    private val folder get()=context.cacheDir.resolve("usage-export-process-$fixture")
    private fun durable(file: File,value: String){file.parentFile!!.mkdirs();file.outputStream().use { it.write(value.toByteArray());it.fd.sync() }}
    @Test fun prepare(): Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val store=AgentTaskStore(context,"qa-export-process-$fixture.db");val files=MaterialFiles(context)
        val task=store.accept(fixture,fixture,"seed","synthetic","private-ref","p","m","OFF","config")
        durable(folder.resolve("task"),task.id)
        repeat(75){i ->store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",UUID.randomUUID().toString());put("task_id",task.id);put("role","ocr");put("provenance","unknown")
            put("model_id","model-"+"x".repeat(120));put("provider_id","p");put("prompt_tokens",0L);put("total_tokens",0L);put("created_at",100L)
        })}
        AgentUsageExport.export(files,fixture,null,null,null,"requests",fixture,{store.captureUsage(fixture,null,null,null,"requests",fixture)},{},
            checkpoint={p,item ->if(p==point){
                durable(folder.resolve("artifact"),item.id);durable(folder.resolve("expected"),item.sha256)
                durable(folder.resolve("ready"),point)
                while(true)delay(1000)
            }},history=AgentUsageExportHistory(context))
        fail("Host failed to stop prepared export")
    }
    @Test fun verify(): Unit=runBlocking {
        val files=MaterialFiles(context);val id=folder.resolve("artifact").readText();val expected=folder.resolve("expected").readText()
        AgentTaskStore(context,"qa-export-process-$fixture.db").use { store ->
            val taskId=folder.resolve("task").readText();assertEquals("QUEUED",store.get(fixture,taskId).state)
            val intent=AgentUsageExportHistory(context).last(fixture,null,null,null,"requests")!!
            assertEquals(expected,intent.sha256);assertEquals(id,intent.artifactId)
            if(point=="after_history") {
                assertTrue(files.list(fixture).isEmpty())
                assertTrue(runCatching {AgentUsageExport.verifyExisting(files,intent,{})}.isFailure)
                assertTrue(files.list(fixture).isEmpty())
                println("USAGE_EXPORT_REOPEN_VERIFIED_$point")
                return@runBlocking
            }
            val item=files.get(fixture,id);assertEquals(expected,item.sha256)
            if(point in setOf("after_move","after_receipt")){
                val bytes=files.file(item).readBytes()
                val result=AgentUsageExport.verifyExisting(files,intent,{})
                assertTrue(result.reused);assertEquals(75L,result.recordCount);assertEquals(expected,result.sha256)
                assertArrayEquals(bytes,files.verifiedFile(fixture,id).readBytes())
            }else{
                assertFalse(files.file(item).exists())
                assertTrue(runCatching {AgentUsageExport.verifyExisting(files,intent,{})}.isFailure)
                assertFalse(files.file(item).exists())
            }
            assertEquals(75L,store.readUsage(fixture,null,null,null,null,100).requestCount)
        }
        println("USAGE_EXPORT_REOPEN_VERIFIED_$point")
    }
}
