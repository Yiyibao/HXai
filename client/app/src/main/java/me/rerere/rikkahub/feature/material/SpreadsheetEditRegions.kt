package me.rerere.rikkahub.feature.material

import org.w3c.dom.Element

/** Preserve merged display ownership and multi-cell formula ownership on precise edits. */
internal class SpreadsheetEditRegions(root: Element) {
    private data class Address(val column: Int, val row: Int)
    private data class Region(val first: Address, val last: Address, val reason: String) {
        fun contains(a: Address) = a.column in first.column..last.column && a.row in first.row..last.row
    }
    private val regions = ArrayList<Region>()
    init {
        val merges = root.getElementsByTagNameNS(X, "mergeCell")
        require(merges.length <= 2000) { "spreadsheet_region_limit" }
        for (i in 0 until merges.length) regions += range((merges.item(i) as Element).getAttribute("ref"), "merged_follower")
        val formulas = root.getElementsByTagNameNS(X, "f")
        require(formulas.length <= 100000) { "spreadsheet_formula_limit" }
        for (i in 0 until formulas.length) {
            val formula = formulas.item(i) as Element
            if (formula.getAttribute("t") in setOf("array", "dataTable", "shared")) {
                if (formula.hasAttribute("ref")) regions += range(formula.getAttribute("ref"), "formula_region")
                else require(formula.getAttribute("t") == "shared") { "missing_formula_region" }
            }
        }
        require(regions.size <= 4000) { "spreadsheet_region_limit" }
    }
    fun rejection(cell: String): String? {
        val address = address(cell)
        val matches = regions.filter { it.contains(address) }
        if (matches.any { it.reason == "formula_region" }) return "formula_region"
        if (matches.size > 1) return "overlapping_merged_regions"
        return matches.singleOrNull()?.takeIf { it.first != address }?.reason
    }
    private fun range(ref: String, reason: String): Region {
        val endpoints = ref.split(':')
        require(endpoints.size in 1..2) { "invalid_spreadsheet_region" }
        val first = address(endpoints.first()); val last = address(endpoints.last())
        require(first.row <= last.row && first.column <= last.column) { "invalid_spreadsheet_region" }
        return Region(first, last, reason)
    }
    private fun address(value: String): Address {
        val match = Regex("\\$?([A-Z]{1,3})\\$?([1-9][0-9]{0,6})").matchEntire(value)
            ?: error("invalid_spreadsheet_address")
        val column = match.groupValues[1].fold(0) { n, c -> n * 26 + c.code - 64 }
        val row = match.groupValues[2].toInt()
        require(column in 1..16384 && row in 1..1048576) { "invalid_spreadsheet_address" }
        return Address(column, row)
    }
    companion object { private const val X = "http://schemas.openxmlformats.org/spreadsheetml/2006/main" }
}
