package me.rerere.rikkahub.feature.agent

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.BuildConfig

/** Versioned payload on the existing owner/epoch protected frontend channel. Never a model tool. */
internal class ExtensionFrontend(private val context: Context) {
    private val json=Json { encodeDefaults=true }
    suspend fun handle(method: String, owner: String, args: Map<*,*>, guard: () -> Unit): Any = try {
        handleChecked(method,owner,args,guard)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { error("扩展操作未完成；请核对连接、公开客户端、范围、当前档案与授权状态。兑换结果未知时请重新授权，未自动重试。") }
    private suspend fun handleChecked(method: String, owner: String, args: Map<*,*>, guard: () -> Unit): Any {
        require((args["version"] as? Number)?.toInt() == 1) { "扩展管理协议版本不匹配" }
        guard()
        if(method.startsWith("extensions.oauth.")) return ExtensionOAuth(context).use { oauth ->
            fun text(key: String)=args[key] as? String ?: error("缺少 $key")
            val epoch=(args["epoch"] as? Number)?.toLong() ?: error("缺少上下文版本")
            when(method) {
                "extensions.oauth.discover" -> mapOf("metadata" to oauth.discover(text("endpoint"),text("issuer"),guard).toString())
                "extensions.oauth.begin" -> ExtensionInstallationStore(context).use { store ->
                    val slot=text("slot");val metadata=Json.parseToJsonElement(text("metadata")).jsonObject
                    val old=store.get(owner,slot)
                    require((old?.revision ?: 0)==(args["revision"] as? Number)?.toLong()) { "安装已变化，请刷新" }
                    val pin=old ?: store.install(owner,slot,0,ExtensionConfig(name=slot,endpoint=metadata.getValue("resource").jsonPrimitive.content,
                        serverName="oauth-pending",serverVersion="pending",tools=emptyList()),"",guard)
                    require(pin.config.endpoint==metadata["resource"]?.jsonPrimitive?.content)
                    mapOf("flow" to oauth.begin(pin,epoch,metadata,text("clientId"),text("scope"),guard))
                }
                "extensions.oauth.open" -> { oauth.open(owner,text("flow"),epoch,guard);mapOf("opened" to true) }
                "extensions.oauth.status" -> mapOf("status" to oauth.status(owner,text("flow"),epoch))
                "extensions.oauth.complete" -> dto(oauth.complete(owner,text("flow"),epoch,guard))
                "extensions.oauth.cancel" -> { oauth.cancel(owner,text("slot"));mapOf("canceled" to true) }
                else -> error("未知 OAuth 命令")
            }.also { if(method!="extensions.oauth.open") guard() }
        }
        if(method == "extensions.probe") {
            val endpoint=args["endpoint"] as? String ?: error("缺少端点")
            val protocol=args["protocol"] as? String ?: "2025-11-25"
            val token=if(args["useStoredCredential"]==true) ExtensionInstallationStore(context).use { store ->
                val pin=requireNotNull(store.get(owner,args["slot"] as? String ?: ""))
                require(pin.config.endpoint==endpoint && pin.state=="DISABLED" && pin.config.oauth?.expiresAt?.let { it>System.currentTimeMillis() }==true)
                me.rerere.rikkahub.data.datastore.CredentialVault(context).resolve(pin.credential)
            } else args["token"] as? String ?: ""
            ExtensionPolicy.endpoint(endpoint,BuildConfig.SYNTHETIC_MCP && BuildConfig.APPLICATION_ID == "org.hxsi.agent.nativeqa")
            require(protocol in setOf("2025-03-26","2025-06-18","2025-11-25") && token.length <= 8192 && token.none { it == '\r' || it == '\n' })
            val coroutine=currentCoroutineContext()
            val transport=ExtensionMcpTransport(endpoint,protocol,token,AgentTaskStore.hash(owner)) { coroutine.ensureActive();guard() }
            val init=transport.initialize();val tools=transport.tools()
            guard()
            return mapOf("version" to 1,"serverName" to init.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content,
                "serverVersion" to init.getValue("serverInfo").jsonObject.getValue("version").jsonPrimitive.content,
                "sandboxImage" to init["_meta"]?.jsonObject?.get("hxai.sandbox")?.jsonObject?.get("image")?.jsonPrimitive?.content,
                "tools" to tools.map { t -> mapOf("name" to t.getValue("name").jsonPrimitive.content,
                    "schema" to t.getValue("inputSchema").toString()) })
        }
        return ExtensionInstallationStore(context).use { store ->
            fun text(key: String)=args[key] as? String ?: error("缺少 $key")
            fun revision()=(args["revision"] as? Number)?.toLong() ?: error("缺少 revision")
            when(method) {
                "extensions.list" -> ExtensionToolGrantStore(context).use { grants ->
                    mapOf("version" to 1,"installations" to store.list(owner).map { pin ->
                        dto(pin) + mapOf("grantedTools" to grants.currentNames(owner, pin.id, pin.grantEpoch, pin.config.tools).toList())
                    },"events" to store.events(owner),
                    "oauthFlows" to ExtensionOAuth(context).use { it.cleanupCanceled();it.publicFlows(owner,(args["epoch"] as Number).toLong()) })
                }
                "extensions.install" -> {
                    val config=json.decodeFromString<ExtensionConfig>(text("config"))
                    val old=store.get(owner,text("slot"))
                    val stored=args["useStoredCredential"]==true
                    if(stored) require(old!=null && old.config.endpoint==config.endpoint && old.config.oauth!=null && old.config.oauth.expiresAt>System.currentTimeMillis())
                    val token=if(stored) me.rerere.rikkahub.data.datastore.CredentialVault(context).resolve(requireNotNull(old).credential) else args["token"] as? String ?: ""
                    val next=store.install(owner,text("slot"),revision(),config.copy(oauth=if(stored) old?.config?.oauth else null),token,guard)
                    old?.let { ExtensionToolGrantStore(context).use { grants -> grants.drop(owner, it.id) } }
                    dto(next)
                }
                "extensions.command" -> {
                    val old=store.get(owner,text("slot"))
                    val next=store.command(owner,text("slot"),text("id"),revision(),text("action"),guard)
                    ExtensionOAuth(context).use { it.cancel(owner,next.slot) }
                    if(old!=null && old.id!=next.id) ExtensionToolGrantStore(context).use { it.drop(owner, old.id) }
                    dto(next)
                }
                "extensions.grant" -> {
                    val pin=requireNotNull(store.get(owner,text("slot"))) { "未安装" }
                    require(pin.id==text("id") && pin.revision==revision() && pin.state=="ENABLED") { "安装已变化或尚未启用" }
                    val tool=pin.config.tools.single { it.name==text("tool") }
                    ExtensionToolGrantStore(context).use { it.grant(owner, pin.id, tool.name, pin.grantEpoch, ExtensionToolAdmission.contractHash(tool)) }
                    dto(pin) + mapOf("grantedTools" to listOf(tool.name))
                }
                "extensions.revokeTool" -> {
                    val pin=requireNotNull(store.get(owner,text("slot"))) { "未安装" }
                    require(pin.id==text("id") && pin.revision==revision()) { "安装已变化，请刷新" }
                    ExtensionToolGrantStore(context).use { it.revoke(owner, pin.id, text("tool")) }
                    dto(pin) + mapOf("grantedTools" to emptyList<String>())
                }
                else -> error("未知扩展命令")
            }.also { guard() }
        }
    }
    private fun dto(p: ExtensionInstallation): Map<String,Any> = mapOf("slot" to p.slot,"id" to p.id,"revision" to p.revision,
        "grantEpoch" to p.grantEpoch,"state" to p.state,"config" to json.encodeToString(p.config),"hasCredential" to p.credential.isNotEmpty(),
        "authStatus" to (p.config.oauth?.let { if(it.expiresAt>System.currentTimeMillis()) "OAUTH_CONNECTED" else "OAUTH_EXPIRED" } ?: if(p.credential.isEmpty()) "NONE" else "BEARER"))
}
