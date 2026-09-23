package com.tony.appbooster.presentation.screen.performance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony.appbooster.R
import com.tony.appbooster.domain.model.performance.*
import com.tony.appbooster.presentation.screen.common.basescreen.AppBaseScreen
import com.tony.appbooster.presentation.viewmodel.performance.*
import java.util.Locale

/** Measurement route; opening a measured app does not cancel its foreground worker. */
@Composable
fun PerformanceScreen(viewModel: PerformanceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    AppBaseScreen(uiState = state) { model ->
        PerformanceContent(model, viewModel::select, viewModel::start, viewModel::stop,
            onExport = { export.launch("Galaxy-OptiDroid-performance-${model.session?.id}.json") },
            onRefreshApps = { viewModel.refreshApps() })
    }
}

/** Stateless selected-app before/compile/after flow and observed measurements. */
@Composable
fun PerformanceContent(model: PerformanceUiModel, onSelect: (MeasurementApp) -> Unit,
    onStart: (String) -> Unit, onStop: () -> Unit, onExport: () -> Unit, onRefreshApps: () -> Unit = {}) {
    var selecting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val session = model.session
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.performance_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.performance_intro), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { onRefreshApps(); selecting = true }, enabled = !model.busy) {
            Text(session?.app?.label ?: stringResource(R.string.performance_select))
        }
        session?.let { s ->
            Text(s.app.packageName, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.performance_protocol))
            Button(onClick = { confirm = "BEFORE" }, enabled = !model.busy) { Text(stringResource(R.string.performance_before)) }
            Button(onClick = { confirm = "COMPILE" }, enabled = !model.busy && s.before != null) { Text(stringResource(R.string.performance_compile)) }
            Button(onClick = { confirm = "AFTER" }, enabled = !model.busy && s.compilation?.exitCode == 0) { Text(stringResource(R.string.performance_after)) }
            if (model.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${s.activeOperation ?: "Queued"} · ${s.partialSamples.size}/5 launch samples")
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.performance_stop)) }
                Text(stringResource(R.string.performance_stop_detail), style = MaterialTheme.typography.bodySmall)
            }
            s.before?.let { PhaseCard("Before compilation", it) }
            s.compilation?.let { c ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compilation evidence", style = MaterialTheme.typography.titleMedium)
                    Text("${format(c.durationMs / 1000.0)} seconds · exit ${c.exitCode}")
                    Text("Requested: speed, full scope\nART outcome: ${c.outcome.lowercase().replace('_', ' ')}\nReported filter: ${c.actualFilter ?: "unavailable"}")
                    val old = c.artSizeBeforeBytes; val new = c.artSizeAfterBytes
                    Text(if (old != null && new != null) "ART storage: ${format(old / 1048576.0)} → ${format(new / 1048576.0)} MiB" else "ART artifact sizes unavailable")
                    Text("Compilation time is how long the optimizer took, not how fast the app runs.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            s.after?.let { after ->
                PhaseCard("After compilation", after)
                s.before?.let { before ->
                    val problems = comparisonProblems(before, after)
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Observed startup change", style = MaterialTheme.typography.titleMedium)
                        if (problems.isNotEmpty()) {
                            Text("Comparison inconclusive")
                            problems.forEach { Text("• $it") }
                        } else {
                            val a = before.statistics; val b = after.statistics
                            val percent = a.improvementPercent(b)
                            Text(if (percent == 0.0) "Median unchanged" else "${format(kotlin.math.abs(percent))}% ${if (percent > 0) "shorter" else "longer"} median launch time")
                            if (a.minMs <= b.maxMs && b.minMs <= a.maxMs) Text("Sample ranges overlap; this small capture does not establish a reliable gain.")
                        }
                        Text(stringResource(R.string.performance_caveat))
                    } }
                }
            }
            OutlinedButton(onClick = onExport, enabled = !model.busy) { Text(stringResource(R.string.performance_export)) }
        }
        model.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What these measurements mean", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.performance_definitions))
        } }
    }
    if (selecting) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { selecting = false }, title = { Text("Select an app") },
            text = { Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(model.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                        TextButton(onClick = { selecting = false; onSelect(app) }) { Column { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                if (model.apps.isEmpty()) Text("No eligible user-installed launcher apps found.")
                Text("Selecting an app replaces the saved session. Export it first if you want to keep it.", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { selecting = false }) { Text("Close") } })
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (action == "COMPILE") "Compile selected app?" else "Measure five launches?") },
            text = { Text(if (action == "COMPILE") "Compile only ${session?.app?.label} with speed and full DEX scope. This can take several minutes and increase ART storage. Let the phone cool before measuring After."
                else "This closes and reopens ${session?.app?.label} five times. Finish any unsaved work first. Keep the phone unlocked and do not interact with the test app. When the notification finishes, return to OptiDroid. ${if (action == "BEFORE") "A new baseline replaces this session’s previous results." else "Use the same app screen and conditions as Before."}") },
            confirmButton = { TextButton(onClick = { confirm = null; onStart(action) }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun PhaseCard(title: String, phase: MeasurementPhase) {
    val stats = phase.statistics
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("${format(stats.medianMs)} ms median", style = MaterialTheme.typography.headlineSmall)
        Text("Range ${stats.minMs}–${stats.maxMs} ms · 5 process-cold launches")
        Text("Samples: ${phase.samples.joinToString { it.timing.totalTimeMs.toString() }} ms")
        Text("Compiler filter: ${phase.compilerFilter ?: "unavailable"}")
        val temperatures = phase.samples.flatMap { listOfNotNull(it.before.batteryTemperatureDeciC, it.after.batteryTemperatureDeciC) }
        Text(if (temperatures.isEmpty()) "Battery temperature unavailable" else "Battery temperature: ${format(temperatures.min() / 10.0)}–${format(temperatures.max() / 10.0)}°C")
        Text("First-frame launch timing reported by Android. Does not measure full content loading.", style = MaterialTheme.typography.bodySmall)
    } }
}

private fun format(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

@Preview(showBackground = true)
@Composable
private fun PerformancePreview() { MaterialTheme { PerformanceContent(PerformanceUiModel(), {}, {}, {}, {}) } }
