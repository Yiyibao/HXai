package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MathWorkCandidateRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun request(fixture: MathParseCandidateFixture, candidate: JsonObject, confirmed: Boolean, correction: String = "x+x=2*x") = buildJsonObject {
        put("title", "原候选与用户修订分开")
        put("pages", buildJsonArray { add(buildJsonObject {
            put("id", fixture.source.id); put("sha256", fixture.source.sha256); put("number", 1)
            put("transcription", correction); put("ambiguities", if (confirmed) buildJsonArray {} else candidate.getValue("ambiguities"))
            put("candidate", candidate.getValue("reference"))
        }) })
    }
    @Test fun correctedCandidateSurvivesCacheRemovalButNeverSourceRevocation(): Unit = runBlocking {
        val fixture = MathParseCandidateFixture(context)
        try {
            fixture.prepare()
            val inputs = fixture.files.mathWorkInputs
            val candidate = inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId)
            val draftInput = request(fixture, candidate, false)
            val draft = inputs.create(fixture.owner, draftInput.toString(), false, {})
            assertEquals(JsonPrimitive("WAITING_INPUT"), draft["status"])
            assertTrue(runCatching { inputs.create(fixture.owner, draftInput.toString(), true, {}) }.isFailure)
            val confirmed = inputs.create(fixture.owner, request(fixture, candidate, true).toString(), true, {})
            val id = confirmed.getValue("work_id").jsonPrimitive.content
            assertEquals(JsonPrimitive(2), confirmed["contractVersion"])
            assertEquals(JsonPrimitive("NOT_EVALUATED"), confirmed["mathematics_status"])
            assertEquals(JsonPrimitive("x+x=2*x"), confirmed.getValue("pages").jsonArray[0].jsonObject["transcription"])
            assertEquals(JsonPrimitive("x+x=3*x"), confirmed.getValue("candidate_evidence").jsonArray[0].jsonObject["transcription"])
            assertEquals(buildJsonArray { add(2) }, confirmed.getValue("candidate_evidence").jsonArray[0].jsonObject.getValue("coverage").jsonObject["failed_pages"])
            fixture.directory.deleteRecursively()
            val reopened = MaterialFiles(context).mathWorkInputs.read(fixture.owner, id)
            assertEquals(confirmed, reopened)
            assertTrue(runCatching { inputs.read(UUID.randomUUID().toString(), id) }.isFailure)
            assertEquals(fixture.source.sha256, MaterialFiles.digest(fixture.files.verifiedFile(fixture.owner, fixture.source.id)))
            fixture.files.delete(fixture.owner, fixture.source.id, fixture.source.sha256)
            assertTrue(runCatching { inputs.read(fixture.owner, id) }.isFailure)
            fixture.files.restore(fixture.owner, fixture.source.id, {})
            assertTrue(runCatching { inputs.read(fixture.owner, id) }.isFailure)
        } finally { fixture.close() }
    }
    @Test fun failedBlankForeignAndTamperedCandidateCannotBeConfirmed(): Unit = runBlocking {
        val fixture = MathParseCandidateFixture(context)
        try {
            fixture.prepare()
            val inputs = fixture.files.mathWorkInputs
            for (page in listOf(2, 3)) assertTrue(runCatching {
                inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, page, fixture.taskId)
            }.isFailure)
            assertTrue(runCatching { inputs.parseCandidate(UUID.randomUUID().toString(), fixture.source.id, fixture.source.sha256, 1, fixture.taskId) }.isFailure)
            val candidate = inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId)
            val ref = candidate.getValue("reference").jsonObject
            val forged = JsonObject(candidate + ("reference" to JsonObject(ref + ("candidate_sha256" to JsonPrimitive("0".repeat(64))))))
            assertTrue(runCatching { inputs.create(fixture.owner, request(fixture, forged, true).toString(), true, {}) }.isFailure)
            val stale = JsonObject(candidate + ("reference" to JsonObject(ref + ("result_revision" to JsonPrimitive(2)))))
            assertTrue(runCatching { inputs.create(fixture.owner, request(fixture, stale, true).toString(), true, {}) }.isFailure)
            assertTrue(runCatching { inputs.create(fixture.owner, request(fixture, candidate, true).toString(), true, { error("old context epoch") }) }.isFailure)
            File(fixture.directory, "${fixture.taskId}.result/document").appendText(" ")
            assertTrue(runCatching { inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId) }.isFailure)
        } finally { fixture.close() }
    }
    @Test fun oversizedPageIsRejectedWithoutTruncatingOrOverwritingManualDraft(): Unit = runBlocking {
        val fixture = MathParseCandidateFixture(context)
        try {
            fixture.prepare("x".repeat(4001))
            val manual = buildJsonObject {
                put("title", "已有手工草稿")
                put("pages", buildJsonArray { add(buildJsonObject {
                    put("id", fixture.source.id); put("sha256", fixture.source.sha256); put("number", 1)
                    put("transcription", "x+x=2*x"); put("ambiguities", buildJsonArray {})
                }) })
            }
            val inputs = fixture.files.mathWorkInputs
            val old = inputs.create(fixture.owner, manual.toString(), false, {})
            assertTrue(runCatching { inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId) }.isFailure)
            assertEquals(old, MaterialFiles(context).mathWorkInputs.read(fixture.owner, old.getValue("work_id").jsonPrimitive.content))
            assertEquals(JsonPrimitive(1), old["contractVersion"])
        } finally { fixture.close() }
    }
    @Test fun persistedCancellationStateBlocksNewCandidatesWithoutErasingConfirmedWork(): Unit = runBlocking {
        val fixture = MathParseCandidateFixture(context)
        try {
            fixture.prepare()
            val inputs = fixture.files.mathWorkInputs
            val candidate = inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId)
            val confirmed = inputs.create(fixture.owner, request(fixture, candidate, true).toString(), true, {})
            val id = confirmed.getValue("work_id").jsonPrimitive.content
            val taskFile = File(fixture.directory, "${fixture.taskId}.task.json")
            val original = Json.decodeFromString<DocumentTask>(taskFile.readText())
            for (state in listOf("CANCELLING", "CANCELLED")) {
                // Older/remote state snapshots need not have the separate local request bit.
                taskFile.writeText(Json.encodeToString(original.copy(state = state, cancelRequested = false)))
                assertEquals(confirmed, inputs.read(fixture.owner, id))
                assertTrue("Cancelled state $state exposed in candidate listing",
                    inputs.parseResults(fixture.owner, fixture.source.id, fixture.source.sha256, 0)
                        .getValue("results").jsonArray.isEmpty())
                assertTrue("Cancelled state $state returned a new candidate", runCatching {
                    inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId)
                }.isFailure)
                assertTrue("Cancelled state $state allowed new confirmation", runCatching {
                    inputs.create(fixture.owner, request(fixture, candidate, true, "2*x=x+x").toString(), true, {})
                }.isFailure)
            }
            taskFile.writeText(Json.encodeToString(original.copy(cancelRequested = true)))
            assertTrue(runCatching {
                inputs.parseCandidate(fixture.owner, fixture.source.id, fixture.source.sha256, 1, fixture.taskId)
            }.isFailure)
            assertEquals(confirmed, inputs.read(fixture.owner, id))
            assertEquals(fixture.source.sha256, MaterialFiles.digest(fixture.files.verifiedFile(fixture.owner, fixture.source.id)))
        } finally { fixture.close() }
    }
}
