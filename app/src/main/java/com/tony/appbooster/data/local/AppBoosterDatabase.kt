package com.tony.appbooster.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity

@Database(
    entities = [OptimizationStepEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppBoosterDatabase : RoomDatabase() {
    abstract fun optimizationStepDao(): OptimizationStepDao
}
