package com.tony.appbooster.data.repository

import com.tony.appbooster.domain.client.AdbShellClient
import com.tony.appbooster.domain.model.common.ShellCommandException
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AdbShellDataSourceImpl].
 */
class AdbShellDataSourceImplTest {

    private lateinit var adbClient: AdbShellClient
    private lateinit var dataSource: AdbShellDataSourceImpl

    @Before
    fun setUp() {
        adbClient = mockk()
        dataSource = AdbShellDataSourceImpl(adbClient)
    }

    @Test
    fun `given detailed command succeeds when executeCommand then returns stdout`() = runTest {
        val command = ShellCommandSpec.DumpsysPackageDexopt
        coEvery { adbClient.executeDetailed(command) } returns ShellCommandResult(
            exitCode = 0,
            stdout = "ok",
            stderr = ""
        )

        val result = dataSource.executeCommand(command)

        assertEquals("ok", result.getOrThrow())
        coVerify(exactly = 1) { adbClient.executeDetailed(command) }
        coVerify(exactly = 0) { adbClient.execute(any()) }
    }

    @Test
    fun `given detailed command exits non-zero when executeCommand then returns failure`() = runTest {
        val command = ShellCommandSpec.PackageCompile(
            packageName = "com.example.app",
            mode = "speed-profile"
        )
        coEvery { adbClient.executeDetailed(command) } returns ShellCommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "compile failed"
        )

        val result = dataSource.executeCommand(command)

        val error = result.exceptionOrNull()
        assertTrue(error is ShellCommandException)
        assertEquals(1, (error as ShellCommandException).exitCode)
        assertEquals("compile failed", error.stderr)
        coVerify(exactly = 1) { adbClient.executeDetailed(command) }
        coVerify(exactly = 0) { adbClient.execute(any()) }
    }
}
