package com.tony.appbooster.presentation.worker

/**
 * Applies duplicate suppression and interval-based throttling for foreground notification updates.
 *
 * @property minUpdateIntervalMs Minimum delay required between non-forced publishes.
 */
internal class ForegroundNotificationPublishPolicy(
    private val minUpdateIntervalMs: Long
) {
    private var lastPublishedAtMs: Long = -minUpdateIntervalMs
    private var lastPublishedState: ForegroundNotificationRenderState? = null

    /**
     * Evaluates whether a pending state should be published.
     *
     * @param nowMs Current elapsed realtime in milliseconds.
     * @param state Candidate notification render state.
     * @param forceUpdate When true, bypasses duplicate and throttling checks.
     * @return Publish decision for this update.
     */
    fun decide(
        nowMs: Long,
        state: ForegroundNotificationRenderState,
        forceUpdate: Boolean
    ): ForegroundNotificationPublishDecision {
        if (forceUpdate) {
            return ForegroundNotificationPublishDecision(shouldPublish = true)
        }

        if (lastPublishedState == null) {
            return ForegroundNotificationPublishDecision(shouldPublish = true)
        }

        val duplicate = lastPublishedState == state
        if (duplicate) {
            return ForegroundNotificationPublishDecision(
                shouldPublish = false,
                skippedEventName = "skipped_duplicate"
            )
        }

        if ((nowMs - lastPublishedAtMs) < minUpdateIntervalMs) {
            return ForegroundNotificationPublishDecision(
                shouldPublish = false,
                skippedEventName = "skipped_throttled"
            )
        }

        return ForegroundNotificationPublishDecision(
            shouldPublish = true
        )
    }

    /**
     * Stores a successfully published state for future dedupe/throttle checks.
     *
     * @param nowMs Publish timestamp in elapsed realtime milliseconds.
     * @param state State that was rendered by `setForeground(...)`.
     */
    fun markPublished(nowMs: Long, state: ForegroundNotificationRenderState) {
        lastPublishedAtMs = nowMs
        lastPublishedState = state
    }
}
