# M5Stack Loader

## ⚠️ Disclaimer

**This app was built almost entirely by an LLM coding assistant** (Claude, via Claude Code) —
the flashing protocol implementation, the UI, and this document. A human (the repo owner)
directed the work, reviewed the changes, and tested it against real M5Stack hardware, but the
code has not had an independent security or safety audit.

Flashing overwrites the bootloader-level firmware of a physical device. A failed or
interrupted write can leave it unbootable, and while the app includes a ROM-bootloader
fallback and MD5 verification specifically to guard against that, no software guarantee is
absolute. **Use it at your own risk.** It is provided with no warranty of any kind — see
[`LICENSE`](LICENSE). If something looks wrong, please open an issue rather than assuming the
code is correct because an AI wrote it.

## What it does

An Android app that flashes [M5_NightscoutMon](https://github.com/psonnera/M5_NightscoutMon)
onto an M5Stack over USB, with as little fuss as possible: plug the device into the phone,
confirm what was found, tap **Flash**.

The app works out which M5Stack you have, downloads the matching binaries from the firmware
repository, and burns them. You never pick a model or a file. It can also write your phone's
Wi-Fi credentials into the device as part of the same flash, so it joins your network on
first boot without any manual setup on the device itself.

## How to use it

1. Plug the M5Stack into the phone with a USB-OTG cable or adapter. Android offers to open
   M5Stack Loader — allow it (tick "use by default" to skip this prompt next time). If it
   doesn't prompt, open the app manually with the device attached.
2. Before or after plugging in, optionally fill in your Wi-Fi network name and password (the
   app tries to prefill the SSID from what the phone is currently connected to). Leave the
   checkbox on to have those credentials written to the device; tap "Why do we ask for this?"
   for the privacy details — they never leave the phone except onto the device over USB.
3. The app resets the device into its bootloader, identifies the model and flash size, and
   fetches the matching firmware build (cached after the first run, re-validated against the
   server on later ones).
4. Check the model and firmware shown, then tap **Flash**. A progress bar and a scrollable
   log show what's happening; don't unplug the device while this runs.
5. The device reboots into M5_NightscoutMon — you can unplug it. If you set up Wi-Fi, the app
   checks whether the device has joined your network and its on-device config page is
   reachable at `http://m5ns`; if so, it offers to open that page in your browser.

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
- **Wi-Fi provisioning** writes an NVS partition image containing the SSID/password under
  the `M5NSconfig` namespace M5_NightscoutMon reads at boot — no serial console or on-device
  setup step needed. It's built and flashed alongside the firmware, not sent to it afterward.
- **Firmware caching** re-validates each cached binary with a conditional HTTP request
  (ETag) rather than trusting "present on disk", since the firmware repository's `master`
  can gain new commits under an unbumped version string.

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
