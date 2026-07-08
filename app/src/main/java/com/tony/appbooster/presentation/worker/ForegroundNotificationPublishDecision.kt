package com.tony.appbooster.presentation.worker

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
