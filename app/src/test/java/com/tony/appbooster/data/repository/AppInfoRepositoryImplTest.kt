package com.tony.appbooster.data.repository

import com.tony.appbooster.BuildConfig
import com.tony.appbooster.domain.model.common.Resource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoRepositoryImplTest {
    @Test
    fun `app info matches the executing build metadata`() = runTest {
        val result = AppInfoRepositoryImpl().getAppInfo()

        val appInfo = when (result) {
            is Resource.Success -> result.data
            is Resource.Error -> throw AssertionError("Expected build metadata, got ${result.data}")
        }
        assertEquals(BuildConfig.VERSION_NAME, appInfo.versionName)
        assertEquals(BuildConfig.VERSION_CODE.toString(), appInfo.versionCode)
    }
}
