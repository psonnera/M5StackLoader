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
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Resolves an mDNS ".local" hostname to an IP address by sending our own query over
 * multicast, rather than relying on the OS/browser to do it. M5_NightscoutMon calls only
 * `MDNS.begin()` - no NetBIOS, no `MDNS.addService()` - so a bare hostname never resolves on
 * Android (no NetBIOS/LLMNR client) and there is no advertised service for NsdManager to find.
 * `.local` resolution via Android's own resolver is otherwise only reliable from API 31 on.
 */
object MdnsResolver {

    private const val MDNS_PORT = 5353
    private const val MDNS_GROUP = "224.0.0.251"
    private const val ATTEMPTS = 3
    private const val ATTEMPT_TIMEOUT_MS = 300

    /** Builds a one-shot mDNS query for "<hostname>.local", type A, requesting a unicast reply. */
    internal fun buildQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0)) // ID
        out.write(byteArrayOf(0, 0)) // flags: standard query
        out.write(byteArrayOf(0, 1)) // QDCOUNT = 1
        out.write(byteArrayOf(0, 0)) // ANCOUNT
        out.write(byteArrayOf(0, 0)) // NSCOUNT
        out.write(byteArrayOf(0, 0)) // ARCOUNT
        out.write(encodeQName("$hostname.local"))
        out.write(byteArrayOf(0, 1)) // QTYPE = A
        // QCLASS = IN(1) with the top "QU" bit set: ask for a unicast reply where supported,
        // though we also join the multicast group so a plain multicast reply is caught too.
        out.write(byteArrayOf(0x80.toByte(), 1))
        return out.toByteArray()
    }

    /**
     * Extracts the first A record for "<hostname>.local" from a received mDNS packet.
     * Returns null for anything malformed, truncated, or not naming our host - untrusted
     * network input, so no assumption about the sender is trusted.
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
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("m5stackloader-mdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            try {
                resolveOnce(context, network, hostname)
            } finally {
                lock?.release()
            }
        }

    private fun resolveOnce(context: Context, network: Network, hostname: String): InetAddress? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val interfaceName = connectivityManager.getLinkProperties(network)?.interfaceName
                ?: return null
            val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null
            val group = InetAddress.getByName(MDNS_GROUP)
            val groupAddress = InetSocketAddress(group, MDNS_PORT)

            val socket = MulticastSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(MDNS_PORT))
                network.bindSocket(socket)
                socket.joinGroup(groupAddress, networkInterface)
                socket.soTimeout = ATTEMPT_TIMEOUT_MS

                val query = buildQuery(hostname)
                val queryPacket = DatagramPacket(query, query.size, group, MDNS_PORT)
                val buffer = ByteArray(512)
                val responsePacket = DatagramPacket(buffer, buffer.size)

                repeat(ATTEMPTS) {
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
                }
                null
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
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
