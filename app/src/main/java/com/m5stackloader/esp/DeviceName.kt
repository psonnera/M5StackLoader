/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.esp

/**
 * Derives a device name unique to this M5Stack from its factory MAC address, so several
 * devices on one network get distinct mDNS names / SoftAP SSIDs instead of colliding on a
 * fixed name.
 *
 * The firmware (`applyDefaultDeviceName` in M5NSconfig.cpp) derives this exact same suffix
 * independently, from the same eFuse bytes read on-device, so both sides agree on the name
 * without any communication between them.
 */
object DeviceName {
    /** mDNS/SoftAP hostname, e.g. "m5ns-7a3f" for a device whose MAC ends in ...:7A:3F. */
    fun hostname(mac: ByteArray): String = "m5ns-%02x%02x".format(mac[4], mac[5])

    /** The value written to the firmware's `device_name` NVS key, e.g. "M5NS-7A3F". */
    fun deviceName(mac: ByteArray): String = "M5NS-%02X%02X".format(mac[4], mac[5])
}
