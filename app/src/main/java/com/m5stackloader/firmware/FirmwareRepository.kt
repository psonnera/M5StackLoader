/*
 * M5StackLoader - flash M5_NightscoutMon onto an M5Stack from Android.
 * Copyright (C) 2026 Patrick Sonnerat <psonnera>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package com.m5stackloader.firmware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A downloaded binary, ready to be written at [offset]. */
data class LoadedPart(val offset: Int, val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Fetches firmware.json and the binaries it names from the M5_NightscoutMon repository,
 * caching them so a repeat flash (or a retry after a failure) costs no network.
 */
class FirmwareRepository(private val cacheRoot: File) {

    suspend fun fetchManifest(): List<FirmwareVariant> = withContext(Dispatchers.IO) {
        val json = String(download("$BASE_URL/firmware.json"), Charsets.UTF_8)
        val variants = FirmwareManifest.parse(json)
        if (variants.isEmpty()) throw IOException("The firmware manifest is empty or unreadable.")
        variants
    }

    /**
     * Downloads every binary of [variant], reporting bytes fetched so far.
     * Files already in the cache are reused.
     */
    suspend fun fetchBinaries(
        variant: FirmwareVariant,
        onProgress: (downloaded: Int, partsDone: Int, partsTotal: Int) -> Unit,
    ): List<LoadedPart> = withContext(Dispatchers.IO) {
        val directory = File(cacheRoot, "${variant.path}/${variant.version}").apply { mkdirs() }
        val parts = ArrayList<LoadedPart>(variant.parts.size)
        var downloaded = 0

        variant.parts.forEachIndexed { index, part ->
            val cached = File(directory, part.fileName)
            val bytes = if (cached.isFile && cached.length() > 0) {
                cached.readBytes()
            } else {
                val fetched = download("$BASE_URL/${variant.path}/${part.fileName}")
                validate(part, fetched)
                // Write via a temp file so an interrupted download can't poison the cache.
                val temp = File(directory, "${part.fileName}.tmp")
                temp.writeBytes(fetched)
                if (!temp.renameTo(cached)) temp.delete()
                fetched
            }
            validate(part, bytes)
            downloaded += bytes.size
            parts += LoadedPart(part.offset, part.fileName, bytes)
            onProgress(downloaded, index + 1, variant.parts.size)
        }
        parts
    }

    /**
     * Guards against writing something that isn't firmware - a GitHub error page, say.
     * Bootloader and application images are ESP images and start with 0xE9; the
     * partition table is a plain blob, so only its size is checked.
     */
    private fun validate(part: FirmwarePart, bytes: ByteArray) {
        if (bytes.isEmpty()) throw IOException("${part.fileName} is empty.")
        val isEspImage = part.fileName.contains("bootloader") || part.fileName.endsWith(".ino.bin")
        if (isEspImage && (bytes[0].toInt() and 0xFF) != ESP_IMAGE_MAGIC) {
            throw IOException("${part.fileName} is not a valid ESP image (it may have failed to download).")
        }
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed (HTTP $status): $url")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/psonnera/M5_NightscoutMon/master/Binaries"

        private const val ESP_IMAGE_MAGIC = 0xE9
    }
}
