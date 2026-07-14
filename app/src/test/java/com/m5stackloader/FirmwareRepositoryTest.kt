package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.firmware.FirmwarePart
import com.m5stackloader.firmware.FirmwareRepository
import com.m5stackloader.firmware.FirmwareVariant
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FirmwareRepository talks to a real M5_NightscoutMon repository whose `master` branch is
 * pushed to several times a day under an unbumped version string - the whole point of its
 * caching being conditional (ETag) rather than "present = current" is that a stale on-disk
 * file must never be served once the server disagrees. A tiny raw-socket HTTP server lets
 * these tests prove that without hitting GitHub (Android's unit-test classpath does not
 * carry `com.sun.net.httpserver`, hence the hand-rolled server rather than that or a new
 * test dependency).
 */
class FirmwareRepositoryTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var server: FakeHttpServer
    private lateinit var repository: FirmwareRepository

    @Before
    fun start() {
        server = FakeHttpServer()
        repository = FirmwareRepository(tempDir.root, baseUrl = "http://127.0.0.1:${server.port}/Binaries")
    }

    @After
    fun stop() = server.close()

    private fun fetch(): ByteArray = runBlocking {
        val variant = FirmwareVariant(
            name = "test",
            version = "v1.0.0", // deliberately never bumped, like the real manifest
            path = "Variant",
            chip = Chip.ESP32,
            // Not named "*.ino.bin"/"*bootloader*" so validate() doesn't require ESP-image
            // magic - these tests are about caching, not image validation.
            parts = listOf(FirmwarePart(offset = 0x8000, fileName = "partitions.bin")),
        )
        repository.fetchBinaries(variant) { _, _, _ -> }.single().bytes
    }

    @Test
    fun `a first fetch downloads and caches the file`() {
        val bytes = fetch()
        assertArrayEquals(server.body, bytes)
        assertEquals(1, server.requestsServed)
    }

    @Test
    fun `an unchanged file is served from cache after one conditional request`() {
        fetch()
        server.requestsServed = 0

        val bytes = fetch()
        assertArrayEquals(server.body, bytes)
        assertEquals("should be a conditional request, not a full re-download", 1, server.requestsServed)
    }

    @Test
    fun `a file changed on the server under the same unbumped version is not served stale`() {
        // The exact bug this repository's caching was rewritten for: M5_NightscoutMon
        // pushes new binaries to master under a firmware.json version that does not change.
        val first = fetch()

        server.body = "v2 - changed on the server, same version string".toByteArray()
        server.etag = "\"v2\""

        val second = fetch()
        assertTrue("the new content must be served, not the stale cache", !second.contentEquals(first))
        assertArrayEquals(server.body, second)
    }

    @Test
    fun `an unreachable server falls back to the cache instead of failing the flash`() {
        val first = fetch()
        server.close()

        val second = fetch()
        assertArrayEquals(first, second)
    }
}

/** A minimal single-threaded HTTP/1.1 server: just enough GET + If-None-Match to test against. */
private class FakeHttpServer {
    private val socket = ServerSocket(0)
    var body = "v1".toByteArray()
    var etag = "\"v1\""
    var requestsServed = 0

    val port get() = socket.localPort

    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: Exception) { break }
            try {
                handle(client)
            } catch (e: Exception) {
                // A connection dropped mid-request (e.g. the "unreachable server" test
                // closing the socket) is not a test failure.
            }
        }
    }.apply { isDaemon = true; start() }

    private fun handle(client: Socket) {
        client.use {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            reader.readLine() ?: return // request line, e.g. "GET /Binaries/... HTTP/1.1"
            var ifNoneMatch: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val (name, value) = line.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
                if (name.equals("If-None-Match", ignoreCase = true)) ifNoneMatch = value
            }
            requestsServed++

            val out = client.getOutputStream()
            if (ifNoneMatch == etag) {
                out.write("HTTP/1.1 304 Not Modified\r\nETag: $etag\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write(
                    ("HTTP/1.1 200 OK\r\nETag: $etag\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(body)
            }
            out.flush()
        }
    }

    fun close() {
        socket.close()
        thread.join(1000)
    }
}
