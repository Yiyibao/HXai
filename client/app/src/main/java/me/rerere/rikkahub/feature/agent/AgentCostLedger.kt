package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage

internal object AgentCostLedger {
    fun create(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS usage_costs(request_id TEXT PRIMARY KEY REFERENCES usage(request_id),basis TEXT NOT NULL,receipt TEXT)")
    fun begin(db: SQLiteDatabase,id: String,basis: JsonObject?) {
        db.execSQL("INSERT INTO usage_costs(request_id,basis) VALUES(?,?)",arrayOf(id,(basis ?: TrustedCost.unknown()).toString()))
    }
    fun finish(db: SQLiteDatabase,id: String,usage: TokenUsage?) {
        val existing=db.rawQuery("SELECT basis,receipt FROM usage_costs WHERE request_id=?",arrayOf(id)).use {
            if(!it.moveToFirst()) return // Historical physical request: never invent a current tariff.
            it.getString(0) to it.getString(1)
        }
        val receipt=TrustedCost.calculate(Json.parseToJsonElement(existing.first).jsonObject,usage).toString()
        require(existing.second==null || existing.second==receipt) { "Conflicting cost receipt" }
        if(existing.second==null) check(db.compileStatement("UPDATE usage_costs SET receipt=? WHERE request_id=? AND receipt IS NULL").use {
            it.bindString(1,receipt);it.bindString(2,id);it.executeUpdateDelete()
        }==1)
    }
    fun receipt(db: SQLiteDatabase,id: String): JsonObject = db.rawQuery("SELECT receipt FROM usage_costs WHERE request_id=?",arrayOf(id)).use {
        if(!it.moveToFirst() || it.isNull(0)) TrustedCost.unknown() else Json.parseToJsonElement(it.getString(0)).jsonObject
    }
    // Caller supplies only host-authored SQL and owner-filtered arguments inside the Store transaction.
    fun summary(db: SQLiteDatabase,where: String,args: List<String>): JsonObject = db.rawQuery(
        "SELECT x.receipt FROM usage u JOIN tasks t ON t.id=u.task_id LEFT JOIN usage_costs x ON x.request_id=u.request_id WHERE $where",args.toTypedArray()).use { c ->
        TrustedCost.aggregate(sequence {
            while(c.moveToNext()) yield(if(c.isNull(0)) TrustedCost.unknown() else Json.parseToJsonElement(c.getString(0)).jsonObject)
        }.asIterable())
    }
}
