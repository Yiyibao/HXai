package io.requery.android.database.sqlite

/** Adapter for the bundled driver's COMMIT error path; pin recovery to the writer. */
internal fun SQLiteDatabase.rollbackFailedNativeCommit() {
    // A nested transaction error must leave its still-active outer transaction alone.
    if (threadSession.hasTransaction()) return
    // The driver has released its writer and popped the Java transaction.
    // Session.execute intercepts ROLLBACK and requires that missing transaction.
    // Recover on the native writer while Room still holds its transaction lock.
    val field = SQLiteSession::class.java.getDeclaredField("mConnectionPool")
    field.isAccessible = true
    val pool = field.get(threadSession) as SQLiteConnectionPool
    val writer = pool.acquireConnection(
        "ROLLBACK", SQLiteConnectionPool.CONNECTION_FLAG_PRIMARY_CONNECTION_AFFINITY, null,
    )
    try { writer.execute("ROLLBACK", null, null) }
    finally { pool.releaseConnection(writer) }
}
