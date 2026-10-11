package me.rerere.rikkahub.service

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WarningFixGalleryTest {
    @Test fun galleryWritesExactBytesAndRemovesFailedRows() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val names = (1..3).map { "QA-warning-gallery-${UUID.randomUUID()}.png" }
        fun find(name: String): android.net.Uri? = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null
        )?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()) }
        val source = File(context.cacheDir, "QA-warning-${UUID.randomUUID()}.png")
        val missing = File(context.cacheDir, "QA-warning-missing-${UUID.randomUUID()}.png")
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        try {
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    context.exportImageFile(activity, source, names[0])
                    context.exportImage(activity, bitmap, names[1])
                    context.exportImageFile(activity, missing, names[2])
                }
            }
            for (name in names.take(2)) {
                val target = requireNotNull(find(name)) { "Gallery output missing" }
                val delivered = resolver.openInputStream(target)!!.use { it.readBytes() }
                assertEquals("Exact source/delivery digest: $name", digest(source.readBytes()), digest(delivered))
            }
            assertNull("Failed source must leave no empty MediaStore row", find(names[2]))
        } finally {
            names.forEach { name -> find(name)?.let { resolver.delete(it, null, null) } }
            source.delete()
            bitmap.recycle()
        }
    }
}
