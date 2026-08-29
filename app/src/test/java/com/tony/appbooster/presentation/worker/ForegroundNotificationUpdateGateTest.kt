package com.tony.appbooster.presentation.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationUpdateGateTest {
    private val gate = ForegroundNotificationUpdateGate<String>(minimumIntervalMs = 1_000L)

    @Test
    fun `changed updates are limited to one per interval`() {
        assertTrue(gate.shouldPublish("start", nowMs = 10_000L))
        assertFalse(gate.shouldPublish("package-one", nowMs = 10_100L))
        assertTrue(gate.shouldPublish("package-one", nowMs = 11_000L))
    }

    @Test
    fun `duplicate updates remain suppressed after interval`() {
        assertTrue(gate.shouldPublish("same", nowMs = 10_000L))
        assertFalse(gate.shouldPublish("same", nowMs = 20_000L))
    }

    @Test
    fun `forced terminal update publishes immediately`() {
        assertTrue(gate.shouldPublish("running", nowMs = 10_000L))
        assertTrue(gate.shouldPublish("terminal", nowMs = 10_001L, force = true))
    }
}
