package com.tony.appbooster.data.repository

import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimizationLogcatFormatterTest {

    @Test
    fun `package verification contains stable evidence fields`() {
        val record = OptimizationLogcatFormatter.packageVerification(
            runId = 42L,
            packageName = "com.example.app",
            requestedFilter = "speed-profile",
            beforeFilter = "verify",
            actualFilter = "speed-profile",
            outcome = OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER,
            source = "cmd-package-dump",
            attempted = true,
            exitCode = 0,
            durationMs = 1250L
        )

        assertEquals(
            "event=package_verification run_id=42 package=com.example.app " +
                "requested=speed-profile before=verify actual=speed-profile " +
                "outcome=VERIFIED_REQUESTED_FILTER source=cmd-package-dump " +
                "attempted=true exit_code=0 duration_ms=1250",
            record
        )
    }

    @Test
    fun `run summary keeps unverified separate from success skipped and failed`() {
        val record = OptimizationLogcatFormatter.runSummary(
            runId = 42L,
            status = "COMPLETED_WITH_ISSUES",
            targeted = 10,
            attempted = 7,
            success = 5,
            skipped = 2,
            failed = 1,
            unverified = 2,
            alreadyMatching = 1,
            noProfile = 0,
            osAdjusted = 1,
            notApplicable = 1,
            verificationUnavailable = 1
        )

        assertEquals(
            "event=run_summary run_id=42 status=COMPLETED_WITH_ISSUES targeted=10 " +
                "attempted=7 success=5 skipped=2 failed=1 unverified=2 " +
                "already_matching=1 no_profile=0 os_adjusted=1 not_applicable=1 " +
                "verification_unavailable=1",
            record
        )
    }
}
