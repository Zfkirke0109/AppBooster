package com.tony.appbooster.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppBoosterMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `optimization_runs` (
                    `runId` INTEGER NOT NULL,
                    `modeKey` TEXT NOT NULL,
                    `requestedCompilerFilter` TEXT NOT NULL,
                    `fullDexoptScope` INTEGER NOT NULL,
                    `forceOptimize` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `statusMessage` TEXT,
                    `startedAtMs` INTEGER NOT NULL,
                    `finishedAtMs` INTEGER,
                    `totalTargetedCount` INTEGER NOT NULL,
                    `processedCount` INTEGER NOT NULL,
                    `optimizedSucceededCount` INTEGER NOT NULL,
                    `alreadyOptimizedCount` INTEGER NOT NULL,
                    `skippedNoProfileCount` INTEGER NOT NULL,
                    `failedOrRefusedCount` INTEGER NOT NULL,
                    `unverifiedCount` INTEGER NOT NULL,
                    `canceledCount` INTEGER NOT NULL,
                    `storageTotalBeforeBytes` INTEGER NOT NULL,
                    `storageAvailableBeforeBytes` INTEGER NOT NULL,
                    `storageReserveBytes` INTEGER NOT NULL,
                    `storageCapturedBeforeAtMs` INTEGER NOT NULL,
                    `storageTotalAfterBytes` INTEGER,
                    `storageAvailableAfterBytes` INTEGER,
                    `storageCapturedAfterAtMs` INTEGER,
                    `appVersionName` TEXT NOT NULL,
                    `appVersionCode` INTEGER NOT NULL,
                    `deviceManufacturer` TEXT NOT NULL,
                    `deviceModel` TEXT NOT NULL,
                    `sdkInt` INTEGER NOT NULL,
                    `buildFingerprint` TEXT NOT NULL,
                    `threadPolicy` TEXT NOT NULL,
                    `exportUri` TEXT,
                    `exportError` TEXT,
                    `exportedAtMs` INTEGER,
                    PRIMARY KEY(`runId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_optimization_runs_startedAtMs` " +
                    "ON `optimization_runs` (`startedAtMs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_optimization_runs_status` " +
                    "ON `optimization_runs` (`status`)"
            )

            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `displayCommand` TEXT")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `durationMs` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageTotalBeforeBytes` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageAvailableBeforeBytes` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageReserveBytes` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageCapturedBeforeAtMs` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageTotalAfterBytes` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageAvailableAfterBytes` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `storageCapturedAfterAtMs` INTEGER")
            db.execSQL("ALTER TABLE `optimization_steps` ADD COLUMN `verificationSource` TEXT")
        }
    }
}
