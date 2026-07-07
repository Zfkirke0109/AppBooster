package com.tony.appbooster.domain.model.common

/**
 * Represents the full result of a single shell command execution.
 *
 * Business purpose:
 * - Allows higher layers to distinguish "command unsupported / failed" from
 *   "command succeeded but produced no output".
 * - Enables more reliable system-truth checks (e.g., `cmd package compile --check`).
 *
 * @property exitCode Process exit code returned by the shell service.
 * @property stdout Standard output captured from the command.
 * @property stderr Standard error captured from the command.
 */
data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    /**
     * @return True when [exitCode] equals 0.
     */
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Thrown when a shell command completes but returns a non-zero exit code.
 *
 * @property command Shell command that was executed.
 * @property exitCode Process exit code returned by the shell service.
 * @property stderr Standard error captured from the command.
 * @property stdout Standard output captured from the command.
 */
class ShellCommandException(
    val command: String,
    val exitCode: Int,
    val stderr: String,
    val stdout: String
) : IllegalStateException(
    buildShellCommandFailureMessage(command, exitCode, stderr, stdout)
)

/**
 * Returns this result when successful, otherwise throws [ShellCommandException]
 * with full shell diagnostics.
 */
fun ShellCommandResult.requireSuccess(command: String): ShellCommandResult {
    if (isSuccess) return this

    throw ShellCommandException(
        command = command,
        exitCode = exitCode,
        stderr = stderr,
        stdout = stdout
    )
}

private fun buildShellCommandFailureMessage(
    command: String,
    exitCode: Int,
    stderr: String,
    stdout: String
): String {
    val detail = stderr.trim()
        .ifEmpty { stdout.trim() }
        .ifEmpty { "Command failed" }

    return "$detail (exitCode=$exitCode, command=$command)"
}
