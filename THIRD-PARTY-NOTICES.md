# Third-party notices

M5Stack Loader is licensed GPL-3.0-or-later (see `LICENSE`). It incorporates, derives
from, and redistributes the following works.

---

## Java ESPLoader — derived from

**Copyright (C) 2022-2024 Boris du Reau <boris.dureau@neuf.fr>**
https://github.com/bdureau/ESPLoader — **GPL-3.0**

The original Java port of Espressif's esptool. `app/src/main/java/com/m5stackloader/esp/EspLoader.kt`
is a Kotlin derivative of it: the command set, the flashing sequence and the overall
structure follow that work. The serial transport was rewritten for Android
(usb-serial-for-android instead of jSerialComm), and the packet layer was reworked to
parse SLIP frames and validate reply status rather than sleeping a fixed interval per
block.

GPL-3.0 is the licence of this project as a whole, so this derivation is carried under
the same terms.

---

## esptool — derived from

**Copyright (C) 2014-2023 Fredrik Ahlberg, Angus Gratton, Espressif Systems (Shanghai) CO LTD,
other contributors as noted.**
https://github.com/espressif/esptool — **GPL-2.0-or-later**

Source of the ROM bootloader register layouts, chip magic values, reset sequences,
timeouts, status-byte lengths and framing rules used in `EspLoader.kt` and `Chip.kt`.

esptool is GPL-2.0-**or-later**, which permits its use under GPL-3.0 in this combined work.

---

## Espressif flasher stubs — redistributed verbatim

**Copyright (C) Espressif Systems (Shanghai) CO LTD**
https://github.com/espressif/esptool-legacy-flasher-stub — **Apache-2.0**

`app/src/main/assets/stub_flasher/esp32.json` and `esp32s3.json` are shipped unmodified,
taken from `esptool/targets/stub_flasher/1/`. The full Apache-2.0 licence text ships
alongside them in `app/src/main/assets/stub_flasher/LICENSE-APACHE`, and is therefore
present inside the APK.

---

## usb-serial-for-android — redistributed as a library

**Copyright (c) 2011-2013 Google Inc.**
**Copyright (c) 2013 Mike Wakerly**
https://github.com/mik3y/usb-serial-for-android — **MIT**

Linked as a Gradle dependency (`com.github.mik3y:usb-serial-for-android`) and packaged
into the APK. The MIT licence requires its notice to accompany the software, so it is
reproduced in full:

```
The MIT License (MIT)

Copyright (c) 2011-2013 Google Inc.
Copyright (c) 2013 Mike Wakerly

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Logos

`app/src/main/res/drawable-xxhdpi/` contains two logos, shown on the front page to identify
the projects this app bridges. They are used nominatively — to refer to those projects — and
remain the property of their respective owners. This app is not endorsed by or affiliated
with M5Stack.

- `ic_m5stack.png` — the M5Stack logo, from the
  [M5Stack organisation](https://github.com/m5stack). Cropped to the box mark and rescaled;
  colours unaltered. M5Stack is a trademark of M5Stack Technology Co., Ltd.
- `ic_myscout.png` — from
  [psonnera/cgm-remote-monitor](https://github.com/psonnera/cgm-remote-monitor)
  (`myscout` branch), the project author's own Nightscout fork. Rescaled only.

---

## Firmware

The binaries this app downloads and flashes are **not** part of it. They are built and
published by [psonnera/M5_NightscoutMon](https://github.com/psonnera/M5_NightscoutMon)
(GPL-3.0) and fetched at run time.
