package me.rerere.rikkahub.data.sync

/** Tool grants, task journals and credential files live under noBackup and must not reappear in a restore. */
internal object BackupExclusions {
    private val names = listOf(
        "extension-tool-grants-v1.db",
        "extensions-v1.db",
        "extension-oauth-v1.db",
        "credentials-v1",
        "agent-tasks-v1.db",
        "ocr-cache-v1.json",
    )

    fun rejects(relative: String): Boolean = names.any { it in relative.replace('\\', '/') }
}
