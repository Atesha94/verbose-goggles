package app.tempo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateTest {
    @Test fun parsesUint8() = assertEquals(72, HeartRate.parseBleMeasurement(byteArrayOf(0x00, 72)))

    @Test fun parsesUint16LittleEndian() =
        assertEquals(180, HeartRate.parseBleMeasurement(byteArrayOf(0x01, 0xB4.toByte(), 0x00)))

    @Test fun parsesUint8WithHighBitSet() =
        assertEquals(200, HeartRate.parseBleMeasurement(byteArrayOf(0x00, 0xC8.toByte())))

    @Test fun rejectsMalformedPackets() {
        assertNull(HeartRate.parseBleMeasurement(null))
        assertNull(HeartRate.parseBleMeasurement(byteArrayOf()))
        assertNull(HeartRate.parseBleMeasurement(byteArrayOf(0x00)))
        assertNull(HeartRate.parseBleMeasurement(byteArrayOf(0x01, 0x50)))
    }

    @Test fun rejectsImplausibleValues() {
        assertNull(HeartRate.parseBleMeasurement(byteArrayOf(0x00, 10)))
        assertNull(HeartRate.parseBleMeasurement(byteArrayOf(0x01, 0xFF.toByte(), 0x01)))
    }

    @Test fun zones() {
        assertEquals(0, HeartRate.zone(80, 190))
        assertEquals(1, HeartRate.zone(100, 190))
        assertEquals(3, HeartRate.zone(140, 190))
        assertEquals(5, HeartRate.zone(175, 190))
        assertEquals(0, HeartRate.zone(150, 0))
    }

    @Test fun keytelIsPositiveAndScalesWithTime() {
        val a = HeartRate.keytelCalories(150.0, 10.0, 75.0, 35, HeartRate.Sex.MALE)
        val b = HeartRate.keytelCalories(150.0, 20.0, 75.0, 35, HeartRate.Sex.MALE)
        assertTrue(a > 100 && a < 200, "was $a")
        assertEquals(a * 2, b, 1e-9)
        assertEquals(0.0, HeartRate.keytelCalories(30.0, 10.0, 50.0, 20, HeartRate.Sex.FEMALE))
    }

    @Test fun metFallback() = assertEquals(80.0, HeartRate.metCalories(8.0, 75.0, 8.0), 1e-9)
}
