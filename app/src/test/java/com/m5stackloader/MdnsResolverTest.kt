package com.m5stackloader

import com.m5stackloader.wifi.MdnsResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Exercises the mDNS query/response codec in isolation from any real socket. [buildQuery] is
 * checked against bytes actually captured from a query this app sent and the M5Stack answered
 * on a live LAN; [parseResponse] is checked against the real IP that query resolved to
 * (192.168.1.199), so a change here that silently breaks resolution against real firmware
 * output should show up as a test failure, not a field report.
 */
class MdnsResolverTest {

    @Test
    fun `buildQuery matches bytes a real device answered on the LAN`() {
        // Captured with Resolve-DnsName/manual mDNS probe against a live M5_NightscoutMon
        // device (see plan history) - 04 "m5ns" 05 "local" 00, QTYPE=A, QCLASS=IN with QU bit.
        val expected = hex(
            "00 00 00 00 00 01 00 00 00 00 00 00 04 6d 35 6e 73 " +
                "05 6c 6f 63 61 6c 00 00 01 80 01"
        )

        val query = MdnsResolver.buildQuery("m5ns")

        assertArrayEquals(expected, query)
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
    fun `parseResponse finds the A record when the answer name is written out in full`() {
        val packet = responseBuilder(includeQuestion = false) {
            writeName("m5ns.local")
            writeAnswerBody(ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 199.toByte()))
        }

        val address = MdnsResolver.parseResponse(packet, packet.size, "m5ns")

        assertEquals("192.168.1.199", address?.hostAddress)
    }

    @Test
    fun `parseResponse returns null for a truncated packet`() {
        val packet = responseBuilder(includeQuestion = true) {
            writeBytes(0xC0, 0x0C)
            writeAnswerBody(ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 199.toByte()))
        }.copyOf(15) // cut off mid-answer

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

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

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
