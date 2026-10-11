package me.rerere.rikkahub.service

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class MaterialFidelityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence = File(context.cacheDir, "material-evidence").apply { mkdirs() }
    @Test fun wordImageMathAndTableArePreserved() = runBlocking {
        val bitmap = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        try { Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("8642", 15f, 80f, Paint().apply { color = Color.BLUE; textSize = 64f }) }; bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes) } finally { bitmap.recycle() }
        val file = File(evidence, "fidelity.docx")
        val options = Json.parseToJsonElement("""{"equations":[{"type":"fraction","numerator":"x+1","denominator":"2"},{"type":"superscript","base":"y","exponent":"3"}]}""").jsonObject
        MaterialWriter.write(file, "docx", "中文图片与公式", listOf(listOf("名称", "数值"), listOf("样例", "19")), emptyList(), options, listOf(bytes.toByteArray()))
        val read = MaterialReader.read(file, "docx")
        assertTrue(read.text.contains("(x+1)/(2)")); assertTrue(read.text.contains("(y)^(3)")); assertTrue(read.text.contains("[图片关系 img1]"))
        val extracted = MaterialWordContent.images(file, 0, 5); assertEquals(1, extracted.size)
        val image = BitmapFactory.decodeByteArray(extracted[0].second, 0, extracted[0].second.size)
        assertEquals(240, image.width); assertEquals(120, image.height); image.recycle()
        ZipFile(file).use { zip -> assertTrue(zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText().contains("tblHeader")) }
        assertTrue(runCatching { MaterialWordContent.images(file, 1, 1) }.isFailure)
    }
    @Test fun namedTypedSheetAndRelationshipOrder() = runBlocking {
        val file = File(evidence, "fidelity.xlsx")
        val options = Json.parseToJsonElement("""{"sheetName":"成绩与编号","rows":[["编号","数值","布尔","文本公式","空值"],["0012",12.5,true,"=1+1",null]]}""").jsonObject
        val rows = options["rows"]!!.jsonArray.map { it.jsonArray.map { value -> value.jsonPrimitive.content } }
        MaterialWriter.write(file, "xlsx", "", rows, emptyList(), options)
        val text = MaterialReader.read(file, "xlsx").text
        assertTrue(text.contains("工作表 成绩与编号")); assertTrue(text.contains("A2 [string]: 0012")); assertTrue(text.contains("B2 [number]: 12.5")); assertTrue(text.contains("C2 [boolean]: true")); assertTrue(text.contains("D2 [string]: =1+1"))
        val orderFile = File(evidence, "sheet-order.xlsx")
        ZipFile(file).use { source -> ZipOutputStream(orderFile.outputStream()).use { out ->
            source.entries().asSequence().forEach { entry ->
                var bytes = source.getInputStream(entry).use { it.readBytes() }
                if (entry.name == "xl/workbook.xml") bytes = bytes.toString(Charsets.UTF_8).replace("<sheets>", "<sheets><sheet name=\"先读的工作表\" sheetId=\"2\" r:id=\"other\"/>").toByteArray()
                if (entry.name == "xl/_rels/workbook.xml.rels") bytes = bytes.toString(Charsets.UTF_8).replace("</Relationships>", "<Relationship Id=\"other\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/custom.xml\"/></Relationships>").toByteArray()
                out.putNextEntry(ZipEntry(entry.name)); out.write(bytes); out.closeEntry()
            }
            out.putNextEntry(ZipEntry("xl/worksheets/custom.xml")); out.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\"><v>99</v></c></row></sheetData></worksheet>".toByteArray()); out.closeEntry()
        } }
        val ordered = MaterialReader.read(orderFile, "xlsx").text
        assertTrue(ordered.indexOf("先读的工作表") < ordered.indexOf("成绩与编号")); assertTrue(ordered.contains("99"))
        assertTrue(runCatching { MaterialWriter.write(File(evidence, "bad.xlsx"), "xlsx", "", rows, emptyList(), JsonObject(options + ("sheetName" to JsonPrimitive("bad/name")))) }.isFailure)
    }
    @Test fun longTextAndPdfTableHaveTailAndMultiplePages() = runBlocking {
        val long = (1..2000).joinToString("\n") { "中文长内容行 $it，尾部不会在24000处丢失。" } + "\nTAIL_2000"
        val file = File(evidence, "long.md").apply { writeText(long) }; assertEquals(long, MaterialReader.read(file, "md").text)
        val table = File(evidence, "table-long.pdf")
        val rows = listOf(listOf("序号", "说明")) + (1..160).map { listOf(it.toString(), "中文表格第 $it 行，自动换行与完整显示。".repeat(if (it == 80) 180 else 3)) }
        MaterialWriter.write(table, "pdf", "分页表格\n" + long.take(3000), rows, emptyList())
        val doc = com.artifex.mupdf.fitz.PDFDocument.openDocument(table.path)
        try { assertTrue(doc.countPages() > 5) } finally { doc.destroy() }
        val read = MaterialReader.read(table, "pdf"); assertTrue(read.text.contains("160")); assertTrue(read.text.contains("第 80 行"))
        Unit
    }
}
