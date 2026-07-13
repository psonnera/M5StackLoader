/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.m5stackloader.databinding.ActivityMainBinding
import com.m5stackloader.esp.UsbDevices
import com.m5stackloader.wifi.CurrentWifi
import kotlinx.coroutines.launch

/**
 * One screen, one button. Plug in the M5Stack, confirm what was found, flash it.
 *
 * This activity owns the USB permission dance; [FlashViewModel] owns the flashing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: FlashViewModel by viewModels()

    private val usbManager: UsbManager by lazy {
        getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /** Guards the permission prompt + SSID prefill so they happen once per Ready visit. */
    private var wifiSetupAttempted = false

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) prefillSsid() }

    /** Fires when the user answers the system's "allow this app to access the device?" dialog. */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java,
                    )
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        viewModel.startDetection(device)
                    } else {
                        viewModel.onPermissionDenied()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectToAttachedDevice()

                UsbManager.ACTION_USB_DEVICE_DETACHED -> viewModel.onDeviceDetached()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        binding.wifiEnable.setOnCheckedChangeListener { _, checked ->
            binding.wifiSsidLayout.isEnabled = checked
            binding.wifiPasswordLayout.isEnabled = checked
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.log.collect { lines ->
                        binding.logView.text = lines.joinToString("\n")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Covers both launches: from the launcher with the device already plugged in, and
        // from the USB_DEVICE_ATTACHED intent when the user plugs it in.
        if (viewModel.state.value is UiState.WaitingForDevice) connectToAttachedDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connectToAttachedDevice()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    private fun connectToAttachedDevice() {
        val driver = UsbDevices.find(usbManager) ?: return
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            viewModel.startDetection(device)
        } else {
            requestPermission(device)
        }
    }

    private fun prefillSsid() {
        lifecycleScope.launch {
            val ssid = CurrentWifi.ssid(this@MainActivity) ?: return@launch
            if (binding.wifiSsid.text.isNullOrBlank()) {
                binding.wifiSsid.setText(ssid)
            }
        }
    }

    private fun onFlashClicked() = with(binding) {
        wifiPasswordLayout.error = null

        val ssid = wifiSsid.text?.toString()?.trim().orEmpty()
        val password = wifiPassword.text?.toString().orEmpty()
        val setUpWifi = wifiEnable.isChecked && ssid.isNotEmpty()

        if (setUpWifi && password.isNotEmpty() && password.length !in 8..63) {
            wifiPasswordLayout.error = getString(R.string.wifi_password_error)
            return@with
        }

        viewModel.flash(if (setUpWifi) WifiCredentials(ssid, password) else null)
    }

    private fun requestPermission(device: UsbDevice) {
        // FLAG_MUTABLE: the system fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
        // setPackage: required from Android 14 on, or the broadcast never comes back.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pending)
    }

    private fun render(state: UiState) = with(binding) {
        deviceCard.visibility = View.GONE
        wifiCard.visibility = View.GONE
        progress.visibility = View.GONE
        actionButton.visibility = View.GONE
        if (state !is UiState.Ready) wifiSetupAttempted = false

        when (state) {
            is UiState.WaitingForDevice -> {
                statusTitle.text = getString(R.string.app_name)
                statusDetail.text = state.message
            }

            is UiState.Busy -> {
                statusTitle.text = state.message
                statusDetail.text = ""
                progress.visibility = View.VISIBLE
                val percent = state.percent
                if (percent == null) {
                    progress.isIndeterminate = true
                } else {
                    progress.isIndeterminate = false
                    progress.setProgressCompat(percent, true)
                    statusDetail.text = "$percent%"
                }
            }

            is UiState.Ready -> {
                statusTitle.text = "Ready to flash"
                statusDetail.text = "Check this is right, then flash."
                deviceCard.visibility = View.VISIBLE
                deviceModel.text = state.model
                deviceChip.text = state.chipAndFlash
                firmwareName.text = "${state.firmwareName}\nversion ${state.firmwareVersion}"

                wifiCard.visibility = View.VISIBLE
                if (!wifiSetupAttempted) {
                    wifiSetupAttempted = true
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        prefillSsid()
                    } else {
                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                actionButton.visibility = View.VISIBLE
                actionButton.text = getString(R.string.flash_button)
                actionButton.setOnClickListener { onFlashClicked() }
            }

            is UiState.Done -> {
                statusTitle.text = "Done"
                statusDetail.text = buildString {
                    append("${state.model} has been flashed and is restarting. You can unplug it.")
                    if (state.wifiConfigured) append(" It will join your Wi-Fi network automatically.")
                }
            }

            is UiState.Failed -> {
                statusTitle.text = "Something went wrong"
                statusDetail.text = state.message
                actionButton.visibility = View.VISIBLE
                actionButton.text = getString(R.string.retry_button)
                actionButton.setOnClickListener {
                    viewModel.reset()
                    connectToAttachedDevice()
                }
            }
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.m5stackloader.USB_PERMISSION"
    }
}
