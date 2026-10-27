package me.rerere.rikkahub.feature.material

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.feature.math.MathVerification
import me.rerere.rikkahub.feature.math.mathVerifyTool
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MaterialMathQualityRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private val assets get() = instrumentation.context.assets
    private fun output(name: String, value: JsonObject) {
        File(context.cacheDir, "b05-quality-evidence").apply { mkdirs() }.resolve("$name.json").writeText(value.toString())
    }
    private fun cleanup(owner: String) {
        File(context.filesDir, "materials/$owner").deleteRecursively(); File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
    }

    @Test fun everyExistingContractAndFirstErrorBoundariesUseRealNativeExactTool(): Unit = runBlocking {
        val cases = Json.parseToJsonElement(assets.open("math_quality/representative-contracts.json").reader().readText()).jsonArray
        val versions = mutableSetOf<Int>()
        for (row in cases) {
            val case = row.jsonObject; val input = case.getValue("input").jsonObject
            val result = Json.parseToJsonElement(mathVerifyTool().execute(input).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            for ((key, expected) in case.getValue("expected").jsonObject) assertEquals("${case["id"]}:$key", expected, result[key])
            assertEquals(result.getValue("status").jsonPrimitive.content == "VERIFIED", MathVerification.accepts(input, result))
            if (result["status"] == JsonPrimitive("VERIFIED")) versions += result.getValue("contractVersion").jsonPrimitive.int
            else assertFalse(MathVerification.accepts(input, JsonObject(result + mapOf("status" to JsonPrimitive("VERIFIED"), "verified" to JsonPrimitive(true)))))
            output(case.getValue("id").jsonPrimitive.content, buildJsonObject { put("input", input); put("result", result) })
        }
        assertEquals((1..15).toSet(), versions)
    }

    @Test fun actualPublicDomainTextbookBindsPhysicalPageAndPartialCoverageNotMathematicalTruth(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val source = File.createTempFile("b05-book-", ".pdf", context.cacheDir)
        try {
            assets.open("math_quality/calculus-made-easy.pdf").use { input -> source.outputStream().use { input.copyTo(it) } }
            val reference = Json.parseToJsonElement(assets.open("math_quality/REFERENCE.json").reader().readText()).jsonObject
            assertEquals(reference.getValue("sha256").jsonPrimitive.content, MaterialFiles.digest(source))
            val book = store.import(owner, source.toUri(), "Thompson - Calculus Made Easy (1914).pdf")
            store.organize(owner, book.id, book.sha256, "数学/公开教材", listOf("微积分", "公版来源"))
            val content = store.readContent(owner, book.id)
            assertEquals(292, content.totalPages)
            assertEquals(200, content.extractedPages)
            val page = store.readSourceLocation(owner, book.id, book.sha256, "pdf_physical_page", 29, 0, 24000)
            val quote = page.getValue("untrusted_file_data").jsonPrimitive.content
            assertTrue(quote.replace(Regex("\\s+"), " ").contains("Dividing across"))
            val receipt = page.getValue("source_receipt").jsonObject
            val location = receipt.getValue("source_locations").jsonArray.single().jsonObject
            assertEquals(29, location.getValue("number").jsonPrimitive.int)
            assertEquals("pdf_physical_page", location.getValue("kind").jsonPrimitive.content)
            val locator = receipt.getValue("locator").jsonObject
            val start = locator.getValue("start").jsonPrimitive.int
            val checked = store.revalidateSourceTextPage(owner, book.id, book.sha256, start, quote.length, receipt, quote)
            assertEquals("VERIFIED", checked.getValue("receipt_revalidation").jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(runCatching { store.revalidateSourceTextPage(owner, book.id, book.sha256, start, quote.length, receipt, "FORGED TEXTBOOK QUOTE") }.isFailure)
            val input = buildJsonObject { put("operation", "derivative"); put("expression", "x^2"); put("claimed_derivative", "x+x") }
            val result = MathVerification.verify(input)
            assertTrue(MathVerification.accepts(input, result))
            output("actual-textbook", buildJsonObject {
                put("reference", reference); put("material_id", book.id); put("source_sha256", book.sha256)
                put("source_page", page); put("total_pages", content.totalPages!!); put("attempted_pages", content.extractedPages!!)
                put("math_input", input); put("math_result", result)
                put("notice", "Physical PDF page29 independently checked, not inferred printed page. Historical author's exposition is not upgraded to a modern general proof. Exact polynomial derivative only; whole book and OCR correctness not asserted.")
            })
            assertEquals(reference.getValue("sha256").jsonPrimitive.content, MaterialFiles.digest(store.verifiedFile(owner, book.id)))
        } finally { source.delete(); cleanup(owner) }
    }

    @Test fun ambiguousImageRequiresExplicitVersionBoundTranscriptionAndRejectsStaleConfirmation(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val source = File.createTempFile("b05-ambiguous-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE); drawText("x? 1/l", 10f, 70f, Paint().apply { color = android.graphics.Color.BLACK; textSize = 50f }) }
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            val item = store.import(owner, source.toUri(), "ambiguous-symbols.png")
            val pending = MaterialMathEvidence.source(store, owner, item.id, item.sha256, 0, 4000)
            assertEquals("WAITING_INPUT", pending.getValue("status").jsonPrimitive.content)
            assertEquals("NOT_EVALUATED", pending.getValue("mathematics_status").jsonPrimitive.content)
            store.confirmSymbol(owner, item.id, item.sha256, "x")
            assertEquals("WAITING_INPUT", MaterialMathEvidence.source(store, owner, item.id, item.sha256, 0, 4000).getValue("status").jsonPrimitive.content)
            MaterialLibraryFrontend(context, store).handle("library.confirmSymbols", owner,
                mapOf("id" to item.id, "sha256" to item.sha256, "transcription" to "User confirms x^2, not x^3; the denominator is x, not l."), {}, { null })
            val ready = MaterialMathEvidence.source(store, owner, item.id, item.sha256, 0, 4000)
            assertEquals("SOURCE_READY", ready.getValue("status").jsonPrimitive.content)
            assertTrue(ready.getValue("user_transcription_confirmed").jsonPrimitive.boolean)
            assertEquals(JsonNull, ready.getValue("source_page"))
            assertEquals("NOT_EVALUATED", ready.getValue("mathematics_status").jsonPrimitive.content)
            store.record(store.get(owner, item.id).copy(symbolSourceSha256 = "0".repeat(64)))
            assertEquals("WAITING_INPUT", MaterialMathEvidence.source(store, owner, item.id, item.sha256, 0, 4000).getValue("status").jsonPrimitive.content)
            assertTrue(runCatching { store.confirmSymbol(owner, item.id, "0".repeat(64), "forged") }.isFailure)
            store.file(item).appendText("changed bytes")
            assertTrue(runCatching { MaterialMathEvidence.source(store, owner, item.id, item.sha256, 0, 4000) }.isFailure)
            output("ambiguity-confirmation", buildJsonObject { put("before", pending); put("after_explicit_confirmation", ready); put("notice", "Synthetic image tests confirmation semantics only; no OCR recognition/accuracy claim.") })
        } finally { source.delete(); cleanup(owner) }
    }
}
