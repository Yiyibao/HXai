package me.rerere.rikkahub.data.files

/** Describes actual host capabilities, not a promise that an imported workflow can run. */
object SkillCompatibility {
    data class Status(val state: String, val detail: String, val files: List<String>)
    private val text = setOf("md", "txt", "json", "yaml", "yml", "toml", "csv", "py", "js", "sh", "ps1", "r")
    private val assets = setOf("docx", "xlsx", "pptx", "pdf", "png", "jpg", "jpeg", "webp")
    fun inspect(skill: SkillMetadata): Status {
        val seen = mutableSetOf<String>()
        val files = skill.skillDir.walkTopDown().onEnter { directory -> seen.add(directory.canonicalPath) && (directory.canonicalPath.startsWith(skill.skillDir.canonicalPath + java.io.File.separator) || directory.canonicalFile == skill.skillDir.canonicalFile) }.filter { it.isFile && SkillPaths.resolveSkillFile(skill.skillDir, it.relativeTo(skill.skillDir).invariantSeparatorsPath) != null }.take(SkillImportLimits.ENTRIES + 1).map { it.relativeTo(skill.skillDir).invariantSeparatorsPath }.toList()
        val instructions = SkillPaths.resolveSkillFile(skill.skillDir, "SKILL.md")?.inputStream()?.use { SkillImportLimits.read(it, SkillImportLimits.MARKDOWN_BYTES) }?.toString(Charsets.UTF_8).orEmpty()
        val scripts = files.any { it.startsWith("scripts/") || it.substringAfterLast('.', "") in setOf("py", "js", "sh", "ps1", "r") }
        val commands = Regex("(?i)\\b(python[0-9.]*|node|npm|npx|bash|pandoc|soffice|libreoffice|docker|uv|pip)\\b").containsMatchIn(listOfNotNull(skill.compatibility, skill.allowedTools, instructions).joinToString("\n"))
        val unknownAssets = files.any { it.startsWith("assets/") && it.substringAfterLast('.', "").lowercase() !in text + assets }
        val nameValid = skill.name.length in 1..64 && skill.name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && skill.name == skill.skillDir.name
        val detail = buildList {
            add("可按需加载说明、文本参考资料；Office/PDF/图片资产可导入当前档案并由内置文件工具处理，能力以工具声明为准。")
            if (scripts || commands) add("依赖缺失：当前移动客户端不能执行Python/Node/Shell或桌面命令；可读取脚本源码，完整社区流程未运行。")
            if (unknownAssets) add("部分资产格式不能读取或执行，导入不等于支持。")
            if (!nameValid) add("名称不符合Agent Skills约定；为兼容既有技能仍保留。")
            if (!skill.allowedTools.isNullOrBlank()) add("allowed-tools仅作声明显示，不授予额外权限或绕过用户上传限制。")
        }.joinToString("\n")
        return Status(if (scripts || commands || unknownAssets || !nameValid) "PARTIAL" else "READY", detail, files)
    }
}
