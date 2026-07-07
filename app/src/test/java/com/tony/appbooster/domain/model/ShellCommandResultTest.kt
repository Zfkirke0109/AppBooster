package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.common.ShellCommandException
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.requireSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShellCommandResult] success validation.
 */
class ShellCommandResultTest {

    @Test
    fun `given exit code zero when requireSuccess then returns same result`() {
        val result = ShellCommandResult(exitCode = 0, stdout = "Success", stderr = "")

        val checked = result.requireSuccess("cmd package compile")

        assertSame(result, checked)
    }

    @Test
    fun `given non-zero exit code when requireSuccess then throws shell command exception`() {
        val result = ShellCommandResult(
            exitCode = 42,
            stdout = "stdout detail",
            stderr = "stderr detail"
        )

        val exception = kotlin.runCatching {
            result.requireSuccess("cmd package compile -m speed -f com.example")
        }.exceptionOrNull()

        assertTrue(exception is ShellCommandException)
        val shellException = exception as ShellCommandException
        assertEquals(42, shellException.exitCode)
        assertEquals("stderr detail", shellException.stderr)
        assertEquals("stdout detail", shellException.stdout)
        assertTrue(shellException.message?.contains("exitCode=42") == true)
        assertTrue(shellException.message?.contains("stderr detail") == true)
    }
}
