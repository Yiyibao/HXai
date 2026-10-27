package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import java.util.UUID

/** Host-authored pins. Remote metadata never supplies capabilities or installation grants. */
@Serializable
internal data class McpPinnedConfig(
    val port: Int,
    val recordId: String,
    val sha256: String,
    val serverName: String = "synthetic",
    val serverVersion: String = "1",
    val protocol: String = "2025-03-26",
    val toolContractHash: String = CONTRACT_HASH,
) {
    fun validate() {
        require(port in 1024..65535 && recordId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(serverName.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(serverVersion.matches(Regex("[a-zA-Z0-9._-]{1,40}")))
        require(protocol == "2025-03-26" && toolContractHash == CONTRACT_HASH)
    }
    companion object {
        // Covers the only supported remote tool and exact schema, independent of annotations.
        val CONTRACT_HASH = AgentTaskStore.hash("read_fixture\n{\"type\":\"object\"}")
    }
}

internal data class McpInstallation(
    val owner: String, val id: String, val revision: Long, val grantEpoch: Long,
    val state: String, val config: McpPinnedConfig,
)

/** Independent noBackup registry, not an executor. All mutations are trusted-UI CAS commands.
 * Tombstones survive uninstall and process exit; task grants/UNKNOWN are never changed here. */
internal class McpInstallationStore(context: Context, name: String = "mcp-installations-v1.db") :
    SQLiteOpenHelper(context, context.noBackupFilesDir.resolve(name).absolutePath, null, 1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("MCP registry is corrupt; preserved for recovery") }), java.io.Closeable {
    init {
        check(BuildConfig.SYNTHETIC_MCP && BuildConfig.APPLICATION_ID == "org.hxsi.agent.nativeqa")
        require(name.matches(Regex("[a-zA-Z0-9_-]+\\.db")))
    }
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE installations (owner TEXT PRIMARY KEY, installation_id TEXT NOT NULL UNIQUE, revision INTEGER NOT NULL CHECK(revision>0), grant_epoch INTEGER NOT NULL CHECK(grant_epoch>0), state TEXT NOT NULL CHECK(state IN ('DISABLED','ENABLED','REVOKED','UNINSTALLED')), config_json TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        error("Unsupported MCP registry schema; preserved")
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        error("Unsupported MCP registry schema; preserved")

    fun get(owner: String): McpInstallation? = read(readableDatabase, owner)

    /** Installation/replacement alone never grants permission. Enabled installations must stop first. */
    fun install(owner: String, expectedRevision: Long, config: McpPinnedConfig): McpInstallation = transaction { db ->
        require(owner.isNotBlank() && owner.length <= 200)
        config.validate()
        val old = read(db, owner)
        require((old?.revision ?: 0) == expectedRevision && old?.state != "ENABLED") { "MCP installation changed or is enabled" }
        val next = McpInstallation(owner, UUID.randomUUID().toString(), Math.addExact(expectedRevision, 1),
            Math.addExact(old?.grantEpoch ?: 0, 1), "DISABLED", config)
        write(db, next)
        next
    }

    /** REVOKED/UNINSTALLED require a fresh explicit installation; enable cannot undo revocation. */
    fun command(owner: String, id: String, expectedRevision: Long, action: String): McpInstallation = transaction { db ->
        val old = requireNotNull(read(db, owner))
        require(old.id == id && old.revision == expectedRevision) { "Stale MCP management command" }
        val state = when (action) {
            "enable" -> { require(old.state == "DISABLED"); "ENABLED" }
            "disable" -> { require(old.state == "ENABLED"); "DISABLED" }
            "revoke" -> { require(old.state in setOf("DISABLED", "ENABLED")); "REVOKED" }
            "uninstall" -> { require(old.state != "UNINSTALLED"); "UNINSTALLED" }
            else -> error("Unsupported MCP command")
        }
        val next = old.copy(id = UUID.randomUUID().toString(), revision = Math.addExact(old.revision, 1),
            grantEpoch = Math.addExact(old.grantEpoch, 1), state = state)
        write(db, next)
        next
    }

    fun checkCurrent(pin: McpInstallation) {
        require(pin.state == "ENABLED" && get(pin.owner) == pin) { "MCP installation or authorization changed" }
        pin.config.validate()
    }

    private fun read(db: SQLiteDatabase, owner: String): McpInstallation? = db.rawQuery(
        "SELECT installation_id,revision,grant_epoch,state,config_json FROM installations WHERE owner=?", arrayOf(owner)
    ).use { row ->
        if (!row.moveToFirst()) null else McpInstallation(owner, row.getString(0), row.getLong(1), row.getLong(2),
            row.getString(3), Json.decodeFromString<McpPinnedConfig>(row.getString(4)).also { it.validate() })
    }
    private fun write(db: SQLiteDatabase, value: McpInstallation) {
        db.insertWithOnConflict("installations", null, ContentValues().apply {
            put("owner", value.owner); put("installation_id", value.id); put("revision", value.revision)
            put("grant_epoch", value.grantEpoch); put("state", value.state); put("config_json", Json.encodeToString(value.config))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
}
