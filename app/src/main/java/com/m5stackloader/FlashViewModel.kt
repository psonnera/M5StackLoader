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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m5stackloader.esp.Chip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
     * as the firmware's own saveConfigToFlash() does.
     */
    private fun buildWifiNvsImage(partitionSize: Int, wifi: WifiCredentials): ByteArray {
        val strings = buildList {
            add(NvsImage.Entry("SSID0", wifi.ssid))
            if (wifi.password.isNotEmpty()) add(NvsImage.Entry("PASS0", wifi.password))
        }
        return NvsImage.build(partitionSize, WIFI_NVS_NAMESPACE, strings)
    }

    fun onDeviceDetached() {
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

    private companion object {
        const val PLUG_IN_PROMPT = "Plug your M5Stack into this phone with a USB cable."
        const val MAX_LOG_LINES = 200
        const val WIFI_NVS_NAMESPACE = "M5NSconfig"
    }
}
