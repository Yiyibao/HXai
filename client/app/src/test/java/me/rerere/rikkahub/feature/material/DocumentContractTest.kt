package me.rerere.rikkahub.feature.material

import org.junit.Assert.*
import org.junit.Test

class DocumentContractTest {
    private fun manifest() = DocumentManifest(1, "a".repeat(64), 1, "4.0.10", "1", "basic", 3,
        listOf(2, 2), listOf(2), emptyList(), emptyList(), false, emptyList(),
        listOf(DocumentAsset("document", "document.json", "application/json", 100, "b".repeat(64))))

    @Test fun acceptsPartialRangeWithoutClaimingFullDocument() {
        DocumentContract.validate(manifest(), "a".repeat(64), 2, 2)
    }
    @Test fun rejectsFalseCoverageAndChangedSource() {
        for (bad in listOf(manifest().copy(complete = true), manifest().copy(processedPages = listOf(1)),
            manifest().copy(sourceSha256 = "c".repeat(64)), manifest().copy(failedPages = listOf(2)))) {
            assertTrue(runCatching { DocumentContract.validate(bad, "a".repeat(64), 2, 2) }.isFailure)
        }
    }
    @Test fun rejectsUnsafeAssetAndOversizedResult() {
        for (asset in listOf(manifest().assets[0].copy(assetId = "../secret"), manifest().assets[0].copy(path = "../secret"),
            manifest().assets[0].copy(size = 70L * 1024 * 1024), manifest().assets[0].copy(mediaType = "text/html"))) {
            assertTrue(runCatching { DocumentContract.validate(manifest().copy(assets = listOf(asset)), "a".repeat(64), 2, 2) }.isFailure)
        }
    }
    @Test fun endpointRejectsPlaintextAndCredentialBearingUrls() {
        assertEquals("hxnf.top", DocumentContract.endpoint("https://hxnf.top/documents").host)
        for (url in listOf("http://hxnf.top", "https://user:secret@hxnf.top", "https://hxnf.top/?token=x", "https://hxnf.top/#x")) {
            assertTrue(runCatching { DocumentContract.endpoint(url) }.isFailure)
        }
    }
}
