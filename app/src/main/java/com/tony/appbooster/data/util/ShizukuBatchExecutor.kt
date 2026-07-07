package com.tony.appbooster.data.util

import android.util.Log
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandException
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes shell commands in controllable batches to avoid Binder IPC buffer overflow.
 *
 * Android's Binder IPC has a ~1MB transaction buffer limit. When querying all installed packages
 * or executing massive batch compile commands, sending everything at once can exhaust this limit,
 * resulting in `DeadObjectException: Transaction failed on small parcel`.
 *
 * This executor:
 * - Chunks operations into smaller batches (default 50 packages per batch)
 * - Injects delays between batches to allow Binder to flush buffers
 * - Tracks success/failure per batch for resumable operations
 * - Logs detailed metrics for debugging
 *
 * Business purpose:
 * - Prevent "Full Scan and Optimize All Apps" from overwhelming the Binder
 * - Enable safe processing of 1000+ app devices
 * - Improve reliability and observability of large-scale operations
 *
 * @property shellDataSource Low-level shell command execution.
 */
@Singleton
class ShizukuBatchExecutor @Inject constructor(
    private val shellDataSource: AdbShellDataSource
) {
    companion object {
        private const val TAG = "ShizukuBatchExecutor"

        /** Maximum packages per batch to stay safely under Binder 1MB limit. */
        private const val DEFAULT_BATCH_SIZE = 50

        /** Delay between batches to allow Binder buffer to flush (milliseconds). */
        private const val BATCH_DELAY_MS = 200L

        /**
         * Maximum retries per batch before giving up.
         * DeadObjectException often resolves with a brief retry.
         */
        private const val MAX_BATCH_RETRIES = 2
    }

    /**
     * Executes a batch-safe package compilation across all provided packages.
     *
     * Chunks packages into batches of [DEFAULT_BATCH_SIZE], executes compile commands
     * sequentially with inter-batch delays, and tracks per-package results for resumability.
     *
     * @param packages List of package names to optimize.
     * @param compileMode Compilation mode (e.g., "speed", "speed-profile").
     * @param onProgress Called for each package with (success, packageName, errorMessage).
     * @return Map of package names to (success: Boolean, error: String?).
     * @throws ShellCommandException if a batch fails irreversibly after retries.
     */
    suspend fun executeCompilationBatch(
        packages: List<String>,
        compileMode: String,
        onProgress: suspend (success: Boolean, packageName: String, error: String?) -> Unit
    ): Map<String, Pair<Boolean, String?>> {
        Log.d(TAG, "Starting batch execution: ${packages.size} packages, batch size=$DEFAULT_BATCH_SIZE")

        val results = mutableMapOf<String, Pair<Boolean, String?>>()
        val batches = packages.chunked(DEFAULT_BATCH_SIZE)

        batches.forEachIndexed { batchIndex, batch ->
            Log.d(TAG, "Executing batch ${batchIndex + 1}/${batches.size}: ${batch.size} packages")

            batch.forEach { packageName ->
                val result = executeWithRetry(packageName, compileMode, MAX_BATCH_RETRIES)
                results[packageName] = result

                val success = result.first
                val error = result.second
                onProgress(success, packageName, error)

                if (!success) {
                    Log.w(TAG, "Package failed: $packageName - $error")
                } else {
                    Log.d(TAG, "Package compiled: $packageName")
                }
            }

            // Delay between batches to allow Binder to flush its buffer.
            // This significantly reduces DeadObjectException on large device APK counts.
            if (batchIndex < batches.size - 1) {
                Log.d(TAG, "Batch ${batchIndex + 1} complete, waiting ${BATCH_DELAY_MS}ms for Binder flush...")
                delay(BATCH_DELAY_MS)
            }
        }

        val successCount = results.count { it.value.first }
        Log.d(TAG, "Batch execution complete: $successCount/${packages.size} succeeded")

        return results
    }

    /**
     * Executes a single package compilation with exponential backoff retry on DeadObjectException.
     *
     * @param packageName Package to compile.
     * @param compileMode Compilation mode.
     * @param maxRetries Maximum retry attempts.
     * @return Pair of (success: Boolean, errorMessage: String?).
     */
    private suspend fun executeWithRetry(
        packageName: String,
        compileMode: String,
        maxRetries: Int
    ): Pair<Boolean, String?> {
        var lastError: String? = null

        repeat(maxRetries) { attempt ->
            try {
                val command = ShellCommandSpec.PackageCompile(
                    packageName = packageName,
                    mode = compileMode,
                    force = true
                )

                val result = shellDataSource.executeCommandDetailed(command).getOrNull()
                if (result != null && result.exitCode == 0) {
                    return Pair(true, null)
                } else {
                    lastError = "Exit code ${result?.exitCode}: ${result?.stderr}"
                    // Continue to retry
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()

                // Check for DeadObjectException specifically
                if (e.cause?.toString()?.contains("DeadObjectException") == true ||
                    e.toString().contains("DeadObjectException")
                ) {
                    Log.w(TAG, "DeadObjectException on attempt ${attempt + 1}/$maxRetries for $packageName, retrying...")
                    if (attempt < maxRetries - 1) {
                        delay(100L * (attempt + 1)) // Exponential backoff: 100ms, 200ms, etc.
                    }
                } else {
                    // Non-retryable error, fail fast
                    return Pair(false, lastError)
                }
            }
        }

        return Pair(false, lastError)
    }

    /**
     * Batches large package lists for analysis queries to avoid overwhelming `dumpsys` parsing.
     *
     * This should be used when querying compilation status of all installed packages.
     * Instead of dumping all packages at once, split into manageable chunks with delays.
     *
     * @param packages Packages to query.
     * @param onQueryBatch Called for each batch: index, batch size, packages.
     * @return Combined results from all batch queries.
     */
    suspend fun <T> executeBatchedQuery(
        packages: List<String>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        onQueryBatch: suspend (batchIndex: Int, batch: List<String>) -> List<T>
    ): List<T> {
        Log.d(TAG, "Starting batched query: ${packages.size} items, batch size=$batchSize")

        val allResults = mutableListOf<T>()
        val batches = packages.chunked(batchSize)

        batches.forEachIndexed { batchIndex, batch ->
            try {
                val batchResults = onQueryBatch(batchIndex, batch)
                allResults.addAll(batchResults)

                if (batchIndex < batches.size - 1) {
                    delay(BATCH_DELAY_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch query failed at batch $batchIndex", e)
                throw e
            }
        }

        Log.d(TAG, "Batched query complete: ${allResults.size} results")
        return allResults
    }
}
