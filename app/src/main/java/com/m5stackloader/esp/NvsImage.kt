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
 * Builds an ESP-IDF NVS partition image (format version 2) containing a handful of
 * string entries under one namespace, so it can be flashed straight to a device's
 * `nvs` partition and read back by the Arduino `Preferences` library.
 *
 * Byte-for-byte compatible with Espressif's own `nvs_partition_gen.py`, verified
 * against its output in NvsImageTest - every field and offset here mirrors that tool.
 */
object NvsImage {

    data class Entry(val key: String, val value: String)

    private const val PAGE_SIZE = 4096
    private const val ENTRY_SIZE = 32
    private const val ENTRIES_PER_PAGE = 126
    private const val ENTRY_TABLE_OFFSET = PAGE_SIZE - ENTRIES_PER_PAGE * ENTRY_SIZE // 64
    private const val BITMAP_OFFSET = 32
    private const val MAX_KEY_LENGTH = 15

    private const val TYPE_U8 = 0x01
    private const val TYPE_STRING = 0x21
    private const val NS_INDEX_FOR_NAMESPACE_ENTRIES = 0

    /**
     * @param partitionSize must be a positive multiple of the 4096-byte page size and at
     *   least two pages (NVS always needs one page free for its own garbage collection).
     * @param namespace the Preferences namespace the firmware will open, e.g. "M5NSconfig".
     * @param strings the keys to store; all live in a single namespace on a single page.
     */
    fun build(partitionSize: Int, namespace: String, strings: List<Entry>): ByteArray {
        require(partitionSize > 0 && partitionSize % PAGE_SIZE == 0) {
            "partitionSize must be a positive multiple of $PAGE_SIZE, got $partitionSize"
        }
        require(partitionSize >= 2 * PAGE_SIZE) { "NVS needs at least one free page" }
        require(namespace.length in 1..MAX_KEY_LENGTH) { "namespace must be 1-$MAX_KEY_LENGTH chars" }
        for (entry in strings) {
            require(entry.key.length in 1..MAX_KEY_LENGTH) { "key '${entry.key}' must be 1-$MAX_KEY_LENGTH chars" }
        }

        val image = ByteArray(partitionSize) { 0xFF.toByte() }
        val written = ArrayList<IntRange>() // entry-index ranges marked "written" in the bitmap

        var nextEntry = 0
        nextEntry += writeNamespaceEntry(image, nextEntry, namespace)
        written += 0 until nextEntry

        val namespaceIndex = 1
        for (entry in strings) {
            val used = writeStringEntry(image, nextEntry, namespaceIndex, entry.key, entry.value)
            written += nextEntry until (nextEntry + used)
            nextEntry += used
        }

        check(nextEntry <= ENTRIES_PER_PAGE) {
            "Too many entries for a single NVS page: $nextEntry (max $ENTRIES_PER_PAGE)"
        }

        markWritten(image, written)
        writePageHeader(image)
        return image
    }

    /** Namespace-definition entry: binds [namespace] to index 1 for later entries. */
    private fun writeNamespaceEntry(image: ByteArray, entryIndex: Int, namespace: String): Int {
        val offset = ENTRY_TABLE_OFFSET + entryIndex * ENTRY_SIZE
        image[offset] = NS_INDEX_FOR_NAMESPACE_ENTRIES.toByte()
        image[offset + 1] = TYPE_U8.toByte()
        image[offset + 2] = 1 // span: just this header entry
        image[offset + 3] = 0xFF.toByte() // chunk index: not a blob
        // data (8 bytes @ offset+24): assigned namespace index, then 0xFF padding.
        image[offset + 24] = 1
        writeKey(image, offset, namespace)
        writeEntryCrc(image, offset)
        return 1
    }

