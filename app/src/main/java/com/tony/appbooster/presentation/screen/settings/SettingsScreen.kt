package com.tony.appbooster.presentation.screen.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony.appbooster.R
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import com.tony.appbooster.presentation.screen.common.basescreen.AppBaseScreen
import com.tony.appbooster.presentation.screen.settings.components.HeavyAppPackageSelector
import com.tony.appbooster.presentation.screen.settings.components.AboutCard
import com.tony.appbooster.presentation.screen.settings.components.OptimizationTypeSelector
import com.tony.appbooster.presentation.screen.settings.components.RollbackCandidatesCard
import com.tony.appbooster.presentation.screen.settings.components.SettingsSection
import com.tony.appbooster.presentation.screen.settings.components.ShizukuStatusCard
import com.tony.appbooster.presentation.screen.settings.components.TelemetryStatusCard
import com.tony.appbooster.presentation.tools.isTabletLayout
import com.tony.appbooster.presentation.viewmodel.base.UIState
import com.tony.appbooster.presentation.viewmodel.base.UIStatus
import com.tony.appbooster.presentation.viewmodel.settings.SettingsUiState
import com.tony.appbooster.presentation.viewmodel.settings.SettingsViewModel
import com.tony.appbooster.presentation.viewmodel.settings.SettingsUiEffect
import kotlinx.coroutines.flow.collectLatest

/**
 * Entry point composable for the Settings screen. It wires the Hilt-provided
 * [SettingsViewModel] into the base screen wrapper and delegates concrete
 * rendering to the internal content composable.
 *
 * @param viewModel The ViewModel exposing settings state and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collectLatest { effect ->
            when (effect) {
                is SettingsUiEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState
    ) { data ->
        SettingsScreenContent(
            data = data,
            onOptimizationTypeClick = { newType ->
                viewModel.onOptimizationTypeSelected(newType)
            },
            onHeavyPackageInputChanged = viewModel::onHeavyAppPackageInputChanged,
            onAddHeavyPackage = viewModel::onAddHeavyAppPackageClicked,
            onRemoveHeavyPackage = viewModel::onRemoveHeavyAppPackageClicked,
            onRollbackPackage = viewModel::onRollbackPackageClicked,
            onRetryTelemetryExport = viewModel::onRetryTelemetryExportClicked,
            snackbarHostState = snackbarHostState
        )
    }
}

/**
 * Renders the visual structure of the Settings screen using the provided
 * [SettingsUiState], allowing the user to inspect and change optimization
 * mode as well as view the current Shizuku status and app information.
 *
 * Automatically switches between a single-column phone layout and a
 * two-column tablet layout based on the current [LocalWindowSizeClass].
 *
 * @param data The current UI state snapshot for the Settings screen.
 * @param onOptimizationTypeClick Callback invoked when user requests a new optimization type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    data: SettingsUiState,
    onOptimizationTypeClick: (AppOptimizationType) -> Unit,
    onHeavyPackageInputChanged: (String) -> Unit,
    onAddHeavyPackage: () -> Unit,
    onRemoveHeavyPackage: (String) -> Unit,
    onRollbackPackage: (String) -> Unit,
    onRetryTelemetryExport: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val isTablet = isTabletLayout()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_top_bar_title),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
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
        if (isTablet) {
            SettingsTabletLayout(
                data = data,
                onOptimizationTypeClick = onOptimizationTypeClick,
                onHeavyPackageInputChanged = onHeavyPackageInputChanged,
                onAddHeavyPackage = onAddHeavyPackage,
                onRemoveHeavyPackage = onRemoveHeavyPackage,
                onRollbackPackage = onRollbackPackage,
                onRetryTelemetryExport = onRetryTelemetryExport,
                modifier = Modifier.padding(padding)
            )
        } else {
            SettingsPhoneLayout(
                data = data,
                onOptimizationTypeClick = onOptimizationTypeClick,
                onHeavyPackageInputChanged = onHeavyPackageInputChanged,
                onAddHeavyPackage = onAddHeavyPackage,
                onRemoveHeavyPackage = onRemoveHeavyPackage,
                onRollbackPackage = onRollbackPackage,
                onRetryTelemetryExport = onRetryTelemetryExport,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/**
 * Single-column phone layout: all settings sections stacked vertically with scroll.
 */
