package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** Reading evidence and user transcription are not OCR accuracy or a mathematical proof. */
object MaterialMathEvidence {
    suspend fun source(files: MaterialFiles, owner: String, id: String, sha256: String, offset: Int, count: Int): JsonObject {
        require(offset >= 0 && count in 1..4000)
        val item = files.get(owner, id)
        check(item.sha256 == sha256)
        files.verifiedFile(owner, id)
        val image = item.extension in setOf("png", "jpg", "jpeg", "webp")
        val page = if (image) null else files.readLibraryTextPage(owner, id, sha256, offset, count)
        val needsConfirmation = image || item.symbolCheckPending || (page?.get("pages_without_extractable_text") as? JsonArray)?.isNotEmpty() == true
        val confirmed = item.mathInputConfirmed && !item.symbolCheckPending && !item.symbolCorrection.isNullOrBlank() && item.symbolSourceSha256 == sha256
        val ready = !needsConfirmation || confirmed
        check(files.get(owner, id) == item) { "来源或符号校对已改变，请重新核对" }
        return buildJsonObject {
            put("contractVersion", 1); put("status", if (ready) "SOURCE_READY" else "WAITING_INPUT")
            put("material_id", id); put("version_id", id); put("source_sha256", sha256)
            put("needs_symbol_confirmation", needsConfirmation); put("user_transcription_confirmed", confirmed)
            put("symbol_correction_scope", if (item.mathInputConfirmed) "MATH_INPUT" else "SYMBOL_ONLY")
            put("user_transcription", if (confirmed) JsonPrimitive(item.symbolCorrection) else JsonNull)
            put("source_page", page ?: JsonNull)
            put("mathematics_status", "NOT_EVALUATED")
            put("notice", "仅为固定真实材料版本的读取/用户转录证据。用户转录不是机器OCR或教材逐字原文；PDF物理页不等于印刷页，DOCX不推断页码。待确认时先问清关键符号，不按猜测批改/判首错。来源匹配不证明数学结论；继续同一Chat Agent，用math_verify合同1–15核验其明确范围，UNSUPPORTED不当错误/成功，未知前缀不定位后续为首错。")
        }
    }
}
