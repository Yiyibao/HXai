package me.rerere.rikkahub.feature.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MaterialSourceLocationReadTest {
    @Test fun printedPageIsNotASourceLocation() {
        val error = assertThrows(MaterialSourceLocationRead.Rejected::class.java) {
            MaterialSourceLocationRead.validate("printed_page", 1, 0, 20)
        }
        assertEquals("UNSUPPORTED_LOCATION_KIND", error.code)
    }

    @Test fun pdfPhysicalPageAndPptSlideRemainTheOnlyKinds() {
        MaterialSourceLocationRead.validate("pdf_physical_page", 1, 0, 20)
        MaterialSourceLocationRead.validate("ppt_logical_slide", 2, 0, 20)
    }
}
