package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive unit tests for [DexoptStatusParser].
 *
 * Tests cover all supported Android output formats to guarantee
 * reliable parsing across device variants.
 */
class DexoptStatusParserTest {

    @Test
    fun `speed request adjusted to verify is classified and sized from ART result`() {
        val output = """
            DexContainerFileDexoptResult{actualCompilerFilter=verify, status=PERFORMED, sizeBytes=1884388, sizeBeforeBytes=1999000}
            Final Status: PERFORMED
        """.trimIndent()

        val result = DexoptStatusParser.classifyCompileResult("speed", 0, output)

        assertEquals(OptimizationStepOutcome.OS_ADJUSTED_FILTER, result.outcome)
        assertEquals("verify", result.art.actualCompilerFilter)
        assertEquals("PERFORMED", result.art.status)
        assertEquals("PERFORMED", result.art.finalStatus)
        assertEquals(1_884_388L, result.art.sizeBytes)
        assertEquals(1_999_000L, result.art.sizeBeforeBytes)
        assertEquals(-114_612L, result.art.storageDeltaBytes)
        assertTrue(result.stableOsAdjusted)
    }

    @Test
    fun `speed request adjusted to speed-profile and skipped is not applicable`() {
        val output = """
            DexContainerFileDexoptResult{actualCompilerFilter=speed-profile, status=SKIPPED, sizeBytes=50, sizeBeforeBytes=50}
            Final Status: SKIPPED
        """.trimIndent()

        val result = DexoptStatusParser.classifyCompileResult("speed", 0, output)

        assertEquals(OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE, result.outcome)
        assertEquals("speed-profile", result.art.actualCompilerFilter)
        assertTrue(result.stableOsAdjusted)
    }

    @Test
    fun `successful shell response without actual ART filter is verification unavailable`() {
        val result = DexoptStatusParser.classifyCompileResult("speed", 0, "Success")

        assertEquals(OptimizationStepOutcome.VERIFICATION_UNAVAILABLE, result.outcome)
        assertNull(result.art.actualCompilerFilter)
        assertFalse(result.stableOsAdjusted)
    }

    @Test
    fun `nonzero shell exit is failed or refused even if verbose output contains a filter`() {
        val output = "actualCompilerFilter=speed, status=PERFORMED"

        val result = DexoptStatusParser.classifyCompileResult("speed", 255, output)

        assertEquals(OptimizationStepOutcome.FAILED_OR_REFUSED, result.outcome)
        assertEquals("speed", result.art.actualCompilerFilter)
    }

    @Test
    fun `package flags distinguish resource-only packages from code packages`() {
        assertEquals(false, DexoptStatusParser.parsePackageHasCode("pkgFlags=[ SYSTEM ]"))
        assertEquals(true, DexoptStatusParser.parsePackageHasCode("pkgFlags=[ SYSTEM HAS_CODE ]"))
        assertNull(DexoptStatusParser.parsePackageHasCode("Dexopt state:"))
    }

    // ── parseCompileCheckNeedsOptimization ───────────────────────────────────

    @Test
    fun `given blank output when parseCompileCheckNeedsOptimization then returns null`() {
        assertNull(DexoptStatusParser.parseCompileCheckNeedsOptimization(""))
        assertNull(DexoptStatusParser.parseCompileCheckNeedsOptimization("   "))
    }

    @Test
    fun `given true when parseCompileCheckNeedsOptimization then returns true`() {
        assertEquals(true, DexoptStatusParser.parseCompileCheckNeedsOptimization("true"))
    }

    @Test
    fun `given true with whitespace when parseCompileCheckNeedsOptimization then returns true`() {
        assertEquals(true, DexoptStatusParser.parseCompileCheckNeedsOptimization("  true  "))
    }

    @Test
    fun `given false when parseCompileCheckNeedsOptimization then returns false`() {
        assertEquals(false, DexoptStatusParser.parseCompileCheckNeedsOptimization("false"))
    }

    @Test
    fun `given compilation not needed text when parseCompileCheckNeedsOptimization then returns false`() {
        assertEquals(false, DexoptStatusParser.parseCompileCheckNeedsOptimization("Compilation not needed"))
    }

    @Test
    fun `given compilation needed text when parseCompileCheckNeedsOptimization then returns true`() {
        assertEquals(true, DexoptStatusParser.parseCompileCheckNeedsOptimization("Compilation needed"))
    }

    @Test
    fun `given unknown output when parseCompileCheckNeedsOptimization then returns null`() {
        assertNull(DexoptStatusParser.parseCompileCheckNeedsOptimization("cmd: unknown option"))
        assertNull(DexoptStatusParser.parseCompileCheckNeedsOptimization("error: permission denied"))
    }

