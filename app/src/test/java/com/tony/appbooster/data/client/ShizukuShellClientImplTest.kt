package com.tony.appbooster.data.client

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rikka.shizuku.Shizuku

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuShellClientImplTest {
    private val ioDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        mockkStatic(Shizuku::class)
        justRun { Shizuku.addBinderReceivedListenerSticky(any()) }
        justRun { Shizuku.addBinderDeadListener(any()) }
        justRun { Shizuku.addRequestPermissionResultListener(any()) }
        justRun { Shizuku.bindUserService(any(), any()) }
        justRun { Shizuku.unbindUserService(any(), any(), any()) }
        every { Shizuku.isPreV11() } returns false

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        unmockkStatic(Shizuku::class)
    }

    @Test
    fun `live binder with permission is ready when package lookup is filtered`() {
        packageLookupFails()
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_GRANTED

        assertEquals(ShizukuState.Ready, createClient().state.value)
    }

    @Test
    fun `live binder without permission requires permission when package lookup is filtered`() {
        packageLookupFails()
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_DENIED

        assertEquals(ShizukuState.PermissionRequired, createClient().state.value)
    }

    @Test
    fun `dead binder with visible package is not running`() {
        every { Shizuku.pingBinder() } returns false
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } returns PackageInfo()

        assertEquals(ShizukuState.NotRunning, createClient().state.value)
    }

    @Test
    fun `dead binder with missing package is not installed`() {
        every { Shizuku.pingBinder() } returns false
        packageLookupFails()

        assertEquals(ShizukuState.NotInstalled, createClient().state.value)
    }

    private fun createClient() = ShizukuShellClientImpl(context, ioDispatcher)

    private fun packageLookupFails() {
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } throws
            PackageManager.NameNotFoundException()
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
