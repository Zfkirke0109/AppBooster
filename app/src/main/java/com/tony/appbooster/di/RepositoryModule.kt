package com.tony.appbooster.di

import com.tony.appbooster.data.repository.AppInfoRepositoryImpl
import com.tony.appbooster.data.repository.DeviceGuardRepositoryImpl
import com.tony.appbooster.data.repository.SettingsDataStoreRepository
import com.tony.appbooster.domain.repository.AppInfoRepository
import com.tony.appbooster.domain.repository.DeviceGuardRepository
import com.tony.appbooster.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing bindings from domain-level repository interfaces
 * to their concrete data-layer implementations. This module centralizes
 * repository wiring so that use cases and ViewModels depend only on
 * abstractions while Hilt resolves the concrete implementations.
 *
 * @return Configured bindings for [AppInfoRepository] and
 * [SettingsRepository] scoped to the application lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Binds [AppInfoRepositoryImpl] as the concrete implementation for
     * [AppInfoRepository] so that use cases can retrieve app metadata
     * without referencing data-layer details.
     *
     * @param impl Concrete data-layer implementation.
     * @return Bound [AppInfoRepository] instance managed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindAppInfoRepository(
        impl: AppInfoRepositoryImpl
    ): AppInfoRepository

    /**
     * Binds [SettingsDataStoreRepository] as the concrete implementation for
     * [SettingsRepository] so that any use case or ViewModel can persist and
     * observe settings without leaking DataStore details.
     *
     * @param impl Concrete DataStore-backed implementation.
     * @return Bound [SettingsRepository] abstraction managed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsDataStoreRepository
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindDeviceGuardRepository(
        impl: DeviceGuardRepositoryImpl
    ): DeviceGuardRepository
}
