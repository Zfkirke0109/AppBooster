package com.tony.appbooster.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBoosterMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppBoosterDatabase::class.java
    )

    @Test
    fun migration1To2PreservesLegacyStepsAndAddsTelemetryTables() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO optimization_steps (
                    id, runId, stepIndex, totalSteps, skippedCount, packageName,
                    mode, forceOptimize, status, createdAtMs, updatedAtMs
                ) VALUES (
                    1, 42, 0, 1, 0, 'com.example.legacy',
                    'SPEED_PROFILE', 1, 'SUCCEEDED', 1000, 2000
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            AppBoosterMigrations.MIGRATION_1_2
        ).apply {
            query("SELECT packageName, displayCommand FROM optimization_steps WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("com.example.legacy", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'optimization_runs'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "appbooster-migration-test"
    }
}
