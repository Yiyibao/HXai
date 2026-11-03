package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only the host driver terminates this process, after durable ready. */
class ResponseCompletionProcessProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also {require(it.matches(Regex("[a-f0-9-]{36}")))}
    private val checkpoint get()=requireNotNull(args.getString("checkpoint")).also {require(it in setOf("response_wait","response_delivery"))}
    private val folder get()=context.cacheDir.resolve("agent-process-$fixture")
    private val name get()="qa-response-process-$fixture.db"
    private fun durable(file: File,value: String) {
        file.parentFile!!.mkdirs();file.outputStream().use {it.write(value.toByteArray());it.fd.sync()}
    }
    @Test fun prepare() {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        AgentTaskStore(context,name).use {store ->
            val task=store.accept("owner",fixture,"request","goal","ref","provider","model","HIGH","config")
            val lease=store.claim(task.owner,task.id)
            val evidence=buildJsonObject {
                put("owner",task.owner);put("taskId",task.id);put("conversationId",fixture)
                put("fence",lease.fence);put("grantEpoch",1);put("goalRevision",1);put("goalHash",AgentTaskStore.hash("goal"))
                put("providerId",task.providerId);put("modelId",task.modelId);put("reasoning",task.reasoning)
                put("configurationRevision",task.configurationRevision);put("text","请提供讲义。")
            }
            val decision=buildJsonObject {
                put("outcome",if(checkpoint=="response_wait") "needs_input" else "delivered")
                put("goalRevision",1);put("evidenceHash",AgentTaskStore.hash(evidence.toString()))
                put("responseQuote","请提供讲义。");put("reason","synthetic persistence probe, not semantic evaluation")
                put("requiresIndependentEvidence",false)
            }
            store.recordResponse(lease,1,evidence,decision);store.finish(lease,"SUCCEEDED")
            durable(folder.resolve("task"),task.id)
            store.readableDatabase.rawQuery("SELECT evidence_json FROM plans WHERE task_id=?",arrayOf(task.id)).use {
                assertTrue(it.moveToFirst());durable(folder.resolve("evidence-sha"),AgentTaskStore.hash(it.getString(0)))
            }
        }
        durable(folder.resolve("ready"),checkpoint)
        println("QA_PROCESS_READY_$checkpoint");Thread.sleep(120_000);fail("Host did not terminate probe")
    }
    @Test fun verify() {
        val id=folder.resolve("task").readText();val waiting=checkpoint=="response_wait"
        AgentTaskStore(context,name).use {store ->
            store.recover();val task=store.get("owner",id)
            assertEquals(if(waiting) "WAITING_INPUT" else "SUCCEEDED",task.state)
            assertEquals(waiting,store.control(task.owner,id).inputBlocked)
            assertEquals(!waiting,store.plan(task.owner,id).acceptanceVerified)
            store.readableDatabase.rawQuery("SELECT evidence_json FROM plans WHERE task_id=?",arrayOf(id)).use {
                assertTrue(it.moveToFirst());assertEquals(folder.resolve("evidence-sha").readText(),AgentTaskStore.hash(it.getString(0)))
            }
            if(waiting)assertTrue(runCatching {store.resume(task.owner,id)}.isFailure)
            val other=store.accept("other-owner",fixture,"other","other goal","ref","provider","model","HIGH","config")
            val lease=store.claim(other.owner,other.id);store.finish(lease,"COMPLETED_UNVERIFIED")
            assertEquals(if(waiting) "WAITING_INPUT" else "SUCCEEDED",store.get(task.owner,id).state)
        }
        println("QA_PROCESS_VERIFIED_$checkpoint")
    }
}
