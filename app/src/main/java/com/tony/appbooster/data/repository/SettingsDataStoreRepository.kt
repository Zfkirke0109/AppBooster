package com.tony.appbooster.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_DATA_STORE_NAME = "app_settings"


private val Context.settingsDataStore by preferencesDataStore(
    name = SETTINGS_DATA_STORE_NAME
)

/**
 * DataStore-backed implementation of [SettingsRepository] that persists user
 * configuration.
 *
 * The app currently persists only the selected [AppOptimizationType]. Legacy
 * ADB host/port/pairing-code configuration has been removed.
 *
 * @param applicationContext Application-level [Context] used to access DataStore.
 */
@Singleton
class SettingsDataStoreRepository @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context
) : SettingsRepository {

    private object Keys {
        val APP_OPTIMIZATION_TYPE: Preferences.Key<String> =
            stringPreferencesKey("app_optimization_type")
        val HEAVY_APP_PACKAGES: Preferences.Key<Set<String>> =
            stringSetPreferencesKey("heavy_app_packages")
    }

    override fun observeAppOptimizationType(): Flow<Resource<AppOptimizationType>> {
        return applicationContext.settingsDataStore
            .data
            .map { preferences ->
                val rawValue = preferences[Keys.APP_OPTIMIZATION_TYPE]
                val type = rawValue
                    ?.let(AppOptimizationType::fromStoredValue)
                    ?: AppOptimizationType.SPEED_PROFILE

                Resource.Success(type) as Resource<AppOptimizationType>
            }
            .catch { throwable ->
                emit(
                    Resource.Error(
                        ResourceError.DatabaseError(
                            message = throwable.message ?: "Unable to read optimization type"
                        )
                    )
                )
            }
    }

    override fun observeHeavyAppPackages(): Flow<Resource<Set<String>>> {
        return applicationContext.settingsDataStore
            .data
            .map { preferences ->
                val packages = preferences[Keys.HEAVY_APP_PACKAGES].orEmpty()
                Resource.Success(packages) as Resource<Set<String>>
            }
            .catch { throwable ->
                emit(
                    Resource.Error(
                        ResourceError.DatabaseError(
                            message = throwable.message ?: "Unable to read heavy app packages"
                        )
                    )
                )
            }
    }

    override suspend fun getHeavyAppPackages(): Resource<Set<String>> {
        return try {
            Resource.Success(
                applicationContext.settingsDataStore
                    .data
                    .map { preferences -> preferences[Keys.HEAVY_APP_PACKAGES].orEmpty() }
                    .first()
            )
        } catch (throwable: Throwable) {
            Resource.Error(
                ResourceError.DatabaseError(
                    message = throwable.message ?: "Unable to read heavy app packages"
                )
            )
        }
    }

    override suspend fun setAppOptimizationType(
        type: AppOptimizationType
    ): Resource<Unit> {
        return try {
            applicationContext.settingsDataStore.edit { preferences ->
                preferences[Keys.APP_OPTIMIZATION_TYPE] = type.value
            }
            Resource.Success(Unit)
        } catch (throwable: Throwable) {
            Resource.Error(
                ResourceError.DatabaseError(
                    message = throwable.message ?: "Unable to persist optimization type"
                )
            )
        }
    }

    override suspend fun setHeavyAppPackages(
        packageNames: Set<String>
    ): Resource<Unit> {
        return try {
            applicationContext.settingsDataStore.edit { preferences ->
                preferences[Keys.HEAVY_APP_PACKAGES] = packageNames
            }
            Resource.Success(Unit)
        } catch (throwable: Throwable) {
            Resource.Error(
                ResourceError.DatabaseError(
                    message = throwable.message ?: "Unable to persist heavy app packages"
                )
            )
        }
    }
}
