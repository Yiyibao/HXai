package me.rerere.rikkahub.feature.material

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class ParsedBlock(val blockId: String, val page: Int, val type: String, val text: String, val latex: String? = null, val assetId: String? = null)
@Serializable data class ParsedDocument(
    val contractVersion: Int, val sourceSha256: String, val resultRevision: Int,
    val parserVersion: String, val adapterVersion: String, val tier: String, val totalPages: Int,
    val requestedRange: List<Int>, val processedPages: List<Int>, val blankPages: List<Int>,
    val failedPages: List<Int>, val complete: Boolean, val warnings: List<String>, val blocks: List<ParsedBlock>,
)
@Serializable data class DocumentAsset(val assetId: String, val path: String, val mediaType: String, val size: Long, val sha256: String)
@Serializable data class DocumentManifest(
    val contractVersion: Int, val sourceSha256: String, val resultRevision: Int,
    val parserVersion: String, val adapterVersion: String, val tier: String, val totalPages: Int,
    val requestedRange: List<Int>, val processedPages: List<Int>, val blankPages: List<Int>,
    val failedPages: List<Int>, val complete: Boolean, val warnings: List<String>, val assets: List<DocumentAsset>,
)

object DocumentContract {
    fun endpoint(value: String): HttpUrl {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "需要无用户名、查询和片段的 HTTPS 服务地址" }
        return url
    }
    fun range(start: Int, end: Int) { require(start in 1..200 && end in start..200 && end - start < 5) { "每次选择 1–5 个物理页，最多 200 页" } }
    fun validate(manifest: DocumentManifest, sha: String, start: Int, end: Int) {
        range(start, end)
        require(manifest.contractVersion == 1 && manifest.sourceSha256 == sha && manifest.tier == "basic" && manifest.resultRevision > 0)
        require(manifest.totalPages in 1..200 && end <= manifest.totalPages && manifest.requestedRange == listOf(start, end))
        val selected = (start..end).toSet()
        require(manifest.processedPages.distinct().size == manifest.processedPages.size && selected.containsAll(manifest.processedPages))
        require(manifest.failedPages.toSet() == selected - manifest.processedPages.toSet())
        require(manifest.processedPages.containsAll(manifest.blankPages))
        require(manifest.complete == (start == 1 && end == manifest.totalPages && manifest.failedPages.isEmpty()))
        require(manifest.assets.size in 1..2000 && manifest.assets.map { it.assetId }.distinct().size == manifest.assets.size)
        require(manifest.assets.sumOf { it.size } <= 64L * 1024 * 1024)
        manifest.assets.forEach {
            require(it.assetId.matches(Regex("[a-z0-9-]{1,64}")) && it.path.matches(Regex("[a-z0-9.-]{1,100}")) && !it.path.contains(".."))
            require(it.size in 1..(64L * 1024 * 1024) && it.sha256.matches(Regex("[a-f0-9]{64}")))
            require(it.mediaType in setOf("application/json", "text/plain", "image/png"))
        }
        require(manifest.assets.any { it.assetId == "document" && it.mediaType == "application/json" && it.size <= 8L * 1024 * 1024 })
    }
}