    // ── isPackagePresentInDexoptDump ─────────────────────────────────────────

    @Test
    fun `given blank dump when isPackagePresentInDexoptDump then returns false`() {
        assertFalse(DexoptStatusParser.isPackagePresentInDexoptDump("com.example.app", ""))
        assertFalse(DexoptStatusParser.isPackagePresentInDexoptDump("com.example.app", "   "))
    }

    @Test
    fun `given dump containing bracketed package when isPackagePresentInDexoptDump then returns true`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                compiler-filter=speed-profile
        """.trimIndent()
        assertTrue(DexoptStatusParser.isPackagePresentInDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump not containing package when isPackagePresentInDexoptDump then returns false`() {
        val dump = """
            Dexopt state:
              [com.other.app]
                compiler-filter=speed-profile
        """.trimIndent()
        assertFalse(DexoptStatusParser.isPackagePresentInDexoptDump("com.example.app", dump))
    }

    // ── parseCompilerFilterFromDexoptDump ────────────────────────────────────

    @Test
    fun `given dump with speed-profile filter when parseCompilerFilterFromDexoptDump then returns speed-profile`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                compiler-filter=speed-profile
        """.trimIndent()
        assertEquals("speed-profile", DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump with speed filter when parseCompilerFilterFromDexoptDump then returns speed`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                [status=speed]
        """.trimIndent()
        assertEquals("speed", DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump with verify filter when parseCompilerFilterFromDexoptDump then returns verify`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                compiler-filter=verify
        """.trimIndent()
        assertEquals("verify", DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump with quicken filter when parseCompilerFilterFromDexoptDump then returns quicken`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                compiler-filter=quicken
        """.trimIndent()
        assertEquals("quicken", DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump with extract filter when parseCompilerFilterFromDexoptDump then returns extract`() {
        val dump = """
            Dexopt state:
              [com.example.app]
                compiler-filter=run-from-apk
        """.trimIndent()
        assertEquals("extract", DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given dump with only bracketed package when parseCompilerFilterFromDexoptDump then returns unknown-present`() {
        val dump = """
            Dexopt state:
              [com.android.systemui.auto_generated_rro_product__]
        """.trimIndent()
        assertEquals(
            "unknown-present",
            DexoptStatusParser.parseCompilerFilterFromDexoptDump(
                "com.android.systemui.auto_generated_rro_product__", dump
            )
        )
    }

    @Test
    fun `given dump without package when parseCompilerFilterFromDexoptDump then returns null`() {
        val dump = """
            Dexopt state:
              [com.other.app]
                compiler-filter=speed-profile
        """.trimIndent()
        assertNull(DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", dump))
    }

    @Test
    fun `given empty dump when parseCompilerFilterFromDexoptDump then returns null`() {
        assertNull(DexoptStatusParser.parseCompilerFilterFromDexoptDump("com.example.app", ""))
    }

    // ── parseCompilerFilterFromLine ───────────────────────────────────────────

    @Test
    fun `given line with speed-profile when parseCompilerFilterFromLine then returns speed-profile`() {
        assertEquals("speed-profile", DexoptStatusParser.parseCompilerFilterFromLine("compiler-filter=speed-profile"))
    }

    @Test
    fun `given line with everything when parseCompilerFilterFromLine then returns everything`() {
        assertEquals("everything", DexoptStatusParser.parseCompilerFilterFromLine("compiler-filter=everything"))
    }

    @Test
    fun `given line with verify when parseCompilerFilterFromLine then returns verify`() {
        assertEquals("verify", DexoptStatusParser.parseCompilerFilterFromLine("compiler-filter=verify"))
    }

    @Test
    fun `given line with quicken when parseCompilerFilterFromLine then returns quicken`() {
        assertEquals("quicken", DexoptStatusParser.parseCompilerFilterFromLine("compiler-filter=quicken"))
    }

    @Test
    fun `given line with extract when parseCompilerFilterFromLine then returns extract`() {
        assertEquals("extract", DexoptStatusParser.parseCompilerFilterFromLine("run-from-apk"))
    }

    @Test
    fun `given irrelevant line when parseCompilerFilterFromLine then returns null`() {
        assertNull(DexoptStatusParser.parseCompilerFilterFromLine("packageflags=[ SYSTEM HAS_CODE ]"))
        assertNull(DexoptStatusParser.parseCompilerFilterFromLine("userId=1000"))
    }
}