    /** Returns the number of 32-byte entry slots consumed (1 header + data slots). */
    private fun writeStringEntry(
        image: ByteArray,
        entryIndex: Int,
        namespaceIndex: Int,
        key: String,
        value: String,
    ): Int {
        val valueBytes = value.toByteArray(Charsets.UTF_8) + 0 // NUL terminator, included in size
        val dataEntries = (valueBytes.size + ENTRY_SIZE - 1) / ENTRY_SIZE
        val span = 1 + dataEntries

        val headerOffset = ENTRY_TABLE_OFFSET + entryIndex * ENTRY_SIZE
        image[headerOffset] = namespaceIndex.toByte()
        image[headerOffset + 1] = TYPE_STRING.toByte()
        image[headerOffset + 2] = span.toByte()
        image[headerOffset + 3] = 0xFF.toByte()
        writeKey(image, headerOffset, key)
        // data (8 bytes @ headerOffset+24): u16 size, 0xFFFF reserved, u32 CRC32 of the value bytes.
        image[headerOffset + 24] = (valueBytes.size and 0xFF).toByte()
        image[headerOffset + 25] = ((valueBytes.size ushr 8) and 0xFF).toByte()
        writeLeInt(image, headerOffset + 28, crc32(valueBytes))
        writeEntryCrc(image, headerOffset)

        var remaining = valueBytes.size
        var srcPos = 0
        for (i in 0 until dataEntries) {
            val dataOffset = ENTRY_TABLE_OFFSET + (entryIndex + 1 + i) * ENTRY_SIZE
            val chunk = minOf(ENTRY_SIZE, remaining)
            System.arraycopy(valueBytes, srcPos, image, dataOffset, chunk)
            srcPos += chunk
            remaining -= chunk
        }
        return span
    }

    private fun writeKey(image: ByteArray, entryOffset: Int, key: String) {
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 16) {
            image[entryOffset + 8 + i] = if (i < keyBytes.size) keyBytes[i] else 0
        }
    }

    /** CRC32 of an entry (bytes 0-3 and 8-31, i.e. everything except the CRC field itself). */
    private fun writeEntryCrc(image: ByteArray, entryOffset: Int) {
        val crcInput = ByteArray(28)
        System.arraycopy(image, entryOffset, crcInput, 0, 4)
        System.arraycopy(image, entryOffset + 8, crcInput, 4, 24)
        writeLeInt(image, entryOffset + 4, crc32(crcInput))
    }

    private fun markWritten(image: ByteArray, ranges: List<IntRange>) {
        for (range in ranges) {
            for (entryIndex in range) {
                // "11" = empty, "10" = written: clear the low bit of the entry's pair.
                val bitPos = 2 * entryIndex
                val byteIndex = BITMAP_OFFSET + bitPos / 8
                val bitInByte = bitPos % 8
                image[byteIndex] = (image[byteIndex].toInt() and (1 shl bitInByte).inv()).toByte()
            }
        }
    }

    private fun writePageHeader(image: ByteArray) {
        writeLeInt(image, 0, 0xFFFFFFFE.toInt()) // page state: ACTIVE
        writeLeInt(image, 4, 0) // sequence number
        image[8] = 0xFE.toByte() // format version 2
        // bytes 9..27 stay 0xFF (reserved)
        val headerCrcInput = ByteArray(24)
        System.arraycopy(image, 4, headerCrcInput, 0, 24)
        writeLeInt(image, 28, crc32(headerCrcInput))
    }

    private fun writeLeInt(image: ByteArray, offset: Int, value: Int) {
        image[offset] = (value and 0xFF).toByte()
        image[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        image[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        image[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /**
     * The CRC32 variant NVS uses: reflected poly 0xEDB88320, register initialized to 0
     * (not the usual 0xFFFFFFFF), final bitwise complement. Equivalent to Python's
     * `zlib.crc32(data, 0xFFFFFFFF)`; `java.util.zip.CRC32` uses a different init/final
     * pairing and would produce the wrong value here.
     */
    internal fun crc32(data: ByteArray): Int {
        var reg = 0
        for (b in data) {
            val index = (reg xor b.toInt()) and 0xFF
            reg = TABLE[index] xor (reg ushr 8)
        }
        return reg.inv()
    }

    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (POLY xor (c ushr 1)) else (c ushr 1)
            }
            table[i] = c
        }
    }

    private const val POLY = 0xEDB88320.toInt()
}
