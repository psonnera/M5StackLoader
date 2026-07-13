/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.firmware

/**
 * Reads an ESP-IDF partition table binary (the blob flashed at 0x8000) well enough to
 * find where the `nvs` partition lives, so Wi-Fi credentials can be written to the
 * right place even if the firmware's partition layout ever changes.
 */
object PartitionTable {

    data class Partition(
        val type: Int,
        val subType: Int,
        val offset: Int,
        val size: Int,
        val label: String,
    )

    private const val ENTRY_SIZE = 32
    private const val MAGIC_0 = 0xAA
    private const val MAGIC_1 = 0x50
    private const val TYPE_DATA = 0x01
    private const val SUBTYPE_NVS = 0x02

    /** Fallback used if [bytes] can't be parsed - the stock ESP-IDF layout's nvs slot. */
    val DEFAULT_NVS = Partition(TYPE_DATA, SUBTYPE_NVS, offset = 0x9000, size = 0x5000, label = "nvs")

    fun parse(bytes: ByteArray): List<Partition> {
        val partitions = ArrayList<Partition>()
        var offset = 0
        while (offset + ENTRY_SIZE <= bytes.size) {
            val magic0 = bytes[offset].toInt() and 0xFF
            val magic1 = bytes[offset + 1].toInt() and 0xFF
            if (magic0 != MAGIC_0 || magic1 != MAGIC_1) break // MD5 record (0xEBEB) or erased fill

            partitions += Partition(
                type = bytes[offset + 2].toInt() and 0xFF,
                subType = bytes[offset + 3].toInt() and 0xFF,
                offset = readLeInt(bytes, offset + 4),
                size = readLeInt(bytes, offset + 8),
                label = readLabel(bytes, offset + 12),
            )
            offset += ENTRY_SIZE
        }
        return partitions
    }

    /** The `nvs` partition, or [DEFAULT_NVS] if [bytes] is missing, truncated, or unparseable. */
    fun findNvs(bytes: ByteArray?): Partition {
        if (bytes == null) return DEFAULT_NVS
        val found = runCatching { parse(bytes) }.getOrNull()
            ?.firstOrNull { it.type == TYPE_DATA && it.subType == SUBTYPE_NVS }
        if (found == null || found.size < 2 * 4096) return DEFAULT_NVS
        return found
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLabel(bytes: ByteArray, offset: Int): String {
        var end = offset
        while (end < offset + 16 && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}
