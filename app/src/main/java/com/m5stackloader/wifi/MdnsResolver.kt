/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.wifi

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Resolves an mDNS ".local" hostname to an IP address by sending our own query, rather than
 * relying on the OS/browser to do it. M5_NightscoutMon calls only `MDNS.begin()` - no NetBIOS,
 * no `MDNS.addService()` - so a bare hostname never resolves on Android (no NetBIOS/LLMNR
 * client) and there is no advertised service for NsdManager to find. `.local` resolution via
 * Android's own resolver is otherwise only reliable from API 31 on.
 *
 * The query is a "legacy one-shot" (RFC 6762 section 6.7): sent from an ephemeral port, which
 * obliges the responder to reply *unicast* straight back to that port. This matters. A socket
 * on port 5353 never hears the answer on a phone: unicast replies to 5353 are delivered to
 * Android's own mDNS daemon that co-owns the port, and multicast replies are dropped by Wi-Fi
 * chips' multicast filters even under a MulticastLock. Verified against a live device: the
 * same query answered instantly from an ephemeral port and got silence from 5353.
 */
object MdnsResolver {

    private const val TAG = "MdnsResolver"
    private const val MDNS_PORT = 5353
    private const val MDNS_GROUP = "224.0.0.251"
    private const val ATTEMPTS = 3
    private const val ATTEMPT_TIMEOUT_MS = 1000

    /** Builds a legacy one-shot mDNS query for "<hostname>.local", type A, class IN. */
    internal fun buildQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0)) // ID (responders echo it; we don't need to check)
        out.write(byteArrayOf(0, 0)) // flags: standard query
        out.write(byteArrayOf(0, 1)) // QDCOUNT = 1
        out.write(byteArrayOf(0, 0)) // ANCOUNT
        out.write(byteArrayOf(0, 0)) // NSCOUNT
        out.write(byteArrayOf(0, 0)) // ARCOUNT
        out.write(encodeQName("$hostname.local"))
        out.write(byteArrayOf(0, 1)) // QTYPE = A
        out.write(byteArrayOf(0, 1)) // QCLASS = IN (no QU bit: from an ephemeral port the
        //                              reply is unicast by spec, no need to ask)
        return out.toByteArray()
    }

    /**
     * Extracts the first A record for "<hostname>.local" from a received mDNS packet.
     * Returns null for anything malformed, truncated, or not naming our host - untrusted
     * network input, so no assumption about the sender is trusted. Name comparison is
     * case-insensitive: the firmware answers "m5ns" queries with "M5NS.local".
     */
    internal fun parseResponse(packet: ByteArray, length: Int, hostname: String): InetAddress? {
        return try {
            if (length < 12) return null
            val questionCount = readUShort(packet, 4)
            val answerCount = readUShort(packet, 6)
            var offset = 12

            repeat(questionCount) {
                val (_, after) = decodeName(packet, offset)
                offset = after + 4 // QTYPE + QCLASS
            }

            val targetName = "$hostname.local"
            repeat(answerCount) {
                val (name, afterName) = decodeName(packet, offset)
                offset = afterName
                val type = readUShort(packet, offset); offset += 2
                offset += 2 // class
                offset += 4 // ttl
                val rdLength = readUShort(packet, offset); offset += 2
                if (type == 1 && rdLength == 4 && offset + 4 <= length &&
                    name.equals(targetName, ignoreCase = true)
                ) {
                    return InetAddress.getByAddress(packet.copyOfRange(offset, offset + 4))
                }
                offset += rdLength
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Queries for [hostname] on [network] (expected to be the phone's Wi-Fi network) and
     * returns its address, or null if nothing answered within a few short retries.
     */
    suspend fun resolve(context: Context, network: Network, hostname: String): InetAddress? =
        withContext(Dispatchers.IO) {
            // Only the outbound multicast leg could be affected by a Wi-Fi chip's multicast
            // gating; the reply comes back unicast. Held anyway - it is cheap and some OEM
            // stacks are eccentric about multicast in either direction.
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("m5stackloader-mdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            try {
                resolveOnce(network, hostname)
            } finally {
                lock?.release()
            }
        }

    private fun resolveOnce(network: Network, hostname: String): InetAddress? {
        return try {
            val socket = DatagramSocket(0) // ephemeral port: the whole point, see class doc
            try {
                // Routes the query out the Wi-Fi interface even when mobile data is the
                // default network - the Android equivalent of binding to the interface IP.
                network.bindSocket(socket)
                socket.soTimeout = ATTEMPT_TIMEOUT_MS

                val group = InetAddress.getByName(MDNS_GROUP)
                val query = buildQuery(hostname)
                val queryPacket = DatagramPacket(query, query.size, group, MDNS_PORT)
                val buffer = ByteArray(512)
                val responsePacket = DatagramPacket(buffer, buffer.size)

                repeat(ATTEMPTS) { attempt ->
                    socket.send(queryPacket)
                    val deadline = System.currentTimeMillis() + ATTEMPT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            socket.receive(responsePacket)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val address = parseResponse(buffer, responsePacket.length, hostname)
                        if (address != null) return address
                    }
                    Log.d(TAG, "no answer for $hostname.local (attempt ${attempt + 1}/$ATTEMPTS)")
                }
                null
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "mDNS query for $hostname.local failed", e)
            null
        }
    }

    private fun encodeQName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun readUShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /** Decodes a possibly-compressed DNS name starting at [offsetIn]; follows 0xC0 pointers. */
    private fun decodeName(data: ByteArray, offsetIn: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var offset = offsetIn
        var returnOffset = -1
        var jumps = 0
        while (true) {
            val len = data[offset].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (returnOffset == -1) returnOffset = offset + 1
                    break
                }
                (len and 0xC0) == 0xC0 -> {
                    val pointer = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (returnOffset == -1) returnOffset = offset + 2
                    if (++jumps > 10) break // guard against a malicious/looping pointer chain
                    offset = pointer
                }
                else -> {
                    labels.add(String(data, offset + 1, len, Charsets.US_ASCII))
                    offset += 1 + len
                }
            }
        }
        return labels.joinToString(".") to returnOffset
    }
}
