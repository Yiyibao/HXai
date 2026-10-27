package me.rerere.rikkahub.data.ai.tools

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.search.SearchImage
import me.rerere.search.VerifiedWebImage
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class OwnedChatImagesTest {
    @Test fun otherOwnerAndTamperedImagesCannotReachRenderer(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val materials = koin.get<MaterialFiles>()
        val images = koin.get<ChatImages>()
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val bytes = try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() }
            finally { bitmap.recycle() }
        try {
            val saved = persistWebImage(VerifiedWebImage(SearchImage("https://source.example/image.png"),8,8,"image/png","fixture",bytes),materials,owner)
            val file = materials.verifiedFile(owner,saved.id)
            images.resolve(owner,file.toUri().toString(),checkAllowed={})
            assertTrue(runCatching { images.resolve(other,file.toUri().toString(),checkAllowed={}) }.isFailure)
            val changed = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(changed)
            assertTrue(runCatching { images.resolve(owner,file.toUri().toString(),checkAllowed={}) }.isFailure)
        } finally {
            context.filesDir.resolve("materials/$owner").deleteRecursively()
            context.filesDir.resolve("materials/$other").deleteRecursively()
        }
    }

    @Test fun privateAndArbitraryLocalImagesNeverPublishImageParts(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val materials = GlobalContext.get().get<MaterialFiles>()
        val owner = UUID.randomUUID().toString()
        try {
            val tool = displayImageTool(materials,owner)
            listOf("http://127.0.0.1/image.png", "http://169.254.169.254/image.png",
                "http://[::1]/image.png", "file:///private/photo.png", "javascript:alert(1)").forEach { source ->
                val result = tool.execute(kotlinx.serialization.json.buildJsonObject {
                    put("url", kotlinx.serialization.json.JsonPrimitive(source))
                })
                assertTrue(source, result.none { it is me.rerere.ai.ui.UIMessagePart.Image })
            }
        } finally { context.filesDir.resolve("materials/$owner").deleteRecursively() }
    }
}
