package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.CredentialVault
import java.util.UUID

internal data class ExtensionInstallation(val owner: String, val slot: String, val id: String, val revision: Long,
    val grantEpoch: Long, val state: String, val config: ExtensionConfig, val credential: String)

/** Separate from legacy QA registry, task store and backup. No executor or automatic reconnection. */
internal class ExtensionInstallationStore(private val context: Context, name: String = "extensions-v1.db") :
    SQLiteOpenHelper(context, context.noBackupFilesDir.resolve(name).absolutePath, null, 1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Extension registry corrupt; preserved") }), java.io.Closeable {
    init { require(name.matches(Regex("[A-Za-z0-9_-]+\\.db"))) }
    private val json = Json { encodeDefaults = true }
    private val qa get() = BuildConfig.SYNTHETIC_MCP && BuildConfig.APPLICATION_ID == "org.hxsi.agent.nativeqa"
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE installations(owner TEXT NOT NULL,slot TEXT NOT NULL,id TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL,grant_epoch INTEGER NOT NULL,state TEXT NOT NULL,config TEXT NOT NULL,credential TEXT NOT NULL,PRIMARY KEY(owner,slot))")
        db.execSQL("CREATE TABLE events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,slot TEXT NOT NULL,revision INTEGER NOT NULL,action TEXT NOT NULL,created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Future extension schema; preserved")
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit = error("Future extension schema; preserved")
    fun list(owner: String): List<ExtensionInstallation> = readableDatabase.rawQuery("SELECT slot FROM installations WHERE owner=? ORDER BY slot", arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) add(requireNotNull(get(owner,c.getString(0)))) }
    }
    fun get(owner: String, slot: String): ExtensionInstallation? = read(readableDatabase, owner, slot)
    fun find(owner: String, id: String): ExtensionInstallation = list(owner).singleOrNull { it.id == id } ?: error("扩展安装已改变或不属于当前档案")
    fun install(owner: String, slot: String, expectedRevision: Long, config: ExtensionConfig, token: String,
        guard: () -> Unit = {}): ExtensionInstallation = transaction { db ->
        guard(); require(owner.isNotBlank() && owner.length <= 200 && slot.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        config.validate(qa)
        require(token.length <= 8192 && token.none { it == '\r' || it == '\n' })
        val old = read(db,owner,slot)
        require((old?.revision ?: 0) == expectedRevision && old?.state != "ENABLED") { "请先停用并刷新扩展" }
        require(list(owner).size < 64 || old != null)
        val secret = if (token.isEmpty()) "" else CredentialVault(context).protect(token)
        val next = ExtensionInstallation(owner,slot,UUID.randomUUID().toString(),Math.addExact(expectedRevision,1),Math.addExact(old?.grantEpoch ?: 0,1),"DISABLED",config,secret)
        guard(); write(db,next,"install"); next
    }
    fun command(owner: String, slot: String, id: String, revision: Long, action: String, guard: () -> Unit = {}): ExtensionInstallation = transaction { db ->
        guard(); val old = requireNotNull(read(db,owner,slot))
        require(old.id == id && old.revision == revision) { "旧管理命令已拒绝，请刷新" }
        val state = when(action) {
            "enable" -> { require(old.state == "DISABLED"); "ENABLED" }
            "disable" -> { require(old.state == "ENABLED"); "DISABLED" }
            "revoke" -> { require(old.state in setOf("ENABLED","DISABLED")); "REVOKED" }
            "uninstall" -> { require(old.state != "UNINSTALLED"); "UNINSTALLED" }
            else -> error("未知扩展命令")
        }
        val next = old.copy(id=UUID.randomUUID().toString(),revision=Math.addExact(old.revision,1),grantEpoch=Math.addExact(old.grantEpoch,1),state=state,
            credential=if(state in setOf("REVOKED","UNINSTALLED")) "" else old.credential)
        guard(); write(db,next,action); next
    }
    fun check(pin: ExtensionInstallation) { require(pin.state == "ENABLED" && get(pin.owner,pin.slot) == pin) { "扩展已停用、撤权或更新" } }
    fun events(owner: String): List<Map<String,Any>> = readableDatabase.rawQuery("SELECT slot,revision,action,created_at FROM events WHERE owner=? ORDER BY sequence DESC LIMIT 100", arrayOf(owner)).use { c ->
        buildList { while(c.moveToNext()) add(mapOf("slot" to c.getString(0),"revision" to c.getLong(1),"action" to c.getString(2),"time" to c.getLong(3))) }
    }
    private fun read(db: SQLiteDatabase, owner: String, slot: String): ExtensionInstallation? = db.rawQuery("SELECT id,revision,grant_epoch,state,config,credential FROM installations WHERE owner=? AND slot=?",arrayOf(owner,slot)).use { c ->
        if(!c.moveToFirst()) null else ExtensionInstallation(owner,slot,c.getString(0),c.getLong(1),c.getLong(2),c.getString(3),json.decodeFromString<ExtensionConfig>(c.getString(4)).also { it.validate(qa) },c.getString(5)).also {
            require(it.state in setOf("ENABLED","DISABLED","REVOKED","UNINSTALLED") && it.revision > 0 && it.grantEpoch > 0)
            require(it.credential.isEmpty() || CredentialVault.isReference(it.credential))
        }
    }
    private fun write(db: SQLiteDatabase, pin: ExtensionInstallation, action: String) {
        check(db.insertWithOnConflict("installations",null,ContentValues().apply {
            put("owner",pin.owner);put("slot",pin.slot);put("id",pin.id);put("revision",pin.revision);put("grant_epoch",pin.grantEpoch);put("state",pin.state);put("config",json.encodeToString(pin.config));put("credential",pin.credential)
        },SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        check(db.insertOrThrow("events",null,ContentValues().apply { put("owner",pin.owner);put("slot",pin.slot);put("revision",pin.revision);put("action",action);put("created_at",System.currentTimeMillis()) }) != -1L)
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db=writableDatabase;db.beginTransaction()
        try { val result=block(db);db.setTransactionSuccessful();return result } finally { db.endTransaction() }
    }
}
