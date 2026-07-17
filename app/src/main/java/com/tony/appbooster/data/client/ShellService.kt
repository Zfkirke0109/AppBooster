package com.tony.appbooster.data.client

import android.util.Log
import com.tony.appbooster.IShellService
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import java.io.InputStreamReader

/**
 * Shizuku UserService implementation that runs with elevated (shell UID) privileges.
 *
 * This service is started by Shizuku and runs in a separate process with the same
 * privileges as ADB shell. It only executes allowlisted command argv shapes.
 *
 * Note: This class runs in Shizuku's process, not the app's process.
 */
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"
    }

    override fun executeCommand(commandArgs: Array<String>): Array<String> {
        val args = commandArgs.toList()
        val displayCommand = args.joinToString(" ")
        Log.d(TAG, "Executing: $displayCommand")

        return try {
            if (!ShellCommandSpec.isAllowedArgv(args)) {
                return arrayOf("-1", "", "Rejected unsafe shell command: $displayCommand")
            }

            val process = ProcessBuilder(args).start()

            var output = ""
            var error = ""
            val outputReaderThread = Thread {
                output = ShellServiceOutputPolicy.readStdout(
                    commandArgs = args,
                    reader = InputStreamReader(process.inputStream)
                )
            }
            val errorReaderThread = Thread {
                error = ShellServiceOutputPolicy.readStderr(InputStreamReader(process.errorStream))
            }

            outputReaderThread.start()
            errorReaderThread.start()
            val exitCode = process.waitFor()
            outputReaderThread.join()
            errorReaderThread.join()
            process.destroy()

            Log.d(TAG, "Command completed: exitCode=$exitCode")

            arrayOf(exitCode.toString(), output.trim(), error.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Command failed", e)
            arrayOf("-1", "", e.message ?: "Unknown error")
        }
    }

    override fun destroy() {
        Log.d(TAG, "ShellService destroyed")
        System.exit(0)
    }
}
