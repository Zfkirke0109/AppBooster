package com.tony.appbooster.di

import android.content.Context
import androidx.room.Room
import com.tony.appbooster.data.local.AppBoosterDatabase
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppBoosterDatabase(
        @ApplicationContext context: Context
    ): AppBoosterDatabase {
        return Room.databaseBuilder(
            context,
            AppBoosterDatabase::class.java,
            "appbooster.db"
        ).build()
    }

    @Provides
    fun provideOptimizationStepDao(
        database: AppBoosterDatabase
    ): OptimizationStepDao = database.optimizationStepDao()
}
