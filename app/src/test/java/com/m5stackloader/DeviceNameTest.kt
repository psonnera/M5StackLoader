package com.m5stackloader

import com.m5stackloader.esp.DeviceName
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {

    private val mac = byteArrayOf(0x24, 0x6F, 0x28, 0x11, 0x7A, 0x3F.toByte())

    @Test
    fun `hostname is lowercase and uses only the last two MAC bytes`() {
        assertEquals("m5ns-7a3f", DeviceName.hostname(mac))
    }

    @Test
    fun `deviceName is uppercase and uses only the last two MAC bytes`() {
        assertEquals("M5NS-7A3F", DeviceName.deviceName(mac))
    }

    @Test
    fun `pads single-digit hex bytes with a leading zero`() {
        val mac = byteArrayOf(0, 0, 0, 0, 0x0A, 0x03)
        assertEquals("m5ns-0a03", DeviceName.hostname(mac))
        assertEquals("M5NS-0A03", DeviceName.deviceName(mac))
    }
}
