package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.SerialTransport
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import kotlin.random.Random

/**
 * An in-memory ESP ROM bootloader: enough of the real thing to drive [com.m5stackloader.esp.EspLoader]
 * end to end without hardware.
 *
 * It speaks genuine SLIP framing, applies the chip's real status-byte length, emulates the
 * SPI peripheral registers well enough to answer a JEDEC id read, and - the part that
 * matters most - holds an actual array of flash: it inflates the compressed blocks it
 * receives into that array and answers the MD5 command with a digest of whatever is really
 * there. So a bug in our framing, checksums, block splitting or compression shows up as a
 * verification failure, exactly as it would on a real device.
 *
 * The constructor's fault-injection flags reproduce the ways real hardware misbehaves.
 */
class FakeEspRom(
    private val chip: Chip,
    flashCapacityByte: Int,
    override val isNativeUsb: Boolean = false,
    /**
     * Models the real ESP32 ROM's 128-byte UART RX FIFO: if it receives a second SYNC
     * while still replying to the first (up to eight echoes), the FIFO overflows and its
     * SLIP parser is left corrupted - it never answers anything again. This is what a
     * loader that keeps sending SYNC after a successful reply runs into on real hardware.
     */
    private val wedgesOnRepeatedSync: Boolean = false,
    /**
     * How much flash the chip actually has, when that is not what its JEDEC id claims.
     * A SPI flash ignores the address bits above its real capacity, so a smaller chip than
     * advertised mirrors its contents - which is how the loader catches the lie.
     */
    realFlashSize: Int = 1 shl flashCapacityByte,
    /** Whether the chip arrives with a previous firmware in it, as any used device would. */
    factoryFirmware: Boolean = true,
    /** Processes this FLASH_DEFL_DATA block but loses the acknowledgement, once. */
    private val dropAckForBlock: Int? = null,
    /** Corrupts this many MD5 replies before answering honestly. */
    private val garbledMd5Replies: Int = 0,
    /** Bytes at or above this address never reach the flash: they stay erased. */
    private val flashFailsAbove: Int? = null,
    /**
     * Starts with the block-protection bits set in the status register, as the user's
     * M5Stack did after the ROM's SPIUnlock mangled them. A protected chip reads back
     * happily and refuses every erase.
     */
    writeProtected: Boolean = false,
    /** Ignores writes to the status register: a chip locked by its WP pin, in hardware. */
    private val refusesToUnlock: Boolean = false,
    /**
     * Extra bits set in SR1 alongside the block-protection ones, e.g. 0x40 to model an XMC
     * part where SR1 bit 6 is quad-enable rather than a protection bit - a healthy chip that
     * the old, too-wide protection mask mistook for locked.
     */
    extraSr1Bits: Int = 0,
) : SerialTransport {

    private val flash = ByteArray(realFlashSize) { ERASED }

    /** What the loader flashed, keyed by the offset it wrote at: the bytes we inflated. */
    val written = HashMap<Int, ByteArray>()

    /** Number of 32-bit words the loader put in the last FLASH_DEFL_BEGIN. */
    var deflBeginWordCount = 0
        private set

    /** Number of ESP_SYNC commands this fake ROM has received. */
    var syncCommandsReceived = 0
        private set

    /** Every command opcode received, in order. */
    val commands = mutableListOf<Int>()

    /** Every CHANGE_BAUDRATE received, as (requested rate, rate the loader says we are at). */
    val baudChanges = mutableListOf<Pair<Int, Int>>()

    var baudRate = 115200
        private set
    var enteredBootloader = false
        private set

    private var wedged = false
    private var ackDropped = false
    private var md5Replies = 0

    /** True once ESP_MEM_END has jumped into a non-zero entrypoint (the stub is "running"). */
    private var stubActive = false

    private val jedecId = 0xC8 or (0x40 shl 8) or (flashCapacityByte shl 16)
    private val statusBytes = chip.romStatusBytes

    // The flash chip's own status registers. SR1 holds the block-protection bits; SR2 holds
    // the quad-enable bit, which the board needs to keep booting in QIO mode.
    private var sr1 = (if (writeProtected) SR1_BP else 0x00) or extraSr1Bits
    private var sr2 = SR2_QE
    private var writeEnabled = false

    /** SR1 in the low byte, SR2 in the high byte - what the loader sees. */
    val statusRegister: Int get() = sr1 or (sr2 shl 8)

    val quadEnabled: Boolean get() = (sr2 and SR2_QE) != 0

    private val outgoing = ArrayDeque<Byte>()
    private val registers = HashMap<Int, Int>()

    private val frame = ByteArrayOutputStream()
    private var inFrame = false
    private var escaped = false

    // The region the current FLASH_DEFL_BEGIN opened, and the zlib stream feeding it.
    private var region = 0
    private var regionWritten = 0
    private var inflater = Inflater()
    private var regionImage = ByteArrayOutputStream()

    private var dtr = false
    private var rts = false

    init {
        if (factoryFirmware) {
            // Something that is not 0xFF, so the loader's size probe has a fingerprint to
            // compare - just as a device that has booted at least once would have.
            Random(seed = 7).nextBytes(0x8000).copyInto(flash, 0x1000)
        }
    }

    // ------------------------------------------------------------- transport

    override fun write(data: ByteArray) = data.forEach(::consume)

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        if (outgoing.isEmpty()) {
            Thread.sleep(timeoutMs.toLong())   // a real port blocks; don't spin the CPU
            return 0
        }
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

    // ----------------------------------------------------------------- flash

    /** A real SPI flash decodes only as many address bits as it has capacity for. */
    private fun at(address: Int) = Math.floorMod(address, flash.size)

    private fun readFlash(address: Int, length: Int) =
        ByteArray(length) { i -> flash[at(address + i)] }

    private fun writeFlash(address: Int, data: ByteArray, from: Int, length: Int) {
        for (i in 0 until length) {
            val target = address + i
            if (flashFailsAbove != null && target >= flashFailsAbove) continue
            flash[at(target)] = data[from + i]
        }
    }

    private fun eraseFlash(address: Int, length: Int) {
        for (i in 0 until length) flash[at(address + i)] = ERASED
    }

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
        if (wedged) return  // the real ROM's SLIP parser is corrupted; it never answers again
        require(packet[0].toInt() == 0x00) { "not a request" }
        val op = packet[1].toInt() and 0xFF
        val size = le16(packet, 2)
        val body = packet.copyOfRange(8, 8 + size)
        commands += op

        when (op) {
            ESP_SYNC -> {
                syncCommandsReceived++
                if (wedgesOnRepeatedSync && syncCommandsReceived > 1) {
                    wedged = true
                } else {
                    repeat(8) { reply(op) }   // the real ROM echoes a sync eight times
                }
            }

            ESP_READ_REG -> reply(op, value = readRegister(le32(body, 0)))

            ESP_WRITE_REG -> {
                writeRegister(le32(body, 0), le32(body, 4))
                reply(op)
            }

            ESP_SPI_ATTACH, ESP_SPI_SET_PARAMS, ESP_MEM_BEGIN, ESP_MEM_DATA -> reply(op)

            ESP_CHANGE_BAUDRATE -> {
                baudChanges += le32(body, 0) to le32(body, 4)
                reply(op)
            }

            ESP_MEM_END -> {
                // A real ROM given a non-zero entrypoint jumps into it instead of
                // replying cleanly to this command; the newly-running stub then sends
                // its own unwrapped "OHAI" greeting (EspLoader.runStub() ignores a
                // MEM_END failure/timeout for exactly this reason).
                val entrypoint = le32(body, 4)
                if (entrypoint != 0) {
                    stubActive = true
                    sendRawFrame("OHAI".toByteArray(Charsets.US_ASCII))
                } else {
                    reply(op)
                }
            }

            ESP_FLASH_DEFL_BEGIN -> {
                // Length is checked exactly as the real devices check it, because getting it
                // wrong is not forgiven: the flasher stub calls verify_data_len(command, 16)
                // and rejects anything longer, and the ESP32 ROM predates the trailing
                // "encrypted write" word too. Only the ROM of a newer chip wants 20 bytes.
                deflBeginWordCount = size / 4
                val wanted = if (!stubActive && chip.romSupportsEncryptedFlash) 5 else 4
                if (deflBeginWordCount != wanted) {
                    // A real stub reports this as ESP_BAD_DATA_LEN, which its `||` chain
                    // collapses to a bare 1 - the "status 0x01, error 0x00" seen on hardware.
                    reply(op, failed = true)
                    return
                }
                // What a genuinely write-protected M5Stack does: reads answer fine, every
                // erase is refused.
                if ((sr1 and SR1_BP) != 0) {
                    reply(op, failed = true)
                    return
                }

                val eraseSize = le32(body, 0)
                region = le32(body, 12)
                eraseFlash(region, eraseSize)

                // A fresh zlib stream: this is what makes restarting an image safe, and
                // what re-sending a single block would corrupt.
                inflater = Inflater()
                regionWritten = 0
                regionImage = ByteArrayOutputStream()
                written.remove(region)
                reply(op)
            }

            ESP_FLASH_DEFL_DATA -> {
                val length = le32(body, 0)
                val seq = le32(body, 4)
                inflateIntoFlash(body.copyOfRange(16, 16 + length))
                if (seq == dropAckForBlock && !ackDropped) {
                    ackDropped = true   // written, but the loader will never hear about it
                    return
                }
                reply(op)
            }

            ESP_FLASH_DEFL_END -> reply(op)

            ESP_SPI_FLASH_MD5 -> {
                val address = le32(body, 0)
                val length = le32(body, 4)
                val digest = MessageDigest.getInstance("MD5").digest(readFlash(address, length))
                md5Replies++
                if (md5Replies <= garbledMd5Replies) digest[0] = (digest[0] + 1).toByte()
                // The ROM answers with the digest as 32 ASCII hex characters; the stub
                // answers with the raw 16-byte digest.
                val payload = if (stubActive) {
                    digest
                } else {
                    digest.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
                }
                reply(op, payload = payload)
            }

            else -> throw AssertionError("fake ROM got an unexpected command 0x%02X".format(op))
        }
    }

    /** Feeds one compressed block into the region's zlib stream and stores what comes out. */
    private fun inflateIntoFlash(block: ByteArray) {
        inflater.setInput(block)
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = inflater.inflate(chunk)
            if (n == 0) break   // needs more input, or the stream ended
            writeFlash(region + regionWritten, chunk, 0, n)
            regionImage.write(chunk, 0, n)
            regionWritten += n
        }
        written[region] = regionImage.toByteArray()
    }

    private fun readRegister(address: Int): Int = when (address) {
        Chip.CHIP_DETECT_MAGIC_REG_ADDR -> chip.magicValues.first()
        else -> registers[address] ?: 0
    }

    private fun writeRegister(address: Int, value: Int) {
        registers[address] = value
        // Kicking SPI_CMD_USR runs whatever command was queued in the SPI registers.
        if (address == chip.spiRegBase && (value and SPI_CMD_USR) != 0) {
            registers[chip.spiRegBase] = 0          // the command completes immediately
            runSpiFlashCommand()
        }
    }

    /** The SPI flash itself, as driven through the ESP32's SPI peripheral registers. */
    private fun runSpiFlashCommand() {
        val w0Reg = chip.spiRegBase + chip.spiW0Offs
        val usr = registers[chip.spiRegBase + chip.spiUsrOffs] ?: 0
        val command = (registers[chip.spiRegBase + chip.spiUsr2Offs] ?: 0) and 0xFF
        val outgoing = registers[w0Reg] ?: 0
        val outgoingBits = (registers[chip.spiRegBase + chip.spiMosiDlenOffs] ?: 0) + 1

        when (command) {
            SPIFLASH_RDID -> registers[w0Reg] = jedecId
            SPIFLASH_RDSR -> registers[w0Reg] = sr1
            SPIFLASH_RDSR2 -> registers[w0Reg] = sr2

            // The write-enable latch: a status-register write without it is ignored, and it
            // is cleared by the write it authorises.
            SPIFLASH_WREN -> writeEnabled = true

            SPIFLASH_WRSR -> {
                if (writeEnabled && (usr and SPI_USR_MOSI) != 0 && !refusesToUnlock) {
                    sr1 = outgoing and 0xFF
                    // A one-byte write leaves SR2 alone; only a 16-bit one reaches it.
                    if (outgoingBits >= 16) sr2 = (outgoing shr 8) and 0xFF
                }
                writeEnabled = false
            }
        }
    }

    /** Trailing status bytes of zero mean success; a leading 1 means the command failed. */
    private fun reply(op: Int, value: Int = 0, payload: ByteArray = ByteArray(0), failed: Boolean = false) {
        // The ROM's status-byte count is chip-specific, but once the stub is running,
        // every chip uses 2 (mirrors EspLoader.runStub() setting statusBytes = 2).
        val effectiveStatusBytes = if (stubActive) 2 else statusBytes
        val status = ByteArray(effectiveStatusBytes)
        if (failed) status[0] = 1
        val body = payload + status
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
        sendRawFrame(packet)
    }

    /** SLIP-encodes and enqueues [data] verbatim - used for replies and the stub's greeting. */
    private fun sendRawFrame(data: ByteArray) {
        outgoing.addLast(SLIP_END.toByte())
        for (byte in data) {
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

    private fun le16(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val ERASED = 0xFF.toByte()

        const val SLIP_END = 0xC0
        const val SLIP_ESC = 0xDB
        const val SLIP_ESC_END = 0xDC
        const val SLIP_ESC_ESC = 0xDD

        const val SPI_CMD_USR = 1 shl 18
        const val SPI_USR_MOSI = 1 shl 27

        const val SPIFLASH_WRSR = 0x01
        const val SPIFLASH_RDSR = 0x05
        const val SPIFLASH_WREN = 0x06
        const val SPIFLASH_RDID = 0x9F
        const val SPIFLASH_RDSR2 = 0x35

        /** BP1 | BP2: two of SR1's block-protection bits, enough to lock the chip. */
        const val SR1_BP = 0x0C

        /** SR2's quad-enable bit, which the board needs to keep booting in QIO mode. */
        const val SR2_QE = 0x02

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
