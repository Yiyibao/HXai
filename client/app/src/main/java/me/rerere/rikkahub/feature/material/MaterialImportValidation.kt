package me.rerere.rikkahub.feature.material

import java.io.File
import java.nio.charset.CodingErrorAction

internal object MaterialImportValidation {
    fun check(file: File, extension: String) {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        fun magic(vararg values: Int) = count >= values.size && values.indices.all { (header[it].toInt() and 255) == values[it] }
        when (extension) {
            "docx", "xlsx", "pptx" -> {
                val parts = OfficePackage.read(file)
                val root = when (extension) { "docx" -> "word/document.xml"; "xlsx" -> "xl/workbook.xml"; else -> "ppt/presentation.xml" }
                require(root in parts) { "文件内容与Office格式不匹配" }
                SafeOfficeXml.parse(parts.getValue(root))
            }
            "pdf" -> require(magic(37, 80, 68, 70, 45)) { "不是PDF文件" }
            "png" -> require(magic(137, 80, 78, 71, 13, 10, 26, 10)) { "不是PNG图片" }
            "jpg", "jpeg" -> require(magic(255, 216, 255)) { "不是JPEG图片" }
            "webp" -> require(magic(82, 73, 70, 70) && count >= 12 && header[8] == 87.toByte() && header[9] == 69.toByte() && header[10] == 66.toByte() && header[11] == 80.toByte()) { "不是WebP图片" }
            "doc", "xls", "ppt" -> require(magic(208, 207, 17, 224, 161, 177, 26, 225)) { "不是旧版二进制Office文件" }
            else -> {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                java.io.InputStreamReader(file.inputStream(), decoder).use { input ->
                    val buffer = CharArray(8192)
                    while (input.read(buffer) >= 0) { /* Validate without retaining another content copy. */ }
                }
            }
        }
        if (extension in setOf("png", "jpg", "jpeg", "webp")) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 64L * 1024 * 1024) { "图片损坏或解码尺寸超过安全上限" }
        }
    }
}
