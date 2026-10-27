package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.common.ShellCommandResult

/** Recognizes explicit rejection of the optional legacy compile-check capability. */
internal object CompileCheckSupport {
    private val unsupportedOption = Regex(
        """^\s*(?:Error:\s*)?Unknown option:\s*["']?--check["']?\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /** Transport and unrelated command errors never establish lack of platform support. */
    fun isUnsupported(result: ShellCommandResult): Boolean =
        result.exitCode > 0 &&
            (unsupportedOption.containsMatchIn(result.stdout) || unsupportedOption.containsMatchIn(result.stderr))
}
