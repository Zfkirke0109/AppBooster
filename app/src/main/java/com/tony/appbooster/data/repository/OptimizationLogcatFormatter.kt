package com.tony.appbooster.data.repository

import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome

/** Stable, machine-readable Logcat records for device-backed optimization validation. */
internal object OptimizationLogcatFormatter {
    const val TAG = "OptiDroidART"

    fun packageVerification(
        runId: Long,
        packageName: String,
        requestedFilter: String,
        beforeFilter: String?,
        actualFilter: String?,
        outcome: OptimizationStepOutcome,
        source: String,
        attempted: Boolean,
        exitCode: Int?,
        durationMs: Long?
    ): String = buildString {
        append("event=package_verification")
        append(" run_id=").append(runId)
        append(" package=").append(packageName)
        append(" requested=").append(token(requestedFilter))
        append(" before=").append(token(beforeFilter))
        append(" actual=").append(token(actualFilter))
        append(" outcome=").append(outcome.name)
        append(" source=").append(token(source))
        append(" attempted=").append(attempted)
        append(" exit_code=").append(exitCode ?: -1)
        append(" duration_ms=").append(durationMs ?: -1L)
    }

    fun runSummary(
        runId: Long,
        status: String,
        targeted: Int,
        attempted: Int,
        success: Int,
        skipped: Int,
        failed: Int,
        unverified: Int,
        alreadyMatching: Int,
        noProfile: Int,
        osAdjusted: Int,
        notApplicable: Int,
        verificationUnavailable: Int
    ): String = buildString {
        append("event=run_summary")
        append(" run_id=").append(runId)
        append(" status=").append(token(status))
        append(" targeted=").append(targeted)
        append(" attempted=").append(attempted)
        append(" success=").append(success)
        append(" skipped=").append(skipped)
        append(" failed=").append(failed)
        append(" unverified=").append(unverified)
        append(" already_matching=").append(alreadyMatching)
        append(" no_profile=").append(noProfile)
        append(" os_adjusted=").append(osAdjusted)
        append(" not_applicable=").append(notApplicable)
        append(" verification_unavailable=").append(verificationUnavailable)
    }

    private fun token(value: String?): String = value
        ?.replace(Regex("[^A-Za-z0-9._:-]"), "_")
        ?.take(MAX_TOKEN_LENGTH)
        ?.ifBlank { "none" }
        ?: "none"

    private const val MAX_TOKEN_LENGTH = 160
}
