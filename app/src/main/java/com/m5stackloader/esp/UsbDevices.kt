/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.esp

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Finds the M5Stack on the USB bus.
 *
 * The stock prober already knows the CP210x (Basic/Fire/Core2) and CH34x/CH9102
 * (newer Basic) bridges. The CoreS3 has no bridge at all - the ESP32-S3 drives USB
 * itself and enumerates as a CDC device under Espressif's vendor id - so that pairing
 * is added here.
 */
object UsbDevices {

    private fun prober(): UsbSerialProber {
        val table = UsbSerialProber.getDefaultProbeTable()
        table.addProduct(ESPRESSIF_VID, PID_USB_SERIAL_JTAG, CdcAcmSerialDriver::class.java)
        table.addProduct(ESPRESSIF_VID, PID_USB_OTG_CDC, CdcAcmSerialDriver::class.java)
        return UsbSerialProber(table)
    }

    /** The first attached device we know how to talk to, or null. */
    fun find(manager: UsbManager): UsbSerialDriver? =
        prober().findAllDrivers(manager).firstOrNull()

    fun driverFor(manager: UsbManager, device: UsbDevice): UsbSerialDriver? =
        prober().findAllDrivers(manager).firstOrNull { it.device.deviceId == device.deviceId }

    const val ESPRESSIF_VID = 0x303A
    private const val PID_USB_SERIAL_JTAG = 0x1001
    private const val PID_USB_OTG_CDC = 0x0002
}
