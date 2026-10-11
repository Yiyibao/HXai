package me.rerere.rikkahub.service

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

class IndependentAssertionProbeTest {
    private val root = File(System.getProperty("qa.root"))
    private fun doc(name: String, runs: String): File = File(root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl><w:tr><w:tc><w:p>$runs</w:p></w:tc></w:tr></w:tbl></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
    }
    @Test fun tableAssertionMustRejectInsertedTabAndLineBreak() {
        val source = doc("cell-source.docx", "<w:r><w:t>AB</w:t></w:r>")
        for (marker in listOf("tab", "br")) {
            val changed = doc("cell-$marker.docx", "<w:r><w:t>A</w:t><w:$marker/><w:t>B</w:t></w:r>")
            assertTrue("Inserted $marker changes cell text and must be rejected", runCatching { assertWordTablesMatch(source, changed) }.isFailure)
        }
    }
    @Test fun tableAssertionAcceptsRunFormattingAndOrderedControls() {
        val source = doc("ordered-source.docx", "<w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r>")
        val equivalent = doc("ordered-equivalent.docx", "<w:r><w:rPr><w:b/></w:rPr><w:t>A</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>B</w:t><w:cr/></w:r><w:r><w:t>C</w:t></w:r>")
        assertWordTablesMatch(source, equivalent)
        assertEquals(listOf(listOf(listOf("A\tB\nC"))), wordTableCells(source))
        val wrong = doc("ordered-wrong.docx", "<w:r><w:tab/><w:t>AB</w:t><w:br/><w:t>C</w:t></w:r>")
        assertTrue(runCatching { assertWordTablesMatch(source, wrong) }.exceptionOrNull() is AssertionError)
        assertWordTablesMatch(doc("split-source.docx", "<w:r><w:t>AB</w:t></w:r>"), doc("split-target.docx", "<w:r><w:t>A</w:t></w:r><w:r><w:t>B</w:t></w:r>"))
        assertWordTablesMatch(doc("paragraph-source.docx", "<w:r><w:t>A</w:t><w:br/><w:t>B</w:t></w:r>"), doc("paragraph-target.docx", "<w:r><w:t>A</w:t></w:r></w:p><w:p><w:r><w:t>B</w:t></w:r>"))
    }
    @Test fun approvalReadsQueueAfterBlockedMutationAndDoesNotPublishFailedCommit() = runBlocking {
        val initial = Conversation.ofId(Uuid.random()); val s = ConversationSession(initial.id, initial, this, {})
        s.initialize { initial }; s.updateQueue({}) { it.enqueue(listOf(UIMessagePart.Text("first"))) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val approvalStarted = CompletableDeferred<Unit>(); val snapshots = mutableListOf<Conversation>()
        val queueJob = launch(start = CoroutineStart.UNDISPATCHED) { s.updateQueue({ entered.complete(Unit); release.await() }) { it.enqueue(listOf(UIMessagePart.Text("second"))); it.pause() } }
        entered.await()
        val approvalJob = launch(start = CoroutineStart.UNDISPATCHED) { approvalStarted.complete(Unit); s.commitToolApproval({ it.copy(title = "accepted") }, { snapshots += it }) }
        approvalStarted.await(); assertTrue(snapshots.isEmpty()); assertEquals(1, s.messageQueue.persisted().messages.size)
        release.complete(Unit); queueJob.join(); approvalJob.join()
        assertEquals(2, snapshots.single().queue.messages.size); assertTrue(snapshots.single().queue.paused)
        val before = s.state.value; val original = IllegalStateException("QA original")
        val thrown = requireNotNull(runCatching { s.commitToolApproval({ it.copy(title = "not committed") }, { throw original }) }.exceptionOrNull()); assertTrue(generateSequence(thrown) { it.cause }.any { it === original })
        assertEquals(before, s.state.value); s.cleanup()
    }
    @Test fun queueEditEnqueueAndPauseSerializeWithApproval() = runBlocking {
        val c = Conversation.ofId(Uuid.random()); val s = ConversationSession(c.id, c, this, {}); s.initialize { c }
        s.updateQueue({}) { it.enqueue(listOf(UIMessagePart.Text("first"))) }
        val id = s.messageQueue.state.value.messages.single().id
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val snapshots = mutableListOf<Conversation>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) { s.updateQueue({ entered.complete(Unit); release.await() }) { it.enqueue(listOf(UIMessagePart.Text("second"))) } }
        entered.await(); s.messageQueue.beginEdit(id)
        val editor = launch(start = CoroutineStart.UNDISPATCHED) { s.updateQueue({}) { it.finishEdit(id, listOf(UIMessagePart.Text("edited"))); it.pause() } }
        val approval = launch(start = CoroutineStart.UNDISPATCHED) { s.commitToolApproval({ it.copy(title="accepted") }, { snapshots += it }) }
        assertTrue(snapshots.isEmpty()); assertTrue(s.messageQueue.state.value.messages.first().isEditing)
        release.complete(Unit); writer.join(); editor.join(); approval.join()
        val queue = snapshots.single().queue; assertTrue(queue.paused); assertEquals(2, queue.messages.size)
        assertEquals(listOf(UIMessagePart.Text("edited")), queue.messages.first().parts); assertFalse(s.messageQueue.state.value.messages.first().isEditing); s.cleanup()
    }
}

