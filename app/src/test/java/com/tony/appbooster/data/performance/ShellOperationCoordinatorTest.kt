package com.tony.appbooster.data.performance

import com.tony.appbooster.domain.service.ShellOperationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShellOperationCoordinatorTest {
    @Test fun `complete capture excludes compilation and cancellation releases ownership`() = runTest {
        val coordinator = ShellOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val job = launch { coordinator.exclusive { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await()
        var issuedCommand = false
        try { coordinator.exclusive { issuedCommand = true }; fail("Overlapping operation accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(issuedCommand)
        job.cancel(); job.join()
        assertEquals(42, coordinator.exclusive { 42 })
    }
}
