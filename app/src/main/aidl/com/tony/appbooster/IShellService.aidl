// IShellService.aidl
package com.tony.appbooster;

/**
 * AIDL interface for Shizuku-based shell command execution.
 * This service runs with elevated privileges (shell UID).
 */
interface IShellService {
    /**
     * Executes a shell command and returns the result.
     *
     * @param commandArgs Validated command argv to execute.
     * @return Array containing [exitCode, stdout, stderr]
     */
    String[] executeCommand(in String[] commandArgs);

    /**
     * Destroys the service and releases resources.
     */
    void destroy();
}
