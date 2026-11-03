package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FinalPlanJournalRuntimeTest {
    @Test fun rejectedCallReadbackBindsInputOwnerEpochAndSurvivesReopenWithoutPromotion() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-final-journal-${UUID.randomUUID()}.db"
        try {
            AgentTaskStore(context,name).use {store ->
                val task=store.accept("owner","conversation","request","verify a corrected claim","config","provider","model","OFF","revision")
                val lease=store.claim(task.owner,task.id)
                val input="""{"operation":"identity","left":"x+x","right":"3*x"}"""
                val output=listOf<UIMessagePart>(UIMessagePart.Text("""{"status":"DISPROVED"}"""))
                val tool=UIMessagePart.Tool("rejected","math_verify",input,output,executionState=ToolExecutionState.COMPLETED)
                val encoded=Json.encodeToString(output)
                store.beginCall(lease,tool.toolCallId,tool.toolName,tool.input)
                store.finishCall(lease,tool.toolCallId,AgentTaskStore.hash(encoded),false,encoded)
                assertEquals(mapOf(tool.toolCallId to AgentTaskStore.hash(input)),store.returnedMathInputs(lease))
                assertEquals(encoded,store.returnedResult(lease,tool))
                assertTrue(runCatching {store.returnedResult(lease,tool.copy(input="{}"))}.isFailure)
                assertTrue(runCatching {store.returnedResult(lease.copy(task=task.copy(owner="other")),tool)}.isFailure)
                assertTrue(runCatching {store.returnedResult(lease,tool.copy(toolName="create_material_file"))}.isFailure)
                store.readableDatabase.rawQuery("SELECT state FROM calls WHERE task_id=? AND call_id=?",arrayOf(task.id,tool.toolCallId)).use {
                    assertTrue(it.moveToFirst());assertEquals("RETURNED",it.getString(0))
                }
                val before=store.get(task.owner,task.id)
                store.command(task.owner,task.id,UUID.randomUUID().toString(),before.revision,before.fence,"revoke","")
                assertTrue(runCatching {store.returnedResult(lease,tool)}.isFailure)
                assertTrue(runCatching {store.returnedMathInputs(lease)}.isFailure)
                store.finish(lease,"COMPLETED_UNVERIFIED")
                store.close()
                AgentTaskStore(context,name).use {reopened ->
                    reopened.readableDatabase.rawQuery("SELECT state,result_json FROM calls WHERE task_id=? AND call_id=?",arrayOf(task.id,tool.toolCallId)).use {
                        assertTrue(it.moveToFirst());assertEquals("RETURNED",it.getString(0));assertEquals(encoded,it.getString(1))
                    }
                }
            }
        } finally {
            for(suffix in listOf("","-wal","-shm","-journal"))context.noBackupFilesDir.resolve(name+suffix).delete()
        }
    }
    @Test fun oversizedRejectedHistoryIsPreservedRatherThanTruncatedOrAccepted() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-final-history-${UUID.randomUUID()}.db"
        try {
            AgentTaskStore(context,name).use {store ->
                val task=store.accept("owner","conversation","request","retain all rejected claims","config","provider","model","OFF","revision")
                val lease=store.claim(task.owner,task.id)
                val output=Json.encodeToString(listOf<UIMessagePart>(UIMessagePart.Text("""{"status":"UNSUPPORTED"}""")))
                repeat(65) {i ->
                    store.beginCall(lease,"rejected-$i","math_verify","{\"attempt\":$i}")
                    store.finishCall(lease,"rejected-$i",AgentTaskStore.hash(output),false,output)
                }
                assertTrue(runCatching {store.returnedMathInputs(lease)}.isFailure)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM calls WHERE task_id=? AND state='RETURNED'",arrayOf(task.id)).use {
                    assertTrue(it.moveToFirst());assertEquals(65,it.getInt(0))
                }
                assertFalse(store.plan(task.owner,task.id).acceptanceVerified)
                store.finish(lease,"COMPLETED_UNVERIFIED")
            }
        } finally {for(suffix in listOf("","-wal","-shm","-journal"))context.noBackupFilesDir.resolve(name+suffix).delete()}
    }
}
