package me.rerere.rikkahub.feature.imagecreation

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageTemplatesRuntimeTest {
    @Test fun persistenceEditingDeletionAndProfileIsolation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = context.cacheDir.resolve("template-test-${UUID.randomUUID()}")
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        try {
            val store = ImageTemplates(root)
            val item = store.save(a, null, "纸艺封面", "主题：{主题}。分层纸艺，留白构图。", "1536x1024", "high")
            val reopened = ImageTemplates(root)
            assertEquals(item, reopened.list(a).single())
            assertTrue(reopened.list(b).isEmpty())
            assertTrue(runCatching { reopened.save(b, item.id, "越界", item.prompt, item.size, item.quality) }.isFailure)
            assertTrue(runCatching { reopened.delete(b, item.id) }.isFailure)
            assertTrue(runCatching { reopened.save(a, null, "无变量", "固定内容", item.size, item.quality) }.isFailure)
            assertTrue(runCatching { reopened.delete(a, "../escape") }.isFailure)
            reopened.save(a, item.id, "更新的模板", item.prompt, "1024x1024", "low")
            assertEquals("更新的模板", reopened.list(a).single().name)
            assertEquals("low", reopened.list(a).single().quality)
            reopened.delete(a, item.id)
            assertTrue(ImageTemplates(root).list(a).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
