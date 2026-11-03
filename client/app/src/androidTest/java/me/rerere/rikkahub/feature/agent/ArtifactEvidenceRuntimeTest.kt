package me.rerere.rikkahub.feature.agent

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real owned files at the exact evidence consumer; no provider or alternate executor. */
class ArtifactEvidenceRuntimeTest {
    private suspend fun project(store: MaterialFiles, owner: String, item: MaterialFile): JsonObject {
        val k=GlobalContext.get()
        val planner=TaskPlanner(k.get<ProviderManager>(),store,k.get<LearningMemoryStore>(),
            InstrumentationRegistry.getInstrumentation().targetContext)
        val method=TaskPlanner::class.java.declaredMethods.single {it.name=="artifactEvidence"}.apply {isAccessible=true}
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            method.invoke(planner,owner,VerifiedStep("output","render_math_plot","synthetic-call","VERIFIED",null),item,continuation)
        }
    }
    private suspend fun fixture(image: Boolean, check: suspend (MaterialFiles,String,MaterialFile)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val owner=UUID.randomUUID().toString();val store=MaterialFiles(context);val id=UUID.randomUUID().toString()
        val item=store.runTask(MaterialFile(id,owner,if(image)"public.png" else "public.txt",if(image)"png" else "txt",output=true)) {file ->
            if(image) {
                val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
                try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally {bitmap.recycle()}
            } else file.writeText("Actual public text evidence")
            "Synthetic owned output"
        }
        try {check(store,owner,item)} finally {store.delete(owner,id,item.sha256);store.purge(owner,id)}
    }
    @Test fun supplementalPngIsBoundBinaryEvidenceAndNeverPretendsToBeText(): Unit = runBlocking {
        fixture(true) {store,owner,item ->
            val value=project(store,owner,item)
            assertEquals(JsonPrimitive(item.sha256),value["sha256"])
            assertEquals(JsonNull,value["content"])
            assertEquals(JsonPrimitive("NOT_EVALUATED"),value["contentVerification"])
            assertEquals(JsonPrimitive("BINARY_ONLY_VISUAL_CONTENT_NOT_EVALUATED"),value["coverage"])
        }
    }
    @Test fun ordinaryOwnedTextStillHasItsActualContent(): Unit = runBlocking {
        fixture(false) {store,owner,item ->assertEquals(JsonPrimitive("Actual public text evidence"),project(store,owner,item)["content"])}
    }
    @Test fun binaryProjectionCannotBypassOwnerOrActualHash(): Unit = runBlocking {
        fixture(true) {store,owner,item ->
            assertTrue(runCatching {project(store,UUID.randomUUID().toString(),item)}.isFailure)
            store.file(item).appendBytes(byteArrayOf(1))
            assertTrue(runCatching {project(store,owner,item)}.isFailure)
        }
    }
}
