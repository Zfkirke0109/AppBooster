package com.tony.appbooster.data.performance

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.tony.appbooster.data.util.CompileModeSupportParser
import com.tony.appbooster.data.util.DexoptStatusParser
import com.tony.appbooster.di.AdbIoDispatcher
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.common.requireSuccess
import com.tony.appbooster.domain.model.performance.*
import com.tony.appbooster.domain.repository.PerformanceRepository
import com.tony.appbooster.domain.service.ShellOperationCoordinator
import com.tony.appbooster.domain.service.StorageCapacityProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Captures Android-reported timings under an exclusive shell lease and persists each sample. */
@Singleton
class PerformanceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: AdbShellDataSource,
    private val coordinator: ShellOperationCoordinator,
    private val storageCapacityProvider: StorageCapacityProvider,
    @AdbIoDispatcher private val io: CoroutineDispatcher
) : PerformanceRepository {
    private companion object {
        const val ANDROID_UIDS_PER_USER = 100_000
        const val PRIMARY_ANDROID_USER_ID = 0
    }

    private val state = MutableStateFlow<PerformanceSession?>(null)
    override val session = state.asStateFlow()
    private val stateMutex = Mutex()
    private var loaded = false
    private val file get() = AtomicFile(File(context.filesDir, "performance-session-v1.json"))

    override suspend fun load() = withContext(io) {
        stateMutex.withLock {
            if (!loaded) {
                // A corrupt capture must not prevent selecting a fresh session.
                loaded = true
                if (file.baseFile.exists()) {
                    val saved = PerformanceSessionJson.decode(file.readFully().toString(Charsets.UTF_8))
                    state.value = if (saved.activeOperation != null) saved.copy(activeOperation = null,
                        message = "Capture interrupted. Partial samples are retained; repeat the incomplete phase.") else saved
                }
                loaded = true
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun apps(): List<MeasurementApp> = withContext(io) {
        check(Process.myUid() / ANDROID_UIDS_PER_USER == PRIMARY_ANDROID_USER_ID) {
            "Performance measurement is available only in the primary Android profile. Open OptiDroid outside work profiles or Secure Folder."
        }
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName || activity.packageName == "moe.shizuku.privileged.api" ||
                    activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    return@mapNotNull null
                val info = pm.getPackageInfo(activity.packageName, 0)
                MeasurementApp(activity.packageName, resolved.loadLabel(pm).toString(),
                    ComponentName(activity.packageName, activity.name).flattenToShortString(),
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), info.lastUpdateTime)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    override suspend fun select(app: MeasurementApp) = withContext(io) {
        load()
        coordinator.exclusive {
            check(state.value?.activeOperation == null) { "Wait for the current operation to stop." }
            require(apps().any { it == app }) { "The selected app changed. Refresh the app list." }
            save(PerformanceSession(System.currentTimeMillis(), app))
        }
    }

    override suspend fun execute(action: String, sessionId: Long, operationId: String): Result<Unit> = withContext(io) {
        load()
        // Fail outside session mutation if another workflow already owns the shell.
        try {
            coordinator.exclusive {
                val initial = requireNotNull(state.value) { "Select an app first." }
                require(initial.id == sessionId && initial.activeOperation == null) { "This measurement session changed." }
                require(action in setOf("BEFORE", "COMPILE", "AFTER"))
                check(initial.lastOperationId != operationId) { "This operation already started. Repeat the interrupted phase explicitly." }
                validateIdentity(initial.app)
                if (action != "BEFORE") require(initial.before != null) { "Capture the baseline first." }
                if (action == "AFTER") require(initial.compilation?.exitCode == 0) { "Compile the selected app successfully first." }
                // Stale results must disappear before a repeat operation can fail.
                val next = when (action) {
                    "BEFORE" -> initial.copy(before = null, compilation = null, after = null)
                    "COMPILE" -> initial.copy(compilation = null, after = null)
                    else -> initial.copy(after = null)
                }.copy(activeOperation = action, partialSamples = emptyList(), message = null, lastOperationId = operationId)
                save(next)
                try {
                    if (action == "COMPILE") compile(next) else capture(next, action)
                    save(requireNotNull(state.value).copy(activeOperation = null, partialSamples = emptyList()))
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable) { finishWithError("Stopped. No further launches will run; incomplete samples are not compared.") }
                    throw cancel
                } catch (error: Exception) {
                    finishWithError(error.message ?: "Measurement failed.")
                    throw error
                }
            }
            Result.success(Unit)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Result.failure(error) }
    }

    @Suppress("DEPRECATION")
    private fun validateIdentity(app: MeasurementApp) {
        check(Process.myUid() / ANDROID_UIDS_PER_USER == PRIMARY_ANDROID_USER_ID) {
            "Performance measurement requires the primary Android profile."
        }
        val pm = context.packageManager
        val info = pm.getPackageInfo(app.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val component = requireNotNull(ComponentName.unflattenFromString(app.component))
        val activity = pm.getActivityInfo(component, 0)
        require(app.versionCode == version && app.lastUpdateTime == info.lastUpdateTime &&
            activity.enabled && activity.exported && activity.applicationInfo.enabled) {
            "App version or launcher changed. Select the app again and capture a new baseline."
        }
    }

    private suspend fun capture(current: PerformanceSession, action: String) {
        val start = System.currentTimeMillis()
        val uptime = SystemClock.elapsedRealtime()
        val captureBootCount = bootCount()
        val art = artVersion()
        if (action == "AFTER") {
            val before = requireNotNull(current.before)
            require(before.buildFingerprint == Build.FINGERPRINT && before.artVersion == art) {
                "Android or ART changed. Start a new baseline."
            }
            require(before.bootCount == null || captureBootCount == null || before.bootCount == captureBootCount) {
                "Device restarted after the baseline. Capture a new baseline."
            }
        }
        val filter = packageFilter(current.app.packageName)
        val samples = mutableListOf<LaunchSample>()
        repeat(5) {
            currentCoroutineContext().ensureActive()
            validateIdentity(current.app)
            check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the phone before measuring." }
            val before = conditions()
            check(before.screenInteractive) { "Keep the screen on while measuring." }
            check((before.thermalStatus ?: 0) < 2) { "Let the phone cool before measuring (Android thermal status ${before.thermalStatus})." }
            val command = ShellCommandSpec.MeasureColdLaunch(current.app.component)
            val result = shell.executeCommandDetailed(command).getOrThrow().requireSuccess(command.displayCommand)
            currentCoroutineContext().ensureActive()
            val timing = StartupOutputParser.parse(result.stdout, current.app.packageName)
            val after = conditions()
            samples += LaunchSample(timing, System.currentTimeMillis(), before, after, result.stdout)
            save(requireNotNull(state.value).copy(partialSamples = samples.toList()))
            // A consistent settling interval reduces launch-to-launch overlap; cache/profile warming remains disclosed.
            delay(3_000)
        }
        validateIdentity(current.app)
        check(artVersion() == art) { "ART changed during capture. Repeat with a fresh baseline." }
        check(captureBootCount == null || bootCount() == captureBootCount) { "Boot identity changed during capture. Start a new baseline." }
        val phase = MeasurementPhase(current.app, Build.FINGERPRINT, art, start, System.currentTimeMillis(), uptime, samples, filter, captureBootCount)
        phase.statistics // refuse incomplete/invalid phases before committing
        save(requireNotNull(state.value).let { if (action == "BEFORE") it.copy(before = phase) else it.copy(after = phase) })
    }

    private suspend fun compile(current: PerformanceSession) {
        val conditions = conditions()
        check((conditions.thermalStatus ?: 0) < 3) { "Let the phone cool before compiling." }
        check((conditions.batteryPercent ?: 100) >= 35) { "Charge the phone to at least 35% before compiling." }
        val help = shell.executeCommandDetailed(ShellCommandSpec.PackageHelp).getOrThrow().requireSuccess("cmd package help").stdout
        val support = CompileModeSupportParser.parse(help)
        check("speed" in support.supportedFilters && support.supportsFullScope && support.supportsVerboseCompile) {
            "This Android build does not advertise speed, --full and verbose compile evidence. Measurement compilation is unavailable."
        }
        val storage = storageCapacityProvider.snapshot()
        check(!storage.isBelowReserve) {
            "Storage guard: %.2f GiB available; %.2f GiB must remain free.".format(
                storage.availableBytes.toDouble() / (1024L * 1024L * 1024L),
                storage.reserveBytes.toDouble() / (1024L * 1024L * 1024L)
            )
        }
        val cmd = ShellCommandSpec.PackageCompile(current.app.packageName, "speed", force = false, full = true, verbose = true)
        val start = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val result = shell.executeCommandDetailed(cmd).getOrThrow()
        currentCoroutineContext().ensureActive()
        val classified = DexoptStatusParser.classifyCompileResult("speed", result.exitCode, result.stdout)
        val evidence = MeasurementCompile(start, System.currentTimeMillis(), SystemClock.elapsedRealtime() - elapsed,
            cmd.displayCommand, result.exitCode, classified.outcome.name, classified.art.actualCompilerFilter,
            classified.art.sizeBeforeBytes, classified.art.sizeBytes, result.stdout, result.stderr)
        save(requireNotNull(state.value).copy(compilation = evidence))
        result.requireSuccess(cmd.displayCommand)
        validateIdentity(current.app)
    }

    private suspend fun packageFilter(pkg: String): String? {
        val output = shell.executeCommandDetailed(ShellCommandSpec.PackageDump(pkg)).getOrThrow().requireSuccess("Package status").stdout
        return DexoptStatusParser.parseCompilerFilterFromDexoptDump(pkg, output)?.takeUnless { it == "unknown-present" }
    }

    private fun conditions(): MeasurementConditions {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun value(key: String) = battery?.getIntExtra(key, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
        val level = value(BatteryManager.EXTRA_LEVEL)
        val scale = value(BatteryManager.EXTRA_SCALE)
        val power = context.getSystemService(PowerManager::class.java)
        return MeasurementConditions(value(BatteryManager.EXTRA_TEMPERATURE),
            if (level != null && scale != null && scale > 0) level * 100 / scale else null,
            value(BatteryManager.EXTRA_PLUGGED), if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null,
            power.isPowerSaveMode, power.isInteractive)
    }

    private fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun artVersion(): String = listOf("com.google.android.art", "com.android.art").firstNotNullOfOrNull { name ->
        try { context.packageManager.getPackageInfo(name, if (Build.VERSION.SDK_INT >= 29) PackageManager.MATCH_APEX else 0)
            .let { "${it.packageName}:${it.versionName}:${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()}" }
        } catch (_: PackageManager.NameNotFoundException) { null }
    } ?: "unavailable"

    private fun finishWithError(message: String) {
        val terminal = requireNotNull(state.value).copy(activeOperation = null, message = message)
        try { save(terminal) } catch (_: Exception) {
            state.value = terminal.copy(message = "$message The latest state could not be saved; export it before closing the app.")
        }
    }

    private fun save(value: PerformanceSession) {
        val atomic = file
        val stream = atomic.startWrite()
        try { stream.write(PerformanceSessionJson.encode(value).toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        state.value = value
    }

    override suspend fun export(): String = withContext(io) { load(); PerformanceSessionJson.encode(requireNotNull(state.value)) }
}
