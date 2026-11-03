package me.rerere.rikkahub.feature.agent

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AgentUsageNotificationsRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (AgentTaskStore,AgentUsageNotificationJournal,String) -> Unit) {
        val name="qa-notify-${UUID.randomUUID()}"
        try {
            AgentTaskStore(context,"$name-tasks.db").use { s ->
                AgentUsageNotificationJournal(context,"$name-journal.db").use { j -> block(s,j,"$name-journal.db") }
            }
        } finally {
            SQLiteCleanup.delete(context.noBackupFilesDir.resolve("$name-tasks.db"))
            SQLiteCleanup.delete(context.noBackupFilesDir.resolve("$name-journal.db"))
        }
    }
    private fun task(s: AgentTaskStore,owner: String="owner-a")=s.accept(owner,"synthetic",UUID.randomUUID().toString(),
        "private synthetic goal must not enter notification","private-ref","provider","model","HIGH","revision")
    private fun row(s: AgentTaskStore,t: AgentTask,state: String?="VERIFIED",provenance: String="reported",at: Long?=500_000) {
        val id=UUID.randomUUID().toString()
        s.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",id);put("task_id",t.id);put("role","ocr");put("provenance",provenance);put("created_at",at)
            put("prompt_tokens",0);put("total_tokens",0)
        })
        if(state!=null) s.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",t.id);put("call_id",id);put("tool","provider:ocr");put("input_hash","synthetic");put("state",state)
        })
    }
    @Test fun normalZeroInflightAndBelowThresholdAreNotAlerts()=fixture { s,j,_ ->
        val t=task(s);repeat(18) { row(s,t) };row(s,t,"IN_FLIGHT","unknown")
        assertTrue(s.readUsageNotificationCandidates(t.owner,null,500_000).isEmpty())
        assertFalse(AgentUsageNotificationDispatcher(s,j).scan(t.owner,500_000,{true}) { fail("normal input alerted") })
    }
    @Test fun everyRuleUsesOriginalLedgerAndExactTimeBoundaries()=fixture { s,_,_ ->
        val t=task(s);row(s,t,null,at=null);row(s,t,"UNKNOWN",at=1);row(s,t,provenance="unknown",at=1)
        repeat(19) { row(s,t,at=200_000) };row(s,t,at=500_000)
        row(s,t,at=199_999);row(s,t,at=500_001)
        val result=s.readUsageNotificationCandidates(t.owner,null,500_000).single()
        assertEquals(setOf("uncertain","unreported","dense"),result.rules)
        assertEquals(setOf("uncertain","unreported"),s.readUsageNotificationCandidates(t.owner,null,800_001).single().rules)
        assertTrue(runCatching { s.readUsageNotificationCandidates(t.owner,null,-1) }.isFailure)
    }
    @Test fun sameTaskRuleNeverRepeatsButNewRuleAndNewTaskCanAlert()=fixture { s,j,_ ->
        val t=task(s);row(s,t,"UNKNOWN");val d=AgentUsageNotificationDispatcher(s,j);var posts=0
        repeat(3) { assertTrue(d.scan(t.owner,500_000,{true}) { posts++ }) };assertEquals(1,posts)
        row(s,t,"UNKNOWN");d.scan(t.owner,500_000,{true}) { posts++ };assertEquals(1,posts)
        row(s,t,provenance="unknown");d.scan(t.owner,500_000,{true}) { posts++ };assertEquals(2,posts)
        row(s,task(s),"UNKNOWN");d.scan(t.owner,500_000,{true}) { posts++ };assertEquals(3,posts)
    }
    @Test fun disabledGateDoesNotConsumeJournalOrReadAnotherOwner()=fixture { s,j,_ ->
        val t=task(s);row(s,t,"UNKNOWN");val other=task(s,"owner-b");row(s,other,"UNKNOWN")
        val d=AgentUsageNotificationDispatcher(s,j);var posts=0
        assertFalse(d.scan(t.owner,500_000,{false}) { fail() })
        assertEquals(0,j.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        d.scan(t.owner,500_000,{true}) { posts++ };assertEquals(1,posts)
        d.scan(other.owner,500_000,{true}) { posts++ };assertEquals(2,posts)
        assertTrue(s.readUsageNotificationCandidates("foreign",null,500_000).isEmpty())
    }
    @Test fun allPagesAreScannedWithoutTheFirstHundredHidingNewTasks()=fixture { s,j,_ ->
        repeat(103) { row(s,task(s),"UNKNOWN") }
        val d=AgentUsageNotificationDispatcher(s,j);var posts=0
        d.scan("owner-a",500_000,{true}) { posts++ };assertEquals(1,posts)
        assertEquals(103,j.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        row(s,task(s),"UNKNOWN");d.scan("owner-a",500_000,{true}) { posts++ };assertEquals(2,posts)
    }
    @Test fun resolvedFirstPageDoesNotHideALiveRuleOnTheNextPage()=fixture { s,j,_ ->
        val tasks=List(101) { task(s).also { row(s,it,"UNKNOWN") } }.sortedBy { it.id }
        var checks=0;var posts=0
        AgentUsageNotificationDispatcher(s,j).scan("owner-a",500_000,{
            // First page has been reserved; all its receipts become known before post.
            if(++checks==102) tasks.take(100).forEach { t ->
                s.writableDatabase.execSQL("UPDATE calls SET state='VERIFIED' WHERE task_id=?",arrayOf(t.id))
            }
            true
        }) { posts++ }
        assertEquals(1,posts)
        assertEquals(101,j.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        assertEquals(listOf(tasks.last().id),s.readUsageNotificationCandidates("owner-a",null,500_000).map { it.taskId })
    }
    @Test fun lostAckOnFirstPagePreservesItsAttemptsButDoesNotConsumeLaterPages()=fixture { s,j,_ ->
        repeat(101) { row(s,task(s),"UNKNOWN") }
        val dispatcher=AgentUsageNotificationDispatcher(s,j)
        assertTrue(runCatching { dispatcher.scan("owner-a",500_000,{true}) { error("synthetic lost ACK") } }.isFailure)
        assertEquals(100,j.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        var posts=0
        dispatcher.scan("owner-a",500_000,{true}) { posts++ }
        assertEquals(1,posts) // The unattempted task on page two, never replay page one.
        assertEquals(101,j.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        dispatcher.scan("owner-a",500_000,{true}) { fail("all attempts already reserved") }
    }
    @Test fun revokedTaskDoesNotNotifyAndUnknownSlotIsPreserved()=fixture { s,j,_ ->
        val t=task(s);val l=s.claim(t.owner,t.id)
        s.beginRequest(l,"main","synthetic",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        s.finish(l,"FAILED_BLOCKED");val before=s.snapshot(t.owner,t.id)
        val d=AgentUsageNotificationDispatcher(s,j);d.scan(t.owner,500_000,{true}) {}
        assertEquals(before,s.snapshot(t.owner,t.id));assertTrue(runCatching { s.claim("owner-b",task(s,"owner-b").id) }.isFailure)
        val current=s.get(t.owner,t.id);s.command(t.owner,t.id,"revoke",current.revision,current.fence,"revoke")
        assertFalse(d.scan(t.owner,500_000,{true}) { fail("revoked") })
        assertEquals("UNCERTAIN_SIDE_EFFECT",s.get(t.owner,t.id).state)
    }
    @Test fun revokeDuringScanIsRecheckedBeforePosting()=fixture { s,j,_ ->
        val t=task(s);row(s,t,"UNKNOWN");var checks=0;var posts=0
        AgentUsageNotificationDispatcher(s,j).scan(t.owner,500_000,{
            checks++
            if(checks==3) { val c=s.get(t.owner,t.id);s.command(t.owner,t.id,"revoke",c.revision,c.fence,"revoke") }
            true
        }) { posts++ }
        assertEquals(0,posts)
    }
    @Test fun lateReportedReceiptBeforePostSuppressesStaleUnknown()=fixture { s,j,_ ->
        val t=task(s);val l=s.claim(t.owner,t.id)
        val request=s.beginRequest(l,"main","synthetic",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
        s.finish(l,"FAILED_BLOCKED");var checks=0;var posts=0
        AgentUsageNotificationDispatcher(s,j).scan(t.owner,500_000,{
            if(++checks==3) s.finishRequest(l,request,TokenUsage(promptTokens=0,completionTokens=0,totalTokens=0))
            true
        }) { posts++ }
        assertEquals(0,posts);assertEquals(1,s.physicalRequestCount(t.owner,t.id))
    }
    @Test fun crashAfterReservationAndLostSystemAckAreNeverBlindlyReplayed()=fixture { s,j,name ->
        val t=task(s);row(s,t,"UNKNOWN")
        assertTrue(runCatching { AgentUsageNotificationDispatcher(s,j).scan(t.owner,500_000,{true}) { error("synthetic ACK loss") } }.isFailure)
        j.close()
        AgentUsageNotificationJournal(context,name).use { reopened ->
            assertTrue(AgentUsageNotificationDispatcher(s,reopened).scan(t.owner,500_000,{true}) { fail("replayed") })
            assertEquals(1,reopened.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
        }
    }
    @Test fun concurrentReservationsHaveOneWinnerAndOwnerKeysCannotCollide()=fixture { _,j,name ->
        val candidate=UsageNotificationCandidate("owner-a","task",setOf("dense"))
        val pool=Executors.newFixedThreadPool(4)
        try { assertEquals(1,pool.invokeAll(List(12) { Callable {
            AgentUsageNotificationJournal(context,name).use { it.reserve(candidate,1) }
        } }).count { it.get() }) }
        finally { pool.shutdownNow() }
        assertTrue(j.reserve(candidate.copy(owner="owner-b"),1))
        assertTrue(runCatching { j.reserve(candidate.copy(rules=setOf("authorized")),1) }.isFailure)
    }
    @Test fun futureJournalSchemaIsRejectedWithoutDeletingOldAttempts()=fixture { _,j,name ->
        j.reserve(UsageNotificationCandidate("owner","task",setOf("dense")),1);j.close()
        val file=context.noBackupFilesDir.resolve(name)
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=2 }
        AgentUsageNotificationJournal(context,name).use { assertTrue(runCatching { it.writableDatabase }.isFailure) }
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2,it.version);assertEquals(1,it.rawQuery("SELECT COUNT(*) FROM attempts",null).use { c -> c.moveToFirst();c.getInt(0) })
        }
    }
    @Test fun realSystemNotificationUsesPrivateGenericTextAndCancels() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val permission=Manifest.permission.POST_NOTIFICATIONS
        val channel="qa-usage-notification-${UUID.randomUUID()}";val notificationId=0x485856
        val sink=AgentUsageNotificationSink(context,channel,notificationId)
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,permission)
            assertTrue(sink.allowed());sink.post()
            val manager=context.getSystemService(NotificationManager::class.java)
            val deadline=System.currentTimeMillis()+5_000
            while(manager.activeNotifications.none { it.id==notificationId } && System.currentTimeMillis()<deadline) Thread.sleep(50)
            val notification=manager.activeNotifications.single { it.id==notificationId }.notification
            assertEquals(channel,notification.channelId)
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility)
            assertEquals("请求用量需要核对",notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertFalse(notification.extras.toString().contains("private synthetic"))
            assertNotNull(notification.contentIntent)
            sink.cancel()
            val cancelledDeadline=System.currentTimeMillis()+5_000
            while(manager.activeNotifications.any { it.id==notificationId } && System.currentTimeMillis()<cancelledDeadline) Thread.sleep(50)
            assertFalse(manager.activeNotifications.any { it.id==notificationId })
        } finally {
            sink.cancel()
            context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(channel)
            // Host permission phases revoke outside instrumentation: Android kills this process on revoke.
        }
    }
    @Test fun oldDisplaySettingsKeepNotificationsOffAndRoundTripOnlyExplicitOptIn() {
        val old=kotlinx.serialization.json.Json.decodeFromString<me.rerere.rikkahub.data.datastore.DisplaySetting>("{\"enableNotificationOnMessageGeneration\":true}")
        assertFalse(old.enableAgentUsageNotifications)
        val json=kotlinx.serialization.json.Json
        val enabled=old.copy(enableAgentUsageNotifications=true)
        assertEquals(enabled,json.decodeFromString<me.rerere.rikkahub.data.datastore.DisplaySetting>(
            json.encodeToString(me.rerere.rikkahub.data.datastore.DisplaySetting.serializer(),enabled)))
    }
}
