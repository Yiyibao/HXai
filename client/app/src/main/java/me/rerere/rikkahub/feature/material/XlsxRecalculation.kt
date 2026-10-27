package me.rerere.rikkahub.feature.material

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import org.w3c.dom.Element

const val XLSX_RECALCULATE = "xlsx_recalculate"
const val XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

interface OfficeArtifactManifest {
    val sourceSha256: String
    val rendererVersion: String
    val warnings: List<String>
    val assets: List<DocumentAsset>
}

@Serializable data class XlsxRecalculationOptions(val scope: String = "recalculate_workbook")
@Serializable data class XlsxRecalculationProof(
    val status: String, val scope: String, val formulaCount: Int, val sheetCount: Int,
    val numericRelativeTolerance: Double, val numericAbsoluteTolerance: Double, val layout: String,
)
@Serializable data class XlsxRecalculationManifest(
    val contractVersion: Int, val operation: String, override val sourceSha256: String, val sourceExtension: String,
    val renderer: String, override val rendererVersion: String, val renderOptions: XlsxRecalculationOptions,
    val recalculation: XlsxRecalculationProof, override val warnings: List<String>, override val assets: List<DocumentAsset>,
): OfficeArtifactManifest
@Serializable data class XlsxCalculationCell(val sheet: String, val cell: String, val number: Double?, val text: String)
@Serializable data class XlsxCalculationWitness(
    val contractVersion: Int, val sourceSha256: String, val outputSha256: String,
    val sheetNames: List<String>, val cells: List<XlsxCalculationCell>,
)

/** Independently compare real saved OOXML caches with the bounded native-engine witness. */
object XlsxRecalculationReadback {
    private const val X = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val WITNESS_LIMIT = 4L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false }
    private val worksheetPath = Regex("worksheets/[A-Za-z0-9_.-]+\\.xml")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun verify(workbook: File, witnessFile: File, manifest: XlsxRecalculationManifest) {
        require(witnessFile.isFile && witnessFile.length() in 1..WITNESS_LIMIT) { "calculation_witness_limit" }
        require(manifest.contractVersion == 1 && manifest.operation == XLSX_RECALCULATE && manifest.sourceExtension == "xlsx" &&
            manifest.renderer == "LibreOffice" && manifest.rendererVersion.isNotBlank() && manifest.renderOptions == XlsxRecalculationOptions())
        val proof = manifest.recalculation
        require(proof.status == "VERIFIED" && proof.scope == "saved_formula_caches" && proof.layout == "NOT_VERIFIED" &&
            proof.formulaCount in 0..100000 && proof.sheetCount in 1..2000 &&
            proof.numericRelativeTolerance == 1e-12 && proof.numericAbsoluteTolerance == 1e-15)
        val witness = witnessFile.inputStream().use { json.decodeFromStream<XlsxCalculationWitness>(it) }
        require(witness.contractVersion == 1 && witness.sourceSha256 == manifest.sourceSha256 && witness.outputSha256 == MaterialFiles.digest(workbook))
        val names = witness.sheetNames.toSet()
        require(witness.sheetNames.size == proof.sheetCount && names.size == witness.sheetNames.size &&
            names.all { it.length in 1..31 } && witness.cells.size == proof.formulaCount)
        val expected = mutableMapOf<String, MutableMap<String, XlsxCalculationCell>>()
        for (cell in witness.cells) {
            require(cell.sheet in names && cell.cell.length in 2..10 &&
                cell.text.length <= 32767 && (cell.number == null || cell.number.isFinite()))
            var index = 0
            var column = 0
            while (index < cell.cell.length && cell.cell[index] in 'A'..'Z') {
                column = column * 26 + cell.cell[index++].code - 64
            }
            require(index in 1..3 && index < cell.cell.length && cell.cell[index] in '1'..'9')
            var row = 0
            while (index < cell.cell.length) {
                val digit = cell.cell[index++]
                require(digit in '0'..'9')
                row = row * 10 + digit.code - 48
            }
            require(column <= 16384 && row <= 1048576)
            require(expected.getOrPut(cell.sheet) { mutableMapOf() }.put(cell.cell, cell) == null) { "duplicate_calculation_cell" }
        }
        val parts = OfficePackage.read(workbook)
        fun xml(name: String) = SafeOfficeXml.parse(parts.getValue(name))
        val relationships = mutableMapOf<String, Element>()
        val relationshipNodes = xml("xl/_rels/workbook.xml.rels").childNodes
        for (index in 0 until relationshipNodes.length) {
            val relation = relationshipNodes.item(index) as? Element ?: continue
            require(relationships.put(relation.getAttribute("Id"), relation) == null)
        }
        val sheets = xml("xl/workbook.xml").getElementsByTagNameNS(X, "sheet")
        require(sheets.length == proof.sheetCount)
        val actualNames = mutableSetOf<String>()
        var checked = 0
        for (index in 0 until sheets.length) {
            val sheet = sheets.item(index) as Element
            val name = sheet.getAttribute("name")
            require(actualNames.add(name) && name in names)
            val relationship = relationships[sheet.getAttributeNS(R, "id")] ?: error("invalid_worksheet_relationship")
            val mode = relationship.getAttribute("TargetMode")
            require(relationship.getAttribute("Type") == "$R/worksheet" && (mode.isEmpty() || mode == "Internal"))
            val target = relationship.getAttribute("Target").removePrefix("/xl/")
            require(target.matches(worksheetPath))
            val selected = expected[name]
            val cells = xml("xl/$target").getElementsByTagNameNS(X, "c")
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as Element
                val formulas = cell.getElementsByTagNameNS(X, "f")
                if (formulas.length == 0) continue
                require(formulas.length == 1)
                val result = selected?.remove(cell.getAttribute("r")) ?: error("unexpected_calculation_cell")
                val values = cell.getElementsByTagNameNS(X, "v")
                require(values.length == 1) { "formula_cache_missing" }
                val cached = values.item(0) as Element
                require(cached.parentNode == cell) { "invalid_formula_cache" }
                val kind = cell.getAttribute("t").ifBlank { "n" }
                if (result.number == null) {
                    require(kind == "str" && cached.textContent == result.text) { "formula_readback_mismatch" }
                } else {
                    require(kind == "n" || kind == "b")
                    val value = cached.textContent.toDoubleOrNull() ?: error("formula_readback_mismatch")
                    require(value.isFinite() && abs(value - result.number) <= max(1e-15, 1e-12 * max(abs(value), abs(result.number)))) { "formula_readback_mismatch" }
                    require(kind != "b" || cached.textContent == "0" || cached.textContent == "1")
                }
                checked++
            }
        }
        require(checked == proof.formulaCount && expected.values.all { it.isEmpty() }) { "missing_calculation_cells" }
    }
}
