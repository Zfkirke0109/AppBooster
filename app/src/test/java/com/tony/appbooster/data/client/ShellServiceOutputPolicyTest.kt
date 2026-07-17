package com.tony.appbooster.data.client

import java.io.StringReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellServiceOutputPolicyTest {

    @Test
    fun `package dump drops unrelated output but preserves ART and overlay evidence`() {
        val noise = "permission state that must not cross Binder\n".repeat(100_000)
        val rawOutput = buildString {
            append(noise)
            appendLine("Dexopt state:")
            appendLine("  arm64: [status=speed] [reason=cmdline]")
            appendLine("lastUpdateTime=2026-07-17 03:07:25")
            appendLine("codePath=/product/overlay/example")
            appendLine("overlayTarget=android")
        }

        val reduced = ShellServiceOutputPolicy.readStdout(
            commandArgs = listOf("cmd", "package", "dump", "com.example.app"),
            reader = StringReader(rawOutput)
        )

        assertFalse(reduced.contains("permission state"))
        assertTrue(reduced.contains("Dexopt state"))
        assertTrue(reduced.contains("[status=speed]"))
        assertTrue(reduced.contains("lastUpdateTime="))
        assertTrue(reduced.contains("codePath="))
        assertTrue(reduced.contains("overlayTarget="))
        assertTrue(reduced.length <= ShellServiceOutputPolicy.MAX_PACKAGE_EVIDENCE_CHARS)
    }

    @Test
    fun `generic stdout is truncated below Binder safe limit`() {
        val reduced = ShellServiceOutputPolicy.readStdout(
            commandArgs = listOf("dumpsys", "package", "dexopt"),
            reader = StringReader("x".repeat(ShellServiceOutputPolicy.MAX_STDOUT_CHARS * 2))
        )

        assertTrue(reduced.length <= ShellServiceOutputPolicy.MAX_STDOUT_CHARS)
        assertTrue(reduced.endsWith("[output truncated by ShellService]"))
    }

    @Test
    fun `stderr is truncated below Binder safe limit without splitting surrogate pair`() {
        val emoji = "\uD83D\uDE80"
        val reduced = ShellServiceOutputPolicy.readStderr(
            StringReader("e".repeat(ShellServiceOutputPolicy.MAX_STDERR_CHARS) + emoji)
        )

        assertTrue(reduced.length <= ShellServiceOutputPolicy.MAX_STDERR_CHARS)
        assertFalse(reduced.last().isHighSurrogate())
        assertTrue(reduced.endsWith("[output truncated by ShellService]"))
    }
}
