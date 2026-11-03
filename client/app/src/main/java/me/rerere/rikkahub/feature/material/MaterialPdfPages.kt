package me.rerere.rikkahub.feature.material

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

suspend fun renderMaterialPdfPages(store: MaterialFiles, owner: String, id: String, start: Int, count: Int): List<UIMessagePart> = withContext(Dispatchers.IO) {
    val source = store.get(owner, id)
    require(source.extension == "pdf") { "需要 PDF 文件" }
    require(start >= 1 && count in 1..5) { "一次仅处理 1–5 页，页号从 1 开始" }
    ParcelFileDescriptor.open(store.verifiedFile(owner, id), ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
        require(start.toLong() + count - 1 <= renderer.pageCount) { "页范围超出 PDF 实际 ${renderer.pageCount} 页" }
        val result = mutableListOf<UIMessagePart>(UIMessagePart.Text("已渲染物理页 $start–${start + count - 1}，PDF 总共 ${renderer.pageCount} 页；最长边 1536 像素。原图数据，不代表已核验公式或全文。"))
        for (number in start until start + count) {
            currentCoroutineContext().ensureActive()
            renderer.openPage(number - 1).use { page ->
                require(page.width > 0 && page.height > 0)
                val scale = 1536.0 / maxOf(page.width, page.height)
                val bitmap = createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "${source.name} 物理页 $number.png", "png", sourceId = id, pageNumber = number)) { target ->
                        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        "由 PDF 物理页 $number 渲染，原文件 ID=$id；视觉内容尚待核对"
                    }
                    result += UIMessagePart.Image(store.file(item).toUri().toString())
                } finally { bitmap.recycle() }
            }
        }
        result
    } }
}
