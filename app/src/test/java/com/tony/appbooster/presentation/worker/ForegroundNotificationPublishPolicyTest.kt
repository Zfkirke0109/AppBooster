package com.tony.appbooster.presentation.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ForegroundNotificationPublishPolicy].
 */
class ForegroundNotificationPublishPolicyTest {

    @Test
    fun `given duplicate state when not forced then skips as duplicate`() {
        val policy = ForegroundNotificationPublishPolicy(minUpdateIntervalMs = 1000L)
        val state = ForegroundNotificationRenderState(currentLabel = "pkg.one")
        policy.markPublished(nowMs = 1_000L, state = state)

        val decision = policy.decide(nowMs = 3_000L, state = state, forceUpdate = false)

        assertFalse(decision.shouldPublish)
        assertEquals("skipped_duplicate", decision.eventName)
    }

    @Test
    fun `given rapid non-duplicate update when not forced then skips as throttled`() {
        val policy = ForegroundNotificationPublishPolicy(minUpdateIntervalMs = 1000L)
        policy.markPublished(
            nowMs = 1_000L,
            state = ForegroundNotificationRenderState(currentLabel = "pkg.one", progressPercent = 5)
        )

        val decision = policy.decide(
            nowMs = 1_500L,
            state = ForegroundNotificationRenderState(currentLabel = "pkg.two", progressPercent = 10),
            forceUpdate = false
        )

        assertFalse(decision.shouldPublish)
        assertEquals("skipped_throttled", decision.eventName)
    }

    @Test
    fun `given rapid duplicate update when forced then always publishes`() {
        val policy = ForegroundNotificationPublishPolicy(minUpdateIntervalMs = 1000L)
        val state = ForegroundNotificationRenderState(
            currentLabel = "pkg.one",
            progressPercent = 15
        )
        policy.markPublished(nowMs = 2_000L, state = state)

        val decision = policy.decide(nowMs = 2_100L, state = state, forceUpdate = true)

        assertTrue(decision.shouldPublish)
        assertEquals("published", decision.eventName)
    }

    @Test
    fun `given interval elapsed and different state when not forced then publishes`() {
        val policy = ForegroundNotificationPublishPolicy(minUpdateIntervalMs = 1000L)
        policy.markPublished(
            nowMs = 1_000L,
            state = ForegroundNotificationRenderState(currentLabel = "pkg.one", progressPercent = 5)
        )

        val decision = policy.decide(
            nowMs = 2_500L,
            state = ForegroundNotificationRenderState(currentLabel = "pkg.two", progressPercent = 20),
            forceUpdate = false
        )

        assertTrue(decision.shouldPublish)
        assertEquals("published", decision.eventName)
    }
}
