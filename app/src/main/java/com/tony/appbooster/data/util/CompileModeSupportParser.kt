package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.settings.CompileModeSupport

/**
 * Parses `cmd package help` / `cmd package compile` help text into compile support facts.
 */
internal object CompileModeSupportParser {
    private val FILTER_REGEX = Regex("""(?<![a-z0-9-])(speed-profile|everything|speed|verify)(?![a-z0-9-])""")

    fun parse(helpOutput: String): CompileModeSupportSnapshot {
        val compileSection = extractCompileSection(helpOutput)
        val filters = FILTER_REGEX.findAll(compileSection.lowercase())
            .map { it.value }
            .toSet()

        return CompileModeSupportSnapshot(
            supportedFilters = filters,
            supportsFullScope = compileSection.contains("--full"),
            supportsVerboseCompile = compileSection.contains("-v[:") ||
                compileSection.contains("-v:") ||
                Regex("""(^|\s)-v(\s|,|$)""").containsMatchIn(compileSection),
            supportsBackgroundPriority =
                Regex("""(^|\s)-p(?:\s|\])""").containsMatchIn(compileSection) &&
                    Regex("""\bPRIORITY_BACKGROUND\b""").containsMatchIn(compileSection),
            rawHelp = helpOutput
        )
    }

    private fun extractCompileSection(helpOutput: String): String {
        val lines = helpOutput.lineSequence().toList()
        val start = lines.indexOfFirst { line ->
            line.trimStart().startsWith("compile ") ||
                line.trimStart().startsWith("compile[") ||
                line.trimStart().startsWith("compile [")
        }
        if (start < 0) return helpOutput

        val commandIndent = lines[start].takeWhile(Char::isWhitespace).length
        val end = lines.drop(start + 1).indexOfFirst { line ->
            val trimmed = line.trimStart()
            trimmed.isNotEmpty() &&
                line.takeWhile(Char::isWhitespace).length <= commandIndent &&
                !trimmed.startsWith("-") &&
                !trimmed.startsWith("[")
        }

        return if (end < 0) {
            lines.drop(start).joinToString("\n")
        } else {
            lines.subList(start, start + 1 + end).joinToString("\n")
        }
    }
}

internal data class CompileModeSupportSnapshot(
    val supportedFilters: Set<String>,
    val supportsFullScope: Boolean,
    val supportsVerboseCompile: Boolean,
    val supportsBackgroundPriority: Boolean,
    val rawHelp: String
) {
    fun supportFor(mode: String): CompileModeSupport {
        val isSupported = mode in supportedFilters
        return CompileModeSupport(
            requestedMode = mode,
            isSupported = isSupported,
            reason = if (isSupported) null else unsupportedReason()
        )
    }

    fun availableFiltersLabel(): String =
        supportedFilters.sortedWith(compareBy<String> { it != "speed" }.thenBy { it }).joinToString(", ")
            .ifBlank { "unknown" }

    private fun unsupportedReason(): String =
        "Not supported on this Android build. Available filters: ${availableFiltersLabel()}."
}
