package com.tony.appbooster.data.client

import com.tony.appbooster.domain.model.common.ShellCommandSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellCommandOperationNameTest {

    @Test
    fun `capability probes have stable non-obfuscated names`() {
        assertEquals("PackageCompileHelp", ShellCommandSpec.PackageCompileHelp.logOperationName())
        assertEquals("PackageHelp", ShellCommandSpec.PackageHelp.logOperationName())
    }

    @Test
    fun `package operations include the validated package name`() {
        assertEquals(
            "PackageCompile(package=com.example.app)",
            ShellCommandSpec.PackageCompile(
                packageName = "com.example.app",
                mode = "speed-profile"
            ).logOperationName()
        )
    }
}
