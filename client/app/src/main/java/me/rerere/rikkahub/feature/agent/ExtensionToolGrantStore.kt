package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Explicit per-installation tool grants. Enable, schema and server lists are not grants. */
internal object ExtensionToolAdmission {
    fun visible(state: String, toolNames: List<String>, granted: Set<String>): List<String> {
        if (state != "ENABLED") return emptyList()
        return toolNames.filter { it in granted }
    }

    fun current(storedEpoch: Long, storedHash: String, liveEpoch: Long, liveHash: String): Boolean =
        storedHash.isNotEmpty() && storedEpoch == liveEpoch && storedHash == liveHash

    fun contractHash(tool: ExtensionToolPin): String = AgentTaskStore.hash(
        listOf(tool.name, tool.schema.toString(), tool.verification, tool.imageSha256 ?: "",
            tool.scopes.joinToString(";") { "${it.pointer}:${it.kind}:${it.values.joinToString("|")}" }
        ).joinToString("\u0000"))
}

internal class ExtensionToolGrantStore(context: Context) :
    SQLiteOpenHelper(context, context.noBackupFilesDir.resolve("extension-tool-grants-v1.db").absolutePath, null, 2),
    AutoCloseable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tool_grants(owner TEXT NOT NULL, installation_id TEXT NOT NULL, tool_name TEXT NOT NULL, grant_epoch INTEGER NOT NULL, contract_sha256 TEXT NOT NULL, PRIMARY KEY(owner, installation_id, tool_name))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE tool_grants ADD COLUMN contract_sha256 TEXT NOT NULL DEFAULT ''")
        } else error("Unsupported MCP tool-grant schema; preserved")
    }

    fun grant(owner: String, installationId: String, toolName: String, grantEpoch: Long, contractSha256: String) {
        require(owner.isNotBlank() && installationId.isNotBlank() && grantEpoch > 0 && contractSha256.length == 64)
        require(toolName.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO tool_grants(owner,installation_id,tool_name,grant_epoch,contract_sha256) VALUES(?,?,?,?,?)",
            arrayOf<Any>(owner, installationId, toolName, grantEpoch, contractSha256))
    }

    fun revoke(owner: String, installationId: String, toolName: String) {
        writableDatabase.execSQL(
            "DELETE FROM tool_grants WHERE owner=? AND installation_id=? AND tool_name=?",
            arrayOf(owner, installationId, toolName))
    }

    fun drop(owner: String, installationId: String) {
        writableDatabase.execSQL("DELETE FROM tool_grants WHERE owner=? AND installation_id=?", arrayOf(owner, installationId))
    }

    fun allows(owner: String, installationId: String, toolName: String, grantEpoch: Long, contractSha256: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT grant_epoch,contract_sha256 FROM tool_grants WHERE owner=? AND installation_id=? AND tool_name=?",
            arrayOf(owner, installationId, toolName)).use { rows ->
            rows.moveToFirst() && ExtensionToolAdmission.current(rows.getLong(0), rows.getString(1), grantEpoch, contractSha256)
        }

    fun currentNames(owner: String, installationId: String, grantEpoch: Long, tools: List<ExtensionToolPin>): Set<String> =
        tools.filter { allows(owner, installationId, it.name, grantEpoch, ExtensionToolAdmission.contractHash(it)) }.map { it.name }.toSet()
}
