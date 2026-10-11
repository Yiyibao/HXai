package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeliveryClosureTest {
    @Test fun cancellationCorruptionAndTargetRereadRejectFalseSuccess() = runBlocking {
        val evidence = QaEvidenceRun("DeliveryClosureTest.cancellationCorruptionAndTargetRereadRejectFalseSuccess"); val store = GlobalContext.get().get<MaterialFiles>(); val owner = UUID.randomUUID().toString()
        val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "QA-${evidence.runId}.md", "md", true)) { it.writeBytes(ByteArray(32768) { 65 }); "synthetic" }
        val source = store.verifiedFile(owner, item.id); val target = evidence.file("target.md"); val report = StringBuilder(); var status = "FAILED"
        try {
            for (kind in listOf("null", "throw", "wrong-bytes")) {
                val result = runCatching { store.saveVerifiedUsing(owner, item.id, target.toUri(), { target.outputStream() }, {
                    when(kind) { "null" -> null; "throw" -> throw java.io.IOException("QA target re-read"); else -> "wrong".byteInputStream() }
                }) }
                assertTrue("$kind must not report success", result.isFailure); assertEquals(item.sha256, MaterialFiles.digest(source)); report.append("reread-$kind rejected=true sourceUnchanged=true\n")
            }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val copy = launch(Dispatchers.IO) {
                store.saveVerifiedUsing(owner, item.id, target.toUri(), { object : java.io.FilterOutputStream(target.outputStream()) {
                    override fun write(bytes: ByteArray, offset: Int, count: Int) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); out.write(bytes, offset, count) }
                } }, { target.inputStream() })
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) }); copy.cancel(); release.countDown(); copy.join(); assertTrue(copy.isCancelled); assertEquals(item.sha256, MaterialFiles.digest(source)); report.append("midCopyCancellation=true sourceUnchanged=true\n")
            target.delete()
            assertTrue(runCatching { store.saveVerifiedUsing(owner,item.id,target.toUri(),{target.outputStream()},{target.inputStream()},{false}) }.isFailure)
            assertFalse("Changed profile is rejected before opening destination",target.exists())
            val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
            val copyEntered = CountDownLatch(1); val copyRelease = CountDownLatch(1)
            val switched = async(Dispatchers.IO) { runCatching {
                store.saveVerifiedUsing(owner,item.id,target.toUri(),{ object : java.io.FilterOutputStream(target.outputStream()) {
                    override fun write(bytes:ByteArray,offset:Int,count:Int) { copyEntered.countDown(); check(copyRelease.await(10,TimeUnit.SECONDS)); out.write(bytes,offset,count) }
                } },{target.inputStream()},{allowed.get()})
            } }
            assertTrue(withContext(Dispatchers.IO) { copyEntered.await(10,TimeUnit.SECONDS) })
            allowed.set(false); copyRelease.countDown(); assertTrue(switched.await().isFailure)
            assertEquals(item.sha256,MaterialFiles.digest(source)); report.append("profileSwitchBeforeOpen=true midCopyProfileSwitchRejected=true sourceUnchanged=true\n")
            source.appendText("corrupt")
            target.delete(); assertTrue(runCatching { store.saveVerified(owner,item.id,target.toUri()) }.isFailure); assertFalse(target.exists()); report.append("corruptSource rejectedBeforeWrite=true\n")
            status = "PASS"
        } finally {
            require(source.parentFile!!.name == owner); source.delete(); File(source.parentFile, "${item.id}.meta.json").delete(); source.parentFile!!.delete()
            evidence.file("assertions.txt").writeText(report.toString()); evidence.finish(status)
        }
    }
}
