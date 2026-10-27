package com.tony.appbooster.data.util

import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompilationInfoResolverTest {
    private val shell = mockk<AdbShellDataSource>()
    private val resolver = CompilationInfoResolver(shell, OptimizationLogger())
    private val pkg = "com.example.app"

    @Test
    fun `unsupported compile check on stderr is not retried after metadata reset`() = runTest {
        assertUnsupportedCheckIsRemembered(stdout = "", stderr = "Error: Unknown option: --check")
    }

    @Test
    fun `unsupported compile check on stdout is not retried after metadata reset`() = runTest {
        assertUnsupportedCheckIsRemembered(stdout = "Error: Unknown option: --check", stderr = "")
    }

    private suspend fun assertUnsupportedCheckIsRemembered(stdout: String, stderr: String) {
        stubUnknownPackages()
        coEvery { shell.executeCommandDetailed(match { it is ShellCommandSpec.PackageCompileCheck }) } returns
            Result.success(ShellCommandResult(1, stdout, stderr))

        for (name in listOf("com.example.one", "com.example.two", "com.example.one")) {
            assertTrue(resolver.queryPackageCompilationInfo(name, "speed").needsOptimization)
        }
        resolver.resetCaches()
        assertTrue(resolver.queryPackageCompilationInfo("com.example.one", "speed").needsOptimization)

        coVerify(exactly = 1) {
            shell.executeCommandDetailed(match { it is ShellCommandSpec.PackageCompileCheck })
        }
    }

    @Test
    fun `transient check failure does not disable later supported checks`() = runTest {
        stubUnknownPackages()
        coEvery { shell.executeCommandDetailed(match { it is ShellCommandSpec.PackageCompileCheck }) } returnsMany
            listOf(Result.success(ShellCommandResult(1, "", "Temporarily unavailable")),
                Result.success(ShellCommandResult(0, "false", "")))

        assertTrue(resolver.queryPackageCompilationInfo("com.example.one", "speed").needsOptimization)
        assertFalse(resolver.queryPackageCompilationInfo("com.example.two", "speed").needsOptimization)
        coVerify(exactly = 2) {
            shell.executeCommandDetailed(match { it is ShellCommandSpec.PackageCompileCheck })
        }
    }

    @Test
    fun `executable arrow app with unknown ART state remains eligible`() = runTest {
        val name = "com.example.arrow"
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt) } returns
            Result.success(ShellCommandResult(0, "Dexopt state:\n  [$name]", ""))
        coEvery { shell.executeCommand(ShellCommandSpec.DumpsysPackageForPackage(name)) } returns
            Result.success("codePath=/data/app/example/base.apk\npkgFlags=[ HAS_CODE ]")
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.PackageCompileCheck(name)) } returns
            Result.success(ShellCommandResult(1, "", "Error: Unknown option: --check"))

        val info = resolver.queryPackageCompilationInfo(name, "speed")

        assertTrue(info.needsOptimization)
        assertNull(info.skipReason)
    }

    private fun stubUnknownPackages() {
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt) } returns
            Result.success(ShellCommandResult(0, "Dexopt state:", ""))
        coEvery { shell.executeCommand(match { it is ShellCommandSpec.DumpsysPackageForPackage }) } returns
            Result.success("codePath=/data/app/example/base.apk\npkgFlags=[ HAS_CODE ]")
    }

    private fun stubState(filter: String) {
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt) } returns
            Result.success(ShellCommandResult(0, "Dexopt state:\n  [$pkg]\n    arm64: [status=$filter]", ""))
        coEvery { shell.executeCommand(ShellCommandSpec.DumpsysPackageForPackage(pkg)) } returns
            Result.success("lastUpdateTime=1789670000000\npkgFlags=[ HAS_CODE ]")
    }

    @Test
    fun `confirmed resource overlay remains excluded when no ART filter exists`() = runTest {
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt) } returns
            Result.success(ShellCommandResult(0, "Dexopt state:\n  [$pkg]", ""))
        coEvery { shell.executeCommand(ShellCommandSpec.DumpsysPackageForPackage(pkg)) } returns
            Result.success("codePath=/product/overlay/Example.apk\npkgFlags=[ SYSTEM ]\noverlayTarget=android")
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.PackageCompileCheck(pkg)) } returns
            Result.success(ShellCommandResult(1, "", "Unknown option: --check"))
        assertFalse(resolver.queryPackageCompilationInfo(pkg, "speed").needsOptimization)
    }

    @Test
    fun `global filter does not discard package update identity`() = runTest {
        stubState("verify")
        val info = resolver.queryPackageCompilationInfo(pkg, "speed")
        assertEquals(1789670000000L, info.lastUpdateTimeMs)
    }

    @Test
    fun `recent optimization does not invent a stronger requested filter`() = runTest {
        stubState("speed-profile")
        resolver.markOptimized(pkg)
        val info = resolver.queryPackageCompilationInfo(pkg, "speed")
        assertTrue(info.needsOptimization)
        assertEquals("speed-profile", info.compilerFilter)
    }

    @Test
    fun `new scan rechecks optimized package after update`() = runTest {
        stubState("verify")
        resolver.markOptimized(pkg)
        resolver.resetCaches()
        assertTrue(resolver.queryPackageCompilationInfo(pkg, "speed").needsOptimization)
    }

    @Test
    fun `verify does not prove runtime profile is absent`() = runTest {
        stubState("verify")
        assertTrue(resolver.queryPackageCompilationInfo(pkg, "speed-profile").needsOptimization)
    }
}
