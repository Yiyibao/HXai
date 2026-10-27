package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the two phases in separate instrumentations, with host force-stop between them. */
@RunWith(AndroidJUnit4::class)
class AgentUsageNotificationProcessTest {
    @Test fun reservationSurvivesRealProcessExit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val args=InstrumentationRegistry.getArguments()
        val run=requireNotNull(args.getString("notificationRun"));require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val phase=requireNotNull(args.getString("notificationPhase"))
        AgentTaskStore(context,"qa-notify-process-$run-tasks.db").use { store ->
            AgentUsageNotificationJournal(context,"qa-notify-process-$run-attempts.db").use { journal ->
                val t=store.accept("synthetic-owner","conversation","stable-request","synthetic","ref","provider","model","HIGH","revision")
                val candidate=UsageNotificationCandidate(t.owner,t.id,setOf("uncertain"))
                if(phase=="prepare") {
                    val lease=store.claim(t.owner,t.id)
                    store.beginRequest(lease,"ocr","synthetic",AgentRequestSelection(t.providerId,t.modelId,t.reasoning,t.configurationReference))
                    store.finish(lease,"FAILED_BLOCKED")
                    assertTrue(journal.reserve(candidate,1))
                    println("NOTIFICATION_RESERVED_BEFORE_SYSTEM_CALL")
                    java.io.File(context.cacheDir,"notification-ready-$run").writeText("RESERVED")
                    java.util.concurrent.CountDownLatch(1).await(60,java.util.concurrent.TimeUnit.SECONDS)
                    error("Host did not terminate the prepared process")
                } else if(phase=="denied" || phase=="granted") {
                    val sink=AgentUsageNotificationSink(context)
                    assertEquals(phase=="granted",sink.allowed())
                    AgentUsageNotificationDispatcher(store,journal).scan(t.owner,500_000,{sink.allowed()}) { fail("Permission change replayed an old attempt") }
                    assertEquals(1,journal.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts",null).use { it.moveToFirst();it.getInt(0) })
                    assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(t.owner,t.id).state)
                    println("NOTIFICATION_PERMISSION_PHASE_$phase")
                } else {
                    require(phase=="verify")
                    assertFalse(journal.reserve(candidate,2))
                    assertTrue(AgentUsageNotificationDispatcher(store,journal).scan(t.owner,500_000,{true}) { fail("Ambiguous attempt replayed after process death") })
                    assertEquals(1,store.physicalRequestCount(t.owner,t.id))
                    assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(t.owner,t.id).state)
                    val next=store.accept("other-owner","conversation","next","synthetic","ref","provider","model","HIGH","revision")
                    assertTrue(runCatching { store.claim(next.owner,next.id) }.isFailure)
                    println("NOTIFICATION_PROCESS_EXIT_PRESERVED_ATTEMPT_AND_UNKNOWN")
                }
            }
        }
    }
}
