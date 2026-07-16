/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m5stackloader.esp.Chip
import com.m5stackloader.esp.DeviceName
import com.m5stackloader.esp.EspLoader
import com.m5stackloader.esp.FlashSizes
import com.m5stackloader.esp.NvsImage
import com.m5stackloader.esp.StubFlasher
import com.m5stackloader.esp.UsbDevices
import com.m5stackloader.esp.UsbSerialTransport
import com.m5stackloader.firmware.DeviceModel
import com.m5stackloader.firmware.FirmwareRepository
import com.m5stackloader.firmware.FirmwareVariant
import com.m5stackloader.firmware.PartitionTable
import com.m5stackloader.wifi.MdnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UiState {
    /** Nothing plugged in, or waiting for the user to allow access to it. */
    data class WaitingForDevice(val message: String) : UiState

    data class Busy(val message: String, val percent: Int? = null) : UiState

    /** Device identified and firmware chosen; one tap away from flashing. */
    data class Ready(
        val model: String,
        val chipAndFlash: String,
        val firmwareName: String,
        val firmwareVersion: String,
    ) : UiState

    data class Done(val model: String, val wifiConfigured: Boolean = false) : UiState

    data class Failed(val message: String) : UiState
}

/** What the user typed on the Ready screen; null means "don't touch Wi-Fi". */
data class WifiCredentials(val ssid: String, val password: String)

class FlashViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UiState>(UiState.WaitingForDevice(PLUG_IN_PROMPT))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val repository = FirmwareRepository(File(application.cacheDir, "firmware"))

    private var transport: UsbSerialTransport? = null
    private var loader: EspLoader? = null
    private var job: Job? = null

    // What detection found, held for the flash step.
    private var chip: Chip? = null
    private var flashSize = 0
    private var variant: FirmwareVariant? = null
    private var deviceMac: ByteArray? = null
    private var modelName = ""

    /** Identifies the attached device and works out which firmware it needs. */
    fun startDetection(device: UsbDevice) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                _state.value = UiState.Busy("Connecting to the device...")
                _log.value = emptyList()

                val espLoader = withContext(Dispatchers.IO) { openDevice(device) }

                _state.value = UiState.Busy("Identifying the device...")
                val detectedChip = withContext(Dispatchers.IO) {
                    espLoader.connect()
                    val c = espLoader.detectChip()
                    espLoader.spiAttach()
                    espLoader.detectFlashSize()
                    c
                }
                chip = detectedChip
                flashSize = espLoader.flashSize
                modelName = DeviceModel.describe(detectedChip, flashSize)

                // Used to give the device a unique name (see DeviceName); non-fatal if it
                // fails, we just fall back to the legacy shared "m5ns" name.
                deviceMac = withContext(Dispatchers.IO) {
                    runCatching { espLoader.readMac() }
                        .onFailure { note("Could not read the device's MAC address: ${it.message}") }
                        .getOrNull()
                }

                _state.value = UiState.Busy("Looking up the firmware...")
                val variants = repository.fetchManifest()
                val selected = DeviceModel.select(detectedChip, flashSize, variants)
                variant = selected
                note("Firmware: ${selected.name} (${selected.version})")

                _state.value = UiState.Ready(
                    model = modelName,
                    chipAndFlash = "${detectedChip.displayName}, ${FlashSizes.format(flashSize)} flash",
                    firmwareName = selected.name,
                    firmwareVersion = selected.version,
                )
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Downloads the binaries and burns them. Only called after the user confirms. */
    fun flash(wifi: WifiCredentials? = null) {
        val espLoader = loader ?: return
        val selected = variant ?: return
        val target = chip ?: return
        if (job?.isActive == true) return

        job = viewModelScope.launch {
            try {
                _state.value = UiState.Busy("Downloading firmware...")
                val parts = repository.fetchBinaries(selected) { downloaded, done, total ->
                    _state.value = UiState.Busy("Downloading firmware ($done/$total)...")
                    note("Downloaded file $done of $total (${downloaded / 1024} KB)")
                }

                val nvsPartition = PartitionTable.findNvs(parts.firstOrNull { it.offset == 0x8000 }?.bytes)
                val wifiImage = wifi?.let { buildWifiNvsImage(nvsPartition.size, it) }

                val totalBytes = parts.sumOf { it.bytes.size } + (wifiImage?.size ?: 0)
                var writtenBefore = 0

                withContext(Dispatchers.IO) {
                    _state.value = UiState.Busy("Preparing the device...", 0)

                    // Start from a chip we have just reset, not from whatever state detection
                    // left it in: the Ready screen can sit for minutes, and anything detection
                    // did to the ROM would otherwise be inherited by the burn.
                    espLoader.connect()
                    espLoader.detectChip()

                    // The stub is Espressif's own fast flasher; if it refuses to start we can
                    // still flash with the ROM bootloader, just more slowly. But we cannot
                    // simply carry on: by the time the stub fails to announce itself, the ROM
                    // has already jumped into it, and a half-started stub answers in a
                    // different dialect than the ROM does. Reset the chip back into its ROM
                    // bootloader so the fallback starts from a state we understand.
                    try {
                        val stub = StubFlasher.load(getApplication<Application>().assets, target)
                        espLoader.runStub(stub)
                    } catch (e: Exception) {
                        note("Falling back to the slower ROM flasher: ${e.message}")
                        espLoader.connect()
                        espLoader.detectChip()
                    }

                    espLoader.changeBaud(EspLoader.ESP_FLASH_BAUD)
                    espLoader.spiAttach()
                    espLoader.setFlashParameters(flashSize)

                    // A flash whose block-protection bits are set refuses every erase, and no
                    // retry gets past it. Check before writing rather than after failing.
                    espLoader.unlockFlash()

                    for (part in parts) {
                        espLoader.writeFlash(part.bytes, part.offset) { written, _ ->
                            val overall = (writtenBefore + written) * 100 / totalBytes
                            _state.value = UiState.Busy("Writing ${part.fileName}...", overall)
                        }
                        writtenBefore += part.bytes.size
                    }

                    if (wifiImage != null) {
                        note("Writing Wi-Fi credentials for network \"${wifi.ssid}\".")
                        espLoader.writeFlash(wifiImage, nvsPartition.offset) { written, _ ->
                            val overall = (writtenBefore + written) * 100 / totalBytes
                            _state.value = UiState.Busy("Writing Wi-Fi settings...", overall)
                        }
                    }

                    espLoader.finishFlash()
                    espLoader.hardReset()
                }

                _state.value = UiState.Done(modelName, wifiConfigured = wifiImage != null)
            } catch (e: Exception) {
                fail(e)
            } finally {
                closeDevice()
            }
        }
    }

    /**
     * The firmware reads Wi-Fi from Preferences namespace "M5NSconfig", keys SSID0/PASS0
     * (M5NSconfig.cpp in M5_NightscoutMon) - PASS0 is omitted for an open network, exactly
     * as the firmware's own saveConfigToFlash() does. Also writes "device_name" (same
     * namespace, same file) so even a device still running older firmware picks up its
     * unique name - see DeviceName.
     */
    private fun buildWifiNvsImage(partitionSize: Int, wifi: WifiCredentials): ByteArray {
        val strings = buildList {
            add(NvsImage.Entry("SSID0", wifi.ssid))
            if (wifi.password.isNotEmpty()) add(NvsImage.Entry("PASS0", wifi.password))
            deviceMac?.let { add(NvsImage.Entry("device_name", DeviceName.deviceName(it))) }
        }
        return NvsImage.build(partitionSize, WIFI_NVS_NAMESPACE, strings)
    }

    /**
     * The hostname (without ".local") we expect this device to answer to: derived from its
     * MAC (see DeviceName) when we could read one, otherwise the legacy shared name every
     * device used before per-device names existed.
     */
    fun expectedHostname(): String = deviceMac?.let(DeviceName::hostname) ?: LEGACY_CONFIG_HOSTNAME

    /**
     * The M5Stack's own config web UI, reachable once it has rejoined Wi-Fi with the
     * credentials we just wrote. M5_NightscoutMon advertises only mDNS (no NetBIOS, no
     * `MDNS.addService`), so a bare hostname never resolves on Android and there's no
     * service for NsdManager to discover - we resolve the hostname ourselves and return the
     * IP to open. Polls for a while since the reboot-and-associate dance takes a few seconds,
     * and is bound to the phone's own Wi-Fi network so it works even when mobile data is also
     * active.
     *
     * Tries the derived per-device hostname first, then falls back to the legacy shared
     * "m5ns" name so a device still running firmware from before per-device names is still
     * found (e.g. if we flashed it without Wi-Fi credentials, so it never got a device_name
     * write - see buildWifiNvsImage).
     */
    suspend fun configSiteUrl(): String? = withContext(Dispatchers.IO) {
        val connectivityManager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return@withContext null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val wifiNetwork = activeNetwork.takeIf {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return@withContext null

        val candidates = listOf(expectedHostname(), LEGACY_CONFIG_HOSTNAME).distinct()
        note("Looking for ${candidates.joinToString(" or ") { "$it.local" }} on the Wi-Fi network...")
        repeat(CONFIG_PROBE_ATTEMPTS) { attempt ->
            for (hostname in candidates) {
                // Our own resolver first; then, belt and braces, the OS resolver (Android 12+
                // can often do .local itself). Logged distinctly so a bug in our own resolver
                // stays visible in the log instead of being silently masked.
                val address = MdnsResolver.resolve(getApplication(), wifiNetwork, hostname)
                    ?.also { note("Found the device at ${it.hostAddress}.") }
                    ?: systemResolve(wifiNetwork, hostname)
                        ?.also { note("Found the device at ${it.hostAddress} (via the system resolver).") }

                if (address != null) {
                    if (probeConfigSite(wifiNetwork, address)) {
                        return@withContext "http://${address.hostAddress}/"
                    }
                    note("The device is not serving its web page yet, retrying...")
                }
            }
            if ((attempt + 1) % 5 == 0) {
                note("Still looking (attempt ${attempt + 1} of $CONFIG_PROBE_ATTEMPTS)...")
            }
            if (attempt < CONFIG_PROBE_ATTEMPTS - 1) delay(CONFIG_PROBE_INTERVAL_MS)
        }
        note("Could not find the device on the network.")
        null
    }

    private fun systemResolve(network: android.net.Network, hostname: String): java.net.InetAddress? = try {
        network.getAllByName("$hostname.local").firstOrNull { it is java.net.Inet4Address }
    } catch (e: Exception) {
        null
    }

    /**
     * "Is the device's web server up?" as a bare TCP connect to port 80. Deliberately not an
     * HTTP request: this app targets API 28+, where Android's default policy blocks cleartext
     * http:// from app code (so HttpURLConnection would fail unconditionally - the page still
     * opens fine in the browser, which has its own policy), and a TCP accept is also more
     * forgiving than a short HTTP timeout against the ESP32's slow web server.
     */
    private fun probeConfigSite(
        network: android.net.Network,
        address: java.net.InetAddress,
    ): Boolean = try {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(java.net.InetSocketAddress(address, 80), CONFIG_PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun onDeviceDetached() {
        // The Done screen invites the user to unplug; doing so must not wipe it (the
        // config-site offer is still in flight). Plugging a device back in starts a
        // fresh detection cycle from Done just as it would from WaitingForDevice.
        if (_state.value is UiState.Done) {
            closeDevice()
            return
        }
        job?.cancel()
        closeDevice()
        _state.value = UiState.WaitingForDevice(PLUG_IN_PROMPT)
    }

    fun onPermissionDenied() {
        _state.value = UiState.WaitingForDevice(
            "Access to the USB device was denied. Unplug it, plug it back in, and allow access."
        )
    }

    fun reset() {
        job?.cancel()
        closeDevice()
        _state.value = UiState.WaitingForDevice(PLUG_IN_PROMPT)
    }

    private fun openDevice(device: UsbDevice): EspLoader {
        closeDevice()

        val manager = getApplication<Application>()
            .getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbDevices.driverFor(manager, device)
            ?: throw IllegalStateException("This USB device is not a supported M5Stack serial port.")
        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Could not open the USB device. Unplug it and try again.")

        val newTransport = UsbSerialTransport(driver.ports.first(), device, connection)
        newTransport.open(EspLoader.ESP_ROM_BAUD)
        transport = newTransport

        return EspLoader(newTransport) { line -> note(line) }.also { loader = it }
    }

    private fun closeDevice() {
        transport?.close()
        transport = null
        loader = null
    }

    private fun fail(e: Exception) {
        val message = e.message ?: e.toString()
        note("Error: $message")
        _state.value = UiState.Failed(message)
    }

    private fun note(line: String) {
        _log.value = (_log.value + line).takeLast(MAX_LOG_LINES)
    }

    override fun onCleared() {
        closeDevice()
        super.onCleared()
    }

    companion object {
        // The name every device used before per-device names existed (M5NSconfig.cpp's old
        // fixed "M5NS" default); it's what MDNS.begin() registers as "<deviceName>.local".
        // Kept as a discovery fallback for devices still running old firmware. A device
        // renamed in its own config UI won't be found by either name - same limitation the
        // old hardcoded URL had.
        private const val LEGACY_CONFIG_HOSTNAME = "m5ns"

        private const val PLUG_IN_PROMPT = "Plug your M5Stack into this phone with a USB cable."
        private const val MAX_LOG_LINES = 200
        private const val WIFI_NVS_NAMESPACE = "M5NSconfig"
        private const val CONFIG_PROBE_ATTEMPTS = 15
        private const val CONFIG_PROBE_INTERVAL_MS = 2000L
        private const val CONFIG_PROBE_TIMEOUT_MS = 2000
    }
}
