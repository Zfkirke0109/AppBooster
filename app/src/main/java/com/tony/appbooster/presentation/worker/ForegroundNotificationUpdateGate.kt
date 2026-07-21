package com.tony.appbooster.presentation.worker

/**
 * Suppresses duplicate and overly frequent foreground-notification updates.
 * Forced updates are reserved for terminal states that must be visible immediately.
 */
internal class ForegroundNotificationUpdateGate<T>(
    private val minimumIntervalMs: Long
) {
    private var lastPublishedAtMs: Long? = null
    private var lastPublishedState: T? = null

    init {
        require(minimumIntervalMs >= 0L) { "minimumIntervalMs must not be negative" }
    }

    fun shouldPublish(state: T, nowMs: Long, force: Boolean = false): Boolean {
        val previousAtMs = lastPublishedAtMs
        if (!force && state == lastPublishedState) return false
        if (!force && previousAtMs != null && nowMs - previousAtMs < minimumIntervalMs) {
            return false
        }

        lastPublishedAtMs = nowMs
        lastPublishedState = state
        return true
    }
}
