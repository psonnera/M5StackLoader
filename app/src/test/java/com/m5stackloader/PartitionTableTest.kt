package com.m5stackloader

import com.m5stackloader.firmware.PartitionTable
import org.junit.Assert.assertEquals
import org.junit.Test

class PartitionTableTest {

    private fun hex(s: String) = s.replace("\\s".toRegex(), "").let { clean ->
        ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // The real Binaries/Basic_4MB/M5_NightscoutMon.ino.partitions.bin from the
    // M5_NightscoutMon repository: 6 partitions (nvs, otadata, app0, app1, spiffs,
    // coredump), the MD5 record (magic 0xEBEB), then erased 0xFF fill to 3072 bytes.
    // ESP32_16MB and CoreS3 carry an identical nvs entry, just bigger app partitions.
    private val realBasic4mbTable = "aa50010200900000005000006e76730000000000000000000000000000000000aa50010000e00000002000006f74616461746100000000000000000000000000aa5000100000010000001e006170703000000000000000000000000000000000aa50001100001f0000001e006170703100000000000000000000000000000000aa50018200003d00000002007370696666730000000000000000000000000000aa50010300003f0000000100636f726564756d70000000000000000000000000ebebffffffffffffffffffffffffffffb43513b122c41873a9010b82ec3a25bd"

    @Test
    fun `parses every partition of the real Basic_4MB table`() {
        val partitions = PartitionTable.parse(hex(realBasic4mbTable))
        assertEquals(
            listOf("nvs", "otadata", "app0", "app1", "spiffs", "coredump"),
            partitions.map { it.label },
        )
    }

    @Test
    fun `finds nvs at the stock 0x9000 offset in the real table`() {
        val nvs = PartitionTable.findNvs(hex(realBasic4mbTable))
        assertEquals(0x9000, nvs.offset)
        assertEquals(0x5000, nvs.size)
        assertEquals(0x01, nvs.type)
        assertEquals(0x02, nvs.subType)
    }

    @Test
    fun `stops at the MD5 record and does not misread it as a partition`() {
        val partitions = PartitionTable.parse(hex(realBasic4mbTable))
        assertEquals(6, partitions.size)
    }

    @Test
    fun `falls back to the stock nvs location when the bytes are unparseable`() {
        val nvs = PartitionTable.findNvs(ByteArray(64) { 0x11 })
        assertEquals(PartitionTable.DEFAULT_NVS, nvs)
    }

    @Test
    fun `falls back to the stock nvs location when there are no bytes at all`() {
        assertEquals(PartitionTable.DEFAULT_NVS, PartitionTable.findNvs(null))
    }

    @Test
    fun `falls back when the table has no nvs entry`() {
        // otadata (0x00/0x00) and app0 (0x00/0x10) only - no data/nvs (0x01/0x02) entry.
        val table = hex(
            "aa5000000000e00000002000006f74616461746100000000000000000000000000" +
                "aa5000100000010000001e006170703000000000000000000000000000000000",
        )
        assertEquals(PartitionTable.DEFAULT_NVS, PartitionTable.findNvs(table))
    }

    @Test
    fun `an empty table parses to no partitions`() {
        assertEquals(emptyList<PartitionTable.Partition>(), PartitionTable.parse(ByteArray(32) { 0xFF.toByte() }))
    }
}
