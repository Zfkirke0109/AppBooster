package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.common.ShellCommandSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the exact measurement command boundary from unbounded shell access. */
class PerformanceShellCommandsTest {
    @Test fun `allow cold launcher measurement for one explicit component`() {
        assertTrue(ShellCommandSpec.isAllowedArgv(listOf("am", "start", "-S", "-W", "--user", "current", "-n", "com.example.app/.MainActivity", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")))
    }

    @Test fun `reject injection and arbitrary activity arguments`() {
        assertFalse(ShellCommandSpec.isAllowedArgv(listOf("am", "start", "-S", "-W", "--user", "current", "-n", "com.example.app/.MainActivity;reboot", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")))
        assertFalse(ShellCommandSpec.isAllowedArgv(listOf("am", "force-stop", "com.example.app")))
    }
}
