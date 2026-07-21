package com.tony.appbooster.presentation.screen.settings.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tony.appbooster.R
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry

@Composable
internal fun TelemetryStatusCard(
    run: OptimizationRunTelemetry?,
    isExporting: Boolean,
    onRetryExport: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (run == null) {
                Text(stringResource(R.string.telemetry_no_runs))
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Text(
                    text = stringResource(R.string.telemetry_latest_run, run.runId),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                stringResource(
                    R.string.telemetry_status,
                    run.status.name.replace('_', ' ').lowercase()
                )
            )
            Text(
                stringResource(
                    R.string.telemetry_counts,
                    run.optimizedSucceededCount,
                    run.alreadyOptimizedCount,
                    run.failedOrRefusedCount,
                    run.unverifiedCount
                )
            )
            Text(
                stringResource(
                    R.string.telemetry_storage,
                    Formatter.formatShortFileSize(context, run.storageBefore.availableBytes),
                    run.storageAfter?.availableBytes?.let {
                        Formatter.formatShortFileSize(context, it)
                    } ?: "—"
                )
            )

            run.exportError?.let { error ->
                Text(stringResource(R.string.telemetry_export_error, error))
            }
            when {
                run.exportUri != null -> Text(stringResource(R.string.telemetry_exported, run.exportUri))
                run.exportError == null -> Text(stringResource(R.string.telemetry_export_pending))
            }

            OutlinedButton(
                onClick = onRetryExport,
                enabled = run.status.isTerminal && !isExporting
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text(
                    text = stringResource(
                        if (isExporting) R.string.telemetry_exporting
                        else R.string.telemetry_retry_export
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
