package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Migration_32_33_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun actualExportedSchema32MigratesWithoutChangingOldRows() {
        val name = "qa-room32-${UUID.randomUUID()}"
        helper.createDatabase(name, 32).use { old ->
            old.execSQL("INSERT INTO learning_memory_state(profile_id,payload) VALUES ('owner', '{\"fingerprintVersion\":1,\"schemaVersion\":2,\"recoveryBlocked\":true,\"legacySuppressedSources\":[\"opaque\"]}')")
            old.execSQL("INSERT INTO ConversationDeletionEntity(id,profileId,revision,deleted) VALUES ('deleted','owner',7,1)")
        }
        helper.runMigrationsAndValidate(name, 33, true).use { migrated ->
            migrated.query("SELECT payload FROM learning_memory_state WHERE profile_id='owner'").use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("opaque"))
            }
            migrated.query("SELECT revision,deleted FROM ConversationDeletionEntity WHERE id='deleted'").use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)); assertEquals(1, it.getInt(1))
            }
            migrated.query("SELECT COUNT(*) FROM derived_metadata_evidence").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }
}
