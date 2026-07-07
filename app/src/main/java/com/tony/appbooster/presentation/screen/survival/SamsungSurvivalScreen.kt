package com.tony.appbooster.presentation.screen.survival

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony.appbooster.R
import com.tony.appbooster.domain.model.device.DeviceGuardSnapshot
import com.tony.appbooster.domain.model.device.StandbyBucket
import com.tony.appbooster.presentation.screen.common.basescreen.AppBaseScreen
import com.tony.appbooster.presentation.screen.common.basescreen.ErrorDialogConfig
import com.tony.appbooster.presentation.viewmodel.survival.SamsungSurvivalUiEffect
import com.tony.appbooster.presentation.viewmodel.survival.SamsungSurvivalUiEvent
import com.tony.appbooster.presentation.viewmodel.survival.SamsungSurvivalUiState
import com.tony.appbooster.presentation.viewmodel.survival.SamsungSurvivalViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SamsungSurvivalScreen(
    viewModel: SamsungSurvivalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val openSettingsFailed = stringResource(R.string.survival_open_settings_failed)

    LaunchedEffect(viewModel, context) {
        viewModel.uiEffect.collectLatest { effect ->
            when (effect) {
                SamsungSurvivalUiEffect.OpenBatterySettings -> {
                    val intent = Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    runCatching { context.startActivity(intent) }
                        .onFailure { snackbarHostState.showSnackbar(openSettingsFailed) }
                }
                is SamsungSurvivalUiEffect.ShowSnackbar ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        errorDialogConfig = ErrorDialogConfig(
            onCancel = { viewModel.showErrorPopup(false) }
        )
    ) { state ->
        SamsungSurvivalContent(
            state = state,
            snackbarHostState = snackbarHostState,
            onRefresh = { viewModel.onEvent(SamsungSurvivalUiEvent.OnRefreshClicked) },
            onOpenBatterySettings = {
                viewModel.onEvent(SamsungSurvivalUiEvent.OnOpenBatterySettingsClicked)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamsungSurvivalContent(
    state: SamsungSurvivalUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.survival_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SurvivalHero(
                snapshot = state.snapshot,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                onOpenBatterySettings = onOpenBatterySettings
            )

            state.errorMessage?.let { error ->
                GuardStatusRow(
                    title = stringResource(R.string.survival_error_title),
                    description = error,
                    value = null,
                    isHealthy = false
                )
            }

            state.snapshot?.let { snapshot ->
                GuardChecklist(snapshot = snapshot)
            }
        }
    }
}

@Composable
private fun SurvivalHero(
    snapshot: DeviceGuardSnapshot?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val ready = snapshot?.let { it.canOptimize && !it.isStandbyRestricted } == true
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (ready) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = if (ready) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ready) {
                            stringResource(R.string.survival_ready_title)
                        } else {
                            stringResource(R.string.survival_attention_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.survival_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.survival_refresh),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                FilledTonalButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.survival_open_battery_settings),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GuardChecklist(snapshot: DeviceGuardSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuardStatusRow(
            title = stringResource(R.string.survival_battery_title),
            description = stringResource(R.string.survival_battery_description),
            value = snapshot.batteryPercent?.let { "$it%" }
                ?: stringResource(R.string.survival_value_unknown),
            isHealthy = !snapshot.isBatteryLow
        )
        GuardStatusRow(
            title = stringResource(R.string.survival_thermal_title),
            description = stringResource(R.string.survival_thermal_description),
            value = snapshot.thermalStatus.label,
            isHealthy = !snapshot.isThermalSevere
        )
        GuardStatusRow(
            title = stringResource(R.string.survival_standby_title),
            description = stringResource(R.string.survival_standby_description),
            value = snapshot.standbyBucket.rawValue,
            isHealthy = snapshot.standbyBucket.bucket != StandbyBucket.Restricted
        )
        GuardStatusRow(
            title = stringResource(R.string.survival_unrestricted_title),
            description = stringResource(R.string.survival_unrestricted_description),
            value = null,
            isHealthy = snapshot.standbyBucket.bucket != StandbyBucket.Restricted
        )
    }
}

@Composable
private fun GuardStatusRow(
    title: String,
    description: String,
    value: String?,
    isHealthy: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isHealthy) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isHealthy) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isHealthy) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
