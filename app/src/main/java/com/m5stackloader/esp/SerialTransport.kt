/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 */
package com.m5stackloader.esp

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * The byte pipe the [EspLoader] talks over, plus the two modem control lines the
 * ESP auto-reset circuit is wired to.
 */
interface SerialTransport {
    /** True for the ESP32-S3's built-in USB (M5Stack CoreS3), false for a CP210x/CH34x bridge. */
    val isNativeUsb: Boolean

    fun write(data: ByteArray)

    /** Returns the number of bytes read, or 0 if [timeoutMs] elapsed with nothing to read. */
    fun read(dest: ByteArray, timeoutMs: Int): Int

    fun setBaudRate(baud: Int)
    fun setDtr(value: Boolean)
    fun setRts(value: Boolean)

    /** Discards anything already buffered, so a stale reply can't be mistaken for a fresh one. */
    fun purge()

    fun close()
}

class UsbSerialTransport(
    private val port: UsbSerialPort,
    device: UsbDevice,
    private val connection: UsbDeviceConnection,
) : SerialTransport {

    override val isNativeUsb: Boolean = device.vendorId == ESPRESSIF_VID

    fun open(baud: Int) {
        port.open(connection)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int = port.read(dest, timeoutMs)

    override fun setBaudRate(baud: Int) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override fun setDtr(value: Boolean) {
        port.dtr = value
    }

    override fun setRts(value: Boolean) {
        port.rts = value
    }

    override fun purge() {
        // Not every driver implements the buffer purge ioctl; draining by hand always works.
        runCatching { port.purgeHwBuffers(true, true) }

        // A single empty read isn't proof the line is quiet - it can land in a gap between
        // two bursts of a reply flood. Require a few consecutive empty reads before calling
        // it drained, with a hard cap so a wedged device can't hang this forever.
        val scratch = ByteArray(256)
        val deadline = System.currentTimeMillis() + PURGE_TIMEOUT_MS
        var consecutiveEmpty = 0
        while (consecutiveEmpty < PURGE_QUIET_READS && System.currentTimeMillis() < deadline) {
            if (port.read(scratch, PURGE_READ_TIMEOUT_MS) > 0) {
                consecutiveEmpty = 0
            } else {
                consecutiveEmpty++
            }
        }
    }

    override fun close() {
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    companion object {
        const val ESPRESSIF_VID = 0x303A
        private const val WRITE_TIMEOUT_MS = 3000
        private const val PURGE_READ_TIMEOUT_MS = 20
        private const val PURGE_QUIET_READS = 3
        private const val PURGE_TIMEOUT_MS = 500
    }
}
