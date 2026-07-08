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
