package com.tony.appbooster.data.client

import com.tony.appbooster.domain.model.common.PackageNameValidator
import java.io.Reader

/** Keeps Shizuku UserService replies safely below Binder transaction limits. */
internal object ShellServiceOutputPolicy {

    internal const val MAX_PACKAGE_EVIDENCE_CHARS = 64 * 1024
    internal const val MAX_STDOUT_CHARS = 384 * 1024
    internal const val MAX_STDERR_CHARS = 16 * 1024

    private const val TRUNCATION_MARKER = "[output truncated by ShellService]"

    private val compilerFilterSignal = Regex(
        pattern = """(?:actualcompilerfilter|compiler[-_ ]?filter|filter|status)\s*=\s*(?:speed-profile|everything|speed|verify|quicken|run-from-apk|extract)""",
        option = RegexOption.IGNORE_CASE
    )

    fun readStdout(commandArgs: List<String>, reader: Reader): String {
        return if (isPerPackageDump(commandArgs)) {
            var inDexoptSection = false
            var dexoptIndent = 0
            collectBounded(
                reader = reader,
                maxChars = MAX_PACKAGE_EVIDENCE_CHARS,
                includeLine = { line ->
                    val lower = line.trim().lowercase()
                    if (lower.startsWith("dexopt state:")) {
                        inDexoptSection = true
                        dexoptIndent = line.indexOfFirst { !it.isWhitespace() }
                    } else if (line.isNotBlank() &&
                        line.indexOfFirst { !it.isWhitespace() } <= dexoptIndent
                    ) {
                        inDexoptSection = false
                    }
                    val isCompilerSignal = compilerFilterSignal.containsMatchIn(lower)
                    when {
                        lower.contains("dexopt=") || lower.contains("opttimems=") -> false
                        lower.startsWith("dexopt state:") -> true
                        inDexoptSection && (isCompilerSignal || lower.startsWith("[") ||
                            lower.startsWith("package [") || lower.startsWith("path:")) -> true
                        lower.startsWith("lastupdatetime=") -> true
                        lower.startsWith("codepath=") -> true
                        lower.startsWith("resourcepath=") -> true
                        lower.startsWith("overlaytarget=") -> true
                        lower.startsWith("pkgflags=") -> true
                        lower.startsWith("flags=") -> true
                        else -> false
                    }
                }
            )
        } else {
            collectBounded(reader, MAX_STDOUT_CHARS) { true }
        }
    }

    fun readStderr(reader: Reader): String =
        collectBounded(reader, MAX_STDERR_CHARS) { true }

    private fun isPerPackageDump(commandArgs: List<String>): Boolean {
        val isCmdPackageDump = commandArgs.size == 4 &&
            commandArgs.take(3) == listOf("cmd", "package", "dump")
        val isDumpsysPackage = commandArgs.size == 3 &&
            commandArgs.take(2) == listOf("dumpsys", "package") &&
            PackageNameValidator.isValid(commandArgs[2])
        return isCmdPackageDump || isDumpsysPackage
    }

    private fun collectBounded(
        reader: Reader,
        maxChars: Int,
        includeLine: (String) -> Boolean
    ): String {
        require(maxChars > TRUNCATION_MARKER.length + 1)
        val contentLimit = maxChars - TRUNCATION_MARKER.length - 1
        val output = StringBuilder(minOf(contentLimit, 16 * 1024))
        var truncated = false

        reader.buffered().use { buffered ->
            while (true) {
                val line = buffered.readLine() ?: break
                if (!includeLine(line)) continue
                if (truncated) continue

                val separatorLength = if (output.isEmpty()) 0 else 1
                val remaining = contentLimit - output.length - separatorLength
                when {
                    remaining <= 0 -> truncated = true
                    line.length <= remaining -> {
                        if (separatorLength == 1) output.append('\n')
                        output.append(line)
                    }
                    else -> {
                        if (separatorLength == 1) output.append('\n')
                        output.append(line.safePrefix(remaining))
                        truncated = true
                    }
                }
            }
        }

        if (truncated) {
            if (output.isNotEmpty()) output.append('\n')
            output.append(TRUNCATION_MARKER)
        }
        return output.toString().trim()
    }

    private fun String.safePrefix(maxLength: Int): String {
        if (length <= maxLength) return this
        var endIndex = maxLength.coerceAtLeast(0)
        if (endIndex in 1 until length &&
            this[endIndex - 1].isHighSurrogate() &&
            this[endIndex].isLowSurrogate()
        ) {
            endIndex--
        }
        return substring(0, endIndex)
    }
}
