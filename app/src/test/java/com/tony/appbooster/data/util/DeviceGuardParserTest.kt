package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.device.StandbyBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceGuardParserTest {

    @Test
    fun `given thermal mStatus severe output when parsed then returns severe code`() {
        val snapshot = ThermalStatusParser.parse("ThermalManagerService\n  mStatus=3\n")

        assertEquals(3, snapshot.statusCode)
        assertEquals("severe", snapshot.label)
    }

    @Test
    fun `given standby bucket restricted output when parsed then returns restricted`() {
        val snapshot = StandbyBucketParser.parse("restricted")

        assertEquals(StandbyBucket.Restricted, snapshot.bucket)
    }

    @Test
    fun `given numeric standby bucket output when parsed then maps android bucket code`() {
        val snapshot = StandbyBucketParser.parse("10")

        assertEquals(StandbyBucket.Active, snapshot.bucket)
    }
}
