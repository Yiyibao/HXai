package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Host terminates this actual nativeqa process after an fsync checkpoint. */
@RunWith(AndroidJUnit4::class)
class ExecutorHandoffProcessProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val stage get()=requireNotNull(args.getString("stage"))
    private val folder get()=context.cacheDir.resolve("handoff-process-$fixture")
    private val name get()="qa-handoff-process-$fixture.db"
    private fun durable(file: File,value: String) {
        file.parentFile!!.mkdirs();file.outputStream().use { it.write(value.toByteArray());it.fd.sync() }
    }
    @Test fun prepare()=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        require(stage in setOf("requested","effect","stopped","activated"))
        val store=AgentTaskStore(context,name)
        val t=store.accept("owner",fixture,"once","synthetic goal","encrypted-ref","provider","model","HIGH","config")
        val lease=store.claim(t.owner,t.id);val host=store.supervise(lease,Job())
        durable(folder.resolve("task"),t.id);durable(folder.resolve("fence"),lease.fence.toString())
        if(stage=="effect") {
            store.beginCall(lease,"once","create_material_file","synthetic")
            durable(folder.resolve("effect"),"one-synthetic-effect")
        }
        store.requestHandoff(lease,"handoff","cloud")
        if(stage in setOf("stopped","activated")) store.recordExecutorStopped(host.stop("handoff"))
        if(stage=="activated") store.activateHandoff(t.owner,t.id,"handoff","cloud")
        durable(folder.resolve("ready"),stage)
        println("QA_HANDOFF_READY_$stage")
        Thread.sleep(120_000)
        fail("Host must terminate actual process")
    }
    @Test fun verify() {
        val id=folder.resolve("task").readText();val fence=folder.resolve("fence").readText().toLong()
        AgentTaskStore(context,name).use { store ->
            store.recover()
            val t=store.get("owner",id)
            val h=requireNotNull(store.readHandoff(t.owner,id,"handoff"))
            assertEquals(if(stage=="effect") "UNCERTAIN_SIDE_EFFECT" else "STOP_REQUESTED",t.state)
            val other=store.accept("other",fixture,"other","synthetic","ref","p","m","LOW","config")
            assertTrue(runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
            if(stage=="stopped") {
                assertEquals("STOPPED",h.state)
                val next=store.activateHandoff(t.owner,id,"handoff","cloud")
                assertEquals(fence+1,next.fence);store.check(next)
                assertEquals(next,store.activateHandoff(t.owner,id,"handoff","cloud"))
            } else if(stage=="activated") {
                assertEquals("ACTIVATED",h.state);assertEquals(fence+1,t.fence)
                assertTrue(runCatching { store.finish(AgentLease(t,fence),"SUCCEEDED") }.isFailure)
                assertTrue(runCatching { store.check(AgentLease(t,t.fence,"cloud")) }.isFailure)
            } else {
                assertEquals("REQUESTED",h.state)
                assertTrue(runCatching { store.activateHandoff(t.owner,id,"handoff","cloud") }.isFailure)
                assertTrue(runCatching { store.abandonHandoff(t.owner,id,"handoff") }.isFailure)
            }
            assertEquals(stage=="effect",folder.resolve("effect").exists())
            if(stage=="effect") {
                assertEquals("one-synthetic-effect",folder.resolve("effect").readText())
                store.readableDatabase.rawQuery("SELECT state FROM calls WHERE task_id=? AND call_id='once'",arrayOf(id)).use {
                    assertTrue(it.moveToFirst());assertEquals("UNKNOWN",it.getString(0))
                }
            }
            assertEquals("HIGH",t.reasoning)
        }
        println("QA_HANDOFF_VERIFIED_$stage")
    }
}
