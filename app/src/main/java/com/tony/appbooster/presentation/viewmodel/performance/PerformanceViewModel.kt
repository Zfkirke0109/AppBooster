package com.tony.appbooster.presentation.viewmodel.performance

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.tony.appbooster.di.AdbIoDispatcher
import com.tony.appbooster.domain.model.performance.*
import com.tony.appbooster.domain.repository.PerformanceRepository
import com.tony.appbooster.presentation.navigation.interfaces.NavigationManager
import com.tony.appbooster.presentation.viewmodel.base.BaseViewModel
import com.tony.appbooster.presentation.worker.PerformanceWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Immutable measurement screen state. */
data class PerformanceUiModel(val apps: List<MeasurementApp> = emptyList(), val session: PerformanceSession? = null,
    val busy: Boolean = false, val message: String? = null)

/** Coordinates explicit measurement actions and Android's document export picker. */
@HiltViewModel
class PerformanceViewModel @Inject constructor(
    navigation: NavigationManager,
    private val repository: PerformanceRepository,
    @ApplicationContext private val context: Context,
    @AdbIoDispatcher private val io: CoroutineDispatcher
) : BaseViewModel<PerformanceUiModel, Unit, Unit>(navigation) {
    private val work = WorkManager.getInstance(context)
    init {
        updateUiData(PerformanceUiModel())
        viewModelScope.launch(exceptionHandler) {
            repository.load()
            updateUiData(current().copy(apps = repository.apps()))
            combine(repository.session, work.getWorkInfosForUniqueWorkFlow(PerformanceWorker.WORK_NAME)) { session, infos ->
                val active = infos.any { !it.state.isFinished }
                val latest = infos.firstOrNull()
                val error = latest?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
                current().copy(session = session, busy = active || session?.activeOperation != null,
                    message = session?.message ?: error)
            }.collect { updateUiData(it) }
        }
    }
    override fun handleEvent(event: Unit) = Unit
    private fun current() = uiState.value.data ?: PerformanceUiModel()
    /** Select a fresh app identity and discard the previous comparison explicitly. */
    fun select(app: MeasurementApp) = viewModelScope.launch(exceptionHandler) { repository.select(app) }
    /** Run exactly one user-requested phase; launching another app is intentional. */
    fun start(action: String) {
        val session = current().session ?: return
        if (current().busy) return
        updateUiData(current().copy(busy = true, message = null))
        work.enqueueUniqueWork(PerformanceWorker.WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PerformanceWorker>().setInputData(workDataOf("action" to action, "sessionId" to session.id)).build())
    }
    /** Stop future samples after the currently executing shell call returns. */
    fun stop() { work.cancelUniqueWork(PerformanceWorker.WORK_NAME) }
    /** Write a snapshot to the document selected by the user. */
    fun export(uri: Uri) = viewModelScope.launch(exceptionHandler) {
        withContext(io) {
            val json = repository.export()
            requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
        }
        updateUiData(current().copy(message = "Measurement JSON exported."))
    }
}
