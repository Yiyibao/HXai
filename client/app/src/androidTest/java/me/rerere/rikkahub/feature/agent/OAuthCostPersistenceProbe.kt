package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Live probe: host observes fsynced marker and PID, kills it, then calls verify in a new process. */
@RunWith(AndroidJUnit4::class)
class OAuthCostPersistenceProbe {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get()=requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val owner get()="qa-oauth-cost-$fixture"
    private val marker get()=context.noBackupFilesDir.resolve("oauth-cost-probe-$fixture.txt")
    private val database get()="cost-process-$fixture.db"
    private val oauthDatabase get()="oauth-process-$fixture.db"
    @Test fun prepare() {
        val money=AgentTaskStore(context,database).use { db ->
            val task=db.accept(owner,fixture,"once","synthetic","ref","p","m","OFF","r");val l=db.claim(owner,task.id)
            val basis=TrustedCost.basis("https://openrouter.ai/api/v1","any",buildJsonObject {})
            val request=db.beginRequest(l,"main","{}",AgentRequestSelection("p","m","OFF","ref",basis))
            db.finishRequest(l,request,TokenUsage(costUsd="0.000001",costSource="https://openrouter.ai/api/v1/chat/completions"))
            db.beginRequest(l,"main","uncertain",AgentRequestSelection("p","m","OFF","ref",basis))
            task.id
        }
        val flow=LocalModelServer(disconnect={ _,_ -> true }) { "" }.use { server ->
            val pin=ExtensionInstallationStore(context).use { it.install(owner,"oauth",0,ExtensionConfig(name="test",endpoint=server.baseUrl,serverName="synthetic",serverVersion="1",tools=emptyList()),"") }
            ExtensionOAuth(context,oauthDatabase).use { oauth ->
                val redirect="${BuildConfig.APPLICATION_ID}.oauth:/callback"
                val meta=buildJsonObject { put("resource",server.baseUrl);put("issuer",server.baseUrl);put("authorizationEndpoint",server.baseUrl);put("tokenEndpoint",server.baseUrl);put("redirectUri",redirect) }
                val state=oauth.begin(pin,1,meta,"client","read") {}
                oauth.callback(Uri.parse("$redirect?state=$state&code=synthetic"))
                assertTrue(runCatching { oauth.complete(owner,state,1) {} }.isFailure)
                assertEquals(1,server.bodies.size);assertEquals("EXCHANGING",oauth.status(owner,state,1));state
            }
        }
        marker.outputStream().use { it.write("$money\n$flow\n${android.os.Process.myPid()}".toByteArray());it.fd.sync() }
        marker.resolveSibling(marker.name+".ready").outputStream().use { it.write("money_oauth".toByteArray());it.fd.sync() }
        java.util.concurrent.CountDownLatch(1).await(60,java.util.concurrent.TimeUnit.SECONDS)
        fail("Host must force-stop prepared process")
    }
    @Test fun verify() {
        val (taskId,flow,oldPid)=marker.readText().split('\n')
        assertNotEquals(oldPid,android.os.Process.myPid().toString())
        AgentTaskStore(context,database).use { db ->
            db.recover();assertEquals("UNCERTAIN_SIDE_EFFECT",db.get(owner,taskId).state)
            val page=db.readUsage(owner,taskId,null,null,null,100)
            assertEquals(2L,page.requestCount)
            val cost=Json.parseToJsonElement(requireNotNull(page.costJson)).jsonObject
            assertEquals(JsonPrimitive("1"),cost["unknownRequests"])
            assertEquals(JsonPrimitive("0.000001"),cost.getValue("totals").jsonArray.single().jsonObject["amount"])
            val other=db.accept("other",fixture,"next","synthetic","ref","p","m","OFF","r")
            assertTrue(runCatching { db.claim("other",other.id) }.isFailure)
        }
        ExtensionOAuth(context,oauthDatabase).use { oauth ->
            assertEquals("EXCHANGING",oauth.status(owner,flow,1))
            assertTrue(runCatching { oauth.complete(owner,flow,1) {} }.isFailure)
        }
    }
}
