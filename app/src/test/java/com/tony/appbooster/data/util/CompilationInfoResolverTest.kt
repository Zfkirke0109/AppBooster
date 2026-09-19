package com.tony.appbooster.data.util

import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandResult
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompilationInfoResolverTest {
    private val shell = mockk<AdbShellDataSource>()
    private val resolver = CompilationInfoResolver(shell, OptimizationLogger())
    private val pkg = "com.example.app"

    private fun stubState(filter: String) {
        coEvery { shell.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt) } returns
            Result.success(ShellCommandResult(0, "Dexopt state:\n  [$pkg]\n    arm64: [status=$filter]", ""))
        coEvery { shell.executeCommand(ShellCommandSpec.DumpsysPackageForPackage(pkg)) } returns
            Result.success("lastUpdateTime=1789670000000\npkgFlags=[ HAS_CODE ]")
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
