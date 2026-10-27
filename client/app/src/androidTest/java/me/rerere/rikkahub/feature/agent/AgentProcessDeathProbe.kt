package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Host kills the actual isolated app process; no exception substitutes for process death. */
@RunWith(AndroidJUnit4::class)
class AgentProcessDeathProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val checkpoint get()=requireNotNull(args.getString("checkpoint"))
    private val name get()="qa-process-$fixture.db"
    private val folder get()=context.cacheDir.resolve("agent-process-$fixture")
    private fun durable(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
    }

    @Test fun prepare() {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        check(checkpoint in setOf("before_claim","after_claim","after_journal","after_effect","after_receipt"))
        val store=AgentTaskStore(context,name)
        val task=store.accept("qa-owner",fixture,"once","synthetic effect","synthetic-reference","synthetic-provider","synthetic-model","HIGH","synthetic-revision")
        durable(folder.resolve("task"),task.id)
        if(checkpoint!="before_claim") {
            val lease=store.claim(task.owner,task.id)
            durable(folder.resolve("fence"),lease.fence.toString())
            if(checkpoint!="after_claim") {
                store.beginCall(lease,"write-once","create_material_file","synthetic-payload")
                if(checkpoint!="after_journal") {
                    val effect=folder.resolve("effect.txt")
                    durable(effect,"exactly-once-synthetic-effect")
                    if(checkpoint=="after_receipt") {
                        assertEquals("exactly-once-synthetic-effect",effect.readText())
                        store.finishCall(lease,"write-once",AgentTaskStore.hash(effect.readText()),true)
                    }
                }
            }
        }
        durable(folder.resolve("ready"),checkpoint)
        println("QA_PROCESS_READY_$checkpoint")
        Thread.sleep(120_000)
        fail("Host did not terminate the prepared process")
    }

    @Test fun verify() {
        val taskId=folder.resolve("task").readText()
        val oldFence=folder.resolve("fence").takeIf { it.exists() }?.readText()?.toLong() ?: 0
        AgentTaskStore(context,name).use { store ->
            store.recover()
            val current=store.get("qa-owner",taskId)
            val effect=folder.resolve("effect.txt")
            val unsafe=checkpoint in setOf("after_journal","after_effect")
            assertEquals(if(unsafe) "UNCERTAIN_SIDE_EFFECT" else if(checkpoint=="before_claim") "QUEUED" else "INTERRUPTED",current.state)
            if(unsafe) {
                val other=store.accept("qa-other-owner",fixture,"other","synthetic","ref","p","m","OFF","revision")
                assertTrue(runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
                assertTrue(runCatching { store.resume(current.owner,current.id) }.isFailure)
            } else {
                if(current.state!="QUEUED") store.resume(current.owner,current.id)
                val lease=store.claim(current.owner,current.id)
                assertTrue(lease.fence>oldFence)
                if(checkpoint=="after_receipt") {
                    assertTrue(runCatching { store.beginCall(lease,"write-once","create_material_file","synthetic-payload") }.isFailure)
                    store.readableDatabase.rawQuery("SELECT state,result_hash FROM calls WHERE task_id=? AND call_id='write-once'",arrayOf(taskId)).use {
                        assertTrue(it.moveToFirst()); assertEquals("VERIFIED",it.getString(0)); assertEquals(AgentTaskStore.hash(effect.readText()),it.getString(1))
                    }
                }
                if(oldFence>0) assertTrue(runCatching { store.check(AgentLease(current,oldFence)) }.isFailure)
                store.finish(lease,"COMPLETED_UNVERIFIED")
            }
            assertEquals(checkpoint in setOf("after_effect","after_receipt"),effect.exists())
            if(effect.exists()) assertEquals("exactly-once-synthetic-effect",effect.readText())
            assertEquals(if(effect.exists()) 1 else 0,folder.listFiles().orEmpty().count { it.name=="effect.txt" })
        }
        println("QA_PROCESS_VERIFIED_$checkpoint")
    }
}
