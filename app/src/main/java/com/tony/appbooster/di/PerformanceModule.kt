package com.tony.appbooster.di

import com.tony.appbooster.data.performance.PerformanceRepositoryImpl
import com.tony.appbooster.domain.repository.PerformanceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** One measurement session shared by the screen and its foreground worker. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceModule {
    /** Bind the durable Android-backed measurement implementation. */
    @Binds @Singleton abstract fun bindPerformance(impl: PerformanceRepositoryImpl): PerformanceRepository
}
