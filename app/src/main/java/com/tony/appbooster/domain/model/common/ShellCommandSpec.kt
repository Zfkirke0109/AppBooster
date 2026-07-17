package com.tony.appbooster.domain.model.common

/**
 * Typed allowlist of shell commands the app is allowed to execute.
 */
sealed interface ShellCommandSpec {
    val argv: List<String>
    val displayCommand: String

    data object DumpsysPackage : ShellCommandSpec {
        override val argv: List<String> = listOf("dumpsys", "package")
        override val displayCommand: String = argv.joinToString(" ")
    }

    /**
     * Lightweight package enumeration via `pm list packages`.
     *
     * Unlike [DumpsysPackage], this returns a single `package:<name>` line per
     * installed package with no additional metadata. On devices with a large
     * number of installed apps (e.g. Samsung phones with extensive OEM/Knox
     * bloatware), the full `dumpsys package` dump can exceed the Binder IPC
     * transaction size limit when marshalled back through the Shizuku
     * UserService, causing the query to fail and silently return zero
     * packages. `pm list packages` output is orders of magnitude smaller and
     * safely stays within Binder limits regardless of how many apps/packages
     * are installed, so it is used as the primary source for enumerating all
     * installed packages.
     */
    data object ListPackages : ShellCommandSpec {
        override val argv: List<String> = listOf("pm", "list", "packages")
        override val displayCommand: String = argv.joinToString(" ")
    }

    data object DumpsysPackageDexopt : ShellCommandSpec {
        override val argv: List<String> = listOf("dumpsys", "package", "dexopt")
        override val displayCommand: String = argv.joinToString(" ")
    }

    data object DumpsysThermalService : ShellCommandSpec {
        override val argv: List<String> = listOf("dumpsys", "thermalservice")
        override val displayCommand: String = argv.joinToString(" ")
    }

    data object PackageHelp : ShellCommandSpec {
        override val argv: List<String> = listOf("cmd", "package", "help")
        override val displayCommand: String = argv.joinToString(" ")
    }

    data object PackageCompileHelp : ShellCommandSpec {
        override val argv: List<String> = listOf("cmd", "package", "compile", "--help")
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class DumpsysPackageForPackage(
        val packageName: String
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
        }

        override val argv: List<String> = listOf("dumpsys", "package", packageName)
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class PackageDump(
        val packageName: String
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
        }

        override val argv: List<String> = listOf("cmd", "package", "dump", packageName)
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class PackageCompile(
        val packageName: String,
        val mode: String,
        val force: Boolean = true,
        val full: Boolean = false,
        val verbose: Boolean = false
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
            require(mode in COMPILE_MODES) { "Unsupported compile mode: $mode" }
        }

        override val argv: List<String> = buildList {
            add("cmd")
            add("package")
            add("compile")
            add("-m")
            add(mode)
            if (force) add("-f")
            if (full) add("--full")
            if (verbose) add("-v")
            add(packageName)
        }
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class PackageCompileCheck(
        val packageName: String
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
        }

        override val argv: List<String> = listOf("cmd", "package", "compile", "--check", packageName)
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class PackageCompileReset(
        val packageName: String
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
        }

        override val argv: List<String> = listOf("cmd", "package", "compile", "--reset", packageName)
        override val displayCommand: String = argv.joinToString(" ")
    }

    data class GetStandbyBucket(
        val packageName: String
    ) : ShellCommandSpec {
        init {
            PackageNameValidator.requireValid(packageName)
        }

        override val argv: List<String> = listOf("am", "get-standby-bucket", packageName)
        override val displayCommand: String = argv.joinToString(" ")
    }

    companion object {
        private val COMPILE_MODES = setOf("speed-profile", "speed")

        fun isAllowedArgv(argv: List<String>): Boolean {
            return when {
                argv == DumpsysPackage.argv -> true
                argv == ListPackages.argv -> true
                argv == DumpsysPackageDexopt.argv -> true
                argv == DumpsysThermalService.argv -> true
                argv == PackageHelp.argv -> true
                argv == PackageCompileHelp.argv -> true
                argv.size == 3 &&
                    argv[0] == "dumpsys" &&
                    argv[1] == "package" ->
                    PackageNameValidator.isValid(argv[2])

                isAllowedPackageDump(argv) -> true
                isAllowedPackageCompile(argv) -> true
                isAllowedPackageCompileCheck(argv) -> true
                isAllowedPackageCompileReset(argv) -> true
                isAllowedGetStandbyBucket(argv) -> true
                else -> false
            }
        }

        private fun isAllowedPackageDump(argv: List<String>): Boolean =
            argv.size == 4 &&
                argv.take(3) == listOf("cmd", "package", "dump") &&
                PackageNameValidator.isValid(argv[3])

        private fun isAllowedPackageCompile(argv: List<String>): Boolean {
            if (argv.size !in 6..9) return false
            if (argv.take(5) != listOf("cmd", "package", "compile", "-m", argv[4])) return false
            if (argv[4] !in COMPILE_MODES) return false

            val options = argv.subList(5, argv.lastIndex)
            val packageName = argv.last()
            val allowedOptions = setOf("-f", "--full", "-v")
            return options.all { it in allowedOptions } &&
                options.distinct().size == options.size &&
                PackageNameValidator.isValid(packageName)
        }

        private fun isAllowedPackageCompileCheck(argv: List<String>): Boolean =
            argv.size == 5 &&
                argv.take(4) == listOf("cmd", "package", "compile", "--check") &&
                PackageNameValidator.isValid(argv[4])

        private fun isAllowedPackageCompileReset(argv: List<String>): Boolean =
            argv.size == 5 &&
                argv.take(4) == listOf("cmd", "package", "compile", "--reset") &&
                PackageNameValidator.isValid(argv[4])

        private fun isAllowedGetStandbyBucket(argv: List<String>): Boolean =
            argv.size == 3 &&
                argv.take(2) == listOf("am", "get-standby-bucket") &&
                PackageNameValidator.isValid(argv[2])
    }
}

object PackageNameValidator {
    private val PACKAGE_NAME_REGEX =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun isValid(packageName: String): Boolean = PACKAGE_NAME_REGEX.matches(packageName)

    fun requireValid(packageName: String): String {
        require(isValid(packageName)) { "Invalid package name: $packageName" }
        return packageName
    }
}
