package com.tony.appbooster.domain.client

import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.shizuku.ShellResult
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for privileged shell command execution via Shizuku.
 *
 * Shizuku provides a way to run commands with shell (ADB) or root privileges
 * without requiring the device to be connected to a PC. The user must:
 * 1. Install the Shizuku app
 * 2. Start the Shizuku service via ADB (once) or root
 * 3. Grant permission to this app
 *
 * Once authorized, this client can execute any shell command with elevated privileges.
 */
interface ShizukuShellClient {

    /**
     * Current state of Shizuku availability and authorization.
     */
    val state: StateFlow<ShizukuState>

    /**
     * Checks the current Shizuku state and updates [state].
     * Call this when the app resumes to detect if Shizuku was started externally.
     */
    suspend fun refreshState()

    /**
     * Requests Shizuku permission from the user.
     * The result will be reflected in [state].
     */
    suspend fun requestPermission()

    /**
     * Checks if Shizuku is ready to execute commands.
     */
    fun isReady(): Boolean

    /**
     * Executes a shell command with Shizuku privileges.
     *
     * @param command The validated shell command spec to execute.
     * @return [ShellResult] containing exit code, stdout, and stderr.
     * @throws IllegalStateException if Shizuku is not ready.
     */
    suspend fun execute(command: ShellCommandSpec): ShellResult

    /**
     * Executes a shell command and streams output line by line.
     *
     * @param command The validated shell command spec to execute.
     * @return Flow emitting each line of output as it becomes available.
     */
    fun executeStreaming(command: ShellCommandSpec): Flow<Result<String>>

    /**
     * Opens the Shizuku app in Play Store or official download page.
     */
    fun openShizukuInstallPage()

    /**
     * Opens the Shizuku app to help user start the service.
     */
    fun openShizukuApp()
}
