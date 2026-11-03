package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class SpreadsheetEditRegionsTest {
    private val x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun source(file: File, extra: String="", formula: String="") {
        val parts=mapOf("xl/workbook.xml" to """<workbook xmlns="$x" xmlns:r="$rel"><sheets><sheet name="Data" sheetId="1" r:id="s"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="s" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$x"><sheetData><row r="1"><c r="A1" s="2">$formula<v>1</v></c><c r="B1"><v>2</v></c><c r="C1"><v>3</v></c></row></sheetData>$extra</worksheet>""")
        ZipOutputStream(file.outputStream()).use { z -> parts.forEach { (name,data) -> z.putNextEntry(ZipEntry(name));z.write(data.toByteArray());z.closeEntry() } }
    }
    private fun spec(cell: String, expected: Int)=buildJsonObject { put("sheet","Data");put("changes",buildJsonArray {
        add(buildJsonObject { put("cell",cell);put("expected",expected);put("value",9) }) }) }
    @Test fun mergedFollowerIsRejectedWithoutPublishingOrMutatingSource() {
        val dir=kotlin.io.path.createTempDirectory().toFile()
        try {
            val source=File(dir,"source.xlsx");val out=File(dir,"out.xlsx")
            source(source,"""<mergeCells count="1"><mergeCell ref="A1:B1"/></mergeCells>""")
            val bytes=source.readBytes()
            assertTrue(runCatching { MaterialEditor.setCells(source,out,spec("B1",2)) }.isFailure)
            assertFalse(out.exists());assertArrayEquals(bytes,source.readBytes())
            val cell=MaterialEditor.excelCells(source,"Data",0,10).getValue("cells").jsonArray[1].jsonObject
            assertEquals(JsonPrimitive(false),cell["editable"])
            assertEquals(JsonPrimitive("merged_follower"),cell["edit_blocked_reason"])
            MaterialEditor.setCells(source,out,spec("A1",1))
            assertTrue(out.isFile);assertArrayEquals(bytes,source.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun multiCellFormulaFollowerCannotBeOverwrittenEvenWithoutItsOwnFormulaNode() {
        val dir=kotlin.io.path.createTempDirectory().toFile()
        try {
            for(kind in listOf("array","dataTable","shared")) {
                val source=File(dir,"$kind.xlsx");val out=File(dir,"$kind-out.xlsx")
                source(source,formula="""<f t="$kind" ref="A1:B1">ROW(A1:B1)</f>""")
                assertTrue(kind,runCatching { MaterialEditor.setCells(source,out,spec("B1",2)) }.isFailure)
                assertFalse(out.exists())
                MaterialEditor.setCells(source,out,spec("C1",3))
                assertTrue(out.isFile)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun rangesAreBoundedAndAbsoluteCoordinatesHaveTheSameOwnership() {
        fun regions(ref: String)=SpreadsheetEditRegions(SafeOfficeXml.parse("""<worksheet xmlns="$x"><mergeCells><mergeCell ref="$ref"/></mergeCells></worksheet>""".toByteArray()))
        val dollar='$'
        val valid=regions("${dollar}A${dollar}1:${dollar}B${dollar}2")
        assertNull(valid.rejection("A1"));assertEquals("merged_follower",valid.rejection("B2"));assertNull(valid.rejection("C1"))
        for(ref in listOf("B2:A1","A0:B1","A1:XFE1","A1:B1048577","A1 B2")) assertTrue(runCatching { regions(ref) }.isFailure)
    }
}
