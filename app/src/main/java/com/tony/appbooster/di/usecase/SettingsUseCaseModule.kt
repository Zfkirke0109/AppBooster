package com.tony.appbooster.di.usecase

import com.tony.appbooster.domain.repository.SettingsRepository
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import com.tony.appbooster.domain.service.TelemetryExporter
import com.tony.appbooster.domain.usecase.settings.ObserveAppOptimizationTypeUseCase
import com.tony.appbooster.domain.usecase.settings.ObserveHeavyAppPackagesUseCase
import com.tony.appbooster.domain.usecase.settings.SetAppOptimizationTypeUseCase
import com.tony.appbooster.domain.usecase.settings.SetHeavyAppPackagesUseCase
import com.tony.appbooster.domain.usecase.telemetry.ObserveLatestOptimizationTelemetryUseCase
import com.tony.appbooster.domain.usecase.telemetry.RetryLatestTelemetryExportUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides settings-related use cases. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsUseCaseModule {

    @Provides
    @Singleton
    fun provideObserveAppOptimizationTypeUseCase(settingsRepository: SettingsRepository): ObserveAppOptimizationTypeUseCase =
        ObserveAppOptimizationTypeUseCase(settingsRepository)

    @Provides
    @Singleton
    fun provideObserveHeavyAppPackagesUseCase(settingsRepository: SettingsRepository): ObserveHeavyAppPackagesUseCase =
        ObserveHeavyAppPackagesUseCase(settingsRepository)

    @Provides
    @Singleton
    fun provideSetAppOptimizationTypeUseCase(settingsRepository: SettingsRepository): SetAppOptimizationTypeUseCase =
        SetAppOptimizationTypeUseCase(settingsRepository)

    @Provides
    @Singleton
    fun provideSetHeavyAppPackagesUseCase(settingsRepository: SettingsRepository): SetHeavyAppPackagesUseCase =
        SetHeavyAppPackagesUseCase(settingsRepository)

    @Provides
    @Singleton
    fun provideObserveLatestOptimizationTelemetryUseCase(
        repository: OptimizationTelemetryRepository
    ): ObserveLatestOptimizationTelemetryUseCase =
        ObserveLatestOptimizationTelemetryUseCase(repository)

    @Provides
    @Singleton
    fun provideRetryLatestTelemetryExportUseCase(
        repository: OptimizationTelemetryRepository,
        exporter: TelemetryExporter
    ): RetryLatestTelemetryExportUseCase =
        RetryLatestTelemetryExportUseCase(repository, exporter)
}

