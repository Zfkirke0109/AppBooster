package com.tony.appbooster.presentation.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.tony.appbooster.R
import com.tony.appbooster.domain.repository.PerformanceRepository
import com.tony.appbooster.presentation.activity.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Keeps an explicitly started capture alive while its target activity is in front. */
@HiltWorker
class PerformanceWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val repository: PerformanceRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // Automatic retries could turn an interrupted phase into a different experiment.
        if (runAttemptCount > 0) return Result.failure(workDataOf("error" to "Capture interrupted. Repeat the phase explicitly."))
        val action = inputData.getString("action") ?: return Result.failure()
        val sessionId = inputData.getLong("sessionId", -1)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("performance", "Performance measurement", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 174,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "performance")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Galaxy OptiDroid measurement")
            .setContentText(if (action == "COMPILE") "Compiling selected app. Stop waits for the active command." else "Measuring five launches. Keep the phone unlocked.")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(174, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(174, notification))
        return repository.execute(action, sessionId).fold({ Result.success() }, {
            Result.failure(workDataOf("error" to (it.message ?: "Measurement failed").take(500)))
        })
    }
    companion object {
        /** Unique work prevents two user taps from scheduling overlapping sessions. */
        const val WORK_NAME = "performance_measurement"
    }
}
