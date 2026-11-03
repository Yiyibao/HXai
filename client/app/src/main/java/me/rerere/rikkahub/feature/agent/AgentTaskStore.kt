package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ToolPermit(val owner: String, val taskId: String, val capability: String,
    val scope: String, val grantEpoch: Long, val inputHash: String)

internal data class AgentTask(
    val id: String, val owner: String, val conversationId: String, val requestId: String,
    val configurationReference: String, val providerId: String, val modelId: String,
    val reasoning: String, val configurationRevision: String, val state: String,
    val fence: Long, val revision: Long,
    val manualPaused: Boolean = false,
)
internal data class AgentRequestSelection(
    val providerId: String, val modelId: String, val reasoning: String, val configurationReference: String,
)
internal data class AgentLease(val task: AgentTask, val fence: Long)
internal data class CredentialValidationIntent(val owner: String,val taskId: String,val requestId: String,
    val inputHash: String,val validationTaskId: String,val grantEpoch: Long,val scopeHash: String)
internal class AgentTaskBlocked : IllegalStateException("存在结果未知的任务，必须先核验既有结果；不会自动重复执行。")
internal class AgentCommandRejected : IllegalArgumentException("任务或授权状态已变化；该命令未写入，请读取最新状态。")

/** One local user workspace, including all learner profiles. Never included in backup. */
internal class AgentTaskStore(context: Context, name: String = "agent-tasks-v1.db") : SQLiteOpenHelper(
    context, context.noBackupFilesDir.resolve(name).absolutePath, null, 8,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation_id TEXT NOT NULL, request_id TEXT NOT NULL, intent_hash TEXT NOT NULL, configuration_reference TEXT NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, reasoning TEXT NOT NULL, configuration_revision TEXT NOT NULL, state TEXT NOT NULL, fence INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0, manual_pause INTEGER NOT NULL DEFAULT 0 CHECK(manual_pause IN (0,1)), UNIQUE(owner,conversation_id,request_id))")
        db.execSQL("CREATE TABLE slot (singleton INTEGER PRIMARY KEY CHECK(singleton=1), task_id TEXT REFERENCES tasks(id), fence INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO slot(singleton) VALUES(1)")
        db.execSQL("CREATE TABLE calls (task_id TEXT NOT NULL REFERENCES tasks(id), call_id TEXT NOT NULL, tool TEXT NOT NULL, input_hash TEXT NOT NULL, state TEXT NOT NULL, result_hash TEXT, PRIMARY KEY(task_id,call_id))")
        db.execSQL("CREATE TABLE usage (request_id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id), role TEXT NOT NULL, provenance TEXT NOT NULL, prompt_tokens INTEGER, completion_tokens INTEGER, cached_tokens INTEGER, created_at INTEGER, total_tokens INTEGER, provider_id TEXT, model_id TEXT, reasoning TEXT, configuration_reference TEXT)")
        createControlSchema(db)
        createUsageReadIndexes(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion==8 && oldVersion in 1..7) { "Unsupported task schema; database preserved" }
        if (oldVersion<2) db.execSQL("ALTER TABLE usage ADD COLUMN created_at INTEGER")
        if (oldVersion<3) db.execSQL("ALTER TABLE usage ADD COLUMN total_tokens INTEGER")
        if (oldVersion<4) {
            db.execSQL("ALTER TABLE usage ADD COLUMN provider_id TEXT")
            db.execSQL("ALTER TABLE usage ADD COLUMN model_id TEXT")
            db.execSQL("ALTER TABLE usage ADD COLUMN reasoning TEXT")
            db.execSQL("ALTER TABLE usage ADD COLUMN configuration_reference TEXT")
        // Historical rows may contain fake zeros or aggregate several physical requests.
        // Keep diagnostics without inventing per-request selections or reliable totals.
            db.execSQL("UPDATE usage SET provenance='unknown'")
        }
        if (oldVersion<5) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN manual_pause INTEGER NOT NULL DEFAULT 0 CHECK(manual_pause IN (0,1))")
            db.execSQL("UPDATE tasks SET manual_pause=1 WHERE state IN ('PAUSED_BY_USER','STOP_REQUESTED')")
        }
        if (oldVersion<7) createControlSchema(db)
        createUsageReadIndexes(db)
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Unsupported task schema; database preserved")

    private fun createUsageReadIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_owner_id ON tasks(owner,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_task_time ON usage(task_id,created_at)")
    }

    @Synchronized private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
    private fun task(db: SQLiteDatabase, id: String): AgentTask? = db.rawQuery("SELECT id,owner,conversation_id,request_id,configuration_reference,provider_id,model_id,reasoning,configuration_revision,state,fence,revision,manual_pause FROM tasks WHERE id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else AgentTask(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6),it.getString(7),it.getString(8),it.getString(9),it.getLong(10),it.getLong(11),it.getInt(12)!=0)
    }
    @Synchronized fun get(owner: String, id: String): AgentTask = requireNotNull(task(readableDatabase, id)).also { require(it.owner == owner) { "Task access denied" } }
    fun readTaskCatalog(owner: String,kind: String,cursor: String?,limit: Int) = transaction { db ->
        AgentTaskCatalog.list(db,owner,kind,cursor,limit)
    }
    fun readUsageGroups(owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,cursor: String?,limit: Int) = transaction { db ->
        AgentUsageGroups.read(db,owner,taskId,from,until,groupBy,cursor,limit)
    }
    fun captureUsage(owner: String,taskId: String?,from: Long?,until: Long?,groupBy: String,requestId: String) = transaction { db ->
        AgentUsageSnapshot.capture(db,owner,taskId,from,until,groupBy,requestId)
    }
    fun readTaskDetail(owner: String,id: String,cursor: String?,limit: Int) = transaction { db ->
        AgentTaskCatalog.detail(db,owner,id,cursor,limit)
    }
    fun snapshot(owner: String,id: String): AgentTaskSnapshot = transaction { db ->
        val task=requireNotNull(task(db,id));require(task.owner==owner)
        AgentTaskSnapshot(task,control(db,id),plan(owner,id),credentialStatus(db,id),approvalCallIds(owner,id))
    }
    @Synchronized fun find(owner: String, conversationId: String, requestId: String): AgentTask? = readableDatabase.rawQuery("SELECT id FROM tasks WHERE owner=? AND conversation_id=? AND request_id=?", arrayOf(owner, conversationId, requestId)).use { if (it.moveToFirst()) task(readableDatabase,it.getString(0)) else null }
    @Synchronized fun current(owner: String, conversationId: String): AgentTask? = readableDatabase.rawQuery("SELECT id FROM tasks WHERE owner=? AND conversation_id=? AND fence>0 AND request_id NOT LIKE 'role:%' ORDER BY fence DESC LIMIT 1",arrayOf(owner,conversationId)).use { if(it.moveToFirst()) task(readableDatabase,it.getString(0)) else null }
    fun reviseQueued(owner: String, id: String, intent: String) = transaction { db ->
        val existing = requireNotNull(task(db,id)); require(existing.owner == owner)
        val digest = hash(intent)
        db.rawQuery("SELECT intent_hash FROM tasks WHERE id=?",arrayOf(id)).use { check(it.moveToFirst()); if(it.getString(0)==digest) return@transaction }
        check(existing.state in setOf("QUEUED","INTERRUPTED","PAUSED_BY_USER","FAILED_BLOCKED")) { "Cannot edit an executing task" }
        db.execSQL("UPDATE tasks SET intent_hash=?,revision=revision+1 WHERE id=?",arrayOf(digest,id))
        db.execSQL("UPDATE plans SET goal=?,goal_revision=goal_revision+1,planned_revision=0,acceptance_verified=0 WHERE task_id=?",arrayOf(intent,id))
    }

    fun accept(owner: String, conversationId: String, requestId: String, intent: String, reference: String, providerId: String, modelId: String, reasoning: String, configurationRevision: String): AgentTask = transaction { db ->
        val id = UUID.nameUUIDFromBytes("agent:$owner:$conversationId:$requestId".toByteArray(Charsets.UTF_8)).toString()
        task(db,id)?.let { existing ->
            require(existing.owner==owner && existing.conversationId==conversationId && existing.requestId==requestId) {
                "Request identity belongs to a different owner or conversation"
            }
            db.rawQuery("SELECT intent_hash FROM tasks WHERE id=?", arrayOf(id)).use { check(it.moveToFirst()); require(it.getString(0) == hash(intent)) { "Request identity changed" } }
            return@transaction existing
        }
        db.insertOrThrow("tasks",null,ContentValues().apply {
            put("id",id); put("owner",owner); put("conversation_id",conversationId); put("request_id",requestId); put("intent_hash",hash(intent))
            put("configuration_reference",reference); put("provider_id",providerId); put("model_id",modelId); put("reasoning",reasoning); put("configuration_revision",configurationRevision); put("state","QUEUED")
        })
        initializeControl(db,id,owner,reference,intent)
        requireNotNull(task(db,id))
    }
    fun recover() = transaction { db ->
        // Only the application singleton calls this at startup, never a new UI observer.
        db.execSQL("UPDATE calls SET state='UNKNOWN' WHERE state='IN_FLIGHT'")
        db.execSQL("UPDATE plan_steps SET state='UNKNOWN' WHERE state='IN_FLIGHT'")
        db.execSQL("UPDATE tasks SET state=CASE WHEN EXISTS(SELECT 1 FROM calls WHERE calls.task_id=tasks.id AND calls.state='UNKNOWN') THEN 'UNCERTAIN_SIDE_EFFECT' ELSE 'INTERRUPTED' END, revision=revision+1 WHERE state IN ('RUNNING','STOP_REQUESTED')")
        db.execSQL("UPDATE slot SET task_id=NULL WHERE task_id IN (SELECT id FROM tasks WHERE state='INTERRUPTED')")
        // Credential receipts survive exit. The foreground owner-scoped continuation validates
        // live grants and the key-only encrypted configuration before installing a known receipt.
    }.also { usageChanges.tryEmit(Unit) }
    fun pausePending(owner: String, conversationId: String) = transaction { db ->
        db.execSQL("UPDATE tasks SET manual_pause=1,state=CASE WHEN state IN ('RUNNING','UNCERTAIN_SIDE_EFFECT') THEN state ELSE 'PAUSED_BY_USER' END,revision=revision+1 WHERE owner=? AND conversation_id=? AND state IN ('QUEUED','RUNNING','INTERRUPTED','WAITING_INPUT','FAILED_BLOCKED','PAUSED_BY_USER','UNCERTAIN_SIDE_EFFECT')",arrayOf(owner,conversationId))
    }
    /** Only a trusted user command may make an interrupted/waiting task dispatchable. */
    fun resume(owner: String, id: String, allowManualPause: Boolean = true) = transaction { db ->
        val current = requireNotNull(task(db,id)); require(current.owner==owner) { "Task access denied" }
        check(allowManualPause || !current.manualPaused) { "任务由用户暂停；批准工具不会自动恢复任务，请先明确恢复。" }
        check(current.state in setOf("QUEUED","INTERRUPTED","WAITING_INPUT","WAITING_DEVICE","WAITING_AUTHORIZATION","PAUSED_BY_USER","FAILED_BLOCKED","COMPLETED_UNVERIFIED")) { "Task cannot be resumed in its current state" }
        val control=control(db,id)
        check(control.authorization=="ACTIVE" && !control.inputBlocked && !control.deviceBlocked &&
            !control.deviceConfirmation && !control.cancelRequested && !control.approvalBlocked) { "Independent waiting conditions are still present" }
        if (db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",arrayOf(id)).use { it.moveToFirst() }) throw AgentTaskBlocked()
        db.execSQL("UPDATE tasks SET state='QUEUED',manual_pause=0,revision=revision+1 WHERE id=?",arrayOf(id))
    }
    fun claim(owner: String, id: String): AgentLease = transaction { db ->
        val current = requireNotNull(task(db,id)); require(current.owner == owner)
        db.rawQuery("SELECT task_id,fence FROM slot WHERE singleton=1",null).use {
            check(it.moveToFirst()); if (!it.isNull(0)) throw AgentTaskBlocked()
            if (current.state == "UNCERTAIN_SIDE_EFFECT") throw AgentTaskBlocked()
            check(current.state=="QUEUED" && !current.manualPaused) { "任务未排队或已暂停；必须由用户明确恢复。" }
            val control=control(db,id)
            check(control.authorization=="ACTIVE" && !control.inputBlocked && !control.deviceBlocked &&
                !control.deviceConfirmation && !control.cancelRequested && !control.approvalBlocked) { "Independent waiting conditions are still present" }
            val fence = it.getLong(1) + 1
            db.execSQL("UPDATE slot SET task_id=?,fence=? WHERE singleton=1",arrayOf<Any>(id,fence))
            db.execSQL("UPDATE tasks SET state='RUNNING',fence=?,revision=revision+1 WHERE id=?",arrayOf<Any>(fence,id))
            AgentLease(requireNotNull(task(db,id)),fence)
        }
    }
    private fun requireLease(db: SQLiteDatabase, lease: AgentLease) {
        db.rawQuery("SELECT task_id,fence FROM slot WHERE singleton=1",null).use { check(it.moveToFirst() && it.getString(0) == lease.task.id && it.getLong(1) == lease.fence) { "Stale task execution fence" } }
        val current = requireNotNull(task(db,lease.task.id))
        check(current.owner == lease.task.owner && current.fence == lease.fence &&
            (current.state=="RUNNING" || current.state=="STOP_REQUESTED" || current.state=="UNCERTAIN_SIDE_EFFECT")) { "Stale task receipt lease" }
    }
    private fun requireDispatchLease(db: SQLiteDatabase, lease: AgentLease) {
        requireLease(db,lease)
        val current=requireNotNull(task(db,lease.task.id))
        check(current.state=="RUNNING" && !current.manualPaused) { "Task is not running or is paused" }
        val control = control(db,lease.task.id)
        check(control.authorization=="ACTIVE" && !control.inputBlocked && !control.deviceBlocked &&
            !control.deviceConfirmation && !control.cancelRequested && !control.approvalBlocked) { "Task authorization or independent blocker prevents dispatch" }
    }
    fun check(lease: AgentLease) = transaction { requireDispatchLease(it,lease) }
    fun beginCall(lease: AgentLease, callId: String, tool: String, input: String,
        permit: ToolPermit = permit(lease,tool,input)) = transaction { db ->
        requireDispatchLease(db,lease)
        val contract = TrustedToolRegistry.require(tool)
        val currentControl = control(db,lease.task.id)
        require(permit.owner==lease.task.owner && permit.taskId==lease.task.id &&
            permit.capability==contract.capability && permit.scope==lease.task.owner &&
            permit.grantEpoch==currentControl.grantEpoch && permit.inputHash==hash(input)) { "Stale or mismatched dispatch permit" }
        requireGrant(db,lease.task.id,contract.capability,permit.scope,permit.grantEpoch,permit.inputHash)
        if(db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND tool=? AND input_hash=? AND state IN ('IN_FLIGHT','UNKNOWN')",
            arrayOf(lease.task.id,tool,permit.inputHash)).use { it.moveToFirst() }) throw AgentTaskBlocked()
        db.rawQuery("SELECT tool,input_hash,state FROM calls WHERE task_id=? AND call_id=?",arrayOf(lease.task.id,callId)).use {
            if (it.moveToFirst()) { require(it.getString(0)==tool && it.getString(1)==hash(input)) { "Call identity changed" }; error("Call already dispatched; read its durable result instead") }
        }
        // Enforce coverage at the journal boundary as well as in the untrusted planner.
        // A supplementary goal arriving after planning must not dispatch the old plan.
        val plannedStep=db.rawQuery("SELECT plan_json,planned_revision,goal_revision FROM plans WHERE task_id=?",
            arrayOf(lease.task.id)).use { row ->
            check(row.moveToFirst())
            if(row.isNull(0)) null else {
                check(row.getLong(1)==row.getLong(2)) { "目标已更新，必须先重新规划；本次调用未执行。" }
                db.rawQuery("SELECT step_id FROM plan_steps WHERE task_id=? AND tool=? AND state='PLANNED' ORDER BY ordinal LIMIT 1",
                    arrayOf(lease.task.id,tool)).use { step ->
                    check(step.moveToFirst()) { "该工具没有待执行计划步骤；本次调用未执行。" }
                    step.getString(0)
                }
            }
        }
        db.insertOrThrow("calls",null,ContentValues().apply {
            put("task_id",lease.task.id); put("call_id",callId); put("tool",tool); put("input_hash",hash(input)); put("state","IN_FLIGHT")
            put("grant_epoch",permit.grantEpoch); put("capability",permit.capability); put("scope",permit.scope)
        })
        if(plannedStep!=null) check(db.update("plan_steps",ContentValues().apply {
            put("call_id",callId);put("state","IN_FLIGHT")
        },"task_id=? AND step_id=? AND state='PLANNED'",arrayOf(lease.task.id,plannedStep))==1)
    }
    fun finishCall(lease: AgentLease, callId: String, resultHash: String, verified: Boolean, resultJson: String? = null) = transaction { db ->
        requireLease(db,lease)
        resultJson?.let { require(hash(it)==resultHash) }
        val receipt=db.rawQuery("SELECT tool,state,result_hash,result_json FROM calls WHERE task_id=? AND call_id=?",
            arrayOf(lease.task.id,callId)).use {
            check(it.moveToFirst());arrayOf(it.getString(0),it.getString(1),it.getString(2),it.getString(3))
        }
        val tool=receipt[0]
        val unconfirmedRemote= !verified && resultJson!=null &&
            (tool=="convert_material_to_pdf" || tool=="parse_material_enhanced") &&
            Json.decodeFromString<List<me.rerere.ai.ui.UIMessagePart>>(resultJson).filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .firstOrNull()?.let { Json.parseToJsonElement(it.text).jsonObject["state"]?.jsonPrimitive?.content }=="SUBMITTING"
        val state=if(verified) "VERIFIED" else if(unconfirmedRemote) "UNKNOWN" else "RETURNED"
        if(receipt[1]=="VERIFIED" || receipt[1]=="RETURNED") {
            require(receipt[1]==state && receipt[2]==resultHash && receipt[3]==resultJson) { "Conflicting duplicate tool receipt" }
            return@transaction
        }
        check(db.update("calls",ContentValues().apply { put("state",state); put("result_hash",resultHash); put("result_json",resultJson) },
            "task_id=? AND call_id=? AND state IN ('IN_FLIGHT','UNKNOWN')",arrayOf(lease.task.id,callId))==1) { "Missing unsettled call" }
        db.execSQL("UPDATE plan_steps SET state=?,result_json=? WHERE task_id=? AND call_id=?",
            arrayOf(state,resultJson,lease.task.id,callId))
    }
    fun beginRequest(lease: AgentLease, role: String, input: String, selection: AgentRequestSelection): String = transaction { db ->
        requireDispatchLease(db,lease)
        val effectiveReference=credentialReference(db,lease.task.id) ?: lease.task.configurationReference
        require(selection.configurationReference==effectiveReference) { "Provider configuration differs from the verified task configuration" }
        val control = control(db,lease.task.id)
        requireGrant(db,lease.task.id,"provider.request",lease.task.configurationReference,control.grantEpoch,hash(input))
        if (db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND tool LIKE 'provider:%' AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",arrayOf(lease.task.id)).use { it.moveToFirst() }) throw AgentTaskBlocked()
        val requestId = UUID.randomUUID().toString()
        db.insertOrThrow("usage",null,ContentValues().apply {
            put("request_id",requestId); put("task_id",lease.task.id); put("role",role); put("provenance","unknown"); put("created_at",System.currentTimeMillis())
            put("provider_id",selection.providerId); put("model_id",selection.modelId); put("reasoning",selection.reasoning); put("configuration_reference",selection.configurationReference)
            put("accepted_configuration_reference",lease.task.configurationReference)
        })
        db.insertOrThrow("calls",null,ContentValues().apply { put("task_id",lease.task.id); put("call_id",requestId); put("tool","provider:$role"); put("input_hash",hash(input)); put("state","IN_FLIGHT") })
        requestId
    }.also { usageChanges.tryEmit(Unit) }
    fun finishRequest(lease: AgentLease, requestId: String, usage: me.rerere.ai.core.TokenUsage?) = transaction { db ->
        requireLease(db,lease)
        db.rawQuery("SELECT c.state,u.prompt_tokens,u.completion_tokens,u.cached_tokens,u.total_tokens FROM calls c JOIN usage u ON u.request_id=c.call_id WHERE c.task_id=? AND c.call_id=? AND c.tool LIKE 'provider:%'",
            arrayOf(lease.task.id,requestId)).use { receipt ->
            check(receipt.moveToFirst())
            if(receipt.getString(0)=="VERIFIED") {
                fun count(index: Int): Int? = if(receipt.isNull(index)) null else receipt.getInt(index)
                require(count(1)==usage?.promptTokens && count(2)==usage?.completionTokens &&
                    count(3)==usage?.cachedTokens && count(4)==usage?.totalTokens) { "Conflicting duplicate provider receipt" }
                return@transaction
            }
        }
        if (usage != null) db.update("usage",ContentValues().apply {
            put("provenance",if(usage.promptTokens!=null || usage.completionTokens!=null || usage.cachedTokens!=null || usage.totalTokens!=null) "reported" else "unknown")
            put("prompt_tokens",usage.promptTokens); put("completion_tokens",usage.completionTokens); put("cached_tokens",usage.cachedTokens)
            put("total_tokens",usage.totalTokens)
        },"request_id=? AND task_id=?",arrayOf(requestId,lease.task.id))
        val changed=db.update("calls",ContentValues().apply { put("state","VERIFIED") },"task_id=? AND call_id=? AND state IN ('IN_FLIGHT','UNKNOWN')",arrayOf(lease.task.id,requestId))
        check(changed==1 || db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND call_id=? AND state='VERIFIED' AND tool LIKE 'provider:%'",
            arrayOf(lease.task.id,requestId)).use { it.moveToFirst() }) { "Missing physical request receipt" }
    }.also { usageChanges.tryEmit(Unit) }
    fun rejectAuthorization(lease: AgentLease,requestId: String,status: Int) = transaction { db ->
        require(status==401 || status==403)
        requireLease(db,lease)
        val result="{\"status\":\"AUTHORIZATION_REJECTED\",\"httpStatus\":$status}"
        db.rawQuery("SELECT state,result_json FROM calls WHERE task_id=? AND call_id=? AND tool LIKE 'provider:%'",
            arrayOf(lease.task.id,requestId)).use {
            check(it.moveToFirst())
            if(it.getString(0)=="VERIFIED") {
                require(it.getString(1)==result) { "Conflicting authorization receipt" }
                return@transaction
            }
        }
        finishRequest(lease,requestId,null)
        db.execSQL("UPDATE calls SET result_json=?,result_hash=? WHERE task_id=? AND call_id=?",
            arrayOf(result,hash(result),lease.task.id,requestId))
        if(status==401) db.execSQL("UPDATE task_control SET authorization='EXPIRED' WHERE task_id=? AND authorization='ACTIVE'",arrayOf(lease.task.id))
        else db.execSQL("UPDATE task_control SET authorization='REVOKED',grant_epoch=grant_epoch+1 WHERE task_id=? AND authorization!='REVOKED'",arrayOf(lease.task.id))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(lease.task.id))
        reconcileKnownResults(lease)
    }
    /** Late, same-fence receipts may settle uncertainty, never dispatch or claim goal success. */
    fun reconcileKnownResults(lease: AgentLease) = transaction { db ->
        requireLease(db,lease)
        if(requireNotNull(task(db,lease.task.id)).state=="UNCERTAIN_SIDE_EFFECT" &&
            db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",arrayOf(lease.task.id)).use { !it.moveToFirst() })
            finish(lease,"INTERRUPTED")
    }
    fun finish(lease: AgentLease, state: String) = transaction { db ->
        requireLease(db,lease)
        val unsafe = db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN') LIMIT 1",arrayOf(lease.task.id)).use { it.moveToFirst() }
        val control = control(db,lease.task.id)
        val accepted = db.rawQuery("SELECT 1 FROM plans p WHERE task_id=? AND acceptance_verified=1 AND planned_revision=goal_revision AND NOT EXISTS (SELECT 1 FROM plan_steps s WHERE s.task_id=p.task_id AND s.state!='VERIFIED')",
            arrayOf(lease.task.id)).use { it.moveToFirst() }
        val resolved = when {
            unsafe -> "UNCERTAIN_SIDE_EFFECT"
            control.cancelRequested -> "CANCELED"
            requireNotNull(task(db,lease.task.id)).manualPaused -> "PAUSED_BY_USER"
            control.authorization!="ACTIVE" -> "WAITING_AUTHORIZATION"
            control.deviceBlocked || control.deviceConfirmation -> "WAITING_DEVICE"
            control.inputBlocked || control.approvalBlocked -> "WAITING_INPUT"
            state=="SUCCEEDED" && !accepted -> "COMPLETED_UNVERIFIED"
            state in setOf("SUCCEEDED","COMPLETED_UNVERIFIED") && accepted -> "SUCCEEDED"
            else -> state
        }
        if (unsafe) db.execSQL("UPDATE calls SET state='UNKNOWN' WHERE task_id=? AND state='IN_FLIGHT'",arrayOf(lease.task.id))
        db.execSQL("UPDATE tasks SET state=?,manual_pause=CASE WHEN ?='PAUSED_BY_USER' THEN 1 ELSE manual_pause END,revision=revision+1 WHERE id=?",arrayOf(resolved,state,lease.task.id))
        if(unsafe) db.execSQL("UPDATE plan_steps SET state='UNKNOWN' WHERE task_id=? AND state='IN_FLIGHT'",arrayOf(lease.task.id))
        if (!unsafe) db.execSQL("UPDATE slot SET task_id=NULL WHERE task_id=? AND fence=?",arrayOf<Any>(lease.task.id,lease.fence))
    }.also { usageChanges.tryEmit(Unit) }

    // A single SELECT has its own consistent snapshot; don't reserve a writer transaction for an advisory read.
    @Synchronized fun readUsageNotificationCandidates(owner: String, after: String?, now: Long, limit: Int = 100) =
        AgentUsageNotificationReader.read(readableDatabase,owner,after,now,limit)
    @Synchronized fun readUsageNotificationRules(owner: String, taskId: String, now: Long): Set<String> =
        AgentUsageNotificationReader.read(readableDatabase,owner,null,now,1,taskId).firstOrNull()?.rules.orEmpty()

    private fun createControlSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS task_control(task_id TEXT PRIMARY KEY REFERENCES tasks(id),authorization TEXT NOT NULL DEFAULT 'ACTIVE',grant_epoch INTEGER NOT NULL DEFAULT 1,input_blocked INTEGER NOT NULL DEFAULT 0,device_blocked INTEGER NOT NULL DEFAULT 0,device_confirmation INTEGER NOT NULL DEFAULT 0,cancel_requested INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS grants(task_id TEXT NOT NULL REFERENCES tasks(id),capability TEXT NOT NULL,scope TEXT NOT NULL,grant_epoch INTEGER NOT NULL,input_hash TEXT NOT NULL DEFAULT '',PRIMARY KEY(task_id,capability,scope,input_hash))")
        db.execSQL("CREATE TABLE IF NOT EXISTS plans(task_id TEXT PRIMARY KEY REFERENCES tasks(id),goal TEXT NOT NULL,goal_revision INTEGER NOT NULL DEFAULT 1,planned_revision INTEGER NOT NULL DEFAULT 0,plan_json TEXT,acceptance_verified INTEGER NOT NULL DEFAULT 0,evidence_json TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS plan_steps(task_id TEXT NOT NULL REFERENCES tasks(id),step_id TEXT NOT NULL,tool TEXT NOT NULL,purpose TEXT NOT NULL,ordinal INTEGER NOT NULL,call_id TEXT,state TEXT NOT NULL DEFAULT 'PLANNED',result_json TEXT,PRIMARY KEY(task_id,step_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS task_commands(task_id TEXT NOT NULL REFERENCES tasks(id),request_id TEXT NOT NULL,input_hash TEXT NOT NULL,PRIMARY KEY(task_id,request_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS credential_overrides(task_id TEXT PRIMARY KEY REFERENCES tasks(id),configuration_reference TEXT NOT NULL,validation_task_id TEXT NOT NULL REFERENCES tasks(id),grant_epoch INTEGER NOT NULL,scope_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS credential_commands(task_id TEXT NOT NULL REFERENCES tasks(id),request_id TEXT NOT NULL,input_hash TEXT NOT NULL,PRIMARY KEY(task_id,request_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS credential_validation_intents(task_id TEXT NOT NULL REFERENCES tasks(id),request_id TEXT NOT NULL,input_hash TEXT NOT NULL,validation_task_id TEXT NOT NULL REFERENCES tasks(id),grant_epoch INTEGER NOT NULL,scope_hash TEXT NOT NULL,PRIMARY KEY(task_id,request_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS task_approval_waits(task_id TEXT NOT NULL REFERENCES tasks(id),call_id TEXT NOT NULL,tool TEXT NOT NULL,input_hash TEXT NOT NULL,PRIMARY KEY(task_id,call_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS task_tool_decisions(task_id TEXT NOT NULL REFERENCES tasks(id),call_id TEXT NOT NULL,tool TEXT NOT NULL,input_hash TEXT NOT NULL,grant_epoch INTEGER NOT NULL,decision_kind TEXT NOT NULL,decision_json TEXT NOT NULL,PRIMARY KEY(task_id,call_id))")
        val acceptedReferenceExists=db.rawQuery("PRAGMA table_info(usage)",null).use {
            var found=false;while(it.moveToNext()) if(it.getString(1)=="accepted_configuration_reference") found=true;found
        }
        if(!acceptedReferenceExists) {
            db.execSQL("ALTER TABLE usage ADD COLUMN accepted_configuration_reference TEXT")
            db.execSQL("UPDATE usage SET accepted_configuration_reference=configuration_reference")
        }
        for ((column,type) in listOf("result_json" to "TEXT","grant_epoch" to "INTEGER","capability" to "TEXT","scope" to "TEXT")) {
            val exists=db.rawQuery("PRAGMA table_info(calls)",null).use { cursor ->
                var found=false; while(cursor.moveToNext()) if(cursor.getString(1)==column) found=true; found
            }
            if(!exists) db.execSQL("ALTER TABLE calls ADD COLUMN $column $type")
        }
        db.rawQuery("SELECT id,owner,configuration_reference FROM tasks",null).use {
            while(it.moveToNext()) initializeControl(db,it.getString(0),it.getString(1),it.getString(2),"")
        }
    }
    private fun initializeControl(db: SQLiteDatabase,id: String,owner: String,reference: String,goal: String) {
        db.execSQL("INSERT OR IGNORE INTO task_control(task_id) VALUES(?)",arrayOf(id))
        db.execSQL("INSERT OR IGNORE INTO plans(task_id,goal) VALUES(?,?)",arrayOf(id,goal))
        for(capability in TrustedToolRegistry.automaticCapabilities + "provider.request") {
            db.execSQL("INSERT OR IGNORE INTO grants(task_id,capability,scope,grant_epoch,input_hash) VALUES(?,?,?,1,'')",
                arrayOf(id,capability,if(capability=="provider.request") reference else owner))
        }
    }
    private fun control(db: SQLiteDatabase,id: String): AgentControl =
        db.rawQuery("SELECT authorization,grant_epoch,input_blocked,device_blocked,device_confirmation,cancel_requested,EXISTS(SELECT 1 FROM task_approval_waits w WHERE w.task_id=c.task_id) FROM task_control c WHERE task_id=?",arrayOf(id)).use {
            check(it.moveToFirst()); AgentControl(it.getString(0),it.getLong(1),it.getInt(2)!=0,it.getInt(3)!=0,it.getInt(4)!=0,it.getInt(5)!=0,it.getInt(6)!=0)
        }
    @Synchronized fun control(owner: String,id: String): AgentControl { get(owner,id); return control(readableDatabase,id) }
    private fun requireGrant(db: SQLiteDatabase,id: String,capability: String,scope: String,epoch: Long,inputHash: String) {
        check(db.rawQuery("SELECT 1 FROM grants WHERE task_id=? AND capability=? AND scope=? AND grant_epoch=? AND (input_hash='' OR input_hash=?)",
            arrayOf(id,capability,scope,epoch.toString(),inputHash)).use { it.moveToFirst() }) { "Capability, scope or bound input is not authorized" }
    }
    fun permit(lease: AgentLease,tool: String,input: String): ToolPermit = transaction { db ->
        requireDispatchLease(db,lease)
        ToolPermit(lease.task.owner,lease.task.id,TrustedToolRegistry.require(tool).capability,
            lease.task.owner,control(db,lease.task.id).grantEpoch,hash(input))
    }
    private fun registerApprovalWaits(db: SQLiteDatabase,id: String,tools: List<me.rerere.ai.ui.UIMessagePart.Tool>) {
        for(tool in tools) {
            require(tool.isPending)
            TrustedToolRegistry.require(tool.toolName)
            val digest=hash(tool.input)
            db.rawQuery("SELECT tool,input_hash FROM task_approval_waits WHERE task_id=? AND call_id=?",arrayOf(id,tool.toolCallId)).use {
                if(it.moveToFirst()) require(it.getString(0)==tool.toolName && it.getString(1)==digest) { "Pending tool identity changed" }
            }
            val decided=db.rawQuery("SELECT tool,input_hash FROM task_tool_decisions WHERE task_id=? AND call_id=?",arrayOf(id,tool.toolCallId)).use {
                if(!it.moveToFirst()) false else {
                    require(it.getString(0)==tool.toolName && it.getString(1)==digest) { "Decided tool identity changed" }
                    true
                }
            }
            if(decided) continue
            db.execSQL("INSERT OR IGNORE INTO task_approval_waits(task_id,call_id,tool,input_hash) VALUES(?,?,?,?)",
                arrayOf(id,tool.toolCallId,tool.toolName,digest))
        }
        if(tools.isNotEmpty()) db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(id))
    }
    fun waitForToolApproval(lease: AgentLease,tools: List<me.rerere.ai.ui.UIMessagePart.Tool>) = transaction { db ->
        requireLease(db,lease)
        registerApprovalWaits(db,lease.task.id,tools)
    }
    /** Repairs the Room-checkpoint/native-wait gap using the owning session's durable receipt. */
    fun reconcileApprovalWaits(owner: String,id: String,revision: Long,fence: Long,tools: List<me.rerere.ai.ui.UIMessagePart.Tool>) = transaction { db ->
        val current=requireNotNull(task(db,id))
        require(current.owner==owner && current.revision==revision && current.fence==fence)
        registerApprovalWaits(db,id,tools)
    }
    fun decideTool(owner: String,id: String,tool: me.rerere.ai.ui.UIMessagePart.Tool,
        decision: me.rerere.ai.ui.ToolApprovalState,expectedEpoch: Long) = transaction { db ->
        require(requireNotNull(task(db,id)).owner==owner)
        val kind=when(decision) {
            is me.rerere.ai.ui.ToolApprovalState.Approved -> "approved"
            is me.rerere.ai.ui.ToolApprovalState.Denied -> "denied"
            is me.rerere.ai.ui.ToolApprovalState.Answered -> "answered"
            else -> error("A human decision is required")
        }
        val encoded=Json.encodeToString(decision)
        val digest=hash(tool.input)
        db.rawQuery("SELECT tool,input_hash,grant_epoch,decision_kind,decision_json FROM task_tool_decisions WHERE task_id=? AND call_id=?",
            arrayOf(id,tool.toolCallId)).use {
            if(it.moveToFirst()) {
                require(it.getString(0)==tool.toolName && it.getString(1)==digest &&
                    it.getLong(2)==expectedEpoch && it.getString(3)==kind && it.getString(4)==encoded) { "Conflicting human decision" }
                return@transaction
            }
        }
        db.rawQuery("SELECT tool,input_hash FROM task_approval_waits WHERE task_id=? AND call_id=?",arrayOf(id,tool.toolCallId)).use {
            require(it.moveToFirst() && it.getString(0)==tool.toolName && it.getString(1)==digest) { "Missing bound approval wait" }
        }
        val control=control(db,id)
        require(control.grantEpoch==expectedEpoch && !control.cancelRequested)
        if(kind!="denied") {
            check(control.authorization=="ACTIVE")
            val capability=TrustedToolRegistry.require(tool.toolName).capability
            db.execSQL("INSERT OR REPLACE INTO grants(task_id,capability,scope,grant_epoch,input_hash) VALUES(?,?,?,?,?)",
                arrayOf<Any>(id,capability,owner,expectedEpoch,digest))
        }
        db.execSQL("INSERT INTO task_tool_decisions(task_id,call_id,tool,input_hash,grant_epoch,decision_kind,decision_json) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any>(id,tool.toolCallId,tool.toolName,digest,expectedEpoch,kind,encoded))
        db.execSQL("DELETE FROM task_approval_waits WHERE task_id=? AND call_id=?",arrayOf(id,tool.toolCallId))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(id))
    }
    @Synchronized fun toolDecisions(owner: String,id: String): List<PersistedToolDecision> {
        get(owner,id)
        return readableDatabase.rawQuery("SELECT call_id,tool,input_hash,grant_epoch,decision_json FROM task_tool_decisions WHERE task_id=?",arrayOf(id)).use {
            buildList { while(it.moveToNext()) add(PersistedToolDecision(it.getString(0),it.getString(1),it.getString(2),it.getLong(3),Json.decodeFromString(it.getString(4)))) }
        }
    }
    @Synchronized fun approvalCallIds(owner: String,id: String): List<String> {
        get(owner,id)
        return readableDatabase.rawQuery("SELECT call_id FROM task_approval_waits WHERE task_id=? ORDER BY call_id",arrayOf(id)).use {
            buildList { while(it.moveToNext()) add(it.getString(0)) }
        }
    }
    fun expireAuthorization(owner: String,id: String) = transaction { db ->
        require(requireNotNull(task(db,id)).owner==owner)
        db.execSQL("UPDATE task_control SET authorization='EXPIRED' WHERE task_id=? AND authorization='ACTIVE'",arrayOf(id))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(id))
    }
    /** Trusted verified-credential callback; cannot reauthorize an actively revoked task. */
    fun restoreAuthorization(owner: String,id: String,expectedEpoch: Long,scopeHash: String) = transaction { db ->
        val current=requireNotNull(task(db,id)); require(current.owner==owner)
        val control=control(db,id)
        check(control.authorization=="EXPIRED" && !control.cancelRequested && control.grantEpoch==expectedEpoch && grantScopeHash(db,id)==scopeHash)
        db.execSQL("UPDATE task_control SET authorization='ACTIVE' WHERE task_id=?",arrayOf(id))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(id))
    }
    private fun grantScopeHash(db: SQLiteDatabase,id: String): String = db.rawQuery(
        "SELECT capability,scope,input_hash,grant_epoch FROM grants WHERE task_id=? ORDER BY capability,scope,input_hash",arrayOf(id)).use {
        val value=StringBuilder(); while(it.moveToNext()) for(index in 0..3) value.append(it.getString(index)).append(0.toChar()); hash(value.toString())
    }
    @Synchronized fun grantScopeHash(owner: String,id: String): String { get(owner,id); return grantScopeHash(readableDatabase,id) }
    private fun credentialStatus(db: SQLiteDatabase,id: String): CredentialStatus? = db.rawQuery(
        "SELECT i.request_id,CASE WHEN EXISTS(SELECT 1 FROM credential_commands r WHERE r.task_id=i.task_id AND r.request_id=i.request_id) THEN 'APPLIED' ELSE v.state END FROM credential_validation_intents i JOIN tasks v ON v.id=i.validation_task_id WHERE i.task_id=? ORDER BY i.rowid DESC LIMIT 1",
        arrayOf(id)).use { if(it.moveToFirst()) CredentialStatus(it.getString(0),it.getString(1)) else null }
    @Synchronized fun credentialStatus(owner: String,id: String): CredentialStatus? {
        get(owner,id);return credentialStatus(readableDatabase,id)
    }
    @Synchronized fun physicalRequestCount(owner: String,id: String): Int {
        get(owner,id);return readableDatabase.rawQuery("SELECT COUNT(*) FROM usage WHERE task_id=?",arrayOf(id)).use { it.moveToFirst();it.getInt(0) }
    }
    fun readUsage(owner: String,taskId: String?,from: Long?,until: Long?,cursor: String?,limit: Int): AgentUsagePage =
        transaction { db -> AgentUsageLedger.read(db,owner,taskId,from,until,cursor,limit) }
    fun readUsageAlerts(owner: String,taskId: String?,from: Long?,until: Long?,now: Long = System.currentTimeMillis()): AgentUsageAlerts =
        transaction { db -> AgentUsageAlertReader.read(db,owner,taskId,from,until,now) }
    private fun credentialReference(db: SQLiteDatabase,id: String): String? = db.rawQuery(
        "SELECT c.configuration_reference FROM credential_overrides c JOIN task_control t ON t.task_id=c.task_id WHERE c.task_id=? AND c.grant_epoch=t.grant_epoch",arrayOf(id)).use {
        if(it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized fun credentialReference(owner: String,id: String): String? { get(owner,id);return credentialReference(readableDatabase,id) }
    private fun credentialValidation(db: SQLiteDatabase,id: String,requestId: String): CredentialValidationIntent? =
        db.rawQuery("SELECT t.owner,i.input_hash,i.validation_task_id,i.grant_epoch,i.scope_hash FROM credential_validation_intents i JOIN tasks t ON t.id=i.task_id WHERE i.task_id=? AND i.request_id=?",arrayOf(id,requestId)).use {
            if(!it.moveToFirst()) null else CredentialValidationIntent(it.getString(0),id,requestId,it.getString(1),it.getString(2),it.getLong(3),it.getString(4))
        }
    @Synchronized fun credentialValidation(owner: String,id: String,requestId: String): CredentialValidationIntent? {
        get(owner,id);return credentialValidation(readableDatabase,id,requestId)
    }
    fun registerCredentialValidation(owner: String,id: String,requestId: String,inputHash: String,validationTaskId: String,
        expectedRevision: Long,expectedFence: Long,expectedEpoch: Long,scopeHash: String): CredentialValidationIntent = transaction { db ->
        val current=requireNotNull(task(db,id));require(current.owner==owner)
        credentialValidation(db,id,requestId)?.let {
            require(it.inputHash==inputHash) { "Credential command identity changed" };return@transaction it
        }
        require(current.revision==expectedRevision && current.fence==expectedFence) { "Task revision or fence has changed" }
        val control=control(db,id)
        check(control.authorization=="EXPIRED" && control.grantEpoch==expectedEpoch && grantScopeHash(db,id)==scopeHash) { "Original authorization scope changed" }
        val validation=requireNotNull(task(db,validationTaskId))
        require(validation.owner==owner && validation.providerId==current.providerId && validation.modelId==current.modelId &&
            validation.reasoning==current.reasoning && validation.requestId.startsWith("role:credential_validation:"))
        db.execSQL("INSERT INTO credential_validation_intents(task_id,request_id,input_hash,validation_task_id,grant_epoch,scope_hash) VALUES(?,?,?,?,?,?)",
            arrayOf<Any>(id,requestId,inputHash,validationTaskId,expectedEpoch,scopeHash))
        CredentialValidationIntent(owner,id,requestId,inputHash,validationTaskId,expectedEpoch,scopeHash)
    }
    private fun verifiedCredentialReceipt(db: SQLiteDatabase,validation: AgentTask): Boolean =
        control(db,validation.id).authorization=="ACTIVE" &&
            db.rawQuery("SELECT 1 FROM calls c JOIN usage u ON u.request_id=c.call_id WHERE c.task_id=? AND c.state='VERIFIED' AND c.result_json IS NULL AND u.role='credential_validation' AND u.configuration_reference=? AND NOT EXISTS(SELECT 1 FROM calls p WHERE p.task_id=c.task_id AND p.state IN ('IN_FLIGHT','UNKNOWN')) LIMIT 1",
                arrayOf(validation.id,validation.configurationReference)).use { it.moveToFirst() }
    private fun credentialApplied(db: SQLiteDatabase,id: String,requestId: String,inputHash: String): Boolean =
        db.rawQuery("SELECT input_hash FROM credential_commands WHERE task_id=? AND request_id=?",arrayOf(id,requestId)).use {
            if(!it.moveToFirst()) false else { require(it.getString(0)==inputHash) { "Credential command identity changed" };true }
        }
    @Synchronized fun credentialApplied(owner: String,id: String,requestId: String,inputHash: String): Boolean {
        get(owner,id);return credentialApplied(readableDatabase,id,requestId,inputHash)
    }
    fun installCredential(owner: String,id: String,requestId: String,inputHash: String,expectedEpoch: Long,
        scopeHash: String,reference: String,validationTaskId: String): AgentTask = transaction { db ->
        val current=requireNotNull(task(db,id));require(current.owner==owner)
        if(credentialApplied(db,id,requestId,inputHash)) return@transaction current
        val intent=requireNotNull(credentialValidation(db,id,requestId)) { "No accepted credential-validation intent" }
        require(intent.inputHash==inputHash && intent.validationTaskId==validationTaskId &&
            intent.grantEpoch==expectedEpoch && intent.scopeHash==scopeHash)
        val validation=requireNotNull(task(db,validationTaskId))
        require(validation.owner==owner && validation.providerId==current.providerId && validation.modelId==current.modelId &&
            validation.reasoning==current.reasoning && validation.configurationReference==reference &&
            (validation.state=="COMPLETED_UNVERIFIED" || validation.state=="INTERRUPTED") && verifiedCredentialReceipt(db,validation)) { "Credential validation does not match the locked task" }
        restoreAuthorization(owner,id,expectedEpoch,scopeHash)
        db.execSQL("INSERT OR REPLACE INTO credential_overrides(task_id,configuration_reference,validation_task_id,grant_epoch,scope_hash) VALUES(?,?,?,?,?)",
            arrayOf<Any>(id,reference,validationTaskId,expectedEpoch,scopeHash))
        db.execSQL("INSERT INTO credential_commands(task_id,request_id,input_hash) VALUES(?,?,?)",arrayOf(id,requestId,inputHash))
        db.execSQL("UPDATE tasks SET state=CASE WHEN state='WAITING_AUTHORIZATION' THEN 'INTERRUPTED' ELSE state END WHERE id=?",arrayOf(id))
        requireNotNull(task(db,id))
    }
    @Synchronized fun commandRecorded(owner: String,id: String,requestId: String): Boolean {
        get(owner,id)
        return readableDatabase.rawQuery("SELECT 1 FROM task_commands WHERE task_id=? AND request_id=?",
            arrayOf(id,requestId)).use { it.moveToFirst() }
    }
    fun command(owner: String,id: String,requestId: String,expectedRevision: Long,expectedFence: Long,
        action: String,payload: String="",safetyOnly: Boolean=false): AgentTask = transaction { db ->
        // The task catalog may stop any owned task, including one whose conversation
        // was deleted. It cannot grant, resume, dispatch, or change the accepted goal.
        if(safetyOnly) require(action in setOf("pause","cancel","revoke") && payload.isEmpty())
        val current=requireNotNull(task(db,id)); require(current.owner==owner)
        val digest=hash("$action:$payload:$expectedRevision:$expectedFence")
        db.rawQuery("SELECT input_hash FROM task_commands WHERE task_id=? AND request_id=?",arrayOf(id,requestId)).use {
            if(it.moveToFirst()) { require(it.getString(0)==digest); return@transaction current }
        }
        require(current.revision==expectedRevision && current.fence==expectedFence) { "Task revision or fence has changed" }
        require(requestId.length in 1..200)
        if(safetyOnly && action!="revoke") check(current.state !in setOf("SUCCEEDED","CANCELED")) {
            "A safety command cannot rewrite a terminal task"
        }
        when(action) {
            "resume" -> {
                val control=control(db,id)
                check(control.authorization=="ACTIVE" && !control.inputBlocked && !control.deviceBlocked &&
                    !control.deviceConfirmation && !control.cancelRequested && !control.approvalBlocked)
                check(current.state in setOf("PAUSED_BY_USER","INTERRUPTED","WAITING_INPUT","WAITING_DEVICE","WAITING_AUTHORIZATION","FAILED_BLOCKED","COMPLETED_UNVERIFIED","QUEUED"))
                check(db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN')",arrayOf(id)).use { !it.moveToFirst() })
                db.execSQL("UPDATE tasks SET manual_pause=0,state='QUEUED' WHERE id=?",arrayOf(id))
            }
            "pause","cancel" -> {
                db.execSQL("UPDATE tasks SET manual_pause=1 WHERE id=?",arrayOf(id))
                if(action=="cancel") db.execSQL("UPDATE task_control SET cancel_requested=1 WHERE task_id=?",arrayOf(id))
                if(current.state!="RUNNING" && current.state!="STOP_REQUESTED" && current.state!="UNCERTAIN_SIDE_EFFECT")
                    db.execSQL("UPDATE tasks SET state=? WHERE id=?",arrayOf(if(action=="cancel") "CANCELED" else "PAUSED_BY_USER",id))
                if(current.state=="RUNNING" || current.state=="STOP_REQUESTED") db.execSQL("UPDATE tasks SET state='STOP_REQUESTED' WHERE id=?",arrayOf(id))
            }
            "revoke" -> db.execSQL("UPDATE task_control SET authorization='REVOKED',grant_epoch=grant_epoch+1 WHERE task_id=?",arrayOf(id))
            "wait_input","wait_device","require_device_confirmation","provide_input","device_available","confirm_device" -> {
                val (column,value)=when(action) {
                    "wait_input" -> "input_blocked" to 1
                    "wait_device" -> "device_blocked" to 1
                    "require_device_confirmation" -> "device_confirmation" to 1
                    "provide_input" -> "input_blocked" to 0
                    "device_available" -> "device_blocked" to 0
                    else -> "device_confirmation" to 0
                }
                db.execSQL("UPDATE task_control SET $column=? WHERE task_id=?",arrayOf<Any>(value,id))
                if(action=="provide_input") {
                    require(payload.isNotBlank() && payload.length<=100000)
                    db.execSQL("UPDATE plans SET goal=goal || char(10) || ?,goal_revision=goal_revision+1,acceptance_verified=0 WHERE task_id=?",arrayOf(payload,id))
                }
            }
            "supplement" -> {
                require(payload.isNotBlank() && payload.length<=100000)
                check(current.state!="CANCELED")
                if(current.state=="SUCCEEDED") db.execSQL("UPDATE tasks SET state='COMPLETED_UNVERIFIED' WHERE id=?",arrayOf(id))
                db.execSQL("UPDATE plans SET goal=goal || char(10) || ?,goal_revision=goal_revision+1,acceptance_verified=0 WHERE task_id=?",arrayOf(payload,id))
            }
            else -> error("Unsupported task command")
        }
        db.execSQL("INSERT INTO task_commands(task_id,request_id,input_hash) VALUES(?,?,?)",arrayOf(id,requestId,digest))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(id))
        requireNotNull(task(db,id))
    }.also { usageChanges.tryEmit(Unit) }
    fun plan(owner: String,id: String): PersistedPlan {
        get(owner,id)
        val steps=readableDatabase.rawQuery("SELECT step_id,tool,call_id,state,result_json FROM plan_steps WHERE task_id=? ORDER BY ordinal",arrayOf(id)).use {
            buildList { while(it.moveToNext()) add(VerifiedStep(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4))) }
        }
        return readableDatabase.rawQuery("SELECT goal,goal_revision,planned_revision,plan_json,acceptance_verified FROM plans WHERE task_id=?",arrayOf(id)).use {
            check(it.moveToFirst()); PersistedPlan(it.getString(0),it.getLong(1),it.getLong(2),
                if(it.isNull(3)) null else Json.decodeFromString<AgentPlan>(it.getString(3)),steps,it.getInt(4)!=0)
        }
    }
    fun publishPlan(lease: AgentLease,goalRevision: Long,plan: AgentPlan,availableTools: Set<String>) = transaction { db ->
        requireDispatchLease(db,lease); plan.validate(availableTools)
        check(db.rawQuery("SELECT goal_revision FROM plans WHERE task_id=?",arrayOf(lease.task.id)).use { it.moveToFirst() && it.getLong(0)==goalRevision }) { "Goal changed during planning" }
        val existing=plan(lease.task.owner,lease.task.id)
        check(existing.steps.none { it.state in setOf("IN_FLIGHT","UNKNOWN") }) { "Cannot replan unresolved effects" }
        for(step in existing.steps.filter { it.state=="VERIFIED" }) {
            require(plan.steps.any { it.id==step.id && it.tool==step.tool }) { "Replanning cannot discard verified steps" }
        }
        db.execSQL("DELETE FROM plan_steps WHERE task_id=? AND state!='VERIFIED'",arrayOf(lease.task.id))
        plan.steps.forEachIndexed { index,step ->
            db.execSQL("INSERT OR IGNORE INTO plan_steps(task_id,step_id,tool,purpose,ordinal) VALUES(?,?,?,?,?)",
                arrayOf<Any>(lease.task.id,step.id,step.tool,step.purpose,index))
            // Retained verified rows keep their identity, state and receipt; their projection
            // must still reflect the newly accepted plan's order and description.
            db.execSQL("UPDATE plan_steps SET purpose=?,ordinal=? WHERE task_id=? AND step_id=?",
                arrayOf<Any>(step.purpose,index,lease.task.id,step.id))
        }
        db.execSQL("UPDATE plans SET plan_json=?,planned_revision=?,acceptance_verified=0 WHERE task_id=?",arrayOf<Any>(Json.encodeToString(plan),goalRevision,lease.task.id))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(lease.task.id))
    }
    fun acceptGoal(lease: AgentLease,goalRevision: Long,evidenceJson: String) = transaction { db ->
        requireDispatchLease(db,lease)
        val plan=plan(lease.task.owner,lease.task.id)
        require(plan.revision==goalRevision && plan.plannedRevision==goalRevision && plan.plan?.acceptance?.isNotEmpty()==true)
        require(requireNotNull(plan.plan).acceptance.all { predicate -> plan.steps.any { it.id==predicate.stepId && it.state=="VERIFIED" } })
        require(plan.steps.all { it.state=="VERIFIED" }) { "Every planned step must be independently verified before accepting the goal" }
        check(db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN')",arrayOf(lease.task.id)).use { !it.moveToFirst() })
        db.execSQL("UPDATE plans SET acceptance_verified=1,evidence_json=? WHERE task_id=?",arrayOf(evidenceJson,lease.task.id))
    }
    @Synchronized fun completedResult(owner: String,id: String,tool: String,input: String): String? {
        get(owner,id)
        return readableDatabase.rawQuery("SELECT result_json,result_hash FROM calls WHERE task_id=? AND tool=? AND input_hash=? AND state='VERIFIED' AND result_json IS NOT NULL ORDER BY rowid DESC LIMIT 1",
            arrayOf(id,tool,hash(input))).use { if(!it.moveToFirst()) null else it.getString(0).also { result -> check(hash(result)==it.getString(1)) } }
    }
    @Synchronized fun recordedRemoteTask(owner: String,conversationId: String,tool: me.rerere.ai.ui.UIMessagePart.Tool): AgentTask? {
        if(tool.toolName!="convert_material_to_pdf" && tool.toolName!="parse_material_enhanced") return null
        val contract=TrustedToolRegistry.require(tool.toolName)
        return readableDatabase.rawQuery(
            "SELECT c.task_id,c.result_json,c.result_hash,c.grant_epoch FROM calls c JOIN tasks t ON t.id=c.task_id WHERE t.owner=? AND t.conversation_id=? AND c.call_id=? AND c.tool=? AND c.input_hash=? AND c.capability=? AND c.scope=? AND c.state='RETURNED' AND c.result_json IS NOT NULL",
            arrayOf(owner,conversationId,tool.toolCallId,tool.toolName,hash(tool.input),contract.capability,owner)).use { row ->
            if(!row.moveToFirst()) return@use null
            val id=row.getString(0)
            if(current(owner,conversationId)?.id!=id || control(owner,id).grantEpoch!=row.getLong(3)) return@use null
            val receipt=row.getString(1);check(hash(receipt)==row.getString(2))
            val original=Json.decodeFromString<List<me.rerere.ai.ui.UIMessagePart>>(receipt).filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .firstOrNull()?.let { Json.parseToJsonElement(it.text).jsonObject } ?: return@use null
            val actual=tool.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().firstOrNull()?.let {
                Json.parseToJsonElement(it.text).jsonObject
            } ?: return@use null
            if(original["task_id"]==null || listOf("task_id","operation","source_id","batch_id","requested_start","requested_end").any {
                original[it]!=actual[it]
            }) return@use null
            get(owner,id)
        }
    }
    private fun requireToolDispatch(db: SQLiteDatabase,lease: AgentLease,tool: me.rerere.ai.ui.UIMessagePart.Tool) {
        requireDispatchLease(db,lease)
        val contract=TrustedToolRegistry.require(tool.toolName)
        val epoch=control(db,lease.task.id).grantEpoch
        val digest=hash(tool.input)
        check(db.rawQuery("SELECT 1 FROM calls WHERE task_id=? AND call_id=? AND tool=? AND input_hash=? AND capability=? AND scope=? AND grant_epoch=? AND state IN ('IN_FLIGHT','RETURNED')",
            arrayOf(lease.task.id,tool.toolCallId,tool.toolName,digest,contract.capability,lease.task.owner,epoch.toString())).use { it.moveToFirst() }) {
            "Remote operation has no current bound task call"
        }
        requireGrant(db,lease.task.id,contract.capability,lease.task.owner,epoch,digest)
    }
    fun checkToolDispatch(lease: AgentLease,tool: me.rerere.ai.ui.UIMessagePart.Tool) = transaction { requireToolDispatch(it,lease,tool) }
    fun verifyReturnedCall(lease: AgentLease,tool: me.rerere.ai.ui.UIMessagePart.Tool,resultJson: String) = transaction { db ->
        requireToolDispatch(db,lease,tool)
        check(db.update("calls",ContentValues().apply {
            put("state","VERIFIED");put("result_json",resultJson);put("result_hash",hash(resultJson))
        },"task_id=? AND call_id=? AND state='RETURNED'",arrayOf(lease.task.id,tool.toolCallId))==1)
        db.execSQL("UPDATE plan_steps SET state='VERIFIED',result_json=? WHERE task_id=? AND call_id=?",
            arrayOf(resultJson,lease.task.id,tool.toolCallId))
        db.execSQL("UPDATE tasks SET revision=revision+1 WHERE id=?",arrayOf(lease.task.id))
        db.execSQL("UPDATE plans SET acceptance_verified=0 WHERE task_id=?",arrayOf(lease.task.id))
    }
    companion object {
        // Invalidation only: never transport task content, grants or request bodies.
        val usageChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay=1,
            onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()
    }
}
