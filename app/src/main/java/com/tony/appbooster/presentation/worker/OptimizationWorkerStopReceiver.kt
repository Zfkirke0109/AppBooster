package com.tony.appbooster.presentation.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.tony.appbooster.domain.repository.AdbRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Receives the Stop action from the foreground notification and cancels the running Worker.
 *
 * Business purpose:
 * - Allow the user to stop analysis/optimization from the system notification.
 * - Ensure the UI receives the same "Canceled" state as when stopping via the in-app HeroCard.
 *
 * Implementation note:
 * - WorkManager cancellation only stops the Worker. To keep UI state consistent,
 *   we also request repository-side cancellation.
 */
class OptimizationWorkerStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val workId = intent.getStringExtra(EXTRA_WORK_ID) ?: return
        val workUuid = runCatching { UUID.fromString(workId) }.getOrNull() ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Request repository-side cancellation first so StateFlows update
                // even if WorkManager stops the worker before its cancellation
                // handler runs.
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    WorkerStopReceiverEntryPoint::class.java
                )

                // Safe to call both; each method is a no-op if not running.
                entryPoint.adbRepository().cancelOptimization()
                entryPoint.adbRepository().cancelAnalysis()

                // Stop the worker after repository state has recorded the user's
                // cancellation request.
                WorkManager.getInstance(appContext).cancelWorkById(workUuid)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerStopReceiverEntryPoint {
        fun adbRepository(): AdbRepository
    }

    companion object {
        const val EXTRA_WORK_ID = "extra_work_id"
    }
}
