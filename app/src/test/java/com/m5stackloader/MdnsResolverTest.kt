package com.m5stackloader

import com.m5stackloader.wifi.MdnsResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Exercises the mDNS query/response codec in isolation from any real socket. Both fixtures
 * come from a live exchange with an M5_NightscoutMon device: [REAL_QUERY] is the exact query
 * that device answered, and [REAL_REPLY] is its verbatim 54-byte unicast answer resolving
 * m5ns.local to 192.168.1.199. A codec change that breaks against real firmware output fails
 * here, not in the field.
 */
class MdnsResolverTest {

    private companion object {
        /** The legacy one-shot query (ephemeral source port, QCLASS plain IN) a live device answered. */
        val REAL_QUERY = hex(
            "00 00 00 00 00 01 00 00 00 00 00 00 04 6d 35 6e 73 " +
                "05 6c 6f 63 61 6c 00 00 01 00 01"
        )

        /**
         * The device's actual unicast reply, captured verbatim: question echoed, then an answer
         * whose name is written out in full as "M5NS.local" - uppercase, so case-insensitive
         * matching is load-bearing - with TTL 120 and RDATA 192.168.1.199.
         */
        val REAL_REPLY = hex(
            "00 00 84 00 00 01 00 01 00 00 00 00 04 6d 35 6e 73 05 6c 6f 63 61 6c 00 " +
                "00 01 00 01 04 4d 35 4e 53 05 6c 6f 63 61 6c 00 00 01 00 01 00 00 00 78 " +
                "00 04 c0 a8 01 c7"
        )

        fun hex(s: String): ByteArray =
            s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun `buildQuery matches the query a real device answered on the LAN`() {
        assertArrayEquals(REAL_QUERY, MdnsResolver.buildQuery("m5ns"))
    }

    @Test
    fun `parseResponse decodes the real captured device reply, uppercase name and all`() {
        val address = MdnsResolver.parseResponse(REAL_REPLY, REAL_REPLY.size, "m5ns")

        assertEquals("192.168.1.199", address?.hostAddress)
    }

    @Test
    fun `parseResponse follows a name-compression pointer to find the A record`() {
        val packet = responseBuilder(includeQuestion = true) {
            // Answer name is a pointer back to the question's name at offset 12.
            writeBytes(0xC0, 0x0C)
            writeAnswerBody(ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 199.toByte()))
        }

        val address = MdnsResolver.parseResponse(packet, packet.size, "m5ns")

        assertEquals("192.168.1.199", address?.hostAddress)
    }

    @Test
    fun `parseResponse ignores the transaction ID a legacy responder echoes back`() {
        val packet = responseBuilder(includeQuestion = true) {
            writeBytes(0xC0, 0x0C)
            writeAnswerBody(ip = byteArrayOf(10, 0, 0, 7))
        }
        packet[0] = 0xAB.toByte() // nonzero echoed ID must not confuse the parser
        packet[1] = 0xCD.toByte()

        val address = MdnsResolver.parseResponse(packet, packet.size, "m5ns")

        assertEquals("10.0.0.7", address?.hostAddress)
    }

    @Test
    fun `parseResponse returns null for a truncated packet`() {
        val packet = REAL_REPLY.copyOf(20) // cut off mid-question

        val address = MdnsResolver.parseResponse(packet, packet.size, "m5ns")

        assertNull(address)
    }

    @Test
    fun `parseResponse returns null when no answer names the requested host`() {
        val packet = responseBuilder(includeQuestion = false) {
            writeName("some-other-device.local")
            writeAnswerBody(ip = byteArrayOf(10, 0, 0, 1))
        }

        val address = MdnsResolver.parseResponse(packet, packet.size, "m5ns")

        assertNull(address)
    }

    /**
     * Builds a minimal mDNS response: header, optionally the mirrored question for "m5ns.local",
     * then whatever the answer-writing [block] appends via the given [AnswerScope].
     */
    private fun responseBuilder(includeQuestion: Boolean, block: AnswerScope.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0)) // ID
        out.write(byteArrayOf(0x84.toByte(), 0)) // flags: response, authoritative
        out.write(byteArrayOf(0, if (includeQuestion) 1 else 0)) // QDCOUNT
        out.write(byteArrayOf(0, 1)) // ANCOUNT
        out.write(byteArrayOf(0, 0)) // NSCOUNT
        out.write(byteArrayOf(0, 0)) // ARCOUNT
        if (includeQuestion) {
            AnswerScope(out).writeName("m5ns.local")
            out.write(byteArrayOf(0, 1)) // QTYPE = A
            out.write(byteArrayOf(0, 1)) // QCLASS = IN
        }
        AnswerScope(out).block()
        return out.toByteArray()
    }

    private class AnswerScope(private val out: ByteArrayOutputStream) {
        fun writeBytes(vararg bytes: Int) = out.write(bytes.map { it.toByte() }.toByteArray())

        fun writeName(name: String) {
            for (label in name.split(".")) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
        }

        fun writeAnswerBody(ip: ByteArray) {
            out.write(byteArrayOf(0, 1)) // TYPE = A
            out.write(byteArrayOf(0, 1)) // CLASS = IN
            out.write(byteArrayOf(0, 0, 0, 120)) // TTL = 120s
            out.write(byteArrayOf(0, 4)) // RDLENGTH = 4
            out.write(ip)
        }
    }
}
