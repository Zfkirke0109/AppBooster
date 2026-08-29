package com.tony.appbooster.data.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTextLimiterTest {
    @Test
    fun `null output remains null and is not truncated`() {
        val result = TelemetryTextLimiter.limit(null, maxBytes = 4)

        assertNull(result.value)
        assertFalse(result.truncated)
    }

    @Test
    fun `output at limit is preserved`() {
        val result = TelemetryTextLimiter.limit("1234", maxBytes = 4)

        assertEquals("1234", result.value)
        assertFalse(result.truncated)
    }

    @Test
    fun `output above limit is truncated and marked`() {
        val result = TelemetryTextLimiter.limit("12345", maxBytes = 4)

        assertEquals("1234", result.value)
        assertTrue(result.truncated)
    }

    @Test
    fun `multibyte output respects utf8 byte limit without splitting code point`() {
        val result = TelemetryTextLimiter.limit("a\u20acb", maxBytes = 4)

        assertEquals("a\u20ac", result.value)
        assertEquals(4, result.value?.toByteArray(Charsets.UTF_8)?.size)
        assertTrue(result.truncated)
    }
}
