package com.tony.appbooster.data.util

import com.tony.appbooster.BuildConfig
import com.tony.appbooster.data.util.PackageListQueryService.Companion.SELF_PACKAGE_NAME
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries installed packages from the device via shell commands.
 *
 * Encapsulates package-list parsing logic so that repository
 * classes can stay focused on orchestration. Parsing is unified in a
 * single [parsePackageLines] method shared by the primary and fallback
 * commands.
 *
 * @property shellDataSource Data source that executes shell commands.
 * @property logger Shared logger for diagnostic output.
 * @constructor Creates a query service with required shell and logging dependencies.
 */
@Singleton
class PackageListQueryService @Inject constructor(
    private val shellDataSource: AdbShellDataSource,
    private val logger: OptimizationLogger
) {

    companion object {
        /** Package name of this app, excluded from optimization to prevent self-crash. */
        internal val SELF_PACKAGE_NAME: String = BuildConfig.APPLICATION_ID

        /** Maximum characters shown when previewing raw command output for diagnostics. */
        private const val OUTPUT_PREVIEW_LENGTH = 500
    }

    /**
     * Queries all installed package names from the device.
     *
     * Uses `pm list packages` as the primary source: its output is a single
     * `package:<name>` line per app with no extra metadata, so it stays
     * comfortably within the Binder IPC transaction size limit even on
     * devices with hundreds of installed apps/packages (e.g. Samsung phones
     * with extensive OEM/Knox bloatware). If it yields no parsable packages
     * (unexpected but possible on some OEM shells), falls back to the more
     * verbose `dumpsys package`, which is more likely to fail via Binder
     * transaction overflow on such devices but is kept as a last resort.
     *
     * @return List of normalised package names, or an empty list on failure.
     */
    suspend fun queryInstalledPackages(): List<String> {
        queryPackages(ShellCommandSpec.ListPackages)?.let { return it }

        logger.addLog("pm list packages failed or returned nothing; falling back to dumpsys package.")
        return queryPackages(ShellCommandSpec.DumpsysPackage) ?: emptyList()
    }

    /**
     * Executes [command] and parses its output into a package name list.
     *
     * @param command Shell command expected to enumerate installed packages.
     * @return Parsed package list, or null if the command failed or produced
     * no parsable packages.
     */
    private suspend fun queryPackages(command: ShellCommandSpec): List<String>? {
        logger.addLog("> ${command.displayCommand}")
        val result = shellDataSource.executeCommand(command)

        return result.fold(
            onSuccess = { output ->
                val packages = parsePackageLines(output)
                if (packages.isNotEmpty()) {
                    logger.addLog("Found ${packages.size} packages")
                    return@fold packages
                }

                logger.addLog("${command.displayCommand} returned no parsable packages.")
                logger.addLog("Raw output length: ${output.length} chars")
                logger.addLog("Preview: ${output.take(OUTPUT_PREVIEW_LENGTH).replace("\n", "\\n")}")
                null
            },
            onFailure = { throwable ->
                logger.addLog("Failed to query installed packages via ${command.displayCommand}: ${throwable.message}")
                null
            }
        )
    }

    /**
     * Parses raw package-list output into a deduplicated list of valid package
     * names, excluding [SELF_PACKAGE_NAME].
     *
     * Handles multiple line-ending formats (CRLF, LF, CR) and the
     * Handles the standard `package:<name>` prefix and `dumpsys package`
     * `Package [<name>]` entries.
     *
     * @param output Raw shell command output.
     * @return Parsed and filtered package name list.
     */
    internal fun parsePackageLines(output: String): List<String> {
        val normalised = output
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        return normalised.split("\n")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> extractPackageName(line) }
            .filter { pkg ->
                pkg.contains(".") &&
                    pkg != SELF_PACKAGE_NAME &&
                    !pkg.contains(SELF_PACKAGE_NAME)
            }
            .distinct()
            .toList()
    }

    /**
     * Extracts a package name from a single output line.
     *
     * @param line Trimmed, non-empty line from package-list output.
     * @return Extracted package name, or null if the line is not parseable.
     */
    private fun extractPackageName(line: String): String? = when {
        line.startsWith("package:") -> line.removePrefix("package:").trim().ifEmpty { null }
        line.startsWith("Package [") && line.contains("]") ->
            line.substringAfter("Package [").substringBefore("]").trim().ifEmpty { null }
        // Some Android versions emit bare package names
        line.contains(".") && !line.contains(" ") && !line.contains("=") -> line
        else -> null
    }
}

