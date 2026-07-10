package com.tony.appbooster.data.repository

import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepStatus
import com.tony.appbooster.data.util.CompilationInfoResolver
import com.tony.appbooster.data.util.OptimizationLogger
import com.tony.appbooster.data.util.PackageListQueryService
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.AppCompilationInfo
import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.device.DeviceGuardSnapshot
import com.tony.appbooster.domain.model.device.StandbyBucket
import com.tony.appbooster.domain.model.device.StandbyBucketSnapshot
import com.tony.appbooster.domain.model.device.ThermalStatusSnapshot
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.repository.DeviceGuardRepository
import com.tony.appbooster.domain.repository.SettingsRepository
import io.mockk.coJustRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AdbRepositoryImpl] optimization command execution.
 */
class AdbRepositoryImplTest {

    private lateinit var shellDataSource: AdbShellDataSource
    private lateinit var logger: OptimizationLogger
    private lateinit var packageQuery: PackageListQueryService
    private lateinit var compilationResolver: CompilationInfoResolver
    private lateinit var optimizationStepDao: OptimizationStepDao
    private lateinit var deviceGuardRepository: DeviceGuardRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: AdbRepositoryImpl

    @Before
    fun setUp() {
        shellDataSource = mockk()
        logger = OptimizationLogger()
        packageQuery = mockk()
        compilationResolver = mockk(relaxed = true)
        optimizationStepDao = mockk()
        deviceGuardRepository = mockk()
        settingsRepository = mockk()
        coEvery {
            deviceGuardRepository.getDeviceGuardSnapshot()
        } returns Resource.Success(allowedDeviceGuardSnapshot())
        coEvery { settingsRepository.getHeavyAppPackages() } returns Resource.Success(emptySet())
        repository = AdbRepositoryImpl(
            shellDataSource = shellDataSource,
            logger = logger,
            packageQuery = packageQuery,
            compilationResolver = compilationResolver,
            optimizationStepDao = optimizationStepDao,
            deviceGuardRepository = deviceGuardRepository,
            settingsRepository = settingsRepository
        )
    }

    @Test
    fun `given compile command fails for one package when optimizing then continues and completes remaining packages`() = runTest {
        val goodPackage = "com.example.good"
        val failingPackage = "com.example.bad"
        val runId = 42L
        val goodStep = optimizationStep(
            id = 1L,
            runId = runId,
            stepIndex = 0,
            packageName = goodPackage
        )
        val failingStep = optimizationStep(
            id = 2L,
            runId = runId,
            stepIndex = 1,
            packageName = failingPackage
        )
        val goodCommand = ShellCommandSpec.PackageCompile(
            packageName = goodPackage,
            mode = "speed-profile",
            force = true
        )
        val failingCommand = ShellCommandSpec.PackageCompile(
            packageName = failingPackage,
            mode = "speed-profile",
            force = true
        )
        coEvery { packageQuery.queryInstalledPackages() } returns listOf(goodPackage, failingPackage)
        coEvery { optimizationStepDao.findLatestResumableRunId("SPEED_PROFILE", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(goodStep, failingStep)
        coJustRun { optimizationStepDao.markRunning(goodStep.id, "verify", any()) }
        coJustRun { optimizationStepDao.markRunning(failingStep.id, "verify", any()) }
        coJustRun {
            optimizationStepDao.markSucceeded(
                id = goodStep.id,
                afterFilter = "speed-profile",
                exitCode = 0,
                stdout = "Success",
                stderr = "",
                updatedAtMs = any()
            )
        }
        coJustRun {
            optimizationStepDao.markFailed(
                id = failingStep.id,
                exitCode = 1,
                stdout = "",
                stderr = "Package not found",
                updatedAtMs = any()
            )
        }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(goodPackage, "speed-profile")
        } returnsMany listOf(
            compilationInfo(goodPackage, compilerFilter = "verify"),
            compilationInfo(goodPackage, compilerFilter = "speed-profile", needsOptimization = false)
        )
        coEvery {
            compilationResolver.queryPackageCompilationInfo(failingPackage, "speed-profile")
        } returns compilationInfo(failingPackage, compilerFilter = "verify")
        coEvery {
            shellDataSource.executeCommandDetailed(goodCommand)
        } returns Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
        stubPackageDump(goodPackage, "speed-profile")
        coEvery {
            shellDataSource.executeCommandDetailed(failingCommand)
        } returns Result.success(ShellCommandResult(exitCode = 1, stdout = "", stderr = "Package not found"))

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        // A single package failing to compile (e.g. a Knox/OEM-protected system
        // package) must not abort the whole run: every other package should
        // still be located and processed. The failed package must be counted
        // separately, never as optimized, and the run must finish as
        // CompletedWithIssues rather than a clean Completed.
        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.CompletedWithIssues)
        assertEquals(2, repository.optimizationProgress.value.processedCount)
        assertEquals(1, repository.optimizationProgress.value.optimizedSucceededCount)
        assertEquals(1, repository.optimizationProgress.value.failedOrRefusedCount)
        assertEquals(0, repository.optimizationProgress.value.unverifiedCount)
        assertTrue(repository.logEntries.value.any { entry ->
            entry.type == LogEntryType.ERROR && entry.packageName == failingPackage
        })
        verify(exactly = 1) { compilationResolver.markOptimized(goodPackage) }
        verify(exactly = 0) { compilationResolver.markOptimized(failingPackage) }
        coVerify(exactly = 1) {
            shellDataSource.executeCommandDetailed(goodCommand)
        }
        coVerify(exactly = 1) {
            shellDataSource.executeCommandDetailed(failingCommand)
        }
        coVerify(exactly = 1) {
            optimizationStepDao.markFailed(
                id = failingStep.id,
                exitCode = 1,
                stdout = "",
                stderr = "Package not found",
                updatedAtMs = any()
            )
        }
    }

