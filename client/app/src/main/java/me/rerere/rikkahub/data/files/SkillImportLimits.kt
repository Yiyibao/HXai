package me.rerere.rikkahub.data.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Keep all retained import bytes bounded before any installed skill is replaced. */
internal object SkillImportLimits {
    const val INPUT_BYTES = 8 * 1024 * 1024
    const val FILE_BYTES = 4 * 1024 * 1024
    const val TOTAL_BYTES = 8 * 1024 * 1024
    const val MARKDOWN_BYTES = 256 * 1024
    const val API_BYTES = 1024 * 1024
    const val ENTRIES = 128
    const val DEPTH = 8

    class LimitException : IllegalArgumentException("技能安装内容超出大小、文件数量或目录深度上限")

    fun read(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            if (count > limit - output.size()) throw LimitException()
            output.write(buffer, 0, count)
        }
    }

    fun path(value: String): String {
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/') && !normalized.contains(':') && !normalized.contains('\u0000')) { "技能安装路径无效" }
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "技能安装路径无效" }
        if (parts.size > DEPTH || normalized.length > 1024) throw LimitException()
        return parts.joinToString("/")
    }

    fun zip(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size > INPUT_BYTES) throw LimitException()
        val files = LinkedHashMap<String, ByteArray>()
        var entries = 0
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (++entries > ENTRIES) throw LimitException()
                val name = path(entry.name)
                if (entry.isDirectory) {
                    // Even a malformed directory must not be drained without a bound.
                    read(input, 0)
                } else {
                    require(name !in files) { "技能安装包含重复文件" }
                    val singleLimit = if (name.substringAfterLast('/').equals("SKILL.md", true)) MARKDOWN_BYTES else FILE_BYTES
                    val content = read(input, minOf(singleLimit, TOTAL_BYTES - total))
                    total += content.size
                    files[name] = content
                }
                // Only drain/close after a bounded read completed. On rejection, close the
                // archive itself so closeEntry cannot inflate the remainder of a ZIP bomb.
                input.closeEntry()
            }
        }
        return files
    }
}
