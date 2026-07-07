package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.common.PackageNameValidator
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for shell command validation and argv generation.
 */
class ShellCommandSpecTest {

    @Test
    fun `given valid package name when validated then returns true`() {
        assertTrue(PackageNameValidator.isValid("com.example_app.camera2"))
    }

    @Test
    fun `given shell metacharacter package name when validated then returns false`() {
        assertFalse(PackageNameValidator.isValid("com.example.app;reboot"))
    }

    @Test
    fun `given package compile spec when argv built then uses no shell wrapper`() {
        val spec = ShellCommandSpec.PackageCompile(
            packageName = "com.example.app",
            mode = "speed-profile"
        )

        assertEquals(
            listOf("cmd", "package", "compile", "-m", "speed-profile", "-f", "com.example.app"),
            spec.argv
        )
        assertTrue(ShellCommandSpec.isAllowedArgv(spec.argv))
    }

    @Test
    fun `given unsafe argv when checked then rejected`() {
        val unsafe = listOf("sh", "-c", "cmd package compile -m speed-profile -f com.example.app")

        assertFalse(ShellCommandSpec.isAllowedArgv(unsafe))
    }

    @Test
    fun `given thermal service spec when checked then allowed`() {
        assertEquals(
            listOf("dumpsys", "thermalservice"),
            ShellCommandSpec.DumpsysThermalService.argv
        )
        assertTrue(ShellCommandSpec.isAllowedArgv(ShellCommandSpec.DumpsysThermalService.argv))
    }

    @Test
    fun `given standby bucket spec when argv built then validates package name`() {
        val spec = ShellCommandSpec.GetStandbyBucket("com.tony.appbooster")

        assertEquals(
            listOf("am", "get-standby-bucket", "com.tony.appbooster"),
            spec.argv
        )
        assertTrue(ShellCommandSpec.isAllowedArgv(spec.argv))
        assertFalse(
            ShellCommandSpec.isAllowedArgv(
                listOf("am", "get-standby-bucket", "com.tony.appbooster;reboot")
            )
        )
    }
}
