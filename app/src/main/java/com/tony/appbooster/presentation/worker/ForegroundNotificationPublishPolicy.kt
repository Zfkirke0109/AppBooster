package com.tony.appbooster.presentation.worker

/**
 * Snapshot of material fields used to render and deduplicate notification updates.
 *
 * @property currentLabel Optional package name shown in the content text.
 * @property progressPercent Optional normalized percent [0,100] for determinate progress.
 * @property progressCurrent Optional processed item count.
 * @property progressTotal Optional total item count.
 * @property showStopAction Whether the stop action is shown.
 */
internal data class ForegroundNotificationRenderState(
    val currentLabel: String? = null,
    val progressPercent: Int? = null,
    val progressCurrent: Int? = null,
    val progressTotal: Int? = null,
    val showStopAction: Boolean = true
)

/**
 * Decision returned by [ForegroundNotificationPublishPolicy] for a pending update.
 *
 * @property shouldPublish Whether `setForeground(...)` should be called for this update.
 * @property eventName Debug event name used by worker logging.
 */
internal data class ForegroundNotificationPublishDecision(
    val shouldPublish: Boolean,
    val eventName: String
)

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
