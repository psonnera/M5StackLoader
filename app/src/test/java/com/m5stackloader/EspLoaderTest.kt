package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.EspLoader
import com.m5stackloader.esp.StubFlasher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `connect sends exactly one SYNC when the first one is answered`() {
        // The real ROM answers every SYNC up to eight times; sending a second one while
        // still consuming those replies is what overflows its RX FIFO on real hardware
        // (see FakeEspRom's wedgesOnRepeatedSync). connect() must not do that on success.
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x16)
        val loader = EspLoader(rom)

        loader.connect()

        assertEquals(1, rom.syncCommandsReceived)
    }

    @Test
    fun `detection still succeeds against a ROM that wedges if flooded with SYNC`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x16, wedgesOnRepeatedSync = true)
        val loader = EspLoader(rom)

        loader.connect()
        val chip = loader.detectChip()

        assertEquals(Chip.ESP32, chip)
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
    fun `reads the eFuse MAC address of an ESP32`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x16)
        // esptool's read_mac() layout: the low word packs mac[2..5], the high word's low
        // 16 bits pack mac[0..1]. A real M5Stack's OUI starts 24:6F:28 or 24:0A:C4.
        rom.presetRegister(Chip.ESP32.macHighWordReg, 0x0000246F)
        rom.presetRegister(Chip.ESP32.macLowWordReg, 0x28117A3F.toInt())
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        val mac = loader.readMac()

        assertArrayEquals(
            byteArrayOf(0x24, 0x6F, 0x28, 0x11, 0x7A, 0x3F.toByte()),
            mac,
        )
    }

    @Test
    fun `reads the eFuse MAC address of an ESP32-S3, at its own register addresses`() {
        val rom = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x16)
        rom.presetRegister(Chip.ESP32S3.macHighWordReg, 0x0000C4DE)
        rom.presetRegister(Chip.ESP32S3.macLowWordReg, 0xAD01BEEF.toInt())
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        val mac = loader.readMac()

        assertArrayEquals(
            byteArrayOf(0xC4.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0x01, 0xBE.toByte(), 0xEF.toByte()),
            mac,
        )
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

    /** A stub with nothing to upload but a non-zero entrypoint, so runStub() drives the
     *  real jump-and-greet handshake in [FakeEspRom] instead of a plain command reply. */
    private fun noopStub() = StubFlasher(entry = 0x400807F0, text = ByteArray(0), textStart = 0, data = ByteArray(0), dataStart = 0)

    @Test
    fun `writing through the stub never sends the extra encrypted-write word`() {
        // Reproduces the failure seen on the real M5Stack: every erase refused with
        // "status 0x01, error 0x00", at every baud, but only when the stub was running -
        // the ROM fallback flashed the same device fine.
        //
        // The stub checks its length with verify_data_len(command, 16) and rejects a
        // 20-byte FLASH_DEFL_BEGIN outright. Current esptool does append that word for a
        // stub, but it also ships a newer stub that tolerates both lengths; the stub bundled
        // in app/src/main/assets is the older, strict one (confirmed against the hardware:
        // it accepts the 16-byte form and answers the 20-byte one with a bare error 1).
        // The 16-byte form is accepted by every stub and by the ESP32 ROM, so it is what we
        // send whenever the stub is running, on every chip.
        val esp32 = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val image = ByteArray(512) { 1 }
        EspLoader(esp32).run {
            connect(); detectChip(); spiAttach()
            runStub(noopStub())
            writeFlash(image, 0x1000) { _, _ -> }
        }
        assertEquals(4, esp32.deflBeginWordCount)
        assertArrayEquals("the write must actually land", image, esp32.written[0x1000])

        val s3 = FakeEspRom(Chip.ESP32S3, flashCapacityByte = 0x18, isNativeUsb = true)
        EspLoader(s3).run {
            connect(); detectChip(); spiAttach()
            runStub(noopStub())
            writeFlash(image, 0x0) { _, _ -> }
        }
        assertEquals(4, s3.deflBeginWordCount)
        assertArrayEquals("the write must actually land", image, s3.written[0x0])
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

    // ------------------------------------------------------- how big is the flash, really

    @Test
    fun `a chip whose JEDEC id overstates its size is caught by the address mirror`() {
        // The failing M5Stack reports JEDEC capacity 0x18 (16MB). A SPI flash decodes only
        // as many address bits as it has capacity for, so if it is really a 4MB part, 0x401000
        // reads back exactly what 0x1000 holds. Believing the id would flash the 16MB
        // firmware - and the wrong partition table - onto a 4MB device.
        val lying = FakeEspRom(
            Chip.ESP32,
            flashCapacityByte = 0x18,          // claims 16MB
            realFlashSize = 4 * 1024 * 1024,   // is 4MB
        )
        val loader = EspLoader(lying)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        assertEquals(4 * 1024 * 1024, loader.detectFlashSize())
    }

    @Test
    fun `an honest chip is confirmed at the size it claims`() {
        val honest = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(honest)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        assertEquals(16 * 1024 * 1024, loader.detectFlashSize())
    }

    @Test
    fun `a blank chip cannot be mirror-tested, so its claimed size stands`() {
        // Every address of an erased chip reads 0xFF, so a mirror is indistinguishable from
        // an erase. There is nothing to do but believe the id - and say so.
        val log = mutableListOf<String>()
        val blank = FakeEspRom(
            Chip.ESP32,
            flashCapacityByte = 0x18,
            realFlashSize = 4 * 1024 * 1024,
            factoryFirmware = false,
        )
        val loader = EspLoader(blank) { log += it }

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        assertEquals(16 * 1024 * 1024, loader.detectFlashSize())
        assertTrue("should say why it could not confirm", log.any { it.contains("blank") })
    }

    // ---------------------------------------------------------- write protection

    @Test
    fun `a write-protected flash refuses every erase, however many times it is asked`() {
        // The failure on the real M5Stack: reads answer fine, FLASH_DEFL_BEGIN is refused,
        // at every baud rate, on every attempt. No retry ladder can get past this.
        val locked = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, writeProtected = true)
        val loader = EspLoader(locked)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        val error = assertThrows(Exception::class.java) {
            loader.writeFlash(firmware(20_000), 0x1000) { _, _ -> }
        }
        assertTrue(
            "should say the flash is write-protected: ${error.message}",
            error.message!!.contains("write-protected"),
        )
    }

    @Test
    fun `unlocking clears the protection bits and lets the write through`() {
        val locked = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, writeProtected = true)
        val loader = EspLoader(locked)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.unlockFlash()

        val image = firmware(20_000)
        loader.writeFlash(image, 0x1000) { _, _ -> }

        assertArrayEquals(image, locked.written[0x1000])
    }

    @Test
    fun `unlocking leaves the quad-enable bit alone`() {
        // SR2 carries the quad-enable bit. Clearing it would leave a board whose bootloader
        // runs the flash in QIO mode unable to boot - a far worse outcome than a failed flash.
        val locked = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, writeProtected = true)
        val loader = EspLoader(locked)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.unlockFlash()

        assertTrue("quad-enable must survive the unlock", locked.quadEnabled)
    }

    @Test
    fun `unlockFlash never aborts the flash on its own when the bits will not clear`() {
        // Which status bits mean "protected" is a guess that does not hold for every flash
        // vendor - a wrong guess must not abort a flash that would otherwise have worked.
        // The erase that follows is the real test, and it already reports write-protection
        // on its own (see "a write-protected flash refuses every erase...").
        val locked = FakeEspRom(
            Chip.ESP32,
            flashCapacityByte = 0x18,
            writeProtected = true,
            refusesToUnlock = true,
        )
        val log = mutableListOf<String>()
        val loader = EspLoader(locked) { log += it }

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.unlockFlash()

        assertTrue(
            "should say the bits would not clear: $log",
            log.any { it.contains("Could not clear the protection bits") },
        )
    }

    @Test
    fun `a vendor bit outside BP0-BP2 is not mistaken for write protection`() {
        // Real hardware: an XMC XM25QH128 (as shipped on some M5Stack units) reports SR1 as
        // 0x40, which is its quad-enable bit, not a protection bit. A mask wider than
        // BP0-BP2 reads that as "locked" and aborts a flash that would have worked.
        val healthy = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, extraSr1Bits = 0x40)
        val log = mutableListOf<String>()
        val loader = EspLoader(healthy) { log += it }

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.unlockFlash()

        assertFalse(
            "should not think a healthy chip is write-protected: $log",
            log.any { it.contains("write-protected") },
        )
        assertEquals("nothing should be written to the status register", 0x40, healthy.statusRegister and 0xFF)
    }

    @Test
    fun `an unprotected flash is left completely alone`() {
        val healthy = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(healthy)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.unlockFlash()

        // Nothing written to the status register: it is non-volatile and has a limited
        // number of write cycles, so it is not something to rewrite "just in case".
        assertEquals(FakeEspRom.SR2_QE shl 8, healthy.statusRegister)
    }

    // ------------------------------------------------------------------ recovery

    @Test
    fun `a lost acknowledgement restarts the image instead of resending the block`() {
        // Every block feeds one long zlib stream. Re-sending a block the device already
        // inflated - because only its ack was lost - would decode it twice and corrupt the
        // flash while reporting success. The whole image has to be rewritten instead.
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, dropAckForBlock = 2)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.setFlashParameters(loader.detectFlashSize())

        val image = firmware(70_000)
        loader.writeFlash(image, 0x10000) { _, _ -> }

        // It verified, which it could not have done had block 2 been inflated twice.
        assertArrayEquals(image, rom.written[0x10000])
        assertEquals(
            "the image should have been written from scratch a second time",
            2,
            rom.commands.count { it == FakeEspRom.ESP_FLASH_DEFL_BEGIN },
        )
    }

    @Test
    fun `a garbled verification reply is re-read rather than believed`() {
        // The MD5 reply is the one payload the protocol does not checksum, so a reply
        // mangled on the wire looks exactly like corrupt flash. Rewriting a perfectly good
        // image because of it would be a waste - and at 921600 baud, a common one.
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, garbledMd5Replies = 1)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()

        val image = firmware(20_000)
        loader.writeFlash(image, 0x10000) { _, _ -> }

        assertArrayEquals(image, rom.written[0x10000])
        assertEquals(
            "the image should have been written once, not rewritten",
            1,
            rom.commands.count { it == FakeEspRom.ESP_FLASH_DEFL_BEGIN },
        )
    }

    @Test
    fun `an image that will not verify is retried with the link progressively slowed down`() {
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18, garbledMd5Replies = Int.MAX_VALUE)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.runStub(noopStub())
        loader.changeBaud(EspLoader.ESP_FLASH_BAUD)

        val error = assertThrows(Exception::class.java) {
            loader.writeFlash(firmware(20_000), 0x10000) { _, _ -> }
        }
        assertTrue(error.message!!.contains("Verification failed"))

        // Raised to 921600, then dropped twice. The second word is the rate the link is
        // actually running at, which is not the ROM's once we have already changed it.
        assertEquals(
            listOf(
                EspLoader.ESP_FLASH_BAUD to 115_200,
                460_800 to EspLoader.ESP_FLASH_BAUD,
                115_200 to 460_800,
            ),
            rom.baudChanges,
        )
    }

    @Test
    fun `giving up reports which parts of the flash are wrong`() {
        // A write that stops landing partway through: the region begins correct and ends
        // erased. Without this the user gets "verification failed" and nothing to act on.
        val log = mutableListOf<String>()
        val rom = FakeEspRom(
            Chip.ESP32,
            flashCapacityByte = 0x18,
            flashFailsAbove = 0x10000 + 70_000,
        )
        val loader = EspLoader(rom) { log += it }

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.setFlashParameters(loader.detectFlashSize())

        assertThrows(Exception::class.java) {
            loader.writeFlash(firmware(128 * 1024), 0x10000) { _, _ -> }
        }

        val diagnosis = log.filter { it.contains("matches the firmware") || it.contains("still erased") }
        assertTrue("should map the region it could not verify: $log", diagnosis.isNotEmpty())
        assertTrue(
            "should find the part that never got written: $log",
            diagnosis.any { it.contains("still erased") },
        )
        assertTrue(
            "should find the part that did get written: $log",
            diagnosis.any { it.contains("matches the firmware") },
        )
    }

    @Test
    fun `the stub is made to finish writing before the flash is verified`() {
        // The stub acks a block when it has received it, not when it has written it out, so
        // an MD5 sent straight after the last block can race the write. esptool sends a
        // dummy command in between; so do we.
        val rom = FakeEspRom(Chip.ESP32, flashCapacityByte = 0x18)
        val loader = EspLoader(rom)

        loader.connect()
        loader.detectChip()
        loader.spiAttach()
        loader.runStub(noopStub())
        loader.writeFlash(firmware(20_000), 0x10000) { _, _ -> }

        val lastBlock = rom.commands.indexOfLast { it == FakeEspRom.ESP_FLASH_DEFL_DATA }
        val verify = rom.commands.indexOfFirst { it == FakeEspRom.ESP_SPI_FLASH_MD5 }
        assertTrue("MD5 should come after the last block", verify > lastBlock)
        assertTrue(
            "a command the stub can only answer once the flash is written should sit in between",
            rom.commands.subList(lastBlock + 1, verify).contains(FakeEspRom.ESP_READ_REG),
        )
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