@Composable
private fun SettingsPhoneLayout(
    data: SettingsUiState,
    onOptimizationTypeClick: (AppOptimizationType) -> Unit,
    onHeavyPackageInputChanged: (String) -> Unit,
    onAddHeavyPackage: () -> Unit,
    onRemoveHeavyPackage: (String) -> Unit,
    onRollbackPackage: (String) -> Unit,
    onRetryTelemetryExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SettingsSection(
            title = stringResource(R.string.settings_section_optimization_title),
            subtitle = stringResource(R.string.settings_section_optimization_subtitle)
        ) {
            OptimizationTypeSelector(
                selectedType = data.appOptimizationType,
                onTypeSelected = onOptimizationTypeClick
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_heavy_targets_title),
            subtitle = stringResource(R.string.settings_section_heavy_targets_subtitle)
        ) {
            HeavyAppPackageSelector(
                packageInput = data.heavyAppPackageInput,
                selectedPackages = data.heavyAppPackages,
                onPackageInputChanged = onHeavyPackageInputChanged,
                onAddPackage = onAddHeavyPackage,
                onRemovePackage = onRemoveHeavyPackage
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_rollback_title),
            subtitle = stringResource(R.string.settings_section_rollback_subtitle)
        ) {
            RollbackCandidatesCard(
                candidates = data.rollbackCandidates,
                rollingBackPackageName = data.rollingBackPackageName,
                onRollbackPackage = onRollbackPackage
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_telemetry_title),
            subtitle = stringResource(R.string.settings_section_telemetry_subtitle)
        ) {
            TelemetryStatusCard(
                run = data.latestTelemetryRun,
                isExporting = data.isExportingTelemetry,
                onRetryExport = onRetryTelemetryExport
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_shizuku_title),
            subtitle = stringResource(R.string.settings_section_shizuku_subtitle)
        ) {
            ShizukuStatusCard(shizukuState = data.shizukuState)
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_about_title),
            subtitle = stringResource(R.string.settings_section_about_subtitle)
        ) {
            AboutCard(
                versionName = data.appVersionName,
                versionChannel = data.appVersionChannel
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Two-column tablet layout: Optimization mode on the left column (60%),
 * Shizuku status and About stacked on the right column (40%).
 *
 * Both columns scroll independently so content is never clipped regardless
 * of font scale or screen density.
 */
@Composable
private fun SettingsTabletLayout(
    data: SettingsUiState,
    onOptimizationTypeClick: (AppOptimizationType) -> Unit,
    onHeavyPackageInputChanged: (String) -> Unit,
    onAddHeavyPackage: () -> Unit,
    onRemoveHeavyPackage: (String) -> Unit,
    onRollbackPackage: (String) -> Unit,
    onRetryTelemetryExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Left column: Optimization mode ─────────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(
                title = stringResource(R.string.settings_section_optimization_title),
                subtitle = stringResource(R.string.settings_section_optimization_subtitle)
            ) {
                OptimizationTypeSelector(
                    selectedType = data.appOptimizationType,
                    onTypeSelected = onOptimizationTypeClick
                )
            }
            SettingsSection(
                title = stringResource(R.string.settings_section_heavy_targets_title),
                subtitle = stringResource(R.string.settings_section_heavy_targets_subtitle)
            ) {
                HeavyAppPackageSelector(
                    packageInput = data.heavyAppPackageInput,
                    selectedPackages = data.heavyAppPackages,
                    onPackageInputChanged = onHeavyPackageInputChanged,
                    onAddPackage = onAddHeavyPackage,
                    onRemovePackage = onRemoveHeavyPackage
                )
            }
            SettingsSection(
                title = stringResource(R.string.settings_section_rollback_title),
                subtitle = stringResource(R.string.settings_section_rollback_subtitle)
            ) {
                RollbackCandidatesCard(
                    candidates = data.rollbackCandidates,
                    rollingBackPackageName = data.rollingBackPackageName,
                    onRollbackPackage = onRollbackPackage
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // ── Right column: Shizuku status + About ────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(
                title = stringResource(R.string.settings_section_shizuku_title),
                subtitle = stringResource(R.string.settings_section_shizuku_subtitle)
            ) {
                ShizukuStatusCard(shizukuState = data.shizukuState)
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_telemetry_title),
                subtitle = stringResource(R.string.settings_section_telemetry_subtitle)
            ) {
                TelemetryStatusCard(
                    run = data.latestTelemetryRun,
                    isExporting = data.isExportingTelemetry,
                    onRetryExport = onRetryTelemetryExport
                )
            }

            SettingsSection(
                title = stringResource(R.string.settings_section_about_title),
                subtitle = stringResource(R.string.settings_section_about_subtitle)
            ) {
                AboutCard(
                    versionName = data.appVersionName,
                    versionChannel = data.appVersionChannel
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Preview of [SettingsScreenContent] in light mode for design validation.
 */
@Preview(showBackground = true, name = "Settings - Light")
@Composable
fun SettingsScreenContentLightPreview() {
    val uiState = SettingsUiState(
        appOptimizationType = AppOptimizationType.SPEED_PROFILE,
        appVersionName = "1.0.0",
        appVersionChannel = "Alpha",
        shizukuState = ShizukuState.Ready
    )
    val baseState = UIState(
        status = UIStatus.SUCCESS,
        data = uiState
    )
    AppBaseScreen(uiState = baseState) { data ->
        SettingsScreenContent(
            data = data,
            onOptimizationTypeClick = {},
            onHeavyPackageInputChanged = {},
            onAddHeavyPackage = {},
            onRemoveHeavyPackage = {},
            onRollbackPackage = {},
            onRetryTelemetryExport = {},
            snackbarHostState = remember { SnackbarHostState() }
        )
    }
}

/**
 * Preview of [SettingsScreenContent] in dark mode for design validation.
 */
@Preview(
    showBackground = true,
    name = "Settings - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
fun SettingsScreenContentDarkPreview() {
    val uiState = SettingsUiState(
        appOptimizationType = AppOptimizationType.HEAVY_APPS_SPEED,
        appVersionName = "1.0.0",
        appVersionChannel = "Beta",
        shizukuState = ShizukuState.NotRunning
    )
    val baseState = UIState(
        status = UIStatus.SUCCESS,
        data = uiState
    )
    AppBaseScreen(uiState = baseState) { data ->
        SettingsScreenContent(
            data = data,
            onOptimizationTypeClick = {},
            onHeavyPackageInputChanged = {},
            onAddHeavyPackage = {},
            onRemoveHeavyPackage = {},
            onRollbackPackage = {},
            onRetryTelemetryExport = {},
            snackbarHostState = remember { SnackbarHostState() }
        )
    }
}

