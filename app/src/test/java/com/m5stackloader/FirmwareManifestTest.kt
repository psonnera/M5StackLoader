package com.m5stackloader

import com.m5stackloader.esp.Chip
import com.m5stackloader.firmware.DeviceModel
import com.m5stackloader.firmware.FirmwareManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Parses the real Binaries/firmware.json from psonnera/M5_NightscoutMon.
 *
 * The offsets are the point of this test: the CoreS3 bootloader goes at 0x0 while the
 * ESP32 builds put theirs at 0x1000, and getting that wrong produces a device that
 * does not boot.
 */
class FirmwareManifestTest {

    private val manifestJson = """
        [
          {
            "name": "M5_NightscoutMon for 4MB flash (old Basic <=2020.5, no PSRAM)",
            "version": "v1.0.0",
            "path": "Basic_4MB",
            "commands": [
              "--chip esp32 --port %port --baud %baud --before default_reset --after hard_reset write_flash -z --flash_mode dio --flash_freq 80m 0x1000 %PATH\\M5_NightscoutMon.ino.bootloader.bin 0x8000 %PATH\\M5_NightscoutMon.ino.partitions.bin 0x10000 %PATH\\M5_NightscoutMon.ino.bin"
            ]
          },
          {
            "name": "M5_NightscoutMon for 16MB ESP32 (Basic 16MB/v2.7, Fire, all Core2)",
            "version": "v1.0.0",
            "path": "ESP32_16MB",
            "commands": [
              "--chip esp32 --port %port --baud %baud --before default_reset --after hard_reset write_flash -z --flash_mode dio --flash_freq 80m 0x1000 %PATH\\M5_NightscoutMon.ino.bootloader.bin 0x8000 %PATH\\M5_NightscoutMon.ino.partitions.bin 0x10000 %PATH\\M5_NightscoutMon.ino.bin"
            ]
          },
          {
            "name": "M5_NightscoutMon for CoreS3 (ESP32-S3, 16MB)",
            "version": "v1.0.0",
            "path": "CoreS3",
            "commands": [
              "--chip esp32s3 --port %port --baud %baud --before default_reset --after hard_reset write_flash -z --flash_mode dio --flash_freq 80m 0x0 %PATH\\M5_NightscoutMon.ino.bootloader.bin 0x8000 %PATH\\M5_NightscoutMon.ino.partitions.bin 0x10000 %PATH\\M5_NightscoutMon.ino.bin"
            ]
          }
        ]
    """.trimIndent()

    private val variants = FirmwareManifest.parse(manifestJson)

    @Test
    fun `parses every variant`() {
        assertEquals(3, variants.size)
        assertEquals(listOf("Basic_4MB", "ESP32_16MB", "CoreS3"), variants.map { it.path })
    }

    @Test
    fun `parses chip and the three offsets of an ESP32 build`() {
        val basic = variants.single { it.path == "Basic_4MB" }
        assertEquals(Chip.ESP32, basic.chip)
        assertEquals("v1.0.0", basic.version)
        assertEquals(
            listOf(0x1000, 0x8000, 0x10000),
            basic.parts.map { it.offset },
        )
        assertEquals(
            listOf(
                "M5_NightscoutMon.ino.bootloader.bin",
                "M5_NightscoutMon.ino.partitions.bin",
                "M5_NightscoutMon.ino.bin",
            ),
            basic.parts.map { it.fileName },
        )
    }

    @Test
    fun `CoreS3 bootloader sits at 0x0, not 0x1000`() {
        val coreS3 = variants.single { it.path == "CoreS3" }
        assertEquals(Chip.ESP32S3, coreS3.chip)
        assertEquals(0x0, coreS3.parts.first { it.fileName.contains("bootloader") }.offset)
    }

    @Test
    fun `a 4MB ESP32 gets the Basic build`() {
        val chosen = DeviceModel.select(Chip.ESP32, 4 shl 20, variants)
        assertEquals("Basic_4MB", chosen.path)
    }

    @Test
    fun `a 16MB ESP32 gets the 16MB build`() {
        val chosen = DeviceModel.select(Chip.ESP32, 16 shl 20, variants)
        assertEquals("ESP32_16MB", chosen.path)
    }

    @Test
    fun `an ESP32-S3 gets the CoreS3 build`() {
        val chosen = DeviceModel.select(Chip.ESP32S3, 16 shl 20, variants)
        assertEquals("CoreS3", chosen.path)
    }

    @Test
    fun `an ESP32 with an unexpected flash size is refused rather than guessed at`() {
        assertThrows(IllegalStateException::class.java) {
            DeviceModel.select(Chip.ESP32, 8 shl 20, variants)
        }
    }
}
