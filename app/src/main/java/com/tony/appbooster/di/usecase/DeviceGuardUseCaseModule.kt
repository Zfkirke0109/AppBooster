package com.tony.appbooster.di.usecase

import com.tony.appbooster.domain.repository.DeviceGuardRepository
import com.tony.appbooster.domain.usecase.device.GetDeviceGuardStatusUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceGuardUseCaseModule {

    @Provides
    @Singleton
    fun provideGetDeviceGuardStatusUseCase(
        repository: DeviceGuardRepository
    ): GetDeviceGuardStatusUseCase = GetDeviceGuardStatusUseCase(repository)
}
