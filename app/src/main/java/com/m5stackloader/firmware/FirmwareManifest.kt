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
import org.json.JSONArray

/** One binary and the flash offset it belongs at. */
data class FirmwarePart(val offset: Int, val fileName: String)

/** One entry of the repository's Binaries/firmware.json. */
data class FirmwareVariant(
    val name: String,
    val version: String,
    /** Subdirectory under Binaries/, e.g. "CoreS3". Also identifies the variant. */
    val path: String,
    val chip: Chip,
    val parts: List<FirmwarePart>,
)

/**
 * Reads Binaries/firmware.json from the M5_NightscoutMon repository.
 *
 * The manifest describes each variant as the esptool command line that flashes it, so
 * the chip and the offset/file pairs are pulled straight out of that command rather
 * than being duplicated here. That keeps this app correct if the firmware author
 * changes an offset.
 */
object FirmwareManifest {

    private val CHIP_PATTERN = Regex("""--chip\s+(\S+)""")
    private val PART_PATTERN = Regex("""(0x[0-9a-fA-F]+)\s+%PATH\\(\S+)""")

    fun parse(json: String): List<FirmwareVariant> {
        val array = JSONArray(json)
        val variants = ArrayList<FirmwareVariant>(array.length())

        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val commands = entry.getJSONArray("commands")
            if (commands.length() == 0) continue
            val command = commands.getString(0)

            val chipName = CHIP_PATTERN.find(command)?.groupValues?.get(1) ?: continue
            val chip = chipFor(chipName) ?: continue

            val parts = PART_PATTERN.findAll(command).map { match ->
                FirmwarePart(
                    offset = match.groupValues[1].removePrefix("0x").toLong(16).toInt(),
                    fileName = match.groupValues[2],
                )
            }.toList()
            if (parts.isEmpty()) continue

            variants += FirmwareVariant(
                name = entry.getString("name"),
                version = entry.optString("version", "unknown"),
                path = entry.getString("path"),
                chip = chip,
                parts = parts,
            )
        }
        return variants
    }

    private fun chipFor(name: String): Chip? = when (name.lowercase()) {
        "esp32" -> Chip.ESP32
        "esp32s3" -> Chip.ESP32S3
        else -> null
    }
}
