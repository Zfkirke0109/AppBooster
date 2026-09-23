package com.tony.appbooster.presentation.screen.dashboard.components

import android.os.SystemClock
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tony.appbooster.R
import com.tony.appbooster.presentation.ui.theme.AppBoosterTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Unified in-progress card shared by both optimization and analysis phases.
 *
 * Display data is supplied through [ProcessProgressState]. Package elapsed time
 * updates independently of the progress bar while the screen is visible.
 *
 * @param state Typed state describing the current process – either
 *   [ProcessProgressState.Optimizing] or [ProcessProgressState.Scanning].
 * @param onStop Callback invoked when the user taps the stop button.
 */
@Composable
internal fun ProcessProgressContent(
    state: ProcessProgressState,
    onStop: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(300, easing = EaseOutCubic),
        label = "progressAnimation"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header: title + subtitle left, stop button right ────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Percentage badge
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))

            // Circular stop button – mirrors the ReadyContent play button
            Surface(
                onClick = onStop,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.dashboard_stop_optimization_cd),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ── Progress bar ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
            )
        }

        // ── Current package chip ────────────────────────────────────────
        if (state.currentPackage.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.currentPackage
                            .substringAfterLast(".")
                            .replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.currentPackage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (state is ProcessProgressState.Optimizing) {
            CurrentPackageTiming(state)
        }
    }
}

@Composable
private fun CurrentPackageTiming(state: ProcessProgressState.Optimizing) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val elapsedSeconds by produceState<Long?>(
        initialValue = null,
        key1 = lifecycleOwner,
        key2 = state.runId,
        key3 = state.currentPackage to state.currentPackageStartedAtElapsedMs
    ) {
        value = null
        if (state.currentPackage.isBlank() || state.currentPackageStartedAtElapsedMs == null) {
            return@produceState
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                value = state.currentPackageElapsedSeconds(SystemClock.elapsedRealtime())
                delay(1_000L)
            }
        }
    }
    elapsedSeconds?.let { seconds ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.reliability_package_elapsed, seconds / 60L, seconds % 60L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (seconds >= LONG_RUNNING_PACKAGE_SECONDS) {
                Text(
                    text = stringResource(R.string.reliability_package_long_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val LONG_RUNNING_PACKAGE_SECONDS = 15L

@Preview(name = "Long-running package", showBackground = true)
@Composable
private fun ProcessProgressContentPreview() {
    AppBoosterTheme {
        ProcessProgressContent(
            state = ProcessProgressState.Optimizing(
                title = stringResource(R.string.dashboard_optimizing_title),
                subtitle = "8 / 24 apps",
                progress = 0.33f,
                currentPackage = "com.example.heavy",
                runId = 1L,
                currentPackageStartedAtElapsedMs = SystemClock.elapsedRealtime() - 75_000L
            ),
            onStop = {}
        )
    }
}
