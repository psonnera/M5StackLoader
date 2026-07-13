# M5Stack Loader

An Android app that flashes [M5_NightscoutMon](https://github.com/psonnera/M5_NightscoutMon)
onto an M5Stack over USB, with as little fuss as possible: plug the device into the phone,
confirm what was found, tap **Flash**.

The app works out which M5Stack you have, downloads the matching binaries from the firmware
repository, and burns them. You never pick a model or a file.

## How it decides what to flash

It asks the chip itself rather than trusting the USB descriptor, then maps the answer onto
the three builds in the repository's `Binaries/firmware.json`:

| What the chip reports | Build | M5Stack model |
|---|---|---|
| ESP32, 4MB flash | `Basic_4MB` | Basic up to 2020.5 (no PSRAM) |
| ESP32, 16MB flash | `ESP32_16MB` | Basic 16MB/v2.7, Fire, all Core2 |
| ESP32-S3 | `CoreS3` | CoreS3 |

Offsets come from the manifest, not from this source, so the app stays correct if the
firmware author moves one. (They are not the same across models: the CoreS3 bootloader
lives at `0x0`, the ESP32 ones at `0x1000`.)

Anything else — an 8MB ESP32, an unknown chip — is refused rather than guessed at.

## Requirements

- Android 9 (API 28) or newer, with USB host support
- A USB-OTG cable or adapter
- An internet connection the first time (binaries are then cached)

## Building

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # to a connected phone
./gradlew testDebugUnitTest      # protocol + manifest tests
```

## Using it

1. Plug the M5Stack into the phone. Android offers to open M5Stack Loader — say yes
   (tick "use by default" and you skip this next time).
2. The app resets the device into its bootloader, identifies it, and fetches the firmware.
3. Check the model and firmware shown, then tap **Flash**.
4. The device reboots into M5_NightscoutMon. Unplug it.

If the phone does not offer to open the app, launch it manually with the device attached.

## How it works

The app speaks Espressif's serial bootloader protocol directly:

- **Serial** goes through [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android),
  which covers the CP2104 and CH9102 bridges on the Basic/Fire/Core2. The CoreS3 has no
  bridge — the ESP32-S3 drives USB itself and appears as a CDC device — so that VID/PID is
  registered explicitly.
- **Reset into the bootloader** uses the classic DTR/RTS dance on bridged boards. The CoreS3
  needs a different sequence, because its USB-Serial/JTAG peripheral must never see both
  lines low at once.
- **Flashing** uploads Espressif's flasher stub into RAM and drives it at 921600 baud,
  writing zlib-compressed 16KB blocks. If the stub refuses to start, it falls back to the
  ROM bootloader, which is slower but works. Every image is verified by MD5 against the
  device before the app reports success.

`EspLoader` has no Android dependencies, so it is tested on the JVM against `FakeEspRom`, an
in-memory bootloader that inflates the blocks it is sent and answers MD5 with a digest of
what it actually decoded. A fault in the framing, checksums, block splitting or compression
fails those tests.

## Licence and credits

GPL-3.0-or-later, matching M5_NightscoutMon. Full text in [`LICENSE`](LICENSE).

`EspLoader.kt` is a derivative of **Boris du Reau's [Java ESPLoader](https://github.com/bdureau/ESPLoader)**
(GPL-3.0) — the original Java port of esptool — reworked for Android, with register
layouts, reset sequences and framing rules from **[espressif/esptool](https://github.com/espressif/esptool)**
(GPL-2.0-or-later). Espressif's flasher stubs are redistributed verbatim (Apache-2.0), and
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (MIT) is packaged
into the APK.

Full attribution, including the licence texts these works require to be carried with the
software, is in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
