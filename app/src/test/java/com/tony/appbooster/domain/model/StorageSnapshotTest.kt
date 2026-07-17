package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSnapshotTest {
    @Test
    fun `available bytes below reserve blocks optimization`() {
        val snapshot = StorageSnapshot(
            totalBytes = 20L,
            availableBytes = 4L,
            reserveBytes = 5L,
            capturedAtMs = 1L
        )

        assertTrue(snapshot.isBelowReserve)
    }

    @Test
    fun `available bytes equal to reserve allow optimization`() {
        val snapshot = StorageSnapshot(
            totalBytes = 20L,
            availableBytes = 5L,
            reserveBytes = 5L,
            capturedAtMs = 1L
        )

        assertFalse(snapshot.isBelowReserve)
    }
}
