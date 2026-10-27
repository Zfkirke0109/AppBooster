package com.tony.appbooster.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileModeSupportParserTest {

    @Test
    fun `S26 Android 17 help enables background priority and full scope`() {
        val help = javaClass.getResource("/s26-package-compile.help")?.readText()
            ?: error("Missing S26 help fixture")
        val support = CompileModeSupportParser.parse(help)

        assertEquals(setOf("speed", "speed-profile", "verify"), support.supportedFilters)
        assertTrue(support.supportsFullScope)
        assertTrue(support.supportsVerboseCompile)
        assertTrue(support.supportsBackgroundPriority)
    }

    @Test
    fun `legacy help preserves default priority`() {
        val support = CompileModeSupportParser.parse("compile [-m MODE] PACKAGE\n  speed speed-profile")

        assertFalse(support.supportsBackgroundPriority)
    }

    @Test
    fun `other package commands cannot advertise compile capabilities`() {
        val support = CompileModeSupportParser.parse("""
            Package manager commands:
              compile [-m MODE] PACKAGE
                Available filters: speed speed-profile verify
              install [-p PRIORITY] [--full] [-v]
                PRIORITY_BACKGROUND everything
        """.trimIndent())

        assertFalse(support.supportsBackgroundPriority)
        assertFalse(support.supportsFullScope)
        assertFalse(support.supportsVerboseCompile)
        assertFalse(support.supportFor("everything").isSupported)
    }

    @Test
    fun `given One UI 8_5 package help when parsed then detects filters and full scope`() {
        val support = CompileModeSupportParser.parse(ONE_UI_85_PACKAGE_HELP)

        assertEquals(setOf("speed", "speed-profile", "verify"), support.supportedFilters)
        assertTrue(support.supportsFullScope)
        assertTrue(support.supportsVerboseCompile)
        assertTrue(support.supportFor("speed").isSupported)
        assertTrue(support.supportFor("speed-profile").isSupported)
        assertFalse(support.supportFor("everything").isSupported)
    }

    @Test
    fun `given everything absent when support queried then reason lists available filters`() {
        val support = CompileModeSupportParser.parse(ONE_UI_85_PACKAGE_HELP)
        val everything = support.supportFor("everything")

        assertFalse(everything.isSupported)
        assertEquals(
            "Not supported on this Android build. Available filters: speed, speed-profile, verify.",
            everything.reason
        )
    }

    private companion object {
        private val ONE_UI_85_PACKAGE_HELP = """
            Package manager (package) commands:
              help
              compile [-r COMPILATION_REASON] [-m COMPILER_FILTER] [-p PRIORITY] [-f] [--primary-dex] [--secondary-dex] [--include-dependencies] [--full] [--split SPLIT_NAME] [--reset] [--force-merge-profile] [-v[:LOG_TAGS]] [-a | PACKAGE_NAME]
                -m: select compilation mode
                Available options for the compiler filter:
                  speed
                  speed-profile
                  verify
                --full Dexopt all above. (Recommended)
                -v[:LOG_TAGS] Enable verbose logging
              reconcile-secondary-dex-files
        """.trimIndent()
    }
}
