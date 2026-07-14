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
 *
 * The cache is keyed by (variant path, version) only, and the firmware author does not
 * bump [FirmwareVariant.version] on every push to `master` - a real M5_NightscoutMon
 * binary was seen changing under an unchanged "v1.0.0" several times in one day. Trusting
 * an on-disk file forever under that key would silently flash whatever was cached the
 * first time, however old. Every fetch therefore revalidates the cache with the file's
 * own ETag rather than trusting its mere presence, so the worst a stale cache costs is
 * one conditional request, never stale firmware.
 */
class FirmwareRepository(
    private val cacheRoot: File,
    private val baseUrl: String = BASE_URL,
) {

    suspend fun fetchManifest(): List<FirmwareVariant> = withContext(Dispatchers.IO) {
        // Never conditional: the manifest is small and its whole job is to tell us what
        // changed, so it must always be current.
        val json = String(download("$baseUrl/firmware.json").bytes, Charsets.UTF_8)
        val variants = FirmwareManifest.parse(json)
        if (variants.isEmpty()) throw IOException("The firmware manifest is empty or unreadable.")
        variants
    }

    /**
     * Downloads every binary of [variant], reporting bytes fetched so far.
     * A part unchanged since it was last cached costs one small conditional request
     * instead of a full re-download; a changed or never-seen part is fetched in full.
     */
    suspend fun fetchBinaries(
        variant: FirmwareVariant,
        onProgress: (downloaded: Int, partsDone: Int, partsTotal: Int) -> Unit,
    ): List<LoadedPart> = withContext(Dispatchers.IO) {
        val directory = File(cacheRoot, "${variant.path}/${variant.version}").apply { mkdirs() }
        val parts = ArrayList<LoadedPart>(variant.parts.size)
        var downloaded = 0

        variant.parts.forEachIndexed { index, part ->
            val bytes = fetchCached("$baseUrl/${variant.path}/${part.fileName}", directory, part)
            validate(part, bytes)
            downloaded += bytes.size
            parts += LoadedPart(part.offset, part.fileName, bytes)
            onProgress(downloaded, index + 1, variant.parts.size)
        }
        parts
    }

    /**
     * Serves [part] from [directory] only if the server confirms nothing has changed
     * since it was cached (HTTP 304 against the ETag saved alongside it); otherwise
     * fetches it fresh and updates both. If the server cannot even be reached, an
     * existing cached copy is used as a last resort rather than failing the flash.
     */
    private fun fetchCached(url: String, directory: File, part: FirmwarePart): ByteArray {
        val cached = File(directory, part.fileName)
        val etagFile = File(directory, "${part.fileName}.etag")
        val cachedBytes = cached.takeIf { it.isFile && it.length() > 0 }?.readBytes()
        val etag = cachedBytes?.let {
            etagFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf(String::isNotEmpty)
        }

        val result = try {
            download(url, ifNoneMatch = etag)
        } catch (e: IOException) {
            if (cachedBytes != null) return cachedBytes else throw e
        }
        if (result.notModified) {
            return cachedBytes
                ?: throw IOException("${part.fileName}: the server said it was unchanged, but nothing is cached.")
        }

        validate(part, result.bytes)
        // Write via a temp file so an interrupted download can't poison the cache.
        val temp = File(directory, "${part.fileName}.tmp")
        temp.writeBytes(result.bytes)
        if (!temp.renameTo(cached)) temp.delete()
        if (result.etag != null) etagFile.writeText(result.etag) else etagFile.delete()
        return result.bytes
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

    /** [notModified] means the server confirmed the caller's `If-None-Match` etag is still
     *  current; [bytes] is empty in that case. */
    private class Fetched(val bytes: ByteArray, val etag: String?, val notModified: Boolean)

    private fun download(url: String, ifNoneMatch: String? = null): Fetched {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            if (ifNoneMatch != null) setRequestProperty("If-None-Match", ifNoneMatch)
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return Fetched(EMPTY, null, notModified = true)
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed (HTTP $status): $url")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            return Fetched(bytes, connection.getHeaderField("ETag"), notModified = false)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL =
            "https://raw.githubusercontent.com/psonnera/M5_NightscoutMon/master/Binaries"

        private const val ESP_IMAGE_MAGIC = 0xE9
        private val EMPTY = ByteArray(0)
    }
}
