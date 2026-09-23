package com.tony.appbooster.data.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.PatternSyntaxException

/** Exercises production scan parsing on Android's ICU engine, not the host JDK. */
@RunWith(AndroidJUnit4::class)
class DexoptScanRuntimeTest {
    @Test
    fun androidRejectsTheUnescapedBraceFromVersion171() {
        // Negative control: the host JDK accepts this pattern, but Android rejects it.
        assertThrows(PatternSyntaxException::class.java) {
            Regex("""DexContainerFileDexoptResult\{[^}]*}""")
        }
    }

    @Test
    fun scanResolvesMultiplePackagesAfterTheGlobalDexoptDump() = runBlocking {
        val shell = ScanFixtureShell()
        val logger = OptimizationLogger()
        val resolver = CompilationInfoResolver(shell, logger)

        val first = resolver.queryPackageCompilationInfo("com.example.first", "speed")
        val second = resolver.queryPackageCompilationInfo("com.example.second", "speed")

        assertEquals("verify", first.compilerFilter)
        assertTrue(first.needsOptimization)
        assertEquals(1789670000000L, first.lastUpdateTimeMs)
        assertEquals("speed", second.compilerFilter)
        assertFalse(second.needsOptimization)
        assertEquals(1, shell.globalDumpCalls)
        assertTrue(logger.logEntries.value.any { it.message == "Dexopt status" })
    }

    @Test
    fun verboseArtResultsIncludeEveryContainerOnAndroid() {
        val result = DexoptStatusParser.parseArtCompileResult(
            """
                DexContainerFileDexoptResult{actualCompilerFilter=speed, status=PERFORMED, sizeBytes=100, sizeBeforeBytes=50}
                DexContainerFileDexoptResult{actualCompilerFilter=verify, status=PERFORMED, sizeBytes=20, sizeBeforeBytes=10}
                Final Status: PERFORMED
            """.trimIndent()
        )

        assertEquals("verify", result.actualCompilerFilter)
        assertEquals("PERFORMED", result.finalStatus)
        assertEquals(120L, result.sizeBytes)
        assertEquals(60L, result.storageDeltaBytes)
    }

    private class ScanFixtureShell : AdbShellDataSource {
        var globalDumpCalls = 0

        override suspend fun executeCommand(command: ShellCommandSpec): Result<String> {
            check(command is ShellCommandSpec.DumpsysPackageForPackage)
            return Result.success("lastUpdateTime=1789670000000\npkgFlags=[ HAS_CODE ]")
        }

        override suspend fun executeCommandDetailed(command: ShellCommandSpec): Result<ShellCommandResult> {
            check(command == ShellCommandSpec.DumpsysPackageDexopt)
            globalDumpCalls++
            return Result.success(ShellCommandResult(0, """
                Dexopt state:
                  [com.example.first]
                    arm64: [status=verify] [reason=install]
                  [com.example.second]
                    arm64: [status=speed] [reason=cmdline]
            """.trimIndent(), ""))
        }

        override suspend fun streamCommand(command: ShellCommandSpec): Flow<Result<String>> =
            error("A scan must not start compilation")
    }
}
