package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Accept the earlier development column spelling without losing conversation ownership. */
object Migration_26_27 : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        if ("profile_id" !in columns && "learner_profile_id" in columns) {
            db.execSQL("ALTER TABLE `ConversationEntity` RENAME COLUMN `learner_profile_id` TO `profile_id`")
        } else {
            require("profile_id" in columns && "learner_profile_id" !in columns) {
                "Unsupported conversation ownership schema; original database retained"
            }
        }
        db.execSQL("ALTER TABLE `conversation_folder` ADD COLUMN `profile_id` TEXT NOT NULL DEFAULT 'default-learner'")
    }
}
