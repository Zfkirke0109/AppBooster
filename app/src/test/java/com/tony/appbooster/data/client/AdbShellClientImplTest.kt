package com.tony.appbooster.data.client

import com.tony.appbooster.domain.client.ShizukuShellClient
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.common.ShellConnectionException
import com.tony.appbooster.domain.model.shizuku.ShellResult
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that loss of shell access remains distinct from a package command refusal. */
class AdbShellClientImplTest {

    private val shizukuClient = mockk<ShizukuShellClient>()
    private val state = MutableStateFlow<ShizukuState>(ShizukuState.Ready)
    private val client = AdbShellClientImpl(shizukuClient)
    private val command = ShellCommandSpec.PackageCompile("com.example.app", "speed-profile")

    init {
        every { shizukuClient.state } returns state
        coJustRun { shizukuClient.refreshState() }
    }

    @Test
    fun `unavailable Shizuku reports connection failure before dispatch`() = runTest {
        for (unavailable in listOf(
            ShizukuState.NotInstalled,
            ShizukuState.NotRunning,
            ShizukuState.PermissionRequired,
            ShizukuState.Error("Binder unavailable")
        )) {
            state.value = unavailable
            val failure = runCatching { client.executeDetailed(command) }.exceptionOrNull()
            assertTrue("Expected connection failure for $unavailable", failure is ShellConnectionException)
        }
        coVerify(exactly = 0) { shizukuClient.execute(any()) }
    }

    @Test
    fun `service transport failure pauses access even when Shizuku binder remains ready`() = runTest {
        coEvery { shizukuClient.execute(command) } returns ShellResult(-1, "", "Service connection lost")

        val failure = runCatching { client.executeDetailed(command) }.exceptionOrNull()

        assertTrue(failure is ShellConnectionException)
    }

    @Test
    fun `connection loss during command is reported as connection failure`() = runTest {
        coEvery { shizukuClient.execute(command) } coAnswers {
            state.value = ShizukuState.NotRunning
            ShellResult(1, "", "Command failed")
        }

        val failure = runCatching { client.executeDetailed(command) }.exceptionOrNull()

        assertTrue(failure is ShellConnectionException)
    }

    @Test
    fun `permission loss between preflight and dispatch is reported as connection failure`() = runTest {
        coEvery { shizukuClient.execute(command) } coAnswers {
            state.value = ShizukuState.PermissionRequired
            throw IllegalStateException("Shizuku is not ready")
        }

        val failure = runCatching { client.executeDetailed(command) }.exceptionOrNull()

        assertTrue(failure is ShellConnectionException)
    }

    @Test
    fun `package refusal keeps its exit status when shell connection is healthy`() = runTest {
        coEvery { shizukuClient.execute(command) } returns ShellResult(1, "", "Compile refused")

        val result = client.executeDetailed(command)

        assertEquals(1, result.exitCode)
        assertEquals("Compile refused", result.stderr)
    }

    @Test
    fun `cancellation is never converted into connection failure`() = runTest {
        val cancellation = CancellationException("Stopped")
        coEvery { shizukuClient.execute(command) } throws cancellation

        val failure = runCatching { client.executeDetailed(command) }.exceptionOrNull()

        assertSame(cancellation, failure)
    }
}
