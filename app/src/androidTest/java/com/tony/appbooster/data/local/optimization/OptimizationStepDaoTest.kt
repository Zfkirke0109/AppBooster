package com.tony.appbooster.data.local.optimization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.data.local.AppBoosterDatabase
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizationStepDaoTest {
    private lateinit var database: AppBoosterDatabase
    private lateinit var stepDao: OptimizationStepDao
    private lateinit var runDao: OptimizationRunDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppBoosterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stepDao = database.optimizationStepDao()
        runDao = database.optimizationRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun terminalRunWithPendingStepsIsNotResumed() = runBlocking {
        runDao.upsert(run(runId = 10L, status = OptimizationRunStatus.FAILED, startedAtMs = 300L))
        stepDao.insertAll(listOf(step(runId = 10L, createdAtMs = 300L)))

        assertNull(stepDao.findLatestResumableRunId(MODE, false))
    }

    @Test
    fun pausedRunIsSelectedWhenNewerTerminalRunHasPendingSteps() = runBlocking {
        runDao.upsert(run(runId = 10L, status = OptimizationRunStatus.PAUSED, startedAtMs = 200L))
        runDao.upsert(run(runId = 20L, status = OptimizationRunStatus.FAILED, startedAtMs = 300L))
        stepDao.insertAll(
            listOf(
                step(runId = 10L, createdAtMs = 200L),
                step(runId = 20L, createdAtMs = 300L)
            )
        )

        assertEquals(10L, stepDao.findLatestResumableRunId(MODE, false))
    }

    @Test
    fun legacyRunWithoutTelemetryRowRemainsResumable() = runBlocking {
        stepDao.insertAll(listOf(step(runId = 30L, createdAtMs = 100L)))

        assertEquals(30L, stepDao.findLatestResumableRunId(MODE, false))
    }

    private fun run(
        runId: Long,
        status: OptimizationRunStatus,
        startedAtMs: Long
    ) = OptimizationRunEntity(
        runId = runId,
        modeKey = MODE,
        requestedCompilerFilter = "speed-profile",
        fullDexoptScope = false,
        forceOptimize = false,
        status = status.name,
        startedAtMs = startedAtMs,
        storageTotalBeforeBytes = 100L,
        storageAvailableBeforeBytes = 80L,
        storageReserveBytes = 5L,
        storageCapturedBeforeAtMs = startedAtMs,
        appVersionName = "1.7.0",
        appVersionCode = 10_700L,
        deviceManufacturer = "Samsung",
        deviceModel = "SM-S918U1",
        sdkInt = 36,
        buildFingerprint = "test/fingerprint",
        threadPolicy = "package-manager-managed"
    )

    private fun step(runId: Long, createdAtMs: Long) = OptimizationStepEntity(
        runId = runId,
        stepIndex = 0,
        totalSteps = 1,
        skippedCount = 0,
        packageName = "com.example.app$runId",
        mode = MODE,
        forceOptimize = false,
        createdAtMs = createdAtMs
    )

    private companion object {
        const val MODE = "SPEED_PROFILE"
    }
}
