/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.esp

import android.content.res.AssetManager
import android.util.Base64
import org.json.JSONObject

/**
 * Espressif's flasher stub: a small program uploaded into the chip's RAM and run
 * instead of the ROM bootloader. It writes 16KB blocks (against the ROM's 1KB) and
 * erases as it goes, which is the difference between a ~20 second flash and a ~2
 * minute one.
 *
 * The JSON assets are taken verbatim from esptool (esptool/targets/stub_flasher/1/),
 * licensed Apache-2.0 - see assets/stub_flasher/LICENSE-APACHE.
 */
class StubFlasher(
    val entry: Int,
    val text: ByteArray,
    val textStart: Int,
    val data: ByteArray,
    val dataStart: Int,
) {
    companion object {
        fun load(assets: AssetManager, chip: Chip): StubFlasher {
            val raw = assets.open(chip.stubAsset).bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            return StubFlasher(
                entry = json.getLong("entry").toInt(),
                text = Base64.decode(json.getString("text"), Base64.DEFAULT),
                textStart = json.getLong("text_start").toInt(),
                data = Base64.decode(json.getString("data"), Base64.DEFAULT),
                dataStart = json.getLong("data_start").toInt(),
            )
        }
    }
}
