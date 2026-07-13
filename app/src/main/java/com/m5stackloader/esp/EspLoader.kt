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

    /** Trailing status bytes on every reply. Chip- and stub-dependent; see [Chip.romStatusBytes]. */
    private var statusBytes = 2
    private var flashWriteSize = ROM_FLASH_WRITE_SIZE
    private var ramBlock = ESP_RAM_BLOCK

    // ---------------------------------------------------------------- connect

    /** Resets the chip into its ROM bootloader and syncs with it. */
    fun connect(attempts: Int = 5) {
        var last: Exception? = null
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

    private fun sync() {
        val payload = ByteArray(36) { 0x55 }
        payload[0] = 0x07; payload[1] = 0x07; payload[2] = 0x12; payload[3] = 0x20

        var reply: Response? = null
        var last: Exception? = null
        repeat(7) {
            try {
                reply = command(ESP_SYNC, payload, timeoutMs = SYNC_TIMEOUT_MS)
                return@repeat
            } catch (e: Exception) {
                last = e
            }
        }
        val r = reply ?: throw EspError("no answer to SYNC (${last?.message})")

        // A SYNC reply carries nothing but the status bytes, so its length tells us how
        // many this ROM uses. detectChip() then pins it down authoritatively.
        statusBytes = if (r.payload.size >= 4) 4 else 2

        // The ROM answers a single SYNC up to eight times; drop the echoes.
        discardInput()
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
        log("Detected ${detected.displayName}.")
        return detected
    }

    // ------------------------------------------------------------------ flash

    /** Uploads Espressif's flasher stub into RAM and runs it. */
    fun runStub(stub: StubFlasher) {
        log("Uploading flasher stub...")
        uploadToRam(stub.text, stub.textStart)
        uploadToRam(stub.data, stub.dataStart)

        memFinish(stub.entry)

        val greeting = readSlipFrame(System.currentTimeMillis() + STUB_START_TIMEOUT_MS)
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
        if (io.isNativeUsb) return

        val currentBaud = if (isStub) ESP_ROM_BAUD else 0  // the stub wants the old rate too
        command(ESP_CHANGE_BAUDRATE, pack(baud, currentBaud))
        io.setBaudRate(baud)
        Thread.sleep(50)
        discardInput()
        log("Serial link now at $baud baud.")
    }

    fun spiAttach() {
        // The ROM takes an extra "is legacy" word that the stub does not.
        val arg = if (isStub) pack(0) else pack(0, 0)
        checkCommand("configure SPI flash pins", ESP_SPI_ATTACH, arg)
    }

    /** Reads the flash chip's JEDEC id and turns its capacity byte into a size. */
    fun detectFlashSize(): Int {
        val jedecId = runSpiFlashCommand(SPIFLASH_RDID, readBits = 24)
        val sizeId = (jedecId shr 16) and 0xFF
        val size = FlashSizes.fromSizeId(sizeId)
            ?: throw EspError("Could not read the flash size (JEDEC id 0x%06X).".format(jedecId))
        flashSize = size
        log("Flash: ${FlashSizes.format(size)} (JEDEC id 0x%06X).".format(jedecId))
        return size
    }

    fun setFlashParameters(size: Int) {
        checkCommand(
            "set flash parameters",
            ESP_SPI_SET_PARAMS,
            pack(0, size, FLASH_BLOCK_SIZE, FLASH_SECTOR_SIZE, FLASH_PAGE_SIZE, FLASH_STATUS_MASK),
        )
    }

    /**
     * Compresses [image] and writes it at [offset], then verifies it by MD5.
     * [onProgress] is called with the number of bytes of [image] written so far.
     */
    fun writeFlash(image: ByteArray, offset: Int, onProgress: (written: Int, total: Int) -> Unit) {
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

        var params = pack(writeSize, numBlocks, flashWriteSize, offset)
        if (target.romSupportsEncryptedFlash && !isStub) {
            params += pack(0)  // "not encrypted"; the ESP32 ROM has no such field
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
            writeBlock(block, seq, blockTimeout)
            position = end
            seq++
            // Report progress against the uncompressed image, which is what the user sees.
            onProgress(min(image.size, seq * flashWriteSize * image.size / max(1, compressed.size)), image.size)
        }
        onProgress(image.size, image.size)

        verify(image, offset)
    }

    private fun verify(image: ByteArray, offset: Int) {
        val expected = md5Hex(image)
        val actual = flashMd5(offset, image.size)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw EspError("Verification failed at 0x%X: the flash does not match the firmware.".format(offset))
        }
        log("Verified 0x%X.".format(offset))
    }

    private fun writeBlock(block: ByteArray, seq: Int, timeoutMs: Int) {
        var lastError: Exception? = null
        repeat(WRITE_BLOCK_ATTEMPTS) { attempt ->
            try {
                checkCommand(
                    "write flash block $seq",
                    ESP_FLASH_DEFL_DATA,
                    pack(block.size, seq, 0, 0) + block,
                    checksum = checksum(block),
                    timeoutMs = timeoutMs,
                )
                return
            } catch (e: Exception) {
                lastError = e
                log("Block $seq failed (attempt ${attempt + 1}), retrying: ${e.message}")
                discardInput()
            }
        }
        throw EspError("Could not write flash block $seq (${lastError?.message}).")
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
            checkCommand("leave RAM download mode", ESP_MEM_END, data, timeoutMs = MEM_END_ROM_TIMEOUT_MS)
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
     * M5Stack Basic from a 16MB one.
     */
    private fun runSpiFlashCommand(command: Int, readBits: Int): Int {
        val target = chip ?: throw EspError("Chip not detected yet.")
        val base = target.spiRegBase
        val cmdReg = base
        val usrReg = base + target.spiUsrOffs
        val usr1Reg = base + target.spiUsr1Offs
        val usr2Reg = base + target.spiUsr2Offs
        val w0Reg = base + target.spiW0Offs
        val misoDlenReg = base + target.spiMisoDlenOffs

        val oldUsr = readRegister(usrReg)
        val oldUsr2 = readRegister(usr2Reg)

        var flags = SPI_USR_COMMAND
        if (readBits > 0) {
            flags = flags or SPI_USR_MISO
            writeRegister(misoDlenReg, readBits - 1)
        }
        writeRegister(usr1Reg, 0)
        writeRegister(usrReg, flags)
        writeRegister(usr2Reg, (7 shl SPI_USR2_COMMAND_LEN_SHIFT) or command)
        writeRegister(w0Reg, 0)
        writeRegister(cmdReg, SPI_CMD_USR)

        var done = false
        repeat(10) {
            if (!done && (readRegister(cmdReg) and SPI_CMD_USR) == 0) done = true
        }
        if (!done) throw EspError("The SPI flash command did not complete.")

        val status = readRegister(w0Reg)

        writeRegister(usrReg, oldUsr)
        writeRegister(usr2Reg, oldUsr2)

        val mask = if (readBits >= 32) -1 else (1 shl readBits) - 1
        return status and mask
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

    private fun checkCommand(
        what: String,
        op: Int,
        data: ByteArray = EMPTY,
        checksum: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Response {
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
        while (true) {
            val frame = readSlipFrame(deadline)
                ?: throw EspError("no reply to command 0x%02X".format(op))
            // Ignore anything that isn't a reply to the command we just sent: the ROM
            // emits boot chatter and repeated SYNC echoes.
            if (frame.size < 8) continue
            if ((frame[0].toInt() and 0xFF) != 0x01) continue
            if ((frame[1].toInt() and 0xFF) != op) continue

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

    private fun discardInput() {
        rxLength = 0
        rxPosition = 0
        io.purge()
    }

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
        private const val SPI_CMD_USR = 1 shl 18
        private const val SPI_USR2_COMMAND_LEN_SHIFT = 28
        private const val MASK_ALL = -1  // 0xFFFFFFFF

        // Flash parameters
        private const val FLASH_BLOCK_SIZE = 64 * 1024
        private const val FLASH_SECTOR_SIZE = 4 * 1024
        private const val FLASH_PAGE_SIZE = 256
        private const val FLASH_STATUS_MASK = 0xFFFF

        // Timeouts (ms)
        private const val DEFAULT_TIMEOUT_MS = 3_000
        private const val SYNC_TIMEOUT_MS = 500
        private const val MEM_END_ROM_TIMEOUT_MS = 500
        private const val STUB_START_TIMEOUT_MS = 3_000
        private const val ERASE_REGION_TIMEOUT_PER_MB_MS = 30_000
        private const val ERASE_WRITE_TIMEOUT_PER_MB_MS = 40_000
        private const val MD5_TIMEOUT_PER_MB_MS = 8_000

        private const val WRITE_BLOCK_ATTEMPTS = 3
    }
}
