package com.tony.appbooster.domain.service

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Prevents a scan, compile, rollback and measurement from interleaving commands. */
@Singleton
class ShellOperationCoordinator @Inject constructor() {
    private val mutex = Mutex()
    /** Fail promptly when another complete operation owns the shell. */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        check(mutex.tryLock()) { "Another scan, compilation or measurement is running. Wait for it to finish." }
        return try { block() } finally { mutex.unlock() }
    }
}
