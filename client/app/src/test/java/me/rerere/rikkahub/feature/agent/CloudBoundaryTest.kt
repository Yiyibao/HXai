package me.rerere.rikkahub.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudBoundaryTest {
    @Test fun hostedKeysStayAbsentAndCrossAppStaysOff() {
        assertEquals("ABSENT", CloudBoundary.HOSTED_KEYS)
        assertFalse(CloudBoundary.CROSS_APP_ENABLED)
    }
}
