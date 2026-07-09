package com.tony.appbooster.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileModeSupportParserTest {

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
