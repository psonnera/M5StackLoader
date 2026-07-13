/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the SSID of the Wi-Fi network the phone is currently on, so the flashing
 * screen can prefill it. Every supported API level requires ACCESS_FINE_LOCATION to
 * read this (the OS treats SSID as location-adjacent data) even though nothing here
 * touches actual location; a missing/denied permission just means manual entry.
 */
object CurrentWifi {

    private const val TIMEOUT_MS = 2_000L

    suspend fun ssid(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ssidViaNetworkCallback(context)
        } else {
            ssidViaWifiManager(context)
        }
        return normalize(raw)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun ssidViaNetworkCallback(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback(
                    FLAG_INCLUDE_LOCATION_INFO,
                ) {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return
                        if (continuation.isActive) continuation.resume(wifiInfo.ssid)
                        runCatching { connectivityManager.unregisterNetworkCallback(this) }
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
                connectivityManager.registerDefaultNetworkCallback(callback)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ssidViaWifiManager(context: Context): String? {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifiManager?.connectionInfo?.ssid
    }

    /** Strips the quotes Android wraps SSIDs in and filters out the "not connected" sentinels. */
    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val unquoted = raw.removeSurrounding("\"")
        if (unquoted.isEmpty() || unquoted == "<unknown ssid>" || unquoted == "0x") return null
        return unquoted
    }
}
