package com.tony.appbooster.presentation.worker

/**
 * Decision returned by [ForegroundNotificationPublishPolicy] for a pending update.
 *
 * @property shouldPublish Whether `setForeground(...)` should be called for this update.
 * @property skippedEventName Debug event name used when an update is skipped.
 * Required to be non-null when [shouldPublish] is false.
 */
internal data class ForegroundNotificationPublishDecision(
    val shouldPublish: Boolean,
    val skippedEventName: String? = null
)
