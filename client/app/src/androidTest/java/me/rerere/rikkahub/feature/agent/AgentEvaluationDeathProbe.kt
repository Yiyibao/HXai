package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.feature.material.MaterialFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Dedicated fixture only: actually exits at a durable export boundary, then verifies in a new process. */
@RunWith(AndroidJUnit4::class)
class AgentEvaluationDeathProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also { require(UUID.fromString(it).toString()==it) }
    private val point get()=requireNotNull(args.getString("checkpoint")).also { require(it in setOf("after_intent","after_move")) }
    private val folder get()=context.noBackupFilesDir.resolve("qa-evaluation-process/$fixture")
    private fun durable(file: File,text: String) { file.parentFile!!.mkdirs();file.outputStream().use { it.write(text.toByteArray());it.fd.sync() } }
    @Test fun prepare(): Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val store=AgentTaskStore(context,"qa-evaluation-process-$fixture.db")
        val task=store.accept(fixture,fixture,"seed","synthetic visible question","PRIVATE_REF_NOT_EXPORTED","p","m","HIGH","accepted-config")
        durable(folder.resolve("task"),task.id)
        AgentEvaluationExport.export(MaterialFiles(context),fixture,task.id,fixture,0,0,{
            AgentEvaluationSnapshot.assemble(store.captureEvaluation(fixture,task.id,0,0),
                AgentEvaluationSnapshot.answer(null,fixture,task.id,fixture))
        },{}, { checkpoint -> if(checkpoint==point) {
            val files=MaterialFiles(context);val item=files.get(fixture,AgentEvaluationExport.artifactId(fixture,fixture))
            durable(folder.resolve("expected"),item.sha256)
            durable(folder.resolve("ready"),"$point:${android.os.Process.myPid()}")
            android.os.Process.killProcess(android.os.Process.myPid())
            error("Process did not exit")
        } })
        fail("Expected process exit did not occur")
    }
    @Test fun verify(): Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val ready=folder.resolve("ready").readText();assertEquals(point,ready.substringBefore(':'))
        assertNotEquals(ready.substringAfter(':').toInt(),android.os.Process.myPid())
        val files=MaterialFiles(context);val id=AgentEvaluationExport.artifactId(fixture,fixture)
        val before=files.get(fixture,id);assertEquals(folder.resolve("expected").readText(),before.sha256)
        AgentTaskStore(context,"qa-evaluation-process-$fixture.db").use { store ->
            val task=store.get(fixture,folder.resolve("task").readText())
            assertEquals("QUEUED",task.state)
            val effect=runCatching { AgentEvaluationExport.export(files,fixture,task.id,fixture,0,0,{ error("must never recapture") },{}) }
            if(point=="after_move") {
                assertTrue(effect.isSuccess);assertEquals(before.sha256,MaterialFiles.digest(files.verifiedFile(fixture,id)))
            } else {
                assertTrue(effect.isFailure);assertFalse(files.file(before).exists())
            }
        }
        println("EVALUATION_PROCESS_REOPEN_VERIFIED_$point")
    }
}
