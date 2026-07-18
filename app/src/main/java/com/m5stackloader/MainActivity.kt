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
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        // Keep content (notably the log) above the soft nav bar instead of under it.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

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
        binding.wifiPrivacyLink.setOnClickListener { showWifiPrivacyDialog() }

        binding.modeNightscout.setOnClickListener {
            viewModel.selectMode(FlashMode.NIGHTSCOUT)
            // Location is only used to prefill the Wi-Fi SSID, which only Nightscout needs -
            // so it's asked for here, on choosing Nightscout, not up front for every user.
            ensureSsidPrefill()
            connectToAttachedDevice()
        }
        binding.modeXdrip.setOnClickListener {
            viewModel.selectMode(FlashMode.XDRIP)
            connectToAttachedDevice()
        }
        binding.modeCustom.setOnClickListener { viewModel.selectMode(FlashMode.CUSTOM) }
        binding.customRepoContinue.setOnClickListener {
            viewModel.submitCustomRepo(binding.customRepoInput.text?.toString().orEmpty())
        }

        // Back from any flow returns to the opening chooser rather than leaving the app.
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.state.value is UiState.ChoosingMode) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            } else {
                viewModel.backToChooser()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var previous: UiState? = null
                    viewModel.state.collect { state ->
                        // The fixed sources start detection from their button handler; here we
                        // only cover the custom flow, which reaches WaitingForDevice from the
                        // async repository check (a Busy state) after the button is long gone.
                        if (state is UiState.WaitingForDevice && previous is UiState.Busy) {
                            connectToAttachedDevice()
                        }
                        previous = state
                        render(state)
                    }
                }
                launch {
                    viewModel.log.collect { lines ->
                        binding.logView.text = lines.joinToString("\n")
                        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
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
        val driver = UsbDevices.find(usbManager)

        // A device only matters once a firmware source is chosen and we're waiting for one
        // (or offering a re-flash from Done). If one is plugged in earlier, say so rather
        // than silently ignoring it, so the chooser doesn't look like nothing was detected.
        val current = viewModel.state.value
        if (current !is UiState.WaitingForDevice && current !is UiState.Done) {
            if (driver != null) {
                viewModel.logDiagnostic("M5Stack detected - choose a firmware source above to continue.")
            }
            return
        }

        if (driver == null) {
            viewModel.logDiagnostic(
                "No supported M5Stack found on USB yet. Check the cable is a data cable and " +
                    "that the device is powered on."
            )
            return
        }
        val device = driver.device
        viewModel.logDiagnostic(
            "Found USB device ${device.deviceName} (VID ${device.vendorId}, PID ${device.productId})."
        )

        if (usbManager.hasPermission(device)) {
            viewModel.startDetection(device)
        } else {
            viewModel.logDiagnostic("Requesting USB permission for the device...")
            requestPermission(device)
        }
    }

    /** Fills the Wi-Fi SSID from the current network, asking for location first if needed. */
    private fun ensureSsidPrefill() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            prefillSsid()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

    private fun onFlashClicked() {
        when (viewModel.currentMode()) {
            FlashMode.NIGHTSCOUT -> flashWithWifi()
            // Third-party firmware: the user must accept the disclaimer before every burn.
            FlashMode.CUSTOM -> confirmThirdPartyThenFlash()
            // xDripMon is Bluetooth-only: flash firmware, no Wi-Fi provisioning.
            else -> viewModel.flash(null)
        }
    }

    private fun flashWithWifi() = with(binding) {
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

    private fun confirmThirdPartyThenFlash() {
        val message = HtmlCompat.fromHtml(
            getString(R.string.eula_body), HtmlCompat.FROM_HTML_MODE_LEGACY,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.eula_title)
            .setMessage(message)
            .setPositiveButton(R.string.eula_accept) { _, _ -> viewModel.flash(null) }
            .setNegativeButton(R.string.eula_decline, null)
            .show()
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

    private fun showWifiPrivacyDialog() {
        val message = HtmlCompat.fromHtml(
            getString(R.string.wifi_privacy_body), HtmlCompat.FROM_HTML_MODE_LEGACY,
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wifi_privacy_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun render(state: UiState) = with(binding) {
        deviceCard.visibility = View.GONE
        progress.visibility = View.GONE
        actionButton.visibility = View.GONE
        modeChooser.visibility = View.GONE
        customRepoCard.visibility = View.GONE
        // The subtitle names both projects while choosing, then narrows to the chosen source.
        appDescription.setText(
            when (viewModel.currentMode()) {
                FlashMode.NIGHTSCOUT -> R.string.app_description_nightscout
                FlashMode.XDRIP -> R.string.app_description_xdrip
                FlashMode.CUSTOM -> R.string.app_description_custom
                null -> R.string.app_description
            }
        )
        // Only the Nightscout flow provisions Wi-Fi; shown while there's still something to
        // edit (i.e. before flashing starts and after it finishes).
        wifiCard.visibility = if (
            viewModel.currentMode() == FlashMode.NIGHTSCOUT &&
            state !is UiState.Busy && state !is UiState.Done
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }

        when (state) {
            is UiState.ChoosingMode -> {
                statusTitle.text = getString(R.string.app_name)
                statusDetail.text = getString(R.string.chooser_prompt)
                modeChooser.visibility = View.VISIBLE
            }

            is UiState.EnteringRepo -> {
                statusTitle.text = getString(R.string.mode_custom)
                statusDetail.text = ""
                customRepoCard.visibility = View.VISIBLE
                customRepoLayout.error = state.error
            }

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
                if (state.wifiConfigured) offerConfigSite(state)
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

    /**
     * Polls for the device's config web UI and, once it answers, offers to open it. Guards
     * against the user having unplugged or retried before the probe finishes by checking the
     * state is still this exact [Done] instance before touching the UI.
     */
    private fun offerConfigSite(state: UiState.Done) = with(binding) {
        statusDetail.append("\n" + getString(R.string.config_checking))
        lifecycleScope.launch {
            val url = viewModel.configSiteUrl()
            if (viewModel.state.value !== state) return@launch
            if (url != null) {
                actionButton.visibility = View.VISIBLE
                actionButton.text = getString(R.string.open_config_button)
                actionButton.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } else {
                statusDetail.append(
                    "\n" + getString(R.string.config_not_found, viewModel.expectedHostname())
                )
                // Discovery is the last thing that happens after a flash; without this the
                // screen would dead-end here (unplugging deliberately no longer resets it).
                actionButton.visibility = View.VISIBLE
                actionButton.text = getString(R.string.start_over_button)
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
