/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * The chip constants below are transcribed from esptool, whose copyright notice is
 * preserved here as required by the GNU General Public License:
 *
 * Copyright (C) 2014-2023 Fredrik Ahlberg, Angus Gratton,
 *                         Espressif Systems (Shanghai) CO LTD, other contributors as noted.
 *   esptool - https://github.com/espressif/esptool - GPL-2.0-or-later.
 *   Specifically esptool/targets/esp32.py, esp32s3.py and cmds.py.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.esp

/**
 * Per-chip register layout and protocol quirks.
 *
 * The two that bite hardest if you get them wrong:
 *  - [romStatusBytes]: the ESP32 ROM appends 4 status bytes to every reply, but the
 *    ESP32-S3 ROM (and both stubs) append only 2.
 *  - [romSupportsEncryptedFlash]: the S3 ROM expects one extra word on FLASH_BEGIN /
 *    FLASH_DEFL_BEGIN that the ESP32 ROM does not.
 */
enum class Chip(
    val displayName: String,
    val magicValues: List<Int>,
    val spiRegBase: Int,
    val spiUsrOffs: Int,
    val spiUsr1Offs: Int,
    val spiUsr2Offs: Int,
    val spiMosiDlenOffs: Int,
    val spiMisoDlenOffs: Int,
    val spiW0Offs: Int,
    val romStatusBytes: Int,
    val romSupportsEncryptedFlash: Boolean,
    val stubAsset: String,
    /** ROM .bss variable telling us which console the ROM is using; null if not applicable. */
    val uartDevBufNoReg: Int?,
) {
    ESP32(
        displayName = "ESP32",
        magicValues = listOf(0x00F01D83),
        spiRegBase = 0x3FF42000,
        spiUsrOffs = 0x1C,
        spiUsr1Offs = 0x20,
        spiUsr2Offs = 0x24,
        spiMosiDlenOffs = 0x28,
        spiMisoDlenOffs = 0x2C,
        spiW0Offs = 0x80,
        romStatusBytes = 4,
        romSupportsEncryptedFlash = false,
        stubAsset = "stub_flasher/esp32.json",
        uartDevBufNoReg = null,
    ),
    ESP32S3(
        displayName = "ESP32-S3",
        magicValues = listOf(0x9),
        spiRegBase = 0x60002000,
        spiUsrOffs = 0x18,
        spiUsr1Offs = 0x1C,
        spiUsr2Offs = 0x20,
        spiMosiDlenOffs = 0x24,
        spiMisoDlenOffs = 0x28,
        spiW0Offs = 0x58,
        romStatusBytes = 2,
        romSupportsEncryptedFlash = true,
        stubAsset = "stub_flasher/esp32s3.json",
        uartDevBufNoReg = 0x3FCEF14C,
    );

    companion object {
        const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000

        /** Value of [uartDevBufNoReg] when the ROM console is running over USB-OTG. */
        const val UARTDEV_BUF_NO_USB_OTG = 3

        fun fromMagic(magic: Int): Chip? = entries.firstOrNull { magic in it.magicValues }
    }
}

/** Capacity byte of the SPI flash JEDEC id -> size in bytes. From esptool's DETECTED_FLASH_SIZES. */
object FlashSizes {
    private const val MB = 1024 * 1024

    private val SIZES = mapOf(
        0x12 to (256 * 1024), 0x13 to (512 * 1024), 0x14 to (1 * MB), 0x15 to (2 * MB),
        0x16 to (4 * MB), 0x17 to (8 * MB), 0x18 to (16 * MB), 0x19 to (32 * MB),
        0x1A to (64 * MB), 0x20 to (64 * MB),
        0x32 to (256 * 1024), 0x33 to (512 * 1024), 0x34 to (1 * MB), 0x35 to (2 * MB),
        0x36 to (4 * MB), 0x37 to (8 * MB), 0x38 to (16 * MB), 0x39 to (32 * MB),
    )

    fun fromSizeId(sizeId: Int): Int? = SIZES[sizeId]

    fun format(bytes: Int): String = "${bytes / MB}MB"
}
