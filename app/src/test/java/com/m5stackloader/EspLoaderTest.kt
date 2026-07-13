package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.EspLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Drives the real [EspLoader] against [FakeEspRom]. This is the closest thing to a
 * hardware test that runs on a build machine: the loader has no idea it isn't talking
 * to an M5Stack.
 */
class EspLoaderTest {

    private fun firmware(size: Int): ByteArray {
        // Compressible, but not trivially so - like a real ESP image.
        val random = Random(seed = 42)
        val image = ByteArray(size)
        var i = 0
        while (i < size) {
            val run = random.nextInt(1, 64)
            val value = random.nextInt(0, 256).toByte()
            for (j in 0 until run) {
                if (i + j < size) image[i + j] = value
            }
            i += run
        }
        image[0] = 0xE9.toByte()  // ESP image magic
        return image
    }

    @Test
    fun `identifies a 4MB ESP32`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x16)
        val loader = EspLoader(rom)

        loader.connect()
        val chip = loader.detectChip()
        loader.spiAttach()
        val size = loader.detectFlashSize()

        assertTrue("should have driven the board into its bootloader", rom.enteredBootloader)
        assertEquals(Chip.ESP32, chip)
        assertEquals(4 * 1024 * 1024, size)
    }

    @Test
    fun `identifies a 16MB ESP32`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        assertEquals(16 * 1024 * 1024, loader.detectFlashSize())
    }

    @Test
    fun `identifies an ESP32-S3, whose ROM uses a different status length`() {
        val rom = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x18)
        val loader = EspLoader(rom)

        loader.connect()
        val chip = loader.detectChip()
        loader.spiAttach()

        assertEquals(Chip.ESP32S3, chip)
        assertEquals(16 * 1024 * 1024, loader.detectFlashSize())
    }

    @Test
    fun `writes an image that arrives byte-for-byte and passes MD5 verification`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.setFlashParameters(loader.detectFlashSize())

        val image = firmware(70_000)  // spans many 1KB ROM blocks
        var lastWritten = 0
        var lastTotal = 0

        // writeFlash verifies by MD5 against the fake's inflated copy; if our framing,
        // checksums or compression were wrong, this call would throw.
        loader.writeFlash(image, 0x10000) { written, total ->
            lastWritten = written
            lastTotal = total
        }

        assertArrayEquals(image, rom.written[0x10000])
        assertEquals(image.size, lastTotal)
        assertEquals(image.size, lastWritten)
    }

    @Test
    fun `an image containing SLIP control bytes survives escaping`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        // 0xC0 and 0xDB are the SLIP frame delimiter and escape byte. Compressed output
        // is full of them, and mangling one would corrupt the flash.
        val image = ByteArray(8192) { i -> if (i % 2 == 0) 0xC0.toByte() else 0xDB.toByte() }
        loader.writeFlash(image, 0x1000) { _, _ -> }

        assertArrayEquals(image, rom.written[0x1000])
    }

    @Test
    fun `the ESP32 ROM gets four words on FLASH_DEFL_BEGIN and the S3 ROM gets five`() {
        val esp32 = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        EspLoader(esp32).run {
            connect(); detectChip(); spiAttach()
            writeFlash(ByteArray(512) { 1 }, 0x10000) { _, _ -> }
        }
        assertEquals(4, esp32.deflBeginWordCount)

        // The S3 ROM supports encrypted flash and expects one extra word; sending four
        // would leave it waiting, and sending five to an ESP32 would be a bad command.
        val s3 = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x18)
        EspLoader(s3).run {
            connect(); detectChip(); spiAttach()
            writeFlash(ByteArray(512) { 1 }, 0x0) { _, _ -> }
        }
        assertEquals(5, s3.deflBeginWordCount)
    }

    @Test
    fun `raising the baud rate is skipped on native USB but done on a serial bridge`() {
        val bridge = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        EspLoader(bridge).run {
            connect(); detectChip()
            changeBaud(EspLoader.ESP_FLASH_BAUD)
        }
        assertEquals(EspLoader.ESP_FLASH_BAUD, bridge.baudRate)

        // A CoreS3 speaks USB CDC, which has no line rate to change: asking the ROM to
        // change it would only desynchronise the link.
        val nativeUsb = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x18, isNativeUsb = true)
        EspLoader(nativeUsb).run {
            connect(); detectChip()
            changeBaud(EspLoader.ESP_FLASH_BAUD)
        }
        assertEquals(115200, nativeUsb.baudRate)
    }

    @Test
    fun `a CoreS3 on native USB still flashes, at its bootloader offset of 0x0`() {
        val rom = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x18, isNativeUsb = true)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.setFlashParameters(loader.detectFlashSize())

        val bootloader = firmware(15_104)  // the real CoreS3 bootloader's size
        loader.writeFlash(bootloader, 0x0) { _, _ -> }

        assertArrayEquals(bootloader, rom.written[0x0])
    }
}
