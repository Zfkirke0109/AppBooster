package com.tony.appbooster.presentation.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.tony.appbooster.R
import com.tony.appbooster.presentation.activity.MainActivity
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds and manages the foreground notification used by long-running WorkManager jobs.
 *
 * Business purpose:
 * - Provides a single, consistent notification style for "analysis" and "optimization" workers.
 * - Avoids duplicated notification/channel code across workers.
 * - Centralizes Stop-action wiring through [OptimizationWorkerStopReceiver].
 */
object WorkForegroundNotificationHelper {

    private val isChannelEnsured = AtomicBoolean(false)

    /**
     * Ensures the foreground notification channel exists.
     *
     * @param context Context used to register the notification channel.
     */
    fun ensureChannel(context: Context) {
        if (shouldSkipEnsureChannel()) {
            return
        }

        synchronized(this) {
            if (shouldSkipEnsureChannel()) {
                return
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.optimization_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.optimization_notification_channel_description)
                }
                manager.createNotificationChannel(channel)
            }

            isChannelEnsured.set(true)
        }
    }

    /**
     * Creates a [ForegroundInfo] configured for WorkManager foreground execution.
     *
     * @param context Context used to resolve resources.
     * @param workId WorkManager work id used for the Stop action.
     * @param currentLabel Optional label to show as the notification content text.
     * @param progressPercent Optional 0..100 progress value.
     * @param progressCurrent Optional current step index (e.g., processed apps).
     * @param progressTotal Optional total steps.
     * @param showStopAction When true, includes the stop action button.
     * @return [ForegroundInfo] ready to be passed to `setForeground()`.
     */
    fun createForegroundInfo(
        context: Context,
        workId: String,
        currentLabel: String?,
        progressPercent: Int? = null,
        progressCurrent: Int? = null,
        progressTotal: Int? = null,
        showStopAction: Boolean = true
    ): ForegroundInfo {
        val notification = buildNotification(
            context = context,
            workId = workId,
            currentLabel = currentLabel,
            progressPercent = progressPercent,
            progressCurrent = progressCurrent,
            progressTotal = progressTotal,
            showStopAction = showStopAction
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Builds the notification used for long running work.
     *
     * @param context Context used to resolve strings and create intents.
     * @param workId WorkManager work id used by the Stop action receiver.
     * @param currentLabel Optional label shown as notification content.
     * @param progressPercent Optional 0..100 progress value.
     * @param progressCurrent Optional current step index.
     * @param progressTotal Optional total steps.
     * @param showStopAction When true, includes the stop action button.
     */
    fun buildNotification(
        context: Context,
        workId: String,
        currentLabel: String?,
        progressPercent: Int? = null,
        progressCurrent: Int? = null,
        progressTotal: Int? = null,
        showStopAction: Boolean = true
    ): Notification {
        val title = context.getString(R.string.app_name)

        val progressPrefix = when {
            progressPercent != null -> "${progressPercent.coerceIn(0, 100)}%"
            progressCurrent != null && progressTotal != null && progressTotal > 0 -> "$progressCurrent/$progressTotal"
            else -> null
        }

        val baseText = currentLabel?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.optimization_notification_preparing)

        val contentText = progressPrefix?.let { "$it • $baseText" } ?: baseText

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, OptimizationWorkerStopReceiver::class.java)
            .putExtra(OptimizationWorkerStopReceiver.EXTRA_WORK_ID, workId)

        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(showStopAction)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
        if (showStopAction) {
            builder.addAction(
                NotificationCompat.Action(
                    0,
                    context.getString(R.string.optimization_notification_stop),
                    stopPendingIntent
                )
            )
        }

        // Determinate progress bar when we have a usable percentage.
        // Note: Notification progress bars are not shown in all OEM skins, but it's the standard API.
        progressPercent?.let { percent ->
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }

        // If we do NOT have a percent but we do have current/total, we can still render a percent.
        if (progressPercent == null && progressCurrent != null && progressTotal != null && progressTotal > 0) {
            val derivedPercent = ((progressCurrent.toFloat() / progressTotal.toFloat()) * 100f).roundToInt()
                .coerceIn(0, 100)
            builder.setProgress(100, derivedPercent, false)
        }

        return builder.build()
    }

    private const val NOTIFICATION_CHANNEL_ID = "optimization"
    private const val NOTIFICATION_ID = 1001

    private fun shouldSkipEnsureChannel(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isChannelEnsured.get()
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        isChannelEnsured.set(false)
    }
}
