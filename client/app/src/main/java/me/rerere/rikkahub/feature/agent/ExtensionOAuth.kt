package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Base64
import kotlinx.serialization.json.*
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.CredentialVault
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Public-client code+S256 flow. A callback stores data only, never grants a tool or starts a task. */
internal class ExtensionOAuth(private val context: Context, name: String="extension-oauth-v1.db") :
    SQLiteOpenHelper(context,context.noBackupFilesDir.resolve(name).absolutePath,null,1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("OAuth journal corrupt; preserved") }), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE flows(state TEXT PRIMARY KEY,owner TEXT NOT NULL,slot TEXT NOT NULL,installation TEXT NOT NULL,revision INTEGER NOT NULL,epoch INTEGER NOT NULL,expires INTEGER NOT NULL,status TEXT NOT NULL,secret TEXT NOT NULL,callback TEXT)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int): Unit=error("Future OAuth schema preserved")
    override fun onDowngrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int): Unit=error("Future OAuth schema preserved")
    private val vault=CredentialVault(context)
    private val qa get()=BuildConfig.SYNTHETIC_MCP && BuildConfig.APPLICATION_ID=="org.hxsi.agent.nativeqa"
    private val redirect get()="${BuildConfig.APPLICATION_ID}.oauth:/callback"
    private fun endpoint(s: String) { ExtensionPolicy.endpoint(s,qa) }
    private fun fetch(url: String,guard: () -> Unit): JsonObject {
        endpoint(url);guard()
        val result=client.newCall(Request.Builder().url(url).header("Accept","application/json").get().build()).execute().use { response ->
            require(response.code==200 && response.body.contentType()?.subtype=="json") { "OAuth metadata unavailable" }
            bounded(response.body.byteStream())
        }
        guard();return Json.parseToJsonElement(result).jsonObject
    }
    /** Only endpoints displayed in the foreground are used. Metadata never supplies runtime grants. */
    fun discover(resource: String,issuer: String,guard: () -> Unit): JsonObject {
        endpoint(resource);endpoint(issuer)
        val u=resource.toHttpUrl()
        guard()
        val initial=client.newCall(Request.Builder().url(resource).get().build()).execute().use { r ->
            guard()
            r.challenges().firstOrNull { it.scheme.equals("Bearer",true) }?.authParams?.get("resource_metadata")
        }
        val header=initial
        // Discovered cross-origin network destinations need separate explicit user configuration.
        if(header!=null) require(sameOrigin(header,resource)) { "资源元数据跨站，需由服务所有者提供同源发现入口" }
        val root=u.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val candidates=if(header!=null) listOf(header) else listOf(root+"/.well-known/oauth-protected-resource"+u.encodedPath.trimEnd('/'),root+"/.well-known/oauth-protected-resource").distinct()
        val metadata=candidates.firstNotNullOfOrNull { runCatching { fetch(it,guard) }.getOrNull() } ?: error("OAuth protected resource metadata unavailable")
        require(metadata["resource"]==JsonPrimitive(resource)) { "OAuth resource mismatch" }
        require(metadata["authorization_servers"]?.jsonArray?.any { it==JsonPrimitive(issuer) }==true) { "Issuer not listed by this resource" }
        val i=issuer.toHttpUrl();val origin=i.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val path=i.encodedPath.trimEnd('/')
        val urls=listOf(origin+"/.well-known/oauth-authorization-server"+path,origin+"/.well-known/openid-configuration"+path,issuer.trimEnd('/')+"/.well-known/openid-configuration").distinct()
        val auth=urls.firstNotNullOfOrNull { runCatching { fetch(it,guard) }.getOrNull() } ?: error("OAuth issuer metadata unavailable")
        require(auth["issuer"]==JsonPrimitive(issuer)) { "OAuth issuer mismatch" }
        require(auth["code_challenge_methods_supported"]?.jsonArray?.contains(JsonPrimitive("S256"))==true) { "需 PKCE S256" }
        require(auth["response_types_supported"]?.jsonArray?.contains(JsonPrimitive("code"))==true)
        fun field(k: String)=auth.getValue(k).jsonPrimitive.content.also(::endpoint)
        val authorization=field("authorization_endpoint");val token=field("token_endpoint")
        // These URLs are returned for explicit confirmation before any token/code is sent.
        guard()
        return buildJsonObject {
            put("resource",resource);put("issuer",issuer);put("authorizationEndpoint",authorization);put("tokenEndpoint",token)
            put("redirectUri",redirect);put("scopes",metadata["scopes_supported"] ?: JsonArray(emptyList()))
            put("issuerResponseRequired",auth["authorization_response_iss_parameter_supported"]==JsonPrimitive(true))
        }
    }
    fun begin(pin: ExtensionInstallation,epoch: Long,metadata: JsonObject,clientId: String,scope: String,guard: () -> Unit): String {
        require(pin.state=="DISABLED" && metadata["resource"]==JsonPrimitive(pin.config.endpoint) && metadata["redirectUri"]==JsonPrimitive(redirect))
        require(clientId.length in 1..2048 && clientId.none { it.code<33 } && scope.length<=2048)
        require(scope.isBlank() || scope.split(' ').all { it.matches(Regex("[!#-\\[\\]-~]+")) })
        endpoint(metadata.getValue("issuer").jsonPrimitive.content)
        endpoint(metadata.getValue("authorizationEndpoint").jsonPrimitive.content);endpoint(metadata.getValue("tokenEndpoint").jsonPrimitive.content)
        val state=random();val verifier=random()
        val secret=buildJsonObject { put("metadata",metadata);put("clientId",clientId);put("scope",scope);put("verifier",verifier) }
        guard()
        val reference=vault.protect(secret.toString())
        transaction { db ->
            guard();require(ExtensionInstallationStore(context).use { it.get(pin.owner,pin.slot) }==pin)
            cancel(db,pin.owner,pin.slot)
            db.execSQL("INSERT INTO flows VALUES(?,?,?,?,?,?,?,'PENDING',?,NULL)",arrayOf<Any>(state,pin.owner,pin.slot,pin.id,pin.revision,epoch,System.currentTimeMillis()+600_000,reference))
        }
        cleanupCanceled()
        return state
    }
    fun open(owner: String,state: String,epoch: Long,guard: () -> Unit) {
        val flow=flow(owner,state,epoch)
        require(flow.status=="PENDING")
        require(ExtensionInstallationStore(context).use { it.get(owner,flow.slot) }?.let { it.id==flow.installation && it.revision==flow.revision && it.state=="DISABLED" }==true)
        val secret=Json.parseToJsonElement(vault.resolve(flow.secret)).jsonObject
        val metadata=secret.getValue("metadata").jsonObject
        val url=metadata.getValue("authorizationEndpoint").jsonPrimitive.content.toHttpUrl().newBuilder()
            .addQueryParameter("response_type","code").addQueryParameter("client_id",secret.getValue("clientId").jsonPrimitive.content)
            .addQueryParameter("redirect_uri",redirect).addQueryParameter("state",state).addQueryParameter("resource",metadata.getValue("resource").jsonPrimitive.content)
            .addQueryParameter("scope",secret.getValue("scope").jsonPrimitive.content).addQueryParameter("code_challenge_method","S256")
            .addQueryParameter("code_challenge",challenge(secret.getValue("verifier").jsonPrimitive.content)).build()
        guard();context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url.toString())).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun callback(uri: Uri) = transaction { db ->
        require(uri.scheme=="${BuildConfig.APPLICATION_ID}.oauth" && uri.encodedPath=="/callback" && uri.host==null && uri.fragment==null)
        require(uri.queryParameterNames.all { it in setOf("state","code","error","error_description","iss") } && uri.queryParameterNames.all { uri.getQueryParameters(it).size==1 })
        val state=requireNotNull(uri.getQueryParameter("state"));require(state.matches(Regex("[A-Za-z0-9_-]{43}")))
        val row=db.rawQuery("SELECT owner,slot,installation,revision,secret FROM flows WHERE state=? AND status='PENDING' AND expires>?",arrayOf(state,System.currentTimeMillis().toString())).use {
            require(it.moveToFirst()) { "Stale OAuth callback" };listOf(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4))
        }
        val pin=ExtensionInstallationStore(context).use { it.get(row[0],row[1]) }
        require(pin!=null && pin.id==row[2] && pin.revision.toString()==row[3] && pin.state=="DISABLED")
        val secret=Json.parseToJsonElement(vault.resolve(row[4])).jsonObject
        val issuer=secret.getValue("metadata").jsonObject.getValue("issuer").jsonPrimitive.content
        require(uri.getQueryParameter("iss").let { it==null || it==issuer })
        if(secret.getValue("metadata").jsonObject["issuerResponseRequired"]==JsonPrimitive(true)) require(uri.getQueryParameter("iss")==issuer)
        val code=uri.getQueryParameter("code")
        val status=if(uri.getQueryParameter("error")!=null) "DENIED" else "CALLBACK"
        require(status=="DENIED" || code!=null && code.length in 1..8192)
        val callback=if(status=="CALLBACK") vault.protect(code!!) else null
        db.execSQL("UPDATE flows SET status=?,callback=? WHERE state=?",arrayOf(status,callback,state))
    }
    fun status(owner: String,state: String,epoch: Long): String = flow(owner,state,epoch).status
    fun publicFlows(owner: String,epoch: Long): List<Map<String,Any>> = readableDatabase.rawQuery(
        "SELECT state,slot,status,expires FROM flows WHERE owner=? AND epoch=? ORDER BY rowid DESC LIMIT 32",arrayOf(owner,epoch.toString())).use { c ->
        buildList { while(c.moveToNext()) add(mapOf("flow" to c.getString(0),"slot" to c.getString(1),"status" to
            (if(c.getLong(3)<=System.currentTimeMillis() && c.getString(2) !in setOf("COMPLETE","CANCELED","EXCHANGING")) "EXPIRED" else c.getString(2)))) }
    }
    fun complete(owner: String,state: String,epoch: Long,guard: () -> Unit): ExtensionInstallation {
        val f=flow(owner,state,epoch)
        require(f.status=="CALLBACK") { "OAuth 尚未返回或结果未知；不会重复兑换 code" }
        val secret=Json.parseToJsonElement(vault.resolve(f.secret)).jsonObject
        val meta=secret.getValue("metadata").jsonObject
        val pin=ExtensionInstallationStore(context).use { it.get(owner,f.slot) } ?: error("Installation missing")
        require(pin.id==f.installation && pin.revision==f.revision && pin.state=="DISABLED")
        val code=vault.resolve(requireNotNull(f.callback))
        val body=FormBody.Builder().add("grant_type","authorization_code").add("client_id",secret.getValue("clientId").jsonPrimitive.content)
            .add("redirect_uri",redirect).add("code",code).add("code_verifier",secret.getValue("verifier").jsonPrimitive.content)
            .add("resource",meta.getValue("resource").jsonPrimitive.content).build()
        guard()
        transaction { db ->
            guard();check(db.compileStatement("UPDATE flows SET status='EXCHANGING' WHERE state=? AND status='CALLBACK'").use {
                it.bindString(1,state);it.executeUpdateDelete()
            }==1)
        }
        // Once admitted, EXCHANGING remains sticky on network failure/process death/lost ACK.
        val result=client.newCall(Request.Builder().url(meta.getValue("tokenEndpoint").jsonPrimitive.content).post(body).header("Accept","application/json").build()).execute().use { response ->
            guard();require(response.code==200) { "OAuth token exchange rejected; reconnect explicitly" }
            Json.parseToJsonElement(bounded(response.body.byteStream())).jsonObject
        }
        require(result["token_type"]?.jsonPrimitive?.content?.equals("Bearer",true)==true)
        val token=result.getValue("access_token").jsonPrimitive.content
        require(token.length in 1..8192 && token.all { it.code in 33..126 })
        val requested=secret.getValue("scope").jsonPrimitive.content.split(' ').filter(String::isNotBlank).toSet()
        val granted=result["scope"]?.jsonPrimitive?.content?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: requested
        require(granted==requested) { "返回授权范围发生变化，需重新确认" }
        val lifetime=result["expires_in"]?.jsonPrimitive?.longOrNull
        require(lifetime!=null && lifetime in 1..31_536_000) { "需明确 token 有效期" }
        guard()
        val binding=ExtensionOAuthBinding(meta.getValue("issuer").jsonPrimitive.content,pin.config.endpoint,secret.getValue("clientId").jsonPrimitive.content,granted.sorted(),System.currentTimeMillis()+lifetime*1000)
        val next=ExtensionInstallationStore(context).use { it.install(owner,pin.slot,pin.revision,pin.config.copy(oauth=binding),token,guard) }
        writableDatabase.execSQL("UPDATE flows SET status='COMPLETE' WHERE state=? AND status='EXCHANGING'",arrayOf(state))
        // Do not retain refresh tokens. Reconnect never resumes/regrants tasks.
        eraseSecrets(state)
        return next
    }
    fun cancel(owner: String,slot: String) { transaction { cancel(it,owner,slot) };cleanupCanceled() }
    fun cleanupCanceled() {
        val states=readableDatabase.rawQuery("SELECT state FROM flows WHERE status IN ('CANCELED','DENIED','COMPLETE') AND secret!=''",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        states.forEach(::eraseSecrets)
    }
    private fun cancel(db: SQLiteDatabase,owner: String,slot: String) {
        db.execSQL("UPDATE flows SET status='CANCELED' WHERE owner=? AND slot=? AND status NOT IN ('COMPLETE','CANCELED')",arrayOf(owner,slot))
    }
    private fun eraseSecrets(state: String) {
        val refs=readableDatabase.rawQuery("SELECT secret,callback FROM flows WHERE state=?",arrayOf(state)).use { c ->
            if(!c.moveToFirst()) emptyList() else listOfNotNull(c.getString(0),c.getString(1)).filter(String::isNotEmpty)
        }
        refs.forEach(vault::discard)
        writableDatabase.execSQL("UPDATE flows SET secret='',callback=NULL WHERE state=?",arrayOf(state))
    }
    private data class Flow(val slot: String,val installation: String,val revision: Long,val status: String,val secret: String,val callback: String?)
    private fun flow(owner: String,state: String,epoch: Long): Flow = readableDatabase.rawQuery("SELECT slot,installation,revision,status,secret,callback,expires FROM flows WHERE owner=? AND state=? AND epoch=?",arrayOf(owner,state,epoch.toString())).use {
        require(it.moveToFirst()) { "OAuth owner/epoch mismatch" }
        if(it.getLong(6)<=System.currentTimeMillis() && it.getString(3) !in setOf("EXCHANGING","COMPLETE","CANCELED")) return@use Flow(it.getString(0),it.getString(1),it.getLong(2),"EXPIRED",it.getString(4),it.getString(5))
        Flow(it.getString(0),it.getString(1),it.getLong(2),it.getString(3),it.getString(4),it.getString(5))
    }
    private fun <T> transaction(block: (SQLiteDatabase)->T): T {
        val db=writableDatabase;db.beginTransaction()
        try { val value=block(db);db.setTransactionSuccessful();return value } finally { db.endTransaction() }
    }
    companion object {
        private val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(30,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).build()
        private fun random()=ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.encodeToString(it,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        internal fun challenge(verifier: String)=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun sameOrigin(a: String,b: String): Boolean { val x=a.toHttpUrl();val y=b.toHttpUrl();return x.scheme==y.scheme && x.host==y.host && x.port==y.port }
        private fun bounded(input: java.io.InputStream): String {
            val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096)
            while(true) { val n=input.read(bytes,0,minOf(bytes.size,65537-out.size()));if(n<0) break;out.write(bytes,0,n);require(out.size()<=65536) }
            return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        }
    }
}
