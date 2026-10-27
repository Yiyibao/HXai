package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only pre-v11 entry points, so the same source is the old-version negative control. */
@RunWith(AndroidJUnit4::class)
class ExecutorStopBoundaryTest {
    @Test fun stopRequestedIsNotExecutorStoppedAndCannotReleaseSlot() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-stop-boundary-${UUID.randomUUID()}.db"
        AgentTaskStore(context,name).use { store ->
            fun task(owner: String)=store.accept(owner,"conversation",UUID.randomUUID().toString(),"synthetic","ref","provider","model","HIGH","config")
            val first=task("a");val other=task("b");val lease=store.claim(first.owner,first.id)
            store.finish(lease,"STOP_REQUESTED")
            assertTrue("STOP_REQUESTED is not a trusted stopped receipt; the active executor still owns the slot",
                runCatching { store.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
        }
        SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
    }
}
