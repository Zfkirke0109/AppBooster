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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `given compile command fails when force optimization runs then fails fast without marking package optimized`() = runTest {
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
        coEvery { optimizationStepDao.findLatestResumableRunId("speed-profile", true) } returns null
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
        coEvery {
            shellDataSource.executeCommandDetailed(failingCommand)
        } returns Result.success(ShellCommandResult(exitCode = 1, stdout = "", stderr = "Package not found"))

        val result = repository.executeOptimizationCommand(
            mode = AppOptimizationType.SPEED_PROFILE,
            forceOptimize = true
        )

        assertTrue(result is Resource.Error)
        assertTrue(repository.optimizationProgress.value.result is OptimizationResult.Failed)
        assertEquals(1, repository.optimizationProgress.value.processedCount)
        assertEquals(failingPackage, repository.optimizationProgress.value.currentAppPackage)
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

        coEvery { optimizationStepDao.findLatestResumableRunId("speed-profile", true) } returns runId
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
}
