package com.tony.appbooster.presentation.worker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.repository.AdbRepository
import com.tony.appbooster.domain.usecase.adb.EnsureAdbConnectedUseCase
import com.tony.appbooster.domain.usecase.optimization.OptimizeAppUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Foreground [CoroutineWorker] that runs the app optimization workflow.
 *
 * Business purpose:
 * - Keeps optimization running even when the app is backgrounded.
 * - Shows a persistent notification with the currently optimized package.
 * - Exposes a Stop action that cancels this Worker. Cancellation also requests repository-side
 *   cancellation so the UI reflects the stop immediately when opened.
 *
 * The Worker reuses the singleton [AdbRepository] instance; since the UI subscribes to its
 * progress/log flows, state stays in sync across app/worker.
 *
 * @property repository Repository coordinating shell connection and optimization progress.
 * @constructor Creates the worker with injected dependencies.
 */
@HiltWorker
class OptimizationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: AdbRepository,
    private val ensureAdbConnectedUseCase: EnsureAdbConnectedUseCase,
    private val optimizeAppUseCase: OptimizeAppUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = coroutineScope {
        val optimizationModeRaw = inputData.getString(KEY_OPTIMIZATION_MODE)
            ?: return@coroutineScope Result.failure()

        val mode = parseOptimizationMode(optimizationModeRaw) ?: return@coroutineScope Result.failure()
        val forceOptimize = inputData.getBoolean(KEY_FORCE_OPTIMIZE, false)

        WorkForegroundNotificationHelper.ensureChannel(applicationContext)

        val notificationUpdater = ForegroundNotificationUpdater()
        notificationUpdater.publishStart(workId = id.toString())

        // Update notification whenever the current package/progress changes.
        val notificationJob: Job = launch {
            repository.optimizationProgress
                .distinctUntilChangedBy { progress ->
                    // Avoid spam updates; update when either current app or computed percent changes.
                    "${progress.currentAppPackage}|${(progress.progress * 100f).toInt()}|${progress.processedCount}|${progress.totalCount}"
                }
                .collect { progress ->
                    notificationUpdater.publishProgress(
                        workId = id.toString(),
                        progress = progress
                    )
                }
        }

        try {
            when (ensureAdbConnectedUseCase()) {
                is Resource.Success -> Unit
                is Resource.Error -> return@coroutineScope Result.failure()
            }

            if (isStopped) {
                repository.cancelOptimization()
                return@coroutineScope Result.success()
            }

            when (optimizeAppUseCase(mode, forceOptimize)) {
                is Resource.Success -> Result.success()
                is Resource.Error -> Result.failure()
            }
        } catch (_: CancellationException) {
            // WorkManager cancellation (e.g., notification stop) lands here.
            repository.cancelOptimization()
            Result.success()
        } finally {
            notificationJob.cancel()
        }
    }

    private fun parseOptimizationMode(value: String): AppOptimizationType? {
        return when (value) {
            AppOptimizationType.SPEED_PROFILE.value -> AppOptimizationType.SPEED_PROFILE
            AppOptimizationType.FULL_OPTIMIZATION.value -> AppOptimizationType.FULL_OPTIMIZATION
            else -> null
        }
    }

    companion object {
        const val KEY_OPTIMIZATION_MODE = "optimization_mode"
        const val KEY_FORCE_OPTIMIZE = "force_optimize"
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 1000L
        private const val NOTIFICATION_LOG_TAG = "OptimizationWorkerNotification"

        /**
         * Enqueues a unique optimization worker.
         *
         * @param context Context used to enqueue work.
         * @param mode Optimization mode to run.
         * @param forceOptimize When true, compiles every package regardless
         *        of current compilation status.
         */
        fun enqueue(context: Context, mode: AppOptimizationType, forceOptimize: Boolean = false) {
            val request = androidx.work.OneTimeWorkRequestBuilder<OptimizationWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        KEY_OPTIMIZATION_MODE to mode.value,
                        KEY_FORCE_OPTIMIZE to forceOptimize
                    )
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Cancels the currently running optimization worker, if any.
         *
         * @param context Context used to cancel work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private const val UNIQUE_WORK_NAME = "optimization_work"
        const val TAG = "optimization"
    }

    /**
     * Coalesces foreground notification updates so WorkManager is not spammed by per-tick state
     * emissions.
     *
     * Progress updates are throttled to roughly one publish per second, while start/completed/
     * canceled/failed/paused updates bypass throttling to keep terminal states immediate.
     *
     * @property lastPublishedAtMs Elapsed realtime of the last published notification update.
     * @property lastPublishedState Last published material notification state for deduplication.
     */
    private inner class ForegroundNotificationUpdater {
        private var lastPublishedAtMs: Long = 0L
        private var lastPublishedState: NotificationRenderState? = null

        /**
         * Publishes the required initial foreground notification immediately.
         *
         * @param workId Active work id used by the stop action pending intent.
         */
        suspend fun publishStart(workId: String) {
            publish(
                workId = workId,
                state = NotificationRenderState(),
                forceUpdate = true,
                terminalUpdate = false,
                reason = "start"
            )
        }

        /**
         * Publishes progress notification updates with throttle and duplicate suppression.
         *
         * @param workId Active work id used by the stop action pending intent.
         * @param progress Current optimization progress snapshot.
         */
        suspend fun publishProgress(workId: String, progress: OptimizationProgress) {
            val percent = (progress.progress * 100f).toInt().coerceIn(0, 100)
            val state = NotificationRenderState(
                currentLabel = progress.currentAppPackage.ifBlank { null },
                progressPercent = if (progress.totalCount > 0) percent else null,
                progressCurrent = progress.processedCount,
                progressTotal = progress.totalCount,
                showStopAction = !progress.isTerminalState()
            )

            val terminalReason = progress.terminalReason()
            publish(
                workId = workId,
                state = state,
                forceUpdate = terminalReason != null,
                terminalUpdate = terminalReason != null,
                reason = terminalReason ?: "progress"
            )
        }

        private suspend fun publish(
            workId: String,
            state: NotificationRenderState,
            forceUpdate: Boolean,
            terminalUpdate: Boolean,
            reason: String
        ) {
            val now = SystemClock.elapsedRealtime()
            val duplicate = lastPublishedState == state
            if (!forceUpdate && duplicate) {
                logNotificationEvent("skipped_duplicate", reason, state, now)
                return
            }

            if (!forceUpdate && (now - lastPublishedAtMs) < NOTIFICATION_UPDATE_INTERVAL_MILLIS) {
                logNotificationEvent("skipped_throttled", reason, state, now)
                return
            }

            setForeground(
                WorkForegroundNotificationHelper.createForegroundInfo(
                    context = applicationContext,
                    workId = workId,
                    currentLabel = state.currentLabel,
                    progressPercent = state.progressPercent,
                    progressCurrent = state.progressCurrent,
                    progressTotal = state.progressTotal,
                    showStopAction = state.showStopAction
                )
            )

            lastPublishedAtMs = now
            lastPublishedState = state
            val eventName = if (terminalUpdate) "forced_terminal_update" else "published"
            logNotificationEvent(eventName, reason, state, now)
        }

        private fun logNotificationEvent(
            eventName: String,
            reason: String,
            state: NotificationRenderState,
            timestampMs: Long
        ) {
            if (!Log.isLoggable(NOTIFICATION_LOG_TAG, Log.DEBUG)) {
                return
            }
            Log.d(
                NOTIFICATION_LOG_TAG,
                "event=$eventName reason=$reason ts_ms=$timestampMs " +
                    "label=${state.currentLabel ?: "none"} " +
                    "progress=${state.progressPercent ?: -1} " +
                    "count=${state.progressCurrent}/${state.progressTotal} " +
                    "stopAction=${state.showStopAction}"
            )
        }
    }

    /**
     * Snapshot of material fields used to render and deduplicate notification updates.
     *
     * @property currentLabel Optional package name shown in the content text.
     * @property progressPercent Optional normalized percent [0,100] for determinate progress.
     * @property progressCurrent Optional processed item count.
     * @property progressTotal Optional total item count.
     * @property showStopAction Whether the stop action is shown.
     */
    private data class NotificationRenderState(
        val currentLabel: String? = null,
        val progressPercent: Int? = null,
        val progressCurrent: Int? = null,
        val progressTotal: Int? = null,
        val showStopAction: Boolean = true
    )

    /**
     * Indicates whether the progress snapshot describes a finished optimization run.
     *
     * @receiver Progress snapshot emitted by [AdbRepository.optimizationProgress].
     * @return True when the run ended by completion, cancellation, failure, or pause.
     */
    private fun OptimizationProgress.isTerminalState(): Boolean {
        // Both checks are required: idle startup/placeholder states also have isRunning=false.
        return !isRunning && result !is OptimizationResult.None
    }

    /**
     * Resolves the terminal reason string used for forced immediate notification updates.
     *
     * @receiver Progress snapshot emitted by [AdbRepository.optimizationProgress].
     * @return Terminal reason when the run is finished; otherwise null.
     */
    private fun OptimizationProgress.terminalReason(): String? {
        if (!isTerminalState()) {
            return null
        }
        return when (result) {
            OptimizationResult.Completed -> "completed"
            OptimizationResult.Canceled -> "canceled"
            OptimizationResult.Failed -> "failed"
            is OptimizationResult.Paused -> "paused"
            else -> null
        }
    }
}
