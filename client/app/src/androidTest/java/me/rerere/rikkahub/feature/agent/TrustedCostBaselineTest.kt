package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TrustedCostBaselineTest {
    @Test fun completedUsageHasStructuredCostCoverageInExport() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-cost-baseline-${UUID.randomUUID()}.db"
        AgentTaskStore(context,name).use { store ->
            val t=store.accept("owner","conversation",UUID.randomUUID().toString(),"goal","ref","p","m","OFF","r")
            val l=store.claim(t.owner,t.id)
            val id=store.beginRequest(l,"main","{}",AgentRequestSelection("p","m","OFF","ref"))
            store.finishRequest(l,id,me.rerere.ai.core.TokenUsage(10,2,0,12))
            val data=store.captureUsage(t.owner,t.id,null,null,"requests",UUID.randomUUID().toString())
            assertEquals("Unknown money must have explicit coverage, not a hardcoded amount", "1",
                data.getValue("cost").jsonObject["unknownRequests"]?.jsonPrimitive?.content)
            assertNotNull(data.getValue("records").jsonArray.single().jsonObject["cost"])
        }
    }
}
