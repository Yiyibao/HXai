package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import me.rerere.rikkahub.data.datastore.CredentialVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Local authority boundary, not an HTTP listener or a multi-tenant cloud service. */
internal object AgentAccountSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_accounts(id TEXT PRIMARY KEY,epoch INTEGER NOT NULL DEFAULT 1,revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)))")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_account_profiles(owner TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES agent_accounts(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_sessions(digest TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES agent_accounts(id),account_epoch INTEGER NOT NULL,owner TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),executor_id TEXT NOT NULL,grant_epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)))")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_host_credentials(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES agent_accounts(id),owner TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),provider_id TEXT NOT NULL,grant_epoch INTEGER NOT NULL,reference TEXT NOT NULL,revision INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)))")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_transport_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id TEXT NOT NULL,task_id TEXT NOT NULL,operation TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_provider_commands(account_id TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),command_id TEXT NOT NULL,intent_hash TEXT NOT NULL,request_id TEXT NOT NULL REFERENCES usage(request_id),PRIMARY KEY(account_id,task_id,command_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_hosted_results(request_id TEXT PRIMARY KEY REFERENCES usage(request_id),response_text TEXT NOT NULL,response_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_executor_bindings(task_id TEXT NOT NULL REFERENCES tasks(id),fence INTEGER NOT NULL,executor_id TEXT NOT NULL,instance_id TEXT NOT NULL UNIQUE,PRIMARY KEY(task_id,fence))")
    }
}

internal class AgentTransportDenied : IllegalStateException("Account request unavailable or outside its authorized scope")
internal class AgentBearer(val value: String) {
    override fun toString() = "AgentBearer([redacted])"
}
internal data class AgentCredentialHandle(val id: String, val revision: Long)
internal data class AgentProviderAdmission(val requestId: String,val dispatch: Boolean)
@Serializable
private data class HostedCredential(val account: String,val owner: String,val task: String,val provider: String,val secret: String) {
    override fun toString() = "HostedCredential([redacted])"
}

/** Trusted enrollment/revocation, deliberately absent from tools, bridge and model context.
 * A future server must supply its own authenticated enrollment, KMS and transport.
 */
