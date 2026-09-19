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

    private val filterOrder = listOf("extract", "verify", "quicken", "speed-profile", "speed", "everything")
    private val explicitFilterRegex = Regex(
        """(?:^|[\s,{\[])(?:actualcompilerfilter|compiler[-_ ]?filter|filter|status)\s*=\s*(speed-profile|everything|speed|verify|quicken|run-from-apk|extract)(?=$|[\s,}\]])""",
        RegexOption.IGNORE_CASE
    )
    private val packageHeaderRegex = Regex("""^(?:Package\s+)?\[([A-Za-z0-9_.]+)](?:\s.*)?$""")
    private val barePackageRegex = Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
    private val containerRegex = Regex("""DexContainerFileDexoptResult\{[^}]*}""")

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
        // Raw package dumps include a metadata header before their ART section.
        val sectionStart = lines.indexOfFirst { it.trim().startsWith("Dexopt state:", ignoreCase = true) }
        val sectionIndent = if (sectionStart >= 0) lines[sectionStart].indexOfFirst { !it.isWhitespace() } else -1
        val sectionEnd = if (sectionStart >= 0) {
            (sectionStart + 1 until lines.size).firstOrNull { index ->
                lines[index].isNotBlank() && lines[index].indexOfFirst { !it.isWhitespace() } <= sectionIndent
            } ?: lines.size
        } else lines.size
        val start = (sectionStart + 1 until sectionEnd).firstOrNull { index ->
            packageHeader(lines[index].trim()) == packageName
        } ?: return null
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val filters = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            val trimmed = line.trim()
            // A missing tail could contain a weaker secondary DEX filter.
            if (trimmed.contains("[output truncated by ShellService]")) return "unknown-present"
            if (packageHeader(trimmed) != null || line.indexOfFirst { !it.isWhitespace() } <= indent) break
            if (!isHistoricalResult(trimmed)) {
                explicitFilterRegex.findAll(trimmed).forEach { filters += it.groupValues[1].normalizeCompilerFilter() }
            }
        }
        return weakestFilter(filters) ?: "unknown-present"
    }

    private fun packageHeader(line: String): String? =
        packageHeaderRegex.matchEntire(line)?.groupValues?.get(1)
            ?: line.takeIf { barePackageRegex.matches(it) }

    private fun isHistoricalResult(line: String): Boolean =
        line.contains("dexopt=", ignoreCase = true) || line.contains("optTimeMs=", ignoreCase = true)

    private fun weakestFilter(filters: List<String>): String? =
        filters.takeIf { it.isNotEmpty() && it.all(filterOrder::contains) }
            ?.minByOrNull(filterOrder::indexOf)

    /**
     * Parses the weakest explicit compiler filter across verbose output lines.
     */
    fun parseCompilerFilterFromOutput(output: String): String? {
        if (output.isBlank() || output.contains("[output truncated by ShellService]")) return null
        val filters = output.lineSequence()
            .filterNot(::isHistoricalResult)
            .mapNotNull { parseCompilerFilterFromLine(it.trim().lowercase()) }
            .toList()
        return weakestFilter(filters)
    }

    /** Aggregates all returned containers; a later weaker filter cannot be hidden by the base APK. */
    fun parseArtCompileResult(output: String): ArtCompileResult {
        fun Regex.values(): List<String> = findAll(output).map { it.groupValues[1].trim() }.toList()
        val containers = containerRegex.findAll(output).map { it.value }.toList()
        val expectedCount = containers.size.coerceAtLeast(1)
        val filters = actualFilterRegex.values().map { it.normalizeCompilerFilter() }
        val statuses = resultStatusRegex.values().map(String::uppercase)
        val truncated = output.contains("[output truncated by ShellService]")
        fun sum(regex: Regex): Long? {
            val values = regex.values().mapNotNull(String::toLongOrNull)
            if (truncated || values.size != expectedCount) return null
            return try { values.fold(0L, Math::addExact) } catch (_: ArithmeticException) { null }
        }
        val status = when {
            "FAILED" in statuses -> "FAILED"
            "CANCELLED" in statuses -> "CANCELLED"
            "CANCELED" in statuses -> "CANCELED"
            "PERFORMED" in statuses -> "PERFORMED"
            statuses.isNotEmpty() && statuses.all { it == "SKIPPED" } -> "SKIPPED"
            else -> null
        }
        return ArtCompileResult(
            actualCompilerFilter = if (!truncated && filters.size == expectedCount) weakestFilter(filters) else null,
            status = status,
            finalStatus = finalStatusRegex.values().lastOrNull()?.uppercase(),
            sizeBytes = sum(sizeBytesRegex),
            sizeBeforeBytes = sum(sizeBeforeBytesRegex)
        )
    }

    /** Maps shell and ART evidence to the durable package outcome contract. */
    fun classifyCompileResult(
        requestedFilter: String,
        exitCode: Int,
        output: String
    ): ClassifiedCompileResult {
        val art = parseArtCompileResult(output)
        val skipped = (art.finalStatus ?: art.status) == "SKIPPED"
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
            stableOsAdjusted = outcome == OptimizationStepOutcome.OS_ADJUSTED_FILTER &&
                requestedFilter == "speed"
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
        val lines = output.lineSequence()
            .map(String::trim)
            .toList()
        val flagsLine = lines.firstOrNull { line ->
            line.startsWith("pkgFlags=[", ignoreCase = true)
        } ?: lines.firstOrNull { line ->
            line.startsWith("flags=[", ignoreCase = true)
        }
            ?: return null
        return Regex("""\bHAS_CODE\b""", RegexOption.IGNORE_CASE).containsMatchIn(flagsLine)
    }

    /**
     * Extracts a compiler filter keyword from a single lowercased line.
     */
    fun parseCompilerFilterFromLine(lowercasedLine: String): String? {
        if (isHistoricalResult(lowercasedLine)) return null
        val line = lowercasedLine.trim()
        val explicit = explicitFilterRegex.findAll(line)
            .map { it.groupValues[1].normalizeCompilerFilter() }.toList()
        if (explicit.isNotEmpty()) return weakestFilter(explicit)
        return line.normalizeCompilerFilter().takeIf(filterOrder::contains)
    }

    private fun String.normalizeCompilerFilter(): String =
        lowercase().let { if (it == "run-from-apk") "extract" else it }
}
