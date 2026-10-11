package me.rerere.rikkahub.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.rollbackFailedNativeCommit

/** Roll back a failed native COMMIT without replaying the write. */
internal class RollbackSafeOpenHelperFactory(
    private val factory: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = factory.create(configuration)
        fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase =
            object : SupportSQLiteDatabase by database {
                override fun endTransaction() {
                    try {
                        database.endTransaction()
                    } catch (error: Throwable) {
                        // The bundled requery driver pops its transaction stack before
                        // COMMIT, and releases the native connection even if COMMIT fails.
                        // Keep Room's cached connection valid while rolling back on the
                        // native writer; closing it would invalidate that cache.
                        try { (database as io.requery.android.database.sqlite.SQLiteDatabase).rollbackFailedNativeCommit() }
                        catch (rollbackError: Throwable) { error.addSuppressed(rollbackError) }
                        throw error
                    }
                }
            }
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase: SupportSQLiteDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
