package com.tony.appbooster.presentation.worker

/**
 * Applies duplicate suppression and interval-based throttling for foreground notification updates.
 *
 * @property minUpdateIntervalMs Minimum delay required between non-forced publishes.
 */
internal class ForegroundNotificationPublishPolicy(
    private val minUpdateIntervalMs: Long
) {
    private var lastPublishedAtMs: Long = 0L
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
        val duplicate = lastPublishedState == state
        if (!forceUpdate && duplicate) {
            return ForegroundNotificationPublishDecision(
                shouldPublish = false,
                eventName = "skipped_duplicate"
            )
        }

        if (!forceUpdate && (nowMs - lastPublishedAtMs) < minUpdateIntervalMs) {
            return ForegroundNotificationPublishDecision(
                shouldPublish = false,
                eventName = "skipped_throttled"
            )
        }

        return ForegroundNotificationPublishDecision(
            shouldPublish = true,
            eventName = "published"
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
