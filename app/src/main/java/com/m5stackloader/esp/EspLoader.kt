/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * A Kotlin port of the Espressif ROM bootloader protocol.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This file is a derivative work. The copyright notices of the works it derives
 * from are preserved below, as required by the GNU General Public License.
 *
 * Copyright (C) 2022-2024 Boris du Reau <boris.dureau@neuf.fr>
 *   Java ESPLoader - https://github.com/bdureau/ESPLoader - GPL-3.0.
 *   The original Java port of esptool, and the model for this port's structure,
 *   command set and flashing sequence.
 *
 * Copyright (C) 2014-2023 Fredrik Ahlberg, Angus Gratton,
 *                         Espressif Systems (Shanghai) CO LTD, other contributors as noted.
 *   esptool - https://github.com/espressif/esptool - GPL-2.0-or-later.
 *   Source of the register layouts, reset sequences, timeouts and framing rules.
 *   GPL-2.0-or-later permits relicensing under GPL-3.0 for this combined work.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 */
package com.m5stackloader.esp

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

class EspError(message: String) : Exception(message)

class EspLoader(
    private val io: SerialTransport,
    private val log: (String) -> Unit = {},
) {
    var chip: Chip? = null
        private set
    var flashSize: Int = 0
        private set

    private var isStub = false
    private var usbOtgConsole = false

    /** The rate the link is actually running at, which [changeBaud] has to tell the stub. */
    private var currentBaud = ESP_ROM_BAUD

    /** Trailing status bytes on every reply. Chip- and stub-dependent; see [Chip.romStatusBytes]. */
    private var statusBytes = 2
    private var flashWriteSize = ROM_FLASH_WRITE_SIZE
    private var ramBlock = ESP_RAM_BLOCK

    // ---------------------------------------------------------------- connect

    /** Resets the chip into its ROM bootloader and syncs with it. */
    fun connect(attempts: Int = 5) {
        var last: Exception? = null
        log(if (io.isNativeUsb) "Resetting via native USB..." else "Resetting via serial bridge...")
        repeat(attempts) { attempt ->
            try {
                enterBootloader()
                discardInput()
                sync()
                log("Bootloader responding.")
                return
            } catch (e: Exception) {
                last = e
                log("Sync attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(100)
            }
        }
        throw EspError("The device did not enter its bootloader (${last?.message}).")
    }

    private fun enterBootloader() {
        if (io.isNativeUsb) usbJtagReset() else classicReset()
    }

    /** DTR/RTS wiring of the CP210x/CH34x auto-reset circuit on Basic/Fire/Core2. */
    private fun classicReset() {
        io.setDtr(false)  // IO0 = HIGH
        io.setRts(true)   // EN = LOW, chip held in reset
        Thread.sleep(100)
        io.setDtr(true)   // IO0 = LOW, select download boot
        io.setRts(false)  // EN = HIGH, chip out of reset
        Thread.sleep(50)
        io.setDtr(false)  // IO0 = HIGH, release
        Thread.sleep(50)
    }

    /**
     * The ESP32-S3's built-in USB-Serial/JTAG peripheral drives reset and strapping in
     * hardware from DTR/RTS, but it must never see both lines low at once, so the
     * transitions go through (1,1) instead of (0,0). Mirrors esptool's USBJTAGSerialReset.
     */
    private fun usbJtagReset() {
        io.setRts(false)
        io.setDtr(false)
        Thread.sleep(100)
        io.setDtr(true)
        io.setRts(false)
        Thread.sleep(100)
        io.setRts(true)
        io.setDtr(false)
        io.setRts(true)
        Thread.sleep(100)
        io.setDtr(false)
        io.setRts(false)
        Thread.sleep(50)
    }

    /**
     * Sends SYNC and stops at the first reply. Sending it again after success (as opposed
     * to retrying after a genuine timeout) is not harmless: the real ROM answers every
     * SYNC up to eight times, and its 128-byte UART RX FIFO overflows if we keep sending
     * more while it is still replying - after which its SLIP parser is wedged and it
     * never answers anything again, including the READ_REG that follows in detectChip().
     */
    private fun sync() {
        val payload = ByteArray(36) { 0x55 }
        payload[0] = 0x07; payload[1] = 0x07; payload[2] = 0x12; payload[3] = 0x20

        var last: Exception? = null
        repeat(SYNC_ATTEMPTS) {
            try {
                val r = command(ESP_SYNC, payload, timeoutMs = SYNC_TIMEOUT_MS)

                // A SYNC reply carries nothing but the status bytes, so its length tells
                // us how many this ROM uses. detectChip() then pins it down authoritatively.
                statusBytes = if (r.payload.size >= 4) 4 else 2

                // The ROM answers a single SYNC up to eight times; drop the echoes.
                discardInput()
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw EspError("no answer to SYNC (${last?.message})")
    }

    /** Reads the chip's magic register to work out what we are talking to. */
    fun detectChip(): Chip {
        val magic = readRegister(Chip.CHIP_DETECT_MAGIC_REG_ADDR)
        val detected = Chip.fromMagic(magic)
            ?: throw EspError("Unsupported ESP chip (magic 0x%08X).".format(magic))
        chip = detected
        statusBytes = detected.romStatusBytes

        // A chip whose ROM console runs over USB-OTG takes smaller RAM/flash blocks.
        val bufNoReg = detected.uartDevBufNoReg
        if (io.isNativeUsb && bufNoReg != null) {
            val uartNo = readRegister(bufNoReg) and 0xFF
            if (uartNo == Chip.UARTDEV_BUF_NO_USB_OTG) {
                usbOtgConsole = true
                ramBlock = USB_RAM_BLOCK
                log("Chip console is USB-OTG; using ${USB_RAM_BLOCK}-byte blocks.")
            }
        }
        log("Detected ${detected.displayName} (magic 0x%08X).".format(magic))
        return detected
    }

    /**
     * Reads the chip's factory eFuse base MAC address (esptool `read_mac()`), used to derive
     * a name unique to this device. Requires [detectChip] to have run first.
     */
    fun readMac(): ByteArray {
        val detected = chip ?: throw IllegalStateException("readMac() called before detectChip()")
        val low = readRegister(detected.macLowWordReg)
        val high = readRegister(detected.macHighWordReg)
        return byteArrayOf(
            (high shr 8).toByte(),
            high.toByte(),
            (low shr 24).toByte(),
            (low shr 16).toByte(),
            (low shr 8).toByte(),
            low.toByte(),
        )
    }

    // ------------------------------------------------------------------ flash

    /** Uploads Espressif's flasher stub into RAM and runs it. */
    fun runStub(stub: StubFlasher) {
        log("Uploading flasher stub...")
        uploadToRam(stub.text, stub.textStart)
        uploadToRam(stub.data, stub.dataStart)

        memFinish(stub.entry)

        // The greeting may already be in hand: the ROM jumps into the stub rather than
        // answering MEM_END, so the stub can announce itself while memFinish() is still
        // waiting for an answer that will never come. [nextFrame] looks there first.
        val greeting = nextFrame(System.currentTimeMillis() + STUB_START_TIMEOUT_MS)
            ?: throw EspError("The flasher stub did not start (no greeting).")
        val text = String(greeting, Charsets.US_ASCII)
        if (text != "OHAI") throw EspError("The flasher stub did not start (got \"$text\").")

        isStub = true
        statusBytes = 2
        flashWriteSize = if (usbOtgConsole) USB_RAM_BLOCK else STUB_FLASH_WRITE_SIZE
        log("Flasher stub running.")
    }

    fun changeBaud(baud: Int) {
        // A USB CDC endpoint ignores the line rate entirely, and the ROM refuses the
        // command when its console is the built-in USB.
        if (io.isNativeUsb || baud == currentBaud) return

        // The stub wants to be told the rate the link is running at now - which is not
        // necessarily the ROM's, since we may already have changed it once.
        command(ESP_CHANGE_BAUDRATE, pack(baud, if (isStub) currentBaud else 0))
        io.setBaudRate(baud)
        currentBaud = baud
        Thread.sleep(50)
        discardInput()
        log("Serial link now at $baud baud.")
    }

    fun spiAttach() {
        // The ROM takes an extra "is legacy" word that the stub does not.
        val arg = if (isStub) pack(0) else pack(0, 0)
        checkCommand("configure SPI flash pins", ESP_SPI_ATTACH, arg)
    }

    /**
     * Reads the flash chip's JEDEC id, turns its capacity byte into a size, and then checks
     * that the chip really is that big before we believe it.
     */
    fun detectFlashSize(): Int {
        val jedecId = runSpiFlashCommand(SPIFLASH_RDID, readBits = 24)
        val sizeId = (jedecId shr 16) and 0xFF
        val claimed = FlashSizes.fromSizeId(sizeId)
            ?: throw EspError("Could not read the flash size (JEDEC id 0x%06X).".format(jedecId))
        log("Flash: the chip says ${FlashSizes.format(claimed)} (JEDEC id 0x%06X).".format(jedecId))

        flashSize = runCatching { confirmSize(claimed) }.getOrElse { e ->
            log("Could not double-check the flash size (${e.message}); taking the chip's word for it.")
            claimed
        }
        return flashSize
    }

    /**
     * The JEDEC capacity byte is only what the chip *claims*, and getting it wrong picks the
     * wrong firmware and the wrong partition table for the device.
     *
     * A SPI flash simply ignores the address bits above its real capacity, so a 4MB chip
     * reads back the same bytes at 0x1000 and at 0x401000. Look for that mirror, using only
     * the MD5 command, which the ROM answers as happily as the stub.
     *
     * This needs a region with something in it: on a blank chip every address reads 0xFF, a
     * mirror is indistinguishable from an erase, and we have to take the chip's word.
     */
    private fun confirmSize(claimed: Int): Int {
        // Strictly read-only: no SPI_SET_PARAMS, no flash mode, nothing that leaves the ROM
        // configured differently than the flasher stub will expect to find it. Detection has
        // no business changing the state the burn starts from.
        val fingerprint = flashMd5(PROBE_OFFSET, PROBE_SIZE)
        if (fingerprint.equals(md5Hex(ByteArray(PROBE_SIZE) { ERASED_BYTE }), ignoreCase = true)) {
            log("The flash is blank at 0x%X, so its size cannot be confirmed; taking the chip's word for it."
                .format(PROBE_OFFSET))
            return claimed
        }

        var size = SMALLEST_PROBED_SIZE
        while (size < claimed) {
            if (flashMd5(size + PROBE_OFFSET, PROBE_SIZE).equals(fingerprint, ignoreCase = true)) {
                log("0x%X reads back exactly what 0x%X holds: this chip is really ${FlashSizes.format(size)}, not ${FlashSizes.format(claimed)}."
                    .format(size + PROBE_OFFSET, PROBE_OFFSET))
                return size
            }
            size *= 2
        }
        log("Flash: ${FlashSizes.format(claimed)} confirmed.")
        return claimed
    }

    fun setFlashParameters(size: Int) {
        checkCommand(
            "set flash parameters",
            ESP_SPI_SET_PARAMS,
            pack(0, size, FLASH_BLOCK_SIZE, FLASH_SECTOR_SIZE, FLASH_PAGE_SIZE, FLASH_STATUS_MASK),
        )
    }

    /**
     * Compresses [image], writes it at [offset] and verifies it by MD5, recovering on its
     * own if that fails: rewrite the image, then rewrite it with the link slowed down. If
     * it still will not verify, [diagnose] reports what is actually in the flash before we
     * give up, so a failure says something more useful than "it didn't work".
     *
     * [onProgress] is called with the number of bytes of [image] written so far.
     */
    fun writeFlash(image: ByteArray, offset: Int, onProgress: (written: Int, total: Int) -> Unit) {
        val expected = md5Hex(image)
        var lastError: Exception? = null

        WRITE_ATTEMPT_BAUDS.forEachIndexed { attempt, baud ->
            if (attempt > 0) {
                log("Writing 0x%X again (attempt ${attempt + 1} of ${WRITE_ATTEMPT_BAUDS.size}).".format(offset))
                discardInput()
                // Only ever slow the link down. On a link we never sped up (native USB, or
                // a failure before changeBaud) there is nothing here to fall back to.
                if (baud != null && baud < currentBaud) {
                    runCatching { changeBaud(baud) }
                        .onFailure { log("Could not slow the link to $baud baud: ${it.message}") }
                }
            }
            try {
                writeImage(image, offset, onProgress)
                if (verified(image, offset, expected)) {
                    log("Verified 0x%X.".format(offset))
                    return
                }
                lastError = EspError(
                    "Verification failed at 0x%X: the flash does not match the firmware.".format(offset)
                )
            } catch (e: Exception) {
                lastError = e
                log("Writing 0x%X failed: ${e.message}".format(offset))
            }
        }

        runCatching { diagnose(image, offset, expected) }

        val error = lastError ?: EspError("Could not write 0x%X.".format(offset))
        // Reads work and writes do not: that is the flash refusing to unlock, not a bad link.
        // The chip's write-protect survives the ESP32's reset line - only power does not.
        if (error.message?.contains("erase flash") == true) {
            throw EspError(
                "${error.message} The flash is refusing to erase, which usually means it is " +
                    "write-protected. Unplug the M5Stack, switch it fully off (hold the red " +
                    "button for a few seconds), then plug it back in and try again."
            )
        }
        throw error
    }

    /** One pass of FLASH_DEFL_BEGIN + FLASH_DEFL_DATA blocks. No verification. */
    private fun writeImage(image: ByteArray, offset: Int, onProgress: (written: Int, total: Int) -> Unit) {
        val target = chip ?: throw EspError("Chip not detected yet.")
        val compressed = deflate(image)

        val numBlocks = ceilDiv(compressed.size, flashWriteSize)
        val eraseBlocks = ceilDiv(image.size, flashWriteSize)

        // The stub erases as it writes; the ROM erases the whole region up front and
        // wants that region rounded up to a whole number of blocks.
        val writeSize = if (isStub) image.size else eraseBlocks * flashWriteSize
        val beginTimeout =
            if (isStub) DEFAULT_TIMEOUT_MS
            else timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB_MS, writeSize)

        // The trailing "encrypted write" word belongs to the *ROM* loader of chips newer than
        // the ESP32, whose own ROM predates the field. The flasher stub never takes it: it
        // checks the length with verify_data_len(command, 16) and rejects a 20-byte command
        // outright - and because that check runs through a C `||` chain, the real code is
        // collapsed to a bare 1, which surfaces here as "status 0x01, error 0x00" on every
        // erase, at every baud, forever. Mirrors esptool's flash_defl_begin().
        var params = pack(writeSize, numBlocks, flashWriteSize, offset)
        if (!isStub && target.romSupportsEncryptedFlash) {
            params += pack(0)  // "not encrypted"
        }

        log("Writing ${image.size} bytes (${compressed.size} compressed) at 0x%X...".format(offset))
        checkCommand("erase flash", ESP_FLASH_DEFL_BEGIN, params, timeoutMs = beginTimeout)

        val blockTimeout =
            if (isStub) DEFAULT_TIMEOUT_MS
            else timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB_MS, flashWriteSize)

        var position = 0
        var seq = 0
        while (position < compressed.size) {
            val end = min(position + flashWriteSize, compressed.size)
            val block = compressed.copyOfRange(position, end)
            // Deliberately never retried. Every block feeds one long zlib stream, so a block
            // the device processed but failed to acknowledge would be inflated twice if we
            // sent it again - which corrupts the flash while reporting success. The whole
            // image is rewritten instead; FLASH_DEFL_BEGIN starts a fresh stream.
            checkCommand(
                "write flash block $seq",
                ESP_FLASH_DEFL_DATA,
                pack(block.size, seq, 0, 0) + block,
                checksum = checksum(block),
                timeoutMs = blockTimeout,
                attempts = 1,
            )
            position = end
            seq++
            // Report progress against the uncompressed image, which is what the user sees.
            onProgress(min(image.size, seq * flashWriteSize * image.size / max(1, compressed.size)), image.size)
        }
        onProgress(image.size, image.size)

        // The stub acknowledges a block once it has *received* it, not once it has written
        // it out. A command it cannot answer until that queue drains keeps the MD5 below
        // from racing the last write. esptool does the same, for the same reason.
        if (isStub) {
            checkCommand(
                "wait for the last block to reach the flash",
                ESP_READ_REG,
                pack(Chip.CHIP_DETECT_MAGIC_REG_ADDR),
                timeoutMs = timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB_MS, flashWriteSize),
            )
        }
    }

    /**
     * True if the flash at [offset] holds [image].
     *
     * One mismatching digest is not proof of a bad flash: the MD5 reply is the one payload
     * in this protocol that carries no checksum, so a reply mangled on the wire is
     * indistinguishable from corrupt flash. Ask twice before believing it.
     */
    private fun verified(image: ByteArray, offset: Int, expected: String): Boolean {
        val first = flashMd5(offset, image.size)
        if (first.equals(expected, ignoreCase = true)) return true

        val second = flashMd5(offset, image.size)
        if (second.equals(expected, ignoreCase = true)) {
            log("The first verification reply was garbled; the flash itself is correct.")
            return true
        }
        if (!first.equals(second, ignoreCase = true)) {
            log("The device reports a different flash digest each time it is asked; the serial link is corrupting replies.")
        } else {
            log("Flash at 0x%X holds $first, the firmware is $expected.".format(offset))
        }
        return false
    }

    private enum class Chunk { MATCHES, ERASED, DIFFERENT }

    /**
     * Asks the device for an MD5 of each chunk of the region we could not verify, and works
     * out which chunks hold the firmware, which are still erased and which hold something
     * else. Costs a handful of MD5 commands and turns "verification failed" into a map of
     * what actually reached the flash.
     */
    private fun diagnose(image: ByteArray, offset: Int, expected: String) {
        log("Checking what the flash really holds at 0x%X (the firmware's MD5 is $expected)...".format(offset))

        val chunks = inspect(image, offset, DIAGNOSIS_CHUNK_SIZE, 0, image.size)
        log("  " + summarise(chunks, offset, image.size, DIAGNOSIS_CHUNK_SIZE))

        val firstBad = chunks.firstOrNull { it.second != Chunk.MATCHES }
        if (firstBad == null) {
            log("  Every chunk matches on its own, so the flash is correct and it is the " +
                "whole-region digest that is coming back wrong: the device's replies are " +
                "being corrupted, not its flash.")
            return
        }

        val end = min(firstBad.first + DIAGNOSIS_CHUNK_SIZE, image.size)
        val sectors = inspect(image, offset, FLASH_SECTOR_SIZE, firstBad.first, end)
        log("  Sector by sector from there: " + summarise(sectors, offset, end, FLASH_SECTOR_SIZE))
    }

    /** MD5s each [chunkSize] slice of [image] between [from] and [to] against the flash. */
    private fun inspect(
        image: ByteArray,
        offset: Int,
        chunkSize: Int,
        from: Int,
        to: Int,
    ): List<Pair<Int, Chunk>> {
        val result = mutableListOf<Pair<Int, Chunk>>()
        var at = from
        while (at < to) {
            val end = min(at + chunkSize, to)
            val slice = image.copyOfRange(at, end)
            val actual = flashMd5(offset + at, slice.size)
            val state = when {
                actual.equals(md5Hex(slice), ignoreCase = true) -> Chunk.MATCHES
                actual.equals(md5Hex(ByteArray(slice.size) { ERASED_BYTE }), ignoreCase = true) -> Chunk.ERASED
                else -> Chunk.DIFFERENT
            }
            result += at to state
            at = end
        }
        return result
    }

    /** Collapses runs of same-verdict chunks into "0x1000-0x4FFF matches, 0x5000-... erased". */
    private fun summarise(chunks: List<Pair<Int, Chunk>>, offset: Int, to: Int, chunkSize: Int): String =
        buildString {
            var i = 0
            while (i < chunks.size) {
                val state = chunks[i].second
                var last = i
                while (last + 1 < chunks.size && chunks[last + 1].second == state) last++
                if (isNotEmpty()) append(", ")
                append("0x%X-0x%X ".format(
                    offset + chunks[i].first,
                    offset + min(chunks[last].first + chunkSize, to) - 1,
                ))
                append(
                    when (state) {
                        Chunk.MATCHES -> "matches the firmware"
                        Chunk.ERASED -> "is still erased"
                        Chunk.DIFFERENT -> "holds something else"
                    }
                )
                i = last + 1
            }
        }

    private fun flashMd5(address: Int, size: Int): String {
        val reply = checkCommand(
            "verify flash",
            ESP_SPI_FLASH_MD5,
            pack(address, size, 0, 0),
            timeoutMs = timeoutPerMb(MD5_TIMEOUT_PER_MB_MS, size),
        )
        return when (reply.payload.size) {
            32 -> String(reply.payload, Charsets.US_ASCII)                       // ROM: hex text
            16 -> reply.payload.joinToString("") { "%02x".format(it) }           // stub: raw digest
            else -> throw EspError("Unexpected MD5 reply (${reply.payload.size} bytes).")
        }
    }

    /** Leaves flash mode. Skipped on the ROM, where it would exit the bootloader early. */
    fun finishFlash() {
        if (isStub) {
            checkCommand("leave flash mode", ESP_FLASH_DEFL_END, pack(1))  // 1 = do not reboot yet
        }
    }

    /** Pulls EN low and releases it, so the freshly flashed firmware boots. */
    fun hardReset() {
        io.setRts(true)  // EN = LOW
        if (io.isNativeUsb) {
            // Give the chip time to come out of reset before touching the lines again.
            Thread.sleep(200)
            io.setRts(false)
            Thread.sleep(200)
        } else {
            Thread.sleep(100)
            io.setRts(false)
        }
        log("Device reset; the new firmware is starting.")
    }

    // -------------------------------------------------------------- RAM upload

    private fun uploadToRam(image: ByteArray, offset: Int) {
        if (image.isEmpty()) return
        val blocks = ceilDiv(image.size, ramBlock)
        checkCommand("enter RAM download mode", ESP_MEM_BEGIN, pack(image.size, blocks, ramBlock, offset))
        for (seq in 0 until blocks) {
            val from = seq * ramBlock
            val to = min(from + ramBlock, image.size)
            val block = image.copyOfRange(from, to)
            checkCommand(
                "write to RAM",
                ESP_MEM_DATA,
                pack(block.size, seq, 0, 0) + block,
                checksum = checksum(block),
            )
        }
    }

    private fun memFinish(entrypoint: Int) {
        val data = pack(if (entrypoint == 0) 1 else 0, entrypoint)
        try {
            // Sent once, never retried: by the time it goes unanswered the ROM has already
            // jumped into the stub, so a second one would be answered by different code.
            checkCommand(
                "leave RAM download mode",
                ESP_MEM_END,
                data,
                timeoutMs = MEM_END_ROM_TIMEOUT_MS,
                attempts = 1,
            )
        } catch (e: Exception) {
            // The ROM frequently resets the UART before this reply is flushed; esptool
            // ignores the failure here too, and the stub's greeting is the real proof.
            log("MEM_END gave no clean reply (harmless): ${e.message}")
        }
    }

    // ----------------------------------------------------------- SPI registers

    /**
     * Runs an arbitrary command on the SPI flash by driving the chip's SPI peripheral
     * through its registers. Used to read the JEDEC id, which is how we tell a 4MB
     * M5Stack Basic from a 16MB one, and to read and rewrite the status register.
     *
     * [data]/[dataBits] send bytes to the flash (little-endian, as the peripheral shifts
     * them out); [readBits] reads them back. Mirrors esptool's run_spiflash_command().
     */
    private fun runSpiFlashCommand(
        command: Int,
        data: Int = 0,
        dataBits: Int = 0,
        readBits: Int = 0,
    ): Int {
        val target = chip ?: throw EspError("Chip not detected yet.")
        val base = target.spiRegBase
        val cmdReg = base
        val usrReg = base + target.spiUsrOffs
        val usr1Reg = base + target.spiUsr1Offs
        val usr2Reg = base + target.spiUsr2Offs
        val w0Reg = base + target.spiW0Offs
        val misoDlenReg = base + target.spiMisoDlenOffs
        val mosiDlenReg = base + target.spiMosiDlenOffs

        val oldUsr = readRegister(usrReg)
        val oldUsr2 = readRegister(usr2Reg)

        var flags = SPI_USR_COMMAND
        if (readBits > 0) {
            flags = flags or SPI_USR_MISO
            writeRegister(misoDlenReg, readBits - 1)
        }
        if (dataBits > 0) {
            flags = flags or SPI_USR_MOSI
            writeRegister(mosiDlenReg, dataBits - 1)
        }
        writeRegister(usr1Reg, 0)
        writeRegister(usrReg, flags)
        writeRegister(usr2Reg, (7 shl SPI_USR2_COMMAND_LEN_SHIFT) or command)
        writeRegister(w0Reg, data)
        writeRegister(cmdReg, SPI_CMD_USR)

        var done = false
        repeat(10) {
            if (!done && (readRegister(cmdReg) and SPI_CMD_USR) == 0) done = true
        }
        if (!done) throw EspError("The SPI flash command did not complete.")

        val result = readRegister(w0Reg)

        writeRegister(usrReg, oldUsr)
        writeRegister(usr2Reg, oldUsr2)

        val mask = if (readBits >= 32) -1 else (1 shl readBits) - 1
        return result and mask
    }

    // --------------------------------------------------------- write protection

    /** The flash's two status registers, SR1 in the low byte and SR2 in the high byte. */
    private fun readFlashStatus(): Int {
        val sr1 = runSpiFlashCommand(SPIFLASH_RDSR, readBits = 8)
        val sr2 = runSpiFlashCommand(SPIFLASH_RDSR2, readBits = 8)
        return (sr1 and 0xFF) or ((sr2 and 0xFF) shl 8)
    }

    /** Only SR1 is trusted here: not every chip implements SR2, and a chip that doesn't
     *  answers 0xFF, which would look exactly like every protection bit being set. */
    private fun isWriteProtected(status: Int) = (status and SR1_PROTECT) != 0

    /**
     * Clears the flash's block-protection bits if they are set.
     *
     * These bits live in a *non-volatile* status register, so once a chip ends up protected
     * it stays protected across resets and power cycles, and every erase is refused until
     * they are cleared - which is what "Could not erase flash" means, and why no amount of
     * retrying gets past it. The ROM's SPIUnlock is known to mishandle the status register
     * on some flash parts, so do not rely on it: read the register and clear it ourselves.
     *
     * This is best-effort and never fails the flash on its own: [SR1_PROTECT] is a guess at
     * which bits mean "protected" across vendors, and a wrong guess must not abort a flash
     * that would otherwise have worked. If the bits still look set afterwards, the erase
     * that follows is the real test - and it already has its own diagnostics if it fails.
     */
    fun unlockFlash() {
        val status = readFlashStatus()
        log("Flash status register: 0x%04X.".format(status))
        if (!isWriteProtected(status)) return

        log("The flash is write-protected, which is why it will not erase. Clearing the protection bits...")

        // SR1 alone first, preserving every other SR1 bit (e.g. quad-enable, which some
        // parts keep in SR1 rather than SR2 - clearing it by accident could stop the board
        // from booting in QIO mode).
        val sr1 = status and 0xFF and SR1_PROTECT.inv()
        writeFlashStatus(value = sr1, bits = 8)
        var now = readFlashStatus()

        if (isWriteProtected(now)) {
            // Some parts only accept a 16-bit write covering both registers. Put SR2 back
            // exactly as it was, apart from the bits that take part in protection.
            val sr2 = ((now shr 8) and 0xFF) and (SR2_CMP or SR2_SRP1).inv()
            writeFlashStatus(value = (now and 0xFF and SR1_PROTECT.inv()) or (sr2 shl 8), bits = 16)
            now = readFlashStatus()
        }

        if (isWriteProtected(now)) {
            log(("Could not clear the protection bits (status register stays at 0x%04X); " +
                "trying to flash anyway.").format(now))
            return
        }
        log("Flash unlocked (status register now 0x%04X).".format(now))
    }

    private fun writeFlashStatus(value: Int, bits: Int) {
        runSpiFlashCommand(SPIFLASH_WREN)
        runSpiFlashCommand(SPIFLASH_WRSR, data = value, dataBits = bits)

        // Writing the status register is a non-volatile operation: the chip is busy for a
        // few milliseconds and ignores everything until it finishes.
        repeat(FLASH_BUSY_POLLS) {
            if ((runSpiFlashCommand(SPIFLASH_RDSR, readBits = 8) and SR1_BUSY) == 0) return
            Thread.sleep(FLASH_BUSY_POLL_MS)
        }
        throw EspError("The flash stayed busy after its status register was written.")
    }

    private fun readRegister(address: Int): Int =
        checkCommand("read register 0x%08X".format(address), ESP_READ_REG, pack(address)).value

    private fun writeRegister(address: Int, value: Int) {
        checkCommand(
            "write register 0x%08X".format(address),
            ESP_WRITE_REG,
            pack(address, value, MASK_ALL, 0),
        )
    }

    // ------------------------------------------------------------- the protocol

    private class Response(val value: Int, val payload: ByteArray)

    /**
     * Sends [op] and validates the reply, retrying a couple of times if a frame is lost.
     *
     * Pass `attempts = 1` for any command the device cannot safely be asked to do twice -
     * see the FLASH_DEFL_DATA call in [writeImage].
     */
    private fun checkCommand(
        what: String,
        op: Int,
        data: ByteArray = EMPTY,
        checksum: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        attempts: Int = COMMAND_ATTEMPTS,
    ): Response {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val reply = command(op, data, checksum, timeoutMs)
                if (reply.payload.size < statusBytes) {
                    throw EspError("Could not $what: reply was only ${reply.payload.size} bytes.")
                }
                val statusAt = reply.payload.size - statusBytes
                val status = reply.payload[statusAt].toInt() and 0xFF
                if (status != 0) {
                    val error = reply.payload[statusAt + 1].toInt() and 0xFF
                    throw EspError("Could not $what (status 0x%02X, error 0x%02X).".format(status, error))
                }
                return Response(reply.value, reply.payload.copyOfRange(0, statusAt))
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) {
                    log("$what failed (attempt ${attempt + 1}), retrying: ${e.message}")
                    discardInput()
                }
            }
        }
        throw lastError ?: EspError("Could not $what.")
    }

    private fun command(
        op: Int,
        data: ByteArray = EMPTY,
        checksum: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Response {
        val packet = ByteArray(8 + data.size)
        packet[0] = 0x00                       // direction: request
        packet[1] = op.toByte()
        writeLe16(packet, 2, data.size)
        writeLe32(packet, 4, checksum)
        data.copyInto(packet, 8)

        io.write(slipEncode(packet))

        val deadline = System.currentTimeMillis() + timeoutMs
        val strays = mutableListOf<ByteArray>()
        while (true) {
            val frame = nextFrame(deadline) ?: run {
                // Nothing here answered us, but something else may have been waiting for
                // it - the flasher stub's greeting arrives exactly this way. Keep it.
                strayFrames += strays.takeLast(MAX_STRAY_FRAMES)
                val detail = if (strays.isEmpty()) {
                    "nothing came back"
                } else {
                    "${strays.size} frame(s) arrived but none answered this command"
                }
                throw EspError("no reply to command 0x%02X (%s)".format(op, detail))
            }
            // Anything that isn't a reply to the command we just sent - boot chatter, SYNC
            // echoes, the stub announcing itself - is set aside rather than thrown away.
            val isReply = frame.size >= 8 &&
                (frame[0].toInt() and 0xFF) == 0x01 &&
                (frame[1].toInt() and 0xFF) == op
            if (!isReply) {
                strays += frame
                continue
            }

            val size = readLe16(frame, 2)
            val value = readLe32(frame, 4)
            val end = min(frame.size, 8 + size)
            return Response(value, frame.copyOfRange(8, end))
        }
    }

    // ------------------------------------------------------------------- SLIP

    private val rxBuffer = ByteArray(4096)
    private var rxLength = 0
    private var rxPosition = 0

    /** Frames that turned up while we were waiting for the answer to a different command. */
    private val strayFrames = ArrayDeque<ByteArray>()

    private fun discardInput() {
        rxLength = 0
        rxPosition = 0
        strayFrames.clear()
        io.purge()
    }

    /** The next frame, preferring one we have already read but had no use for at the time. */
    private fun nextFrame(deadline: Long): ByteArray? =
        strayFrames.removeFirstOrNull() ?: readSlipFrame(deadline)

    private fun nextByte(deadline: Long): Int? {
        while (rxPosition >= rxLength) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val read = io.read(rxBuffer, min(remaining, 50L).toInt().coerceAtLeast(1))
            if (read > 0) {
                rxLength = read
                rxPosition = 0
            } else if (read < 0) {
                return null
            }
        }
        return rxBuffer[rxPosition++].toInt() and 0xFF
    }

    /** Reads one 0xC0-delimited SLIP frame, un-escaping as it goes. Null on timeout. */
    private fun readSlipFrame(deadline: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        var inFrame = false
        var escaped = false

        while (true) {
            val b = nextByte(deadline) ?: return null
            if (!inFrame) {
                if (b == SLIP_END) inFrame = true
                continue
            }
            when {
                escaped -> {
                    escaped = false
                    when (b) {
                        SLIP_ESC_END -> out.write(SLIP_END)
                        SLIP_ESC_ESC -> out.write(SLIP_ESC)
                        else -> {  // malformed escape: drop the frame and resynchronise
                            out.reset()
                            inFrame = false
                        }
                    }
                }
                b == SLIP_ESC -> escaped = true
                b == SLIP_END -> {
                    // Two 0xC0 in a row: the first closed nothing, it opened this frame.
                    if (out.size() == 0) continue
                    return out.toByteArray()
                }
                else -> out.write(b)
            }
        }
    }

    private fun slipEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        out.write(SLIP_END)
        for (byte in data) {
            when (byte.toInt() and 0xFF) {
                SLIP_END -> { out.write(SLIP_ESC); out.write(SLIP_ESC_END) }
                SLIP_ESC -> { out.write(SLIP_ESC); out.write(SLIP_ESC_ESC) }
                else -> out.write(byte.toInt())
            }
        }
        out.write(SLIP_END)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ helpers

    private fun checksum(data: ByteArray): Int {
        var check = ESP_CHECKSUM_MAGIC
        for (byte in data) check = check xor (byte.toInt() and 0xFF)
        return check
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val chunk = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun timeoutPerMb(perMbMs: Int, sizeBytes: Int): Int =
        max(DEFAULT_TIMEOUT_MS, (perMbMs.toLong() * sizeBytes / 1_000_000L).toInt())

    private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

    private fun pack(vararg words: Int): ByteArray {
        val out = ByteArray(words.size * 4)
        words.forEachIndexed { index, word -> writeLe32(out, index * 4, word) }
        return out
    }

    private fun writeLe16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeLe32(target: ByteArray, at: Int, value: Int) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        target[at + 2] = ((value shr 16) and 0xFF).toByte()
        target[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readLe16(source: ByteArray, at: Int): Int =
        (source[at].toInt() and 0xFF) or ((source[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(source: ByteArray, at: Int): Int =
        (source[at].toInt() and 0xFF) or
            ((source[at + 1].toInt() and 0xFF) shl 8) or
            ((source[at + 2].toInt() and 0xFF) shl 16) or
            ((source[at + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val ESP_ROM_BAUD = 115200
        const val ESP_FLASH_BAUD = 921600

        private val EMPTY = ByteArray(0)

        // Commands
        private const val ESP_FLASH_BEGIN = 0x02
        private const val ESP_FLASH_DATA = 0x03
        private const val ESP_FLASH_END = 0x04
        private const val ESP_MEM_BEGIN = 0x05
        private const val ESP_MEM_END = 0x06
        private const val ESP_MEM_DATA = 0x07
        private const val ESP_SYNC = 0x08
        private const val ESP_WRITE_REG = 0x09
        private const val ESP_READ_REG = 0x0A
        private const val ESP_SPI_SET_PARAMS = 0x0B
        private const val ESP_SPI_ATTACH = 0x0D
        private const val ESP_CHANGE_BAUDRATE = 0x0F
        private const val ESP_FLASH_DEFL_BEGIN = 0x10
        private const val ESP_FLASH_DEFL_DATA = 0x11
        private const val ESP_FLASH_DEFL_END = 0x12
        private const val ESP_SPI_FLASH_MD5 = 0x13

        private const val ESP_CHECKSUM_MAGIC = 0xEF

        // SLIP framing
        private const val SLIP_END = 0xC0
        private const val SLIP_ESC = 0xDB
        private const val SLIP_ESC_END = 0xDC
        private const val SLIP_ESC_ESC = 0xDD

        // Block sizes
        private const val ROM_FLASH_WRITE_SIZE = 0x400
        private const val STUB_FLASH_WRITE_SIZE = 0x4000
        private const val ESP_RAM_BLOCK = 0x1800
        private const val USB_RAM_BLOCK = 0x800

        // SPI peripheral
        private const val SPIFLASH_RDID = 0x9F
        private const val SPI_USR_COMMAND = 1 shl 31
        private const val SPI_USR_MISO = 1 shl 28
        private const val SPI_USR_MOSI = 1 shl 27
        private const val SPI_CMD_USR = 1 shl 18
        private const val SPI_USR2_COMMAND_LEN_SHIFT = 28
        private const val MASK_ALL = -1  // 0xFFFFFFFF

        // Status register commands, and the bits in it that matter.
        private const val SPIFLASH_WRSR = 0x01
        private const val SPIFLASH_RDSR = 0x05
        private const val SPIFLASH_WREN = 0x06
        private const val SPIFLASH_RDSR2 = 0x35

        private const val SR1_BUSY = 0x01     // a write or erase is in progress
        // BP0-BP2 only. Bits 5-6 are vendor-dependent - on some parts they extend block
        // protection (BP3/BP4/TB), but on others (e.g. XMC's XM25QH128, as used on some
        // M5Stack units) bit 6 is the quad-enable bit instead, unrelated to protection.
        // Treating it as a protect bit makes a perfectly normal chip look locked, and
        // clearing it would risk breaking QIO boot on those parts.
        private const val SR1_PROTECT = 0x1C
        private const val SR2_SRP1 = 0x01
        private const val SR2_CMP = 0x40

        private const val FLASH_BUSY_POLLS = 100
        private const val FLASH_BUSY_POLL_MS = 10L

        // Flash parameters
        private const val FLASH_BLOCK_SIZE = 64 * 1024
        private const val FLASH_SECTOR_SIZE = 4 * 1024
        private const val FLASH_PAGE_SIZE = 256
        private const val FLASH_STATUS_MASK = 0xFFFF
        private const val ERASED_BYTE = 0xFF.toByte()

        /** Granularity of the post-mortem in [diagnose]: one MD5 command per chunk. */
        private const val DIAGNOSIS_CHUNK_SIZE = FLASH_BLOCK_SIZE

        // Where [confirmSize] looks for an address mirror: the bootloader, which is present
        // on any device that has ever booted, and the smallest size worth probing for.
        private const val PROBE_OFFSET = 0x1000
        private const val PROBE_SIZE = 0x1000
        private const val SMALLEST_PROBED_SIZE = 1024 * 1024

        // Timeouts (ms)
        private const val DEFAULT_TIMEOUT_MS = 3_000
        private const val SYNC_TIMEOUT_MS = 500
        private const val MEM_END_ROM_TIMEOUT_MS = 500
        private const val STUB_START_TIMEOUT_MS = 3_000
        private const val ERASE_REGION_TIMEOUT_PER_MB_MS = 30_000
        private const val ERASE_WRITE_TIMEOUT_PER_MB_MS = 40_000
        private const val MD5_TIMEOUT_PER_MB_MS = 8_000

        private const val SYNC_ATTEMPTS = 7
        private const val COMMAND_ATTEMPTS = 3

        /** Cap on set-aside frames, so a chattering device cannot grow the list forever. */
        private const val MAX_STRAY_FRAMES = 8

        /**
         * How [writeFlash] recovers from an image that will not verify: rewrite it as-is,
         * then rewrite it with the link progressively slowed down. Null means "whatever
         * rate we are already running at".
         */
        private val WRITE_ATTEMPT_BAUDS = listOf(null, null, 460_800, ESP_ROM_BAUD)
    }
}
