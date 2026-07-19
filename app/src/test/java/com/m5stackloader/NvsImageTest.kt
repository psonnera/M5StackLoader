package com.m5stackloader

import com.m5stackloader.esp.NvsImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // -------------------------------------------------------------------- merge

    @Test
    fun `merge keeps every other setting and only updates the Wi-Fi keys`() {
        // A device that already holds Nightscout settings plus a previous Wi-Fi network.
        val existing = NvsImage.build(
            0x5000, "M5NSconfig",
            listOf(
                NvsImage.Entry("nsurl", "http://ns.example"),
                NvsImage.Entry("units", "mgdl"),
                NvsImage.Entry("SSID0", "OldNet"),
                NvsImage.Entry("PASS0", "oldpass"),
            ),
        )

        val merged = NvsImage.merge(
            existing, "M5NSconfig",
            listOf(NvsImage.Entry("SSID0", "NewNet"), NvsImage.Entry("PASS0", "newpass")),
        )!!

        assertEquals(0x5000, merged.size)
        val live = readNamespaceStrings(merged, "M5NSconfig")
        assertEquals("http://ns.example", live.value("nsurl"))
        assertEquals("mgdl", live.value("units"))
        assertEquals("NewNet", live.value("SSID0"))
        assertEquals("newpass", live.value("PASS0"))
        // The old network must be tombstoned, not left live alongside the new one.
        assertEquals("only one SSID0 may be live", 1, live.count { it.first == "SSID0" })
        assertEquals("only one PASS0 may be live", 1, live.count { it.first == "PASS0" })
    }

    @Test
    fun `merge leaves untouched entries byte-for-byte identical`() {
        // Proof that entries this class cannot even build (ints, blobs) would survive: the merge
        // only edits the keys it is given, so the namespace + nsurl + units entries - the first
        // five entry slots, bytes 64..224 - must come out exactly as they went in.
        val existing = NvsImage.build(
            0x5000, "M5NSconfig",
            listOf(
                NvsImage.Entry("nsurl", "http://ns.example"),
                NvsImage.Entry("units", "mgdl"),
                NvsImage.Entry("SSID0", "OldNet"),
                NvsImage.Entry("PASS0", "oldpass"),
            ),
        )

        val merged = NvsImage.merge(
            existing, "M5NSconfig",
            listOf(NvsImage.Entry("SSID0", "NewNet")),
        )!!

        assertArrayEquals(existing.copyOfRange(64, 224), merged.copyOfRange(64, 224))
    }

    @Test
    fun `merge returns null for a blank partition, so the caller builds fresh`() {
        val blank = ByteArray(0x5000) { 0xFF.toByte() }
        assertNull(NvsImage.merge(blank, "M5NSconfig", listOf(NvsImage.Entry("SSID0", "X"))))
    }

    @Test
    fun `merge returns null when the namespace is not present`() {
        val other = NvsImage.build(0x5000, "OtherNs", listOf(NvsImage.Entry("k", "v")))
        assertNull(NvsImage.merge(other, "M5NSconfig", listOf(NvsImage.Entry("SSID0", "X"))))
    }

    @Test
    fun `merge returns null for an image that is not a whole number of pages`() {
        assertNull(NvsImage.merge(ByteArray(0x5000 - 1), "M5NSconfig", listOf(NvsImage.Entry("SSID0", "X"))))
    }

    // --- a minimal NVS reader, only for asserting on merge output ---

    private fun List<Pair<String, String>>.value(key: String): String? = lastOrNull { it.first == key }?.second

    /** Every live (written, non-erased) string entry in [namespace], in page/slot order. */
    private fun readNamespaceStrings(image: ByteArray, namespace: String): List<Pair<String, String>> {
        val pageSize = 4096; val entrySize = 32; val entriesPerPage = 126; val tableOff = 64; val bitmapOff = 32
        val active = 0xFFFFFFFE.toInt(); val full = 0xFFFFFFFC.toInt()

        fun le32(at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8) or
            ((image[at + 2].toInt() and 0xFF) shl 16) or ((image[at + 3].toInt() and 0xFF) shl 24)
        fun state(base: Int, i: Int) = (image[base + bitmapOff + (2 * i) / 8].toInt() ushr ((2 * i) % 8)) and 0b11
        fun key(off: Int): String {
            var end = off + 8; while (end < off + 24 && image[end] != 0.toByte()) end++
            return String(image, off + 8, end - (off + 8), Charsets.US_ASCII)
        }

        val out = mutableListOf<Pair<String, String>>()
        var nsIndex = -1
        // Pass 1: resolve the namespace index; pass 2: collect its string entries.
        for (pass in 0..1) {
            for (page in 0 until image.size / pageSize) {
                val base = page * pageSize
                if (le32(base) != active && le32(base) != full) continue
                var i = 0
                while (i < entriesPerPage) {
                    if (state(base, i) != 0b10) { i++; continue }   // 0b10 == written
                    val off = base + tableOff + i * entrySize
                    val span = (image[off + 2].toInt() and 0xFF).coerceIn(1, entriesPerPage - i)
                    val ns = image[off].toInt() and 0xFF
                    val type = image[off + 1].toInt() and 0xFF
                    if (pass == 0 && ns == 0 && type == 0x01 && key(off) == namespace) {
                        nsIndex = image[off + 24].toInt() and 0xFF
                    } else if (pass == 1 && ns == nsIndex && type == 0x21) {
                        val size = (image[off + 24].toInt() and 0xFF) or ((image[off + 25].toInt() and 0xFF) shl 8)
                        val value = String(image, off + entrySize, size - 1, Charsets.UTF_8) // drop NUL
                        out += key(off) to value
                    }
                    i += span
                }
            }
        }
        return out
    }
}
