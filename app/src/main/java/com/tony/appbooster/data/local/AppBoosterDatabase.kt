package com.tony.appbooster.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationRunDao
import com.tony.appbooster.data.local.optimization.OptimizationRunEntity

@Database(
    entities = [OptimizationStepEntity::class, OptimizationRunEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppBoosterDatabase : RoomDatabase() {
    abstract fun optimizationStepDao(): OptimizationStepDao
    abstract fun optimizationRunDao(): OptimizationRunDao
}
