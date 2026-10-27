package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentApprovalBoundaryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(store: AgentTaskStore)=store.accept("owner","conversation","request","parse owned synthetic source","configuration","provider","model","OFF","revision")
    private fun pending(id: String="remote",input: String="{\"id\":\"synthetic-source\",\"start_page\":1,\"end_page\":1}")=
        UIMessagePart.Tool(id,"parse_material_enhanced",input,approvalState=ToolApprovalState.Pending)
    private fun command(store: AgentTaskStore,task: AgentTask,action: String,payload: String="") {
        val current=store.get(task.owner,task.id)
        store.command(task.owner,task.id,UUID.randomUUID().toString(),current.revision,current.fence,action,payload)
    }
    private fun fixture(block: (AgentTaskStore,String)->Unit) {
        val name="qa-approval-${UUID.randomUUID()}.db"
        val store=AgentTaskStore(context,name)
        try { block(store,name) }
        finally { store.close();SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    @Test fun knownDecisionSurvivesExitAndCannotAuthorizeChangedInputOrAnotherOwner()=fixture { store,name ->
        val task=task(store);val lease=store.claim(task.owner,task.id);val tool=pending()
        store.waitForToolApproval(lease,listOf(tool));store.finish(lease,"WAITING_INPUT")
        assertTrue(store.control(task.owner,task.id).approvalBlocked)
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
        assertTrue(runCatching { store.decideTool("other-owner",task.id,tool,ToolApprovalState.Approved,1) }.isFailure)
        assertTrue(runCatching { store.decideTool(task.owner,task.id,tool.copy(input="{\"id\":\"other-source\"}"),ToolApprovalState.Approved,1) }.isFailure)
        store.decideTool(task.owner,task.id,tool,ToolApprovalState.Approved,1)
        store.close()
        AgentTaskStore(context,name).use { reopened ->
            reopened.recover()
            assertFalse(reopened.control(task.owner,task.id).approvalBlocked)
            assertEquals(ToolApprovalState.Approved,reopened.toolDecisions(task.owner,task.id).single().decision)
            val revision=reopened.get(task.owner,task.id).revision
            reopened.decideTool(task.owner,task.id,tool,ToolApprovalState.Approved,1)
            assertEquals("Lost decision ACK must not add another grant or mutation",revision,reopened.get(task.owner,task.id).revision)
            reopened.resume(task.owner,task.id)
            val current=reopened.claim(task.owner,task.id)
            assertTrue(runCatching { reopened.beginCall(current,"wrong-input",tool.toolName,"{\"id\":\"other-source\"}") }.isFailure)
            reopened.beginCall(current,tool.toolCallId,tool.toolName,tool.input)
            reopened.finishCall(current,tool.toolCallId,"independent-result",true)
            reopened.finish(current,"COMPLETED_UNVERIFIED")
            assertEquals("COMPLETED_UNVERIFIED",reopened.get(task.owner,task.id).state)
        }
    }
    @Test fun oneDecisionCannotClearAnotherApprovalOrIndependentWaitsAndPause()=fixture { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id);val first=pending();val second=pending("second")
        store.waitForToolApproval(lease,listOf(first,second));command(store,task,"wait_input")
        command(store,task,"wait_device");command(store,task,"require_device_confirmation");command(store,task,"pause")
        store.finish(lease,"WAITING_INPUT")
        store.decideTool(task.owner,task.id,first,ToolApprovalState.Approved,1)
        assertEquals(listOf("second"),store.approvalCallIds(task.owner,task.id))
        store.decideTool(task.owner,task.id,second,ToolApprovalState.Denied("do not upload"),1)
        val control=store.control(task.owner,task.id)
        assertFalse(control.approvalBlocked);assertTrue(control.inputBlocked)
        assertTrue(control.deviceBlocked);assertTrue(control.deviceConfirmation)
        assertTrue(store.get(task.owner,task.id).manualPaused)
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
        command(store,task,"provide_input","synthetic input");command(store,task,"device_available")
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
        command(store,task,"confirm_device");store.resume(task.owner,task.id)
        val next=store.claim(task.owner,task.id)
        assertTrue(next.fence>lease.fence)
        assertTrue(runCatching { store.waitForToolApproval(lease,listOf(pending("stale"))) }.isFailure)
        store.finish(next,"COMPLETED_UNVERIFIED")
    }
    @Test fun revocationWinsOverLateHumanApprovalWithoutDiscardingPendingInput()=fixture { store,_ ->
        val task=task(store);val lease=store.claim(task.owner,task.id);val tool=pending()
        store.waitForToolApproval(lease,listOf(tool));store.finish(lease,"WAITING_INPUT")
        command(store,task,"revoke")
        assertTrue(runCatching { store.decideTool(task.owner,task.id,tool,ToolApprovalState.Approved,1) }.isFailure)
        assertTrue(runCatching { store.decideTool(task.owner,task.id,tool,ToolApprovalState.Approved,2) }.isFailure)
        assertEquals("REVOKED",store.control(task.owner,task.id).authorization)
        assertEquals(listOf(tool.toolCallId),store.approvalCallIds(task.owner,task.id))
        store.decideTool(task.owner,task.id,tool,ToolApprovalState.Denied("revoked"),2)
        assertFalse(store.control(task.owner,task.id).approvalBlocked)
        assertEquals("REVOKED",store.control(task.owner,task.id).authorization)
        assertTrue(runCatching { store.resume(task.owner,task.id) }.isFailure)
    }
}
