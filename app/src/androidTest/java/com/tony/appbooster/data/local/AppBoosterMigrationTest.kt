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

    @Test
    fun migration2To3BackfillsOutcomesAndAddsRuntimeIdentity() {
        helper.createDatabase(TEST_DATABASE_V3, 2).apply {
            execSQL(
                """
                INSERT INTO optimization_steps (
                    id, runId, stepIndex, totalSteps, skippedCount, packageName,
                    mode, forceOptimize, status, createdAtMs, updatedAtMs
                ) VALUES (
                    2, 43, 0, 1, 0, 'com.example.verified',
                    'SPEED', 1, 'SUCCEEDED', 1000, 2000
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_V3,
            3,
            true,
            AppBoosterMigrations.MIGRATION_2_3
        ).apply {
            query("SELECT outcome, stableOsAdjusted FROM optimization_steps WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("VERIFIED_REQUESTED_FILTER", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            query("PRAGMA table_info(optimization_runs)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(1)
                assertTrue("android_build" in names)
                assertTrue("art_module_version" in names)
                assertTrue("artStorageDeltaBytes" in names)
            }
            close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "appbooster-migration-test"
        const val TEST_DATABASE_V3 = "appbooster-migration-v3-test"
    }
}
