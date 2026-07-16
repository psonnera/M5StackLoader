package com.m5stackloader

import com.m5stackloader.esp.NvsImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [NvsImage] against real output from Espressif's own `nvs_partition_gen.py`
 * (via the `esp-idf-nvs-partition-gen` PyPI package), generated with:
 *
 * ```
 * key,type,encoding,value
 * M5NSconfig,namespace,,
 * SSID0,data,string,TestNet
 * PASS0,data,string,pass12345
 * ```
 * `python -m esp_idf_nvs_partition_gen generate wifi.csv golden.bin 0x5000`
 *
 * and, for the open-network case, the same without the PASS0 row. If this ever needs
 * regenerating, that tool is the source of truth, not this file.
 */
class NvsImageTest {

    // First 224 bytes of golden.bin: page header, entry bitmap, and the 5 entries for
    // the namespace + SSID0 (header+data) + PASS0 (header+data). Everything after this
    // in a real 0x5000 image is 0xFF, which the "blank tail" tests check separately.
    private val goldenWithPassword = "feffffff00000000feffffffffffffffffffffffffffffffffffffff842dbab9aafeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000101ffc0d4ad3a4d354e53636f6e66696700000000000001ffffffffffffff012102ff14ade8d6535349443000000000000000000000000800ffffd6c27d08546573744e657400ffffffffffffffffffffffffffffffffffffffffffffffff012102ff6bf9046e504153533000000000000000000000000a00ffff1e54fbca70617373313233343500ffffffffffffffffffffffffffffffffffffffffffff"

    // First 160 bytes of golden_open.bin: header/bitmap (64) + namespace + SSID0 (header+data) = 3 entries.
    private val goldenOpenNetwork = "feffffff00000000feffffffffffffffffffffffffffffffffffffff842dbab9eaffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000101ffc0d4ad3a4d354e53636f6e66696700000000000001ffffffffffffff012102ff2fb59d67535349443000000000000000000000000800ffff673295e34f70656e4e657400ffffffffffffffffffffffffffffffffffffffffffffffff"

    // First 288 bytes of a golden.bin generated with an added `device_name,data,string,M5NS-7A3F`
    // row (namespace + SSID0 + PASS0 + device_name = 4 entries, header+data each except namespace).
    private val goldenWithDeviceName = "feffffff00000000feffffffffffffffffffffffffffffffffffffff842dbab9aaeaffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000101ffc0d4ad3a4d354e53636f6e66696700000000000001ffffffffffffff012102ff14ade8d6535349443000000000000000000000000800ffffd6c27d08546573744e657400ffffffffffffffffffffffffffffffffffffffffffffffff012102ff6bf9046e504153533000000000000000000000000a00ffff1e54fbca70617373313233343500ffffffffffffffffffffffffffffffffffffffffffff012102ff1e8f080c6465766963655f6e616d6500000000000a00ffffeb2ba2d54d354e532d3741334600ffffffffffffffffffffffffffffffffffffffffffff"

    private fun hex(s: String) = s.replace("\\s".toRegex(), "").let { clean ->
        ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun `crc32 matches the zlib seed-0xFFFFFFFF variant, not standard CRC-32`() {
        // Known-answer test computed with Python: zlib.crc32(b"123456789", 0xFFFFFFFF) == 0xd202d277.
        // (The plain CRC-32/ISO-HDLC check value for the same input is the different 0xCBF43926.)
        val crc = NvsImage.crc32("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0xd202d277.toInt(), crc)
    }

    @Test
    fun `matches Espressif's own generator byte for byte, with a password`() {
        val image = NvsImage.build(
            partitionSize = 0x5000,
            namespace = "M5NSconfig",
            strings = listOf(
                NvsImage.Entry("SSID0", "TestNet"),
                NvsImage.Entry("PASS0", "pass12345"),
            ),
        )
        assertEquals(0x5000, image.size)
        assertArrayEquals(hex(goldenWithPassword), image.copyOfRange(0, 224))
        assertTrue("everything after the used entries must be erased (0xFF)",
            image.copyOfRange(224, image.size).all { it == 0xFF.toByte() })
    }

    @Test
    fun `omits the password entry entirely for an open network`() {
        val image = NvsImage.build(
            partitionSize = 0x5000,
            namespace = "M5NSconfig",
            strings = listOf(NvsImage.Entry("SSID0", "OpenNet")),
        )
        assertArrayEquals(hex(goldenOpenNetwork), image.copyOfRange(0, 160))
        assertTrue(image.copyOfRange(160, image.size).all { it == 0xFF.toByte() })
    }

    @Test
    fun `writes a device_name entry alongside SSID0 and PASS0`() {
        val image = NvsImage.build(
            partitionSize = 0x5000,
            namespace = "M5NSconfig",
            strings = listOf(
                NvsImage.Entry("SSID0", "TestNet"),
                NvsImage.Entry("PASS0", "pass12345"),
                NvsImage.Entry("device_name", "M5NS-7A3F"),
            ),
        )
        assertEquals(0x5000, image.size)
        assertArrayEquals(hex(goldenWithDeviceName), image.copyOfRange(0, 288))
        assertTrue("everything after the used entries must be erased (0xFF)",
            image.copyOfRange(288, image.size).all { it == 0xFF.toByte() })
    }

    @Test
    fun `every page after the first is left fully erased`() {
        val image = NvsImage.build(0x5000, "M5NSconfig", listOf(NvsImage.Entry("SSID0", "Net")))
        assertTrue(image.copyOfRange(4096, image.size).all { it == 0xFF.toByte() })
    }

    @Test
    fun `rejects a partition size that is not a whole number of pages`() {
        assertThrows(IllegalArgumentException::class.java) {
            NvsImage.build(0x5000 - 1, "M5NSconfig", emptyList())
        }
    }

    @Test
    fun `rejects a partition smaller than two pages`() {
        assertThrows(IllegalArgumentException::class.java) {
            NvsImage.build(4096, "M5NSconfig", emptyList())
        }
    }

    @Test
    fun `rejects a key longer than 15 characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            NvsImage.build(0x5000, "M5NSconfig", listOf(NvsImage.Entry("A".repeat(16), "x")))
        }
    }
}
