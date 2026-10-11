from pathlib import Path
p=Path(__file__).resolve().parents[2]/'client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt'
s=p.read_text(encoding='utf-8'); start=s.index('        val deleted = removeTree(skillDir)'); end=s.index('\n    /**',start)
s=s[:start]+'''        // Keep a complete rollback tree before a deletion can partially consume files.
        val root = getSkillsDir()
        val backup = createTempSkillDir(root, name, "uninstall-backup") ?: return@withContext false
        if (!copySkillTree(skillDir, backup)) {
            removeTree(backup)
            return@withContext false
        }
        val trash = createTempSkillDir(root, name, "uninstall") ?: run {
            removeTree(backup)
            return@withContext false
        }
        if (!renameTree(skillDir, trash)) {
            removeTree(trash); removeTree(backup)
            return@withContext false
        }
        var committed = false
        try {
            if (!removeTree(trash)) {
                restoreBackup(backup, skillDir)
                return@withContext false
            }
            // An overriding user skill can be removed without disabling its builtin.
            if (listBuiltinSkills().none { it.name == name }) {
                settingsStore.update { settings ->
                    settings.copy(assistants = settings.assistants.map { assistant ->
                        assistant.copy(enabledSkills = assistant.enabledSkills - name)
                    })
                }
            }
            committed = true
            true
        } catch (error: Exception) {
            restoreBackup(backup, skillDir)
            Log.w(TAG, "Skill uninstall rolled back")
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        } finally {
            if (committed) removeTree(backup)
            if (skillDir.exists()) removeTree(trash)
        }
    }
''' + s[end:]
p.write_text(s,encoding='utf-8')
