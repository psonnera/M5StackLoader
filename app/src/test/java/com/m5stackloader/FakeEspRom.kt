package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.SerialTransport
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * An in-memory ESP ROM bootloader: enough of the real thing to drive [com.m5stackloader.esp.EspLoader]
 * end to end without hardware.
 *
 * It speaks genuine SLIP framing, applies the chip's real status-byte length, emulates the
 * SPI peripheral registers well enough to answer a JEDEC id read, and - the part that
 * matters most - inflates the compressed blocks it receives and answers the MD5 command
 * with a digest of what it actually decoded. So a bug in our framing, checksums, block
 * splitting or compression shows up as a verification failure, exactly as it would on a
 * real device.
 */
class FakeEspRom(
    private val chip: Chip,
    flashCapacityByte: Int,
    override val isNativeUsb: Boolean = false,
) : SerialTransport {

    /** What the loader "flashed", keyed by offset: the inflated bytes we received. */
    val written = HashMap<Int, ByteArray>()

    /** Number of 32-bit words the loader put in the last FLASH_DEFL_BEGIN. */
    var deflBeginWordCount = 0
        private set

    var baudRate = 115200
        private set
    var enteredBootloader = false
        private set

    private val jedecId = 0xC8 or (0x40 shl 8) or (flashCapacityByte shl 16)
    private val statusBytes = chip.romStatusBytes

    private val outgoing = ArrayDeque<Byte>()
    private val registers = HashMap<Int, Int>()

    private val frame = ByteArrayOutputStream()
    private var inFrame = false
    private var escaped = false

    private var offset = 0
    private var compressed = ByteArrayOutputStream()

    private var dtr = false
    private var rts = false

    // ------------------------------------------------------------- transport

    override fun write(data: ByteArray) = data.forEach(::consume)

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        if (outgoing.isEmpty()) return 0
        var n = 0
        while (n < dest.size && outgoing.isNotEmpty()) {
            dest[n++] = outgoing.removeFirst()
        }
        return n
    }

    override fun setBaudRate(baud: Int) {
        baudRate = baud
    }

    override fun setDtr(value: Boolean) {
        dtr = value
    }

    override fun setRts(value: Boolean) {
        // Releasing EN (rts false) while IO0 is held low (dtr true) is what selects
        // download boot on a real board.
        if (rts && !value && dtr) enteredBootloader = true
        rts = value
    }

    override fun purge() {
        outgoing.clear()
    }

    override fun close() = Unit

    // -------------------------------------------------------- SLIP + dispatch

    private fun consume(byte: Byte) {
        val b = byte.toInt() and 0xFF
        if (!inFrame) {
            if (b == SLIP_END) {
                inFrame = true
                frame.reset()
            }
            return
        }
        when {
            escaped -> {
                escaped = false
                frame.write(if (b == SLIP_ESC_END) SLIP_END else SLIP_ESC)
            }
            b == SLIP_ESC -> escaped = true
            b == SLIP_END -> {
                if (frame.size() > 0) handle(frame.toByteArray())
                inFrame = false
            }
            else -> frame.write(b)
        }
    }

    private fun handle(packet: ByteArray) {
        require(packet[0].toInt() == 0x00) { "not a request" }
        val op = packet[1].toInt() and 0xFF
        val size = le16(packet, 2)
        val body = packet.copyOfRange(8, 8 + size)

        when (op) {
            ESP_SYNC -> repeat(8) { reply(op) }   // the real ROM echoes a sync eight times

            ESP_READ_REG -> reply(op, value = readRegister(le32(body, 0)))

            ESP_WRITE_REG -> {
                writeRegister(le32(body, 0), le32(body, 4))
                reply(op)
            }

            ESP_SPI_ATTACH, ESP_SPI_SET_PARAMS, ESP_CHANGE_BAUDRATE,
            ESP_MEM_BEGIN, ESP_MEM_DATA, ESP_MEM_END,
            -> reply(op)

            ESP_FLASH_DEFL_BEGIN -> {
                deflBeginWordCount = size / 4
                offset = le32(body, 12)
                compressed = ByteArrayOutputStream()
                reply(op)
            }

            ESP_FLASH_DEFL_DATA -> {
                val length = le32(body, 0)
                compressed.write(body, 16, length)
                reply(op)
            }

            ESP_FLASH_DEFL_END -> reply(op)

            ESP_SPI_FLASH_MD5 -> {
                val address = le32(body, 0)
                val length = le32(body, 4)
                val image = inflate(compressed.toByteArray())
                written[address] = image
                val digest = MessageDigest.getInstance("MD5")
                    .digest(image.copyOfRange(0, minOf(length, image.size)))
                    .joinToString("") { "%02x".format(it) }
                // The ROM answers with the digest as 32 ASCII hex characters.
                reply(op, payload = digest.toByteArray(Charsets.US_ASCII))
            }

            else -> throw AssertionError("fake ROM got an unexpected command 0x%02X".format(op))
        }
    }

    private fun readRegister(address: Int): Int = when (address) {
        Chip.CHIP_DETECT_MAGIC_REG_ADDR -> chip.magicValues.first()
        else -> registers[address] ?: 0
    }

    private fun writeRegister(address: Int, value: Int) {
        registers[address] = value
        // Kicking SPI_CMD_USR runs the queued SPI command; the only one we're asked for
        // is RDID, whose result lands in W0.
        if (address == chip.spiRegBase && (value and SPI_CMD_USR) != 0) {
            registers[chip.spiRegBase] = 0          // the command completes immediately
            registers[chip.spiRegBase + chip.spiW0Offs] = jedecId
        }
    }

    /** status bytes are all zero: success. */
    private fun reply(op: Int, value: Int = 0, payload: ByteArray = ByteArray(0)) {
        val body = payload + ByteArray(statusBytes)
        val packet = ByteArray(8 + body.size)
        packet[0] = 0x01
        packet[1] = op.toByte()
        packet[2] = (body.size and 0xFF).toByte()
        packet[3] = ((body.size shr 8) and 0xFF).toByte()
        packet[4] = (value and 0xFF).toByte()
        packet[5] = ((value shr 8) and 0xFF).toByte()
        packet[6] = ((value shr 16) and 0xFF).toByte()
        packet[7] = ((value shr 24) and 0xFF).toByte()
        body.copyInto(packet, 8)

        outgoing.addLast(SLIP_END.toByte())
        for (byte in packet) {
            when (byte.toInt() and 0xFF) {
                SLIP_END -> {
                    outgoing.addLast(SLIP_ESC.toByte()); outgoing.addLast(SLIP_ESC_END.toByte())
                }
                SLIP_ESC -> {
                    outgoing.addLast(SLIP_ESC.toByte()); outgoing.addLast(SLIP_ESC_ESC.toByte())
                }
                else -> outgoing.addLast(byte)
            }
        }
        outgoing.addLast(SLIP_END.toByte())
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && inflater.needsInput()) break
            out.write(chunk, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun le16(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val SLIP_END = 0xC0
        const val SLIP_ESC = 0xDB
        const val SLIP_ESC_END = 0xDC
        const val SLIP_ESC_ESC = 0xDD

        const val SPI_CMD_USR = 1 shl 18

        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_MEM_BEGIN = 0x05
        const val ESP_MEM_END = 0x06
        const val ESP_MEM_DATA = 0x07
        const val ESP_SYNC = 0x08
        const val ESP_WRITE_REG = 0x09
        const val ESP_READ_REG = 0x0A
        const val ESP_SPI_SET_PARAMS = 0x0B
        const val ESP_SPI_ATTACH = 0x0D
        const val ESP_CHANGE_BAUDRATE = 0x0F
        const val ESP_FLASH_DEFL_BEGIN = 0x10
        const val ESP_FLASH_DEFL_DATA = 0x11
        const val ESP_FLASH_DEFL_END = 0x12
        const val ESP_SPI_FLASH_MD5 = 0x13
    }
}