internal class AgentAccountAuthority(
    private val store: AgentTaskStore,
    private val vault: CredentialVault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun enroll(account: String,owners: Set<String>) = store.accountTransaction { db ->
        require(account.matches(Regex("[A-Za-z0-9._-]{1,120}")) && owners.isNotEmpty())
        db.execSQL("INSERT OR IGNORE INTO agent_accounts(id) VALUES(?)",arrayOf(account))
        // Re-enrollment never removes a revocation or remaps an existing profile.
        owners.forEach { owner ->
            require(owner.isNotBlank() && owner.length<=200)
            db.execSQL("INSERT OR IGNORE INTO agent_account_profiles(owner,account_id) VALUES(?,?)",arrayOf(owner,account))
            check(mapped(db,account,owner)) { "Profile already belongs to another account" }
        }
    }

    fun issue(account: String,owner: String,taskId: String,executor: String,expiresAt: Long): AgentBearer = store.accountTransaction { db ->
        val time=now()
        require(expiresAt>time && expiresAt-time in 1..86_400_000 && executor.matches(Regex("[A-Za-z0-9._-]{1,120}")) && executor!="local")
        val epoch=activeEpoch(db,account)
        check(mapped(db,account,owner))
        store.get(owner,taskId)
        val control=store.control(owner,taskId)
        check(control.authorization=="ACTIVE")
        val token=AgentBearer(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        db.execSQL("INSERT INTO agent_sessions(digest,account_id,account_epoch,owner,task_id,executor_id,grant_epoch,expires_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any>(AgentTaskStore.hash(token.value),account,epoch,owner,taskId,executor,control.grantEpoch,expiresAt))
        token
    }

    fun revoke(account: String) = store.accountTransaction { db ->
        val epoch=db.rawQuery("SELECT epoch FROM agent_accounts WHERE id=?",arrayOf(account)).use { check(it.moveToFirst());it.getLong(0) }
        check(epoch<Long.MAX_VALUE)
        db.execSQL("UPDATE agent_accounts SET revoked=1,epoch=epoch+1 WHERE id=?",arrayOf(account))
        db.execSQL("UPDATE agent_sessions SET revoked=1 WHERE account_id=?",arrayOf(account))
        db.execSQL("UPDATE agent_host_credentials SET revoked=1 WHERE account_id=?",arrayOf(account))
        // Account credentials cannot establish that a running host actually stopped.
        // Retain the original journal, slot, task authorization and independent blockers.
    }

    fun revokeSession(token: AgentBearer) = store.accountTransaction { db ->
        db.execSQL("UPDATE agent_sessions SET revoked=1 WHERE digest=?",arrayOf(AgentTaskStore.hash(token.value)))
    }

    fun protect(account: String,owner: String,taskId: String,secret: String,previous: AgentCredentialHandle?=null): AgentCredentialHandle = store.accountTransaction { db ->
        activeEpoch(db,account);check(mapped(db,account,owner))
        val task=store.get(owner,taskId);val control=store.control(owner,taskId)
        check(control.authorization=="ACTIVE");require(secret.isNotBlank() && secret.length<=65_536)
        val id=previous?.id ?: UUID.randomUUID().toString()
        val revision=if(previous==null) 1L else {
            credential(db,account,owner,taskId,previous)
            check(previous.revision<Long.MAX_VALUE);previous.revision+1
        }
        val record=Json.encodeToString(HostedCredential(account,owner,taskId,task.providerId,secret))
        val reference=vault.protect(record)
        check(CredentialVault.isReference(reference) && vault.resolve(reference)==record)
        if(previous==null) db.execSQL("INSERT INTO agent_host_credentials(id,account_id,owner,task_id,provider_id,grant_epoch,reference,revision) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id,account,owner,taskId,task.providerId,control.grantEpoch,reference,revision))
        else db.execSQL("UPDATE agent_host_credentials SET reference=?,revision=? WHERE id=?",arrayOf<Any>(reference,revision,id))
        // Old encrypted blobs stay immutable; only the current reference can resolve.
        AgentCredentialHandle(id,revision)
    }

    fun revokeCredential(account: String,owner: String,taskId: String,handle: AgentCredentialHandle) = store.accountTransaction { db ->
        activeEpoch(db,account);check(mapped(db,account,owner));credential(db,account,owner,taskId,handle)
        db.execSQL("UPDATE agent_host_credentials SET revoked=1 WHERE id=?",arrayOf(handle.id))
    }

    fun transport(token: AgentBearer) = AgentAccountTransport(store,this,token)

    internal fun <T> request(token: AgentBearer,owner: String,id: String,executor: String?,operation: String,block: (SQLiteDatabase,String)->T): T = store.accountTransaction { db ->
        if(!token.value.matches(Regex("[A-Za-z0-9_-]{43}"))) throw AgentTransportDenied()
        val account=db.rawQuery("SELECT s.account_id,s.account_epoch,s.owner,s.task_id,s.executor_id,s.grant_epoch,s.expires_at,s.revoked,a.epoch,a.revoked FROM agent_sessions s JOIN agent_accounts a ON a.id=s.account_id WHERE s.digest=?",arrayOf(AgentTaskStore.hash(token.value))).use { c ->
            if(!c.moveToFirst() || c.getInt(7)!=0 || c.getInt(9)!=0 || c.getLong(1)!=c.getLong(8) ||
                c.getString(2)!=owner || c.getString(3)!=id || (executor!=null && c.getString(4)!=executor) || c.getLong(6)<=now()) throw AgentTransportDenied()
            if(!mapped(db,c.getString(0),owner) || store.control(owner,id).grantEpoch!=c.getLong(5)) throw AgentTransportDenied()
            c.getString(0)
        }
        val result=block(db,account)
        db.execSQL("INSERT INTO agent_transport_audit(account_id,task_id,operation,created_at) VALUES(?,?,?,?)",arrayOf<Any>(account,id,operation,now()))
        result
    }

    /** Only the trusted host's provider adapter may unwrap; never a transport DTO. */
    internal fun providerSecret(db: SQLiteDatabase,account: String,lease: AgentLease,handle: AgentCredentialHandle): String {
        store.check(lease)
        val reference=credential(db,account,lease.task.owner,lease.task.id,handle)
        check(CredentialVault.isReference(reference)) // No legacy plaintext fallback.
        val record=try { Json.decodeFromString<HostedCredential>(vault.resolve(reference)) }
            catch (_: Exception) { throw AgentTransportDenied() }
        check(record.account==account && record.owner==lease.task.owner && record.task==lease.task.id && record.provider==lease.task.providerId)
        return record.secret
    }

    private fun credential(db: SQLiteDatabase,account: String,owner: String,id: String,handle: AgentCredentialHandle): String =
        db.rawQuery("SELECT provider_id,grant_epoch,reference FROM agent_host_credentials WHERE id=? AND account_id=? AND owner=? AND task_id=? AND revision=? AND revoked=0",
            arrayOf(handle.id,account,owner,id,handle.revision.toString())).use {
            check(it.moveToFirst()) { "Hosted credential unavailable" }
            check(it.getString(0)==store.get(owner,id).providerId && it.getLong(1)==store.control(owner,id).grantEpoch)
            it.getString(2)
        }
    private fun activeEpoch(db: SQLiteDatabase,account: String): Long = db.rawQuery("SELECT epoch,revoked FROM agent_accounts WHERE id=?",arrayOf(account)).use {
        check(it.moveToFirst() && it.getInt(1)==0) { "Account unavailable" };it.getLong(0)
    }
    private fun mapped(db: SQLiteDatabase,account: String,owner: String): Boolean = db.rawQuery("SELECT 1 FROM agent_account_profiles WHERE owner=? AND account_id=?",arrayOf(owner,account)).use { it.moveToFirst() }
}

/** In-process request transport: no trusted callbacks, stop proofs or verified booleans from a client. */
internal class AgentAccountTransport internal constructor(
    private val store: AgentTaskStore,private val authority: AgentAccountAuthority,private val token: AgentBearer,
) {
    fun task(owner: String,id: String): AgentTask = authority.request(token,owner,id,null,"task.read") { _,_ -> store.get(owner,id) }
    fun handoff(owner: String,id: String,request: String): ExecutorHandoff? = authority.request(token,owner,id,null,"handoff.read") { _,_ -> store.readHandoff(owner,id,request) }
    fun activate(owner: String,id: String,request: String,executor: String): AgentLease = authority.request(token,owner,id,executor,"handoff.activate") { _,_ -> store.activateHandoff(owner,id,request,executor) }

    // Host-only dispatch. The adapter owns this callback; it is not request data.
    // Journal admission and secret resolution are atomic with account authorization.
    internal fun admitProvider(lease: AgentLease,handle: AgentCredentialHandle,commandId: String,input: String,selection: AgentRequestSelection): AgentProviderAdmission =
        authority.request(token,lease.task.owner,lease.task.id,lease.executorId,"provider.admit") { db,account ->
            require(commandId.length in 1..200)
            val task=store.get(lease.task.owner,lease.task.id)
            require(selection.providerId==task.providerId && selection.modelId==task.modelId && selection.reasoning==task.reasoning && selection.costBasis==null)
            store.check(lease)
            val intent=AgentTaskStore.hash(kotlinx.serialization.json.buildJsonArray {
                listOf(input,selection.providerId,selection.modelId,selection.reasoning,selection.configurationReference,handle.id,handle.revision.toString(),lease.fence.toString())
                    .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }.toString())
            db.rawQuery("SELECT intent_hash,request_id FROM agent_provider_commands WHERE account_id=? AND task_id=? AND command_id=?",arrayOf(account,task.id,commandId)).use {
                if(it.moveToFirst()) {
                    require(it.getString(0)==intent) { "Provider command intent changed" }
                    return@request AgentProviderAdmission(it.getString(1),false)
                }
            }
            authority.providerSecret(db,account,lease,handle)
            val requestId=store.beginRequest(lease,"main",input,selection)
            db.execSQL("INSERT INTO agent_provider_commands(account_id,task_id,command_id,intent_hash,request_id) VALUES(?,?,?,?,?)",arrayOf(account,task.id,commandId,intent,requestId))
            AgentProviderAdmission(requestId,true)
        }

    internal fun checkProviderSecret(lease: AgentLease,handle: AgentCredentialHandle): String =
        authority.request(token,lease.task.owner,lease.task.id,lease.executorId,"provider.credentials") { db,account -> authority.providerSecret(db,account,lease,handle) }

    internal fun result(lease: AgentLease,requestId: String): String? =
        authority.request(token,lease.task.owner,lease.task.id,lease.executorId,"provider.result") { db,account ->
            db.rawQuery("SELECT r.response_text,r.response_hash FROM agent_hosted_results r JOIN agent_provider_commands c ON c.request_id=r.request_id WHERE c.account_id=? AND c.task_id=? AND c.request_id=?",arrayOf(account,lease.task.id,requestId)).use {
                if(!it.moveToFirst()) null else it.getString(0).also { text -> check(AgentTaskStore.hash(text)==it.getString(1)) { "Hosted result integrity mismatch" } }
            }
        }
}