    @Test
    fun `given resumable run exists when optimization starts then skips completed steps and resumes pending work`() = runTest {
        val completedPackage = "com.example.done"
        val pendingPackage = "com.example.pending"
        val runId = 77L
        val completedStep = optimizationStep(
            id = 10L,
            runId = runId,
            stepIndex = 0,
            packageName = completedPackage,
            status = OptimizationStepStatus.SUCCEEDED
        )
        val pendingStep = optimizationStep(
            id = 11L,
            runId = runId,
            stepIndex = 1,
            packageName = pendingPackage
        )
        val pendingCommand = ShellCommandSpec.PackageCompile(
            packageName = pendingPackage,
            mode = "speed-profile",
            force = true
        )

        coEvery { optimizationStepDao.findLatestResumableRunId("SPEED_PROFILE", true) } returns runId
        coEvery { optimizationStepDao.prepareResumedRun(runId, any()) } returns listOf(completedStep, pendingStep)
        coJustRun { optimizationStepDao.markRunning(pendingStep.id, "verify", any()) }
        coJustRun {
            optimizationStepDao.markSucceeded(
                id = pendingStep.id,
                afterFilter = "speed-profile",
                exitCode = 0,
                stdout = "Success",
                stderr = "",
                updatedAtMs = any()
            )
        }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(pendingPackage, "speed-profile")
        } returnsMany listOf(
            compilationInfo(pendingPackage, compilerFilter = "verify"),
            compilationInfo(pendingPackage, compilerFilter = "speed-profile", needsOptimization = false)
        )
        coEvery {
            shellDataSource.executeCommandDetailed(pendingCommand)
        } returns Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
        stubPackageDump(pendingPackage, "speed-profile")

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Completed)
        assertEquals(2, repository.optimizationProgress.value.processedCount)
        assertEquals(1f, repository.optimizationProgress.value.progress)
        coVerify(exactly = 0) { packageQuery.queryInstalledPackages() }
        coVerify(exactly = 0) {
            shellDataSource.executeCommandDetailed(match {
                it is ShellCommandSpec.PackageCompile && it.packageName == completedPackage
            })
        }
        coVerify(exactly = 1) {
            shellDataSource.executeCommandDetailed(pendingCommand)
        }
    }

    @Test
    fun `given battery is below guard when optimization starts then pauses without compiling`() = runTest {
        coEvery {
            deviceGuardRepository.getDeviceGuardSnapshot()
        } returns Resource.Success(allowedDeviceGuardSnapshot(batteryPercent = 20))

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Error)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Paused)
        coVerify(exactly = 0) { packageQuery.queryInstalledPackages() }
        coVerify(exactly = 0) { shellDataSource.executeCommandDetailed(any()) }
    }

    @Test
    fun `given full compile mode when optimizing then help fallback is used and command includes full scope`() = runTest {
        val packageName = "com.example.game"
        val step = optimizationStep(id = 21L, runId = 500L, stepIndex = 0, packageName = packageName)
        // One UI 8.5 advertises -v[:LOG_TAGS], so the verbose flag is expected too.
        val expectedCommand = ShellCommandSpec.PackageCompile(
            packageName = packageName,
            mode = "speed",
            force = true,
            full = true,
            verbose = true
        )

        // `cmd package compile --help` fails on One UI 8.5 ("Unknown option: --help");
        // the repository must fall back to `cmd package help`.
        coEvery {
            shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageCompileHelp)
        } returns Result.success(
            ShellCommandResult(exitCode = 1, stdout = "", stderr = "Unknown option: --help")
        )
        coEvery {
            shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageHelp)
        } returns Result.success(
            ShellCommandResult(exitCode = 0, stdout = ONE_UI_85_PACKAGE_HELP, stderr = "")
        )
        coEvery { packageQuery.queryInstalledPackages() } returns listOf(packageName)
        coEvery { optimizationStepDao.findLatestResumableRunId("ADVANCED_FULL_COMPILE", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(step)
        coJustRun { optimizationStepDao.markRunning(any(), any(), any()) }
        coJustRun { optimizationStepDao.markSucceeded(any(), any(), any(), any(), any(), any()) }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(packageName, "speed")
        } returnsMany listOf(
            compilationInfo(packageName, compilerFilter = "verify"),
            compilationInfo(packageName, compilerFilter = "speed", needsOptimization = false)
        )
        coEvery {
            shellDataSource.executeCommandDetailed(expectedCommand)
        } returns Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
        stubPackageDump(packageName, "speed")

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.ADVANCED_FULL_COMPILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Completed)
        assertEquals(1, repository.optimizationProgress.value.optimizedSucceededCount)
        coVerify(exactly = 1) { shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageHelp) }
        coVerify(exactly = 1) { shellDataSource.executeCommandDetailed(expectedCommand) }
    }

    @Test
    fun `given full dex2oat speed mode when optimizing then compiles all eligible packages without full scope`() = runTest {
        val first = "com.example.one"
        val second = "com.example.two"
        val runId = 600L
        val steps = listOf(
            optimizationStep(id = 31L, runId = runId, stepIndex = 0, packageName = first),
            optimizationStep(id = 32L, runId = runId, stepIndex = 1, packageName = second)
        )

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(first, second)
        coEvery { optimizationStepDao.findLatestResumableRunId("FULL_DEX2OAT_SPEED", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns steps
        coJustRun { optimizationStepDao.markRunning(any(), any(), any()) }
        coJustRun { optimizationStepDao.markSucceeded(any(), any(), any(), any(), any(), any()) }
        listOf(first, second).forEach { packageName ->
            coEvery {
                compilationResolver.queryPackageCompilationInfo(packageName, "speed")
            } returnsMany listOf(
                compilationInfo(packageName, compilerFilter = "verify"),
                compilationInfo(packageName, compilerFilter = "speed", needsOptimization = false)
            )
            coEvery {
                shellDataSource.executeCommandDetailed(
                    ShellCommandSpec.PackageCompile(packageName = packageName, mode = "speed", force = true)
                )
            } returns Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
            stubPackageDump(packageName, "speed")
        }

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.FULL_DEX2OAT_SPEED,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Completed)
        assertEquals(2, repository.optimizationProgress.value.optimizedSucceededCount)
        // Normal compile scope: no --full, and the target set must not be
        // narrowed to the heavy-apps selection.
        coVerify(exactly = 0) {
            shellDataSource.executeCommandDetailed(match {
                it is ShellCommandSpec.PackageCompile && it.full
            })
        }
        coVerify(exactly = 0) { settingsRepository.getHeavyAppPackages() }
    }

    @Test
    fun `given heavy apps mode when optimizing then compiles only selected packages without full scope`() = runTest {
        val heavyPackage = "com.example.heavygame"
        val otherPackage = "com.example.other"
        val step = optimizationStep(id = 41L, runId = 700L, stepIndex = 0, packageName = heavyPackage)

        coEvery { settingsRepository.getHeavyAppPackages() } returns Resource.Success(setOf(heavyPackage))
        coEvery { packageQuery.queryInstalledPackages() } returns listOf(heavyPackage, otherPackage)
        coEvery { optimizationStepDao.findLatestResumableRunId("HEAVY_APPS_SPEED", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(step)
        coJustRun { optimizationStepDao.markRunning(any(), any(), any()) }
        coJustRun { optimizationStepDao.markSucceeded(any(), any(), any(), any(), any(), any()) }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(heavyPackage, "speed")
        } returnsMany listOf(
            compilationInfo(heavyPackage, compilerFilter = "verify"),
            compilationInfo(heavyPackage, compilerFilter = "speed", needsOptimization = false)
        )
        coEvery {
            shellDataSource.executeCommandDetailed(
                ShellCommandSpec.PackageCompile(packageName = heavyPackage, mode = "speed", force = true)
            )
        } returns Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
        stubPackageDump(heavyPackage, "speed")

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.HEAVY_APPS_SPEED,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Completed)
        assertEquals(1, repository.optimizationProgress.value.optimizedSucceededCount)
        coVerify(exactly = 0) {
            shellDataSource.executeCommandDetailed(match {
                it is ShellCommandSpec.PackageCompile && it.packageName == otherPackage
            })
        }
        coVerify(exactly = 0) {
            shellDataSource.executeCommandDetailed(match {
                it is ShellCommandSpec.PackageCompile && it.full
            })
        }
    }

    @Test
    fun `given optimization is cancelled during compile when command returns then final state remains canceled`() = runTest {
        val packageName = "com.example.cancel"
        val runId = 800L
        val step = optimizationStep(id = 51L, runId = runId, stepIndex = 0, packageName = packageName)
        val command = ShellCommandSpec.PackageCompile(
            packageName = packageName,
            mode = "speed-profile",
            force = true
        )

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(packageName)
        coEvery { optimizationStepDao.findLatestResumableRunId("SPEED_PROFILE", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(step)
        coJustRun { optimizationStepDao.markRunning(step.id, "verify", any()) }
        coJustRun { optimizationStepDao.markSucceeded(any(), any(), any(), any(), any(), any()) }
        coJustRun { optimizationStepDao.markRunCanceled(any(), any()) }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(packageName, "speed-profile")
        } returnsMany listOf(
            compilationInfo(packageName, compilerFilter = "verify"),
            compilationInfo(packageName, compilerFilter = "speed-profile", needsOptimization = false)
        )
        coEvery { shellDataSource.executeCommandDetailed(command) } coAnswers {
            repository.cancelOptimization()
            Result.success(ShellCommandResult(exitCode = 0, stdout = "Success", stderr = ""))
        }
        stubPackageDump(packageName, "speed-profile")

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertFalse(repository.optimizationProgress.value.isRunning)
        assertEquals("", repository.optimizationProgress.value.currentAppPackage)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Canceled)
        coVerify(exactly = 1) { optimizationStepDao.markRunCanceled(any(), any()) }
    }

    @Test
    fun `given analysis is cancelled during scan when loop checks cancellation then scan does not finalize as successful`() = runTest {
        val firstPackage = "com.example.first"
        val secondPackage = "com.example.second"

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(firstPackage, secondPackage)
        coEvery {
            compilationResolver.queryPackageCompilationInfo(firstPackage, "speed-profile")
        } answers {
            runBlocking { repository.cancelAnalysis() }
            compilationInfo(firstPackage, compilerFilter = "verify")
        }

        val result = repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        assertTrue(result is Resource.Error)
        assertFalse(repository.optimizationAnalysis.value.isScanning)
        assertEquals("", repository.optimizationAnalysis.value.currentPackage)
        assertFalse(repository.optimizationAnalysis.value.hasScanned)
        coVerify(exactly = 0) {
            compilationResolver.queryPackageCompilationInfo(secondPackage, "speed-profile")
        }
    }

    @Test
    fun `given worker cancellation interrupts compile command when stop requested then result is canceled not failed`() = runTest {
        val packageName = "com.example.interrupted"
        val step = optimizationStep(id = 61L, runId = 900L, stepIndex = 0, packageName = packageName)
        val command = ShellCommandSpec.PackageCompile(
            packageName = packageName,
            mode = "speed-profile",
            force = true
        )

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(packageName)
        coEvery { optimizationStepDao.findLatestResumableRunId("SPEED_PROFILE", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(step)
        coJustRun { optimizationStepDao.markRunning(step.id, "verify", any()) }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(packageName, "speed-profile")
        } returns compilationInfo(packageName, compilerFilter = "verify")
        coEvery { shellDataSource.executeCommandDetailed(command) } coAnswers {
            // Stop flow: repository-side cancel lands first, then WorkManager
            // cancellation aborts the in-flight shell command.
            repository.cancelOptimization()
            throw CancellationException("Worker cancelled")
        }

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertFalse(repository.optimizationProgress.value.isRunning)
        assertEquals("", repository.optimizationProgress.value.currentAppPackage)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Canceled)
        assertEquals(0, repository.optimizationProgress.value.failedOrRefusedCount)
    }

    @Test
    fun `given cancelled command surfaces as result failure then package is not counted as failed and run is canceled`() = runTest {
        val packageName = "com.example.wrappedcancel"
        val step = optimizationStep(id = 62L, runId = 901L, stepIndex = 0, packageName = packageName)
        val command = ShellCommandSpec.PackageCompile(
            packageName = packageName,
            mode = "speed-profile",
            force = true
        )

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(packageName)
        coEvery { optimizationStepDao.findLatestResumableRunId("SPEED_PROFILE", true) } returns null
        coJustRun { optimizationStepDao.insertAll(any()) }
        coEvery { optimizationStepDao.getStepsForRun(any()) } returns listOf(step)
        coJustRun { optimizationStepDao.markRunning(step.id, "verify", any()) }
        coEvery {
            compilationResolver.queryPackageCompilationInfo(packageName, "speed-profile")
        } returns compilationInfo(packageName, compilerFilter = "verify")
        // The shell layer wraps execution in runCatching, so worker
        // cancellation can arrive as Result.failure(CancellationException).
        coEvery { shellDataSource.executeCommandDetailed(command) } returns
            Result.failure(CancellationException("Worker cancelled"))

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Canceled)
        assertEquals(0, repository.optimizationProgress.value.failedOrRefusedCount)
        coVerify(exactly = 0) {
            optimizationStepDao.markFailed(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `given worker cancellation interrupts scan then analysis stops without a failure log`() = runTest {
        val firstPackage = "com.example.first"

        coEvery { packageQuery.queryInstalledPackages() } returns listOf(firstPackage)
        coEvery {
            compilationResolver.queryPackageCompilationInfo(firstPackage, "speed-profile")
        } answers {
            throw CancellationException("Worker cancelled")
        }

        val result = repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        assertTrue(result is Resource.Error)
        assertFalse(repository.optimizationAnalysis.value.isScanning)
        assertEquals("", repository.optimizationAnalysis.value.currentPackage)
        assertFalse(repository.optimizationAnalysis.value.hasScanned)
        // A user-visible ERROR entry would misreport the cancellation as a failure.
        assertFalse(repository.logEntries.value.any { it.type == LogEntryType.ERROR })
    }

    private fun optimizationStep(
        id: Long,
        runId: Long,
        stepIndex: Int,
        packageName: String,
        status: String = OptimizationStepStatus.PENDING
    ): OptimizationStepEntity = OptimizationStepEntity(
        id = id,
        runId = runId,
        stepIndex = stepIndex,
        totalSteps = 2,
        skippedCount = 0,
        packageName = packageName,
        mode = "speed-profile",
        forceOptimize = true,
        status = status,
        createdAtMs = 1_000L + stepIndex,
        updatedAtMs = 1_000L + stepIndex
    )

    private fun compilationInfo(
        packageName: String,
        compilerFilter: String,
        needsOptimization: Boolean = true
    ): AppCompilationInfo = AppCompilationInfo(
        packageName = packageName,
        compilerFilter = compilerFilter,
        lastCompilationTimeMs = null,
        lastUpdateTimeMs = null,
        oatFileExists = compilerFilter != "verify",
        needsOptimization = needsOptimization
    )

    private fun stubPackageDump(packageName: String, compilerFilter: String) {
        coEvery {
            shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageDump(packageName))
        } returns Result.success(
            ShellCommandResult(
                exitCode = 0,
                stdout = """
                    Dexopt state:
                      [$packageName]
                        arm64: [status=$compilerFilter] [reason=cmdline]
                """.trimIndent(),
                stderr = ""
            )
        )
    }

    private fun allowedDeviceGuardSnapshot(
        batteryPercent: Int = 80,
        thermalStatus: Int = 0,
        standbyBucket: StandbyBucket = StandbyBucket.Active
    ): DeviceGuardSnapshot = DeviceGuardSnapshot(
        batteryPercent = batteryPercent,
        thermalStatus = ThermalStatusSnapshot(
            statusCode = thermalStatus,
            label = "none",
            rawOutput = "mStatus=$thermalStatus"
        ),
        standbyBucket = StandbyBucketSnapshot(
            bucket = standbyBucket,
            rawValue = standbyBucket.name.lowercase()
        )
    )

    private companion object {
        /** `cmd package help` excerpt as printed by One UI 8.5 / Android 16 on the S23 Ultra. */
        private val ONE_UI_85_PACKAGE_HELP = """
            Package manager (package) commands:
              help
              compile [-r COMPILATION_REASON] [-m COMPILER_FILTER] [-p PRIORITY] [-f] [--primary-dex] [--secondary-dex] [--include-dependencies] [--full] [--split SPLIT_NAME] [--reset] [--force-merge-profile] [-v[:LOG_TAGS]] [-a | PACKAGE_NAME]
                -m: select compilation mode
                Available options for the compiler filter:
                  speed
                  speed-profile
                  verify
                --full Dexopt all above. (Recommended)
                -v[:LOG_TAGS] Enable verbose logging
              reconcile-secondary-dex-files
        """.trimIndent()
    }
}
