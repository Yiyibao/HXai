package me.rerere.rikkahub.feature.agent

import android.net.Uri
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.CredentialVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtensionOAuthRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun rejected(f: ()->Unit)=assertTrue(runCatching(f).isFailure)
    private val redirect get()="${BuildConfig.APPLICATION_ID}.oauth:/callback"
    private fun meta(endpoint: String)=buildJsonObject {
        put("resource",endpoint);put("issuer",endpoint);put("authorizationEndpoint",endpoint);put("tokenEndpoint",endpoint);put("redirectUri",redirect)
    }
    private fun install(endpoint: String,owner: String): ExtensionInstallation=ExtensionInstallationStore(context).use {
        it.install(owner,"oauth",0,ExtensionConfig(name="OAuth",endpoint=endpoint,serverName="synthetic",serverVersion="1",tools=emptyList()),"")
    }
    @Test fun resourceAndIssuerDiscoveryValidateMetadataBeforeBrowserOrTokens() {
        val socket=java.net.ServerSocket(0)
        val origin="http://127.0.0.1:${socket.localPort}"
        val resource="$origin/mcp";val issuer="$origin/issuer"
        var wrongResource=false;var wrongIssuer=false
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker=kotlin.concurrent.thread {
            try { while(!socket.isClosed) socket.accept().use { connection ->
                val reader=connection.getInputStream().bufferedReader()
                val path=reader.readLine().split(' ')[1];requests+=path
                while(reader.readLine().isNotEmpty()) { }
                val response=if(path=="/mcp") "{}" else if("protected-resource" in path) buildJsonObject {
                    put("resource",if(wrongResource) "$origin/other" else resource);put("authorization_servers",buildJsonArray { add(issuer) })
                }.toString() else buildJsonObject {
                    put("issuer",if(wrongIssuer) "$origin/wrong" else issuer);put("authorization_endpoint","$origin/authorize");put("token_endpoint","$origin/token")
                    put("response_types_supported",buildJsonArray { add("code") });put("code_challenge_methods_supported",buildJsonArray { add("S256") })
                }.toString()
                val bytes=response.toByteArray()
                val challenge=if(path=="/mcp") "WWW-Authenticate: Bearer resource_metadata=\"$origin/.well-known/oauth-protected-resource/mcp\"\r\n" else ""
                connection.getOutputStream().apply { write("HTTP/1.1 ${if(path=="/mcp") 401 else 200} QA\r\n${challenge}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
            } } catch (_: java.net.SocketException) { }
        }
        try { ExtensionOAuth(context,"qa-discovery-${UUID.randomUUID()}.db").use { oauth ->
            val m=oauth.discover(resource,issuer) {}
            assertEquals(JsonPrimitive(resource),m["resource"]);assertEquals(JsonPrimitive("$origin/token"),m["tokenEndpoint"])
            assertEquals(listOf("/mcp","/.well-known/oauth-protected-resource/mcp","/.well-known/oauth-authorization-server/issuer"),requests.toList())
            wrongResource=true;rejected { oauth.discover(resource,issuer) {} }
            wrongResource=false;wrongIssuer=true;rejected { oauth.discover(resource,issuer) {} }
            assertFalse(requests.contains("/token"));assertFalse(requests.contains("/authorize"))
        } } finally { socket.close();worker.join(3000) }
    }
    @Test fun browserUsesS256AndResourceAndCallbackDoesNotGrantTools() {
        val owner="oauth-${UUID.randomUUID()}"
        LocalModelServer { """{"access_token":"synthetic-token","token_type":"Bearer","expires_in":3600,"scope":"read"}""" }.use { server ->
            val pin=install(server.baseUrl,owner)
            var browser: Intent?=null
            val wrapped=object : ContextWrapper(context) { override fun startActivity(intent: Intent) { browser=intent } }
            ExtensionOAuth(wrapped,"qa-oauth-${UUID.randomUUID()}.db").use { oauth ->
                val flow=oauth.begin(pin,7,meta(server.baseUrl),"public-client","read") {}
                oauth.open(owner,flow,7) {}
                val url=requireNotNull(browser?.data)
                assertEquals("S256",url.getQueryParameter("code_challenge_method"));assertEquals(server.baseUrl,url.getQueryParameter("resource"))
                assertEquals(43,url.getQueryParameter("code_challenge")!!.length)
                rejected { oauth.status("other",flow,7) };rejected { oauth.status(owner,flow,8) }
                oauth.callback(Uri.parse("$redirect?state=$flow&code=synthetic-code"))
                assertEquals("CALLBACK",oauth.status(owner,flow,7));assertEquals(0,server.bodies.size)
                rejected { oauth.callback(Uri.parse("$redirect?state=$flow&code=synthetic-code")) }
                val next=oauth.complete(owner,flow,7) {}
                assertEquals("DISABLED",next.state);assertTrue(next.config.tools.isEmpty());assertNotEquals(pin.id,next.id)
                assertEquals("synthetic-token",CredentialVault(context).resolve(next.credential))
                val form=server.bodies.single().split('&').associate { val p=it.split('=',limit=2);java.net.URLDecoder.decode(p[0],"UTF-8") to java.net.URLDecoder.decode(p[1],"UTF-8") }
                assertEquals(url.getQueryParameter("code_challenge"),ExtensionOAuth.challenge(form.getValue("code_verifier")))
                assertEquals(server.baseUrl,form["resource"]);assertEquals(redirect,form["redirect_uri"])
                rejected { oauth.complete(owner,flow,7) {} };assertEquals(1,server.bodies.size)
                ExtensionInstallationStore(context).use { rejected { it.command(owner,next.slot,next.id,next.revision,"enable") } }
                val text=context.noBackupFilesDir.resolve(oauth.databaseName.substringAfterLast('/')).takeIf { it.exists() }?.readBytes()?.toString(Charsets.ISO_8859_1).orEmpty()
                assertFalse(text.contains("synthetic-code"));assertFalse(text.contains(form.getValue("code_verifier")))
            }
        }
    }
    @Test fun wrongStateIssuerDuplicateParametersAndRevokedInstallationCannotComplete() {
        val owner="oauth-${UUID.randomUUID()}";val pin=install("https://example.com/mcp",owner)
        ExtensionOAuth(context,"qa-oauth-${UUID.randomUUID()}.db").use { oauth ->
            val flow=oauth.begin(pin,1,meta(pin.config.endpoint),"client","read") {}
            for(uri in listOf("$redirect?state=wrong&code=x","$redirect?state=$flow&code=x&iss=https%3A%2F%2Fevil.com","$redirect?state=$flow&code=x&code=y","$redirect?state=$flow&code=x#fragment"))
                rejected { oauth.callback(Uri.parse(uri)) }
            assertEquals("PENDING",oauth.status(owner,flow,1))
            ExtensionInstallationStore(context).use { it.command(owner,pin.slot,pin.id,pin.revision,"revoke") }
            rejected { oauth.callback(Uri.parse("$redirect?state=$flow&code=x")) }
            rejected { oauth.open(owner,flow,1) {} };rejected { oauth.complete(owner,flow,1) {} }
        }
    }
    @Test fun tokenAckLossStaysUnknownAndIsNeverRetried() {
        val owner="oauth-${UUID.randomUUID()}"
        LocalModelServer(disconnect={ _,_ -> true }) { "" }.use { server ->
            val pin=install(server.baseUrl,owner)
            ExtensionOAuth(context,"qa-oauth-${UUID.randomUUID()}.db").use { oauth ->
                val state=oauth.begin(pin,1,meta(server.baseUrl),"client","read") {}
                oauth.callback(Uri.parse("$redirect?state=$state&code=code"))
                rejected { oauth.complete(owner,state,1) {} }
                assertEquals("EXCHANGING",oauth.status(owner,state,1))
                rejected { oauth.complete(owner,state,1) {} };assertEquals(1,server.bodies.size)
                oauth.cancel(owner,pin.slot);assertEquals("CANCELED",oauth.status(owner,state,1))
            }
        }
    }
    @Test fun expandedScopeIsRejectedAndExpiredFlowOrCredentialCannotEnable() {
        val owner="oauth-${UUID.randomUUID()}"
        LocalModelServer { """{"access_token":"token","token_type":"Bearer","expires_in":3600,"scope":"read write"}""" }.use { server ->
            val pin=install(server.baseUrl,owner)
            ExtensionOAuth(context,"qa-oauth-${UUID.randomUUID()}.db").use { oauth ->
                val state=oauth.begin(pin,1,meta(server.baseUrl),"client","read") {}
                oauth.callback(Uri.parse("$redirect?state=$state&code=code"));rejected { oauth.complete(owner,state,1) {} }
                assertEquals(pin,ExtensionInstallationStore(context).use { it.get(owner,pin.slot) })
                val state2=oauth.begin(pin,1,meta(server.baseUrl),"client","read") {}
                oauth.writableDatabase.execSQL("UPDATE flows SET expires=0 WHERE state=?",arrayOf(state2))
                rejected { oauth.callback(Uri.parse("$redirect?state=$state2&code=code")) }
                assertEquals("EXPIRED",oauth.status(owner,state2,1))
            }
        }
    }
}
