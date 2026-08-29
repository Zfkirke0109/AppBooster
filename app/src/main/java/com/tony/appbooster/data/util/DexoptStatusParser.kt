package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome

/**
 * Parses ART/dexopt related command outputs into normalized compiler filter signals.
 *
 * Business purpose:
 * - Centralizes parsing logic so repository code stays readable.
 * - Avoids reliance on shell utilities like grep/head.
 * - Improves testability by making parsing pure and deterministic.
 */
internal object DexoptStatusParser {

    data class ArtCompileResult(
        val actualCompilerFilter: String?,
        val status: String?,
        val finalStatus: String?,
        val sizeBytes: Long?,
        val sizeBeforeBytes: Long?
    ) {
        val storageDeltaBytes: Long?
            get() = if (sizeBytes != null && sizeBeforeBytes != null) {
                sizeBytes - sizeBeforeBytes
            } else {
                null
            }
    }

    data class ClassifiedCompileResult(
        val outcome: OptimizationStepOutcome,
        val art: ArtCompileResult,
        val stableOsAdjusted: Boolean
    )

    private val actualFilterRegex = Regex(
        """actualCompilerFilter\s*=\s*([^,}\s]+)""",
        RegexOption.IGNORE_CASE
    )
    private val resultStatusRegex = Regex(
        """(?:^|[,\s])status\s*=\s*([^,}\s]+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val finalStatusRegex = Regex(
        """Final\s+Status\s*:\s*([^\r\n]+)""",
        RegexOption.IGNORE_CASE
    )
    private val sizeBytesRegex = Regex("""(?:^|[,\s])sizeBytes\s*=\s*(\d+)""")
    private val sizeBeforeBytesRegex = Regex("""(?:^|[,\s])sizeBeforeBytes\s*=\s*(\d+)""")

    /**
     * Attempts to interpret the output of `cmd package compile --check <package>`.
     *
     * Different Android versions output different formats. We support:
     * - `true` / `false`
     * - Strings containing "compilation needed" / "compilation not needed"
     *
     * @param output Raw command output.
     * @return True if the system says compilation is needed, false if not needed, or null if unknown.
     */
    fun parseCompileCheckNeedsOptimization(output: String): Boolean? {
        if (output.isBlank()) return null

        val lower = output.trim().lowercase()

        if (lower == "true") return true
        if (lower == "false") return false

        if (lower.contains("compilation") && lower.contains("not") && lower.contains("needed")) return false
        if (lower.contains("compilation") && lower.contains("needed")) return true

        if (lower.contains("need") && lower.contains("compile")) {
            if (lower.contains("not") && lower.contains("needed")) return false
            if (lower.contains("needed")) return true
        }

        return null
    }

    /**
     * Checks whether the given package appears in a dexopt dump at all.
     *
     * Some Android builds omit compiler-filter lines for overlay/system packages.
     * In those cases, presence alone is a useful signal that the system is aware
     * of dexopt state, even if details are not reported.
     */
    fun isPackagePresentInDexoptDump(packageName: String, dump: String): Boolean {
        if (dump.isBlank()) return false

        // Match common bracketed forms:
        // - "[com.example.app]"
        // - "Dexopt state:\n  [com.example.app]"
        // - "Dexopt state:  [com.example.app]"
        val needle = "[$packageName]"
        return dump.contains(needle)
    }

    /**
     * Parses compiler filter for a package from the full `dumpsys package dexopt` output.
     *
     * Supports multiple formats across Android versions:
     * - Explicit filter lines (compiler-filter=speed-profile)
     * - Status annotations ([status=speed])
     * - Newer builds that only list the package in a "Dexopt state" section without details
     *   (in this case returns "unknown-present").
     */
    fun parseCompilerFilterFromDexoptDump(packageName: String, dump: String): String? {
        val lines = dump.lineSequence().toList()

        // Prefer the first occurrence of the package name in bracketed form
        val bracketed = "[$packageName]"
        val idx = lines.indexOfFirst { it.contains(bracketed) || it.contains(packageName) }
        if (idx < 0) return null

        val window = lines.subList(idx, minOf(idx + 30, lines.size))
        for (line in window) {
            val lower = line.trim().lowercase()
            parseCompilerFilterFromLine(lower)?.let { return it }
        }

        // If we can see the package in a Dexopt state section but no filter lines are provided,
        // return a marker so callers can treat it differently from "not found".
        return if (isPackagePresentInDexoptDump(packageName, dump)) "unknown-present" else null
    }

    /**
     * Parses the strongest compiler-filter signal from verbose compile or package dump output.
     */
    fun parseCompilerFilterFromOutput(output: String): String? {
        if (output.isBlank()) return null

        output.lineSequence().forEach { line ->
            parseCompilerFilterFromLine(line.trim().lowercase())?.let { return it }
        }
        return null
    }

    /** Parses the bounded verbose result returned by modern ART Service. */
    fun parseArtCompileResult(output: String): ArtCompileResult {
        fun Regex.value(): String? = find(output)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf(String::isNotBlank)

        return ArtCompileResult(
            actualCompilerFilter = actualFilterRegex.value()?.normalizeCompilerFilter(),
            status = resultStatusRegex.value()?.uppercase(),
            finalStatus = finalStatusRegex.value()?.uppercase(),
            sizeBytes = sizeBytesRegex.value()?.toLongOrNull(),
            sizeBeforeBytes = sizeBeforeBytesRegex.value()?.toLongOrNull()
        )
    }

    /** Maps shell and ART evidence to the durable package outcome contract. */
    fun classifyCompileResult(
        requestedFilter: String,
        exitCode: Int,
        output: String
    ): ClassifiedCompileResult {
        val art = parseArtCompileResult(output)
        val skipped = art.finalStatus == "SKIPPED" || art.status == "SKIPPED"
        val outcome = when {
            exitCode != 0 -> OptimizationStepOutcome.FAILED_OR_REFUSED
            skipped -> OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
            art.actualCompilerFilter == null -> OptimizationStepOutcome.VERIFICATION_UNAVAILABLE
            isRequestedFilterSatisfied(requestedFilter, art.actualCompilerFilter) ->
                OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
            else -> OptimizationStepOutcome.OS_ADJUSTED_FILTER
        }
        return ClassifiedCompileResult(
            outcome = outcome,
            art = art,
            stableOsAdjusted = outcome == OptimizationStepOutcome.OS_ADJUSTED_FILTER ||
                outcome == OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
        )
    }

    fun isRequestedFilterSatisfied(requestedFilter: String, actualFilter: String): Boolean {
        val requested = requestedFilter.lowercase()
        val actual = actualFilter.lowercase()
        return when (requested) {
            "speed-profile" -> actual in setOf("speed-profile", "speed", "everything")
            "speed" -> actual in setOf("speed", "everything")
            else -> actual == requested
        }
    }

    /** Returns false only when package flags explicitly prove the APK has no code. */
    fun parsePackageHasCode(output: String): Boolean? {
        val flagsLine = output.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.startsWith("pkgFlags=", ignoreCase = true) ||
                    line.startsWith("flags=", ignoreCase = true)
            }
            ?: return null
        return Regex("""\bHAS_CODE\b""", RegexOption.IGNORE_CASE).containsMatchIn(flagsLine)
    }

    /**
     * Extracts a compiler filter keyword from a single lowercased line.
     */
    fun parseCompilerFilterFromLine(lowercasedLine: String): String? {
        val exactAssignment = Regex(
            """(?:actualcompilerfilter|compiler[-_ ]?filter|filter|status)\s*=\s*(speed-profile|everything|speed|verify|quicken|run-from-apk|extract)"""
        ).find(lowercasedLine)?.groupValues?.getOrNull(1)

        if (exactAssignment != null) {
            return if (exactAssignment == "run-from-apk") "extract" else exactAssignment
        }

        return when {
            lowercasedLine.contains("speed-profile") -> "speed-profile"
            lowercasedLine.contains("everything") -> "everything"
            lowercasedLine.contains("[status=speed]") || (lowercasedLine.contains("speed") && !lowercasedLine.contains("profile")) -> "speed"
            lowercasedLine.contains("quicken") -> "quicken"
            lowercasedLine.contains("verify") -> "verify"
            lowercasedLine.contains("run-from-apk") || lowercasedLine.contains("extract") -> "extract"
            else -> null
        }
    }

    private fun String.normalizeCompilerFilter(): String =
        lowercase().let { if (it == "run-from-apk") "extract" else it }
}

