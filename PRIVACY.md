# Privacy Policy — M5Stack Loader

**Effective date:** 22 July 2026
**App:** M5Stack Loader (Android)
**Developer:** Patrick Sonnerat ([psonnera](https://github.com/psonnera))

M5Stack Loader flashes firmware onto an M5Stack device over a USB cable and can
optionally write your Wi-Fi credentials into that device so it joins your network on
first boot. This policy explains what data the app touches, why, and what happens to
it. The short version: **the app collects nothing.** It has no analytics, no ads, no
accounts, no third-party SDKs, and no servers of its own; the developer never receives
any data from it. The full source code is public at
[github.com/psonnera/M5StackLoader](https://github.com/psonnera/M5StackLoader), so
every statement below can be verified.

## Data the app accesses, and why

### Wi-Fi network name (SSID) — location permission

To read the name of the Wi-Fi network your phone is connected to, Android requires the
**location permission**, because a network name can reveal where you are. The app uses
this permission for exactly one thing: pre-filling the "Network name (SSID)" field in
the Nightscout setup flow, so the M5Stack can be set up to join the same network
without you typing the name.

- The app shows an in-app disclosure and asks for your consent **before** requesting
  the permission. This is optional — if you decline, the app never requests location
  and you type the network name yourself.
- The app never reads your GPS or fused location, never tracks your position, and
  never requests location access in the background.
- The network name is only placed into an editable text field on your screen. It is
  never transmitted over the internet, logged, or sent to the developer.

### Wi-Fi password

If you choose to set up Wi-Fi on the device, the password you enter is written — along
with the network name — **only to your M5Stack, over the USB cable** connected to your
phone. It is never uploaded, transmitted over any network, logged, or stored by the
app after flashing completes.

### USB device information

The app reads the technical identity of the connected USB device (vendor/product ID,
chip type, MAC address) to work out which M5Stack model it is and flash the right
firmware. This information stays on the phone.

### Local network access

After a Wi-Fi-enabled flash, the app can look for the M5Stack on your own local
network (using mDNS) to offer a link to the device's configuration page. This traffic
stays inside your local network and contains no personal data.

## Internet access

The app's only internet use is **downloading firmware files from GitHub**
(`raw.githubusercontent.com`) before flashing. Like any web request, GitHub receives
standard connection metadata (such as your IP address) and handles it under
[GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
The download never includes your Wi-Fi credentials, your location, or any other
information about you.

## Data collection and sharing

- **Collected by the developer:** none. No data of any kind leaves your phone for the
  developer or any third party.
- **Sold or shared:** nothing, with anyone.
- **Health data:** although the firmware being flashed (Nightscout / xDrip monitors)
  displays glucose data once running on the M5Stack, the app itself never accesses,
  stores, or transmits any health data.

## Data stored on your phone, and deletion

The app keeps only two things locally: a cache of downloaded firmware files (so a
repeat flash needs no network) and your answer to the SSID auto-fill question (so you
are not asked again). Both live in the app's private storage and are removed by
clearing the app's storage in Android settings or uninstalling the app. There is
nothing to delete anywhere else, because nothing ever leaves the phone.

## Permissions summary

| Permission | Why the app has it |
|---|---|
| Location (fine + coarse) | Read the current Wi-Fi network name to pre-fill the SSID field (optional; see above) |
| Internet / network state | Download firmware from GitHub |
| Wi-Fi state / multicast | Find the M5Stack's configuration page on your local network |
| USB host (feature) | Talk to the M5Stack over the USB cable |

## Children

The app is a technical utility and is not directed at children. It collects no data
from anyone, including children.

## Changes to this policy

If a future version of the app changes how data is handled, this document will be
updated in the repository, where its full change history is publicly visible.

## Contact

Questions about this policy or the app's data handling:

- Open an issue at [github.com/psonnera/M5StackLoader/issues](https://github.com/psonnera/M5StackLoader/issues)
- Email: mozmoz74@gmail.com
