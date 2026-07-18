/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.firmware

import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.FlashSizes

/**
 * Turns what the chip told us about itself into the firmware variant to flash.
 *
 * The three variants in the manifest map cleanly onto (chip, flash size), so the user
 * never has to pick their model:
 *   ESP32-S3            -> CoreS3
 *   ESP32 with 4MB      -> Basic_4MB   (old Basic, <= 2020.5, no PSRAM)
 *   ESP32 with >= 16MB  -> ESP32_16MB  (Basic 16MB/v2.7, Fire, all Core2)
 */
object DeviceModel {

    private const val PATH_CORE_S3 = "CoreS3"
    private const val PATH_BASIC_4MB = "Basic_4MB"
    private const val PATH_ESP32_16MB = "ESP32_16MB"

    /** The variant subdirectories this app knows how to match a device to. */
    val KNOWN_PATHS = setOf(PATH_CORE_S3, PATH_BASIC_4MB, PATH_ESP32_16MB)

    private const val MB = 1 shl 20

    /** Human-readable name for what is plugged in, for the confirmation screen. */
    fun describe(chip: Chip, flashSize: Int): String = when {
        chip == Chip.ESP32S3 -> "M5Stack CoreS3"
        chip == Chip.ESP32 && flashSize <= 4 * MB -> "M5Stack Basic (pre-2020.5, no PSRAM)"
        chip == Chip.ESP32 -> "M5Stack Basic 16MB / Fire / Core2"
        else -> chip.displayName
    }

    /**
     * Picks the manifest entry for this device, or throws with a message worth showing.
     */
    fun select(chip: Chip, flashSize: Int, variants: List<FirmwareVariant>): FirmwareVariant {
        val wantedPath = when {
            chip == Chip.ESP32S3 -> PATH_CORE_S3
            chip == Chip.ESP32 && flashSize <= 4 * MB -> PATH_BASIC_4MB
            chip == Chip.ESP32 && flashSize >= 16 * MB -> PATH_ESP32_16MB
            else -> throw IllegalStateException(
                "No firmware exists for a ${chip.displayName} with ${FlashSizes.format(flashSize)} of flash."
            )
        }

        val variant = variants.firstOrNull { it.path == wantedPath }
            ?: throw IllegalStateException("The firmware repository has no \"$wantedPath\" build.")

        // The manifest states the chip each build targets; refuse to flash a mismatch
        // rather than brick the device on a repository change.
        check(variant.chip == chip) {
            "Firmware \"$wantedPath\" targets ${variant.chip.displayName}, but this is a ${chip.displayName}."
        }
        return variant
    }
}
