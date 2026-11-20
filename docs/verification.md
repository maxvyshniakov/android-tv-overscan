# Verification

## Device checks

Tested on 2026-09-23 with Google TV Streamer running Android TV 14, connected to a ZEEMR Z1 Pro at 1920 × 1080.

- The Python launcher installed the APK through an existing wireless ADB connection, started the shell helper, and opened the TV app.
- The initial profile imported the already-applied projector correction.
- The user adjusted the four margins with the physical remote and confirmed that control worked.
- The final user-selected margins were left/right 44 pixels and top/bottom 26 pixels. SurfaceFlinger reported destination `(44, 26)-(1876, 1054)`, which is 1832 × 1028 inside the unchanged 1920 × 1080 output.
- Updating the signed APK preserved those margins and started a new helper.
- Force-stopping the app stopped the helper. After resetting the compositor to the full 1920 × 1080 frame, running the launcher restored the saved `(44, 26)-(1876, 1054)` rectangle.
- A screenshot of the updated TV UI confirmed that horizontal ruler labels were rotated and no longer overlapped. The two redundant footer messages were removed.
- The final UI removes the directional hint and step selector and adds Done. A live test verified that Done closes the app and retains the calibrated output after the home-screen transition settles.
- A live regression test reproduced stale dimensions during original preview. The compositor was already full-frame, but the UI still showed the saved calibration. After the fix, Show original produces `(0, 0)-(1920, 1080)`, displays 1920 × 1080 with disabled zero-margin fields, and Restore returns the exact saved rectangle.
- Upgrading from Projector Calibrator 0.1.0 to Android TV Overscan 0.2.0 preserved the 44/44/26/26 profile. The renamed launcher installed the signed update, started display control, and opened the renamed app. The README screenshot was captured from that running version.

### Automatic restore, 2026-11-20

Same streamer and projector, Android TV 14 build `UTTK.260317.003`, security patch 2026-04-01.

- Diagnosis of the original failure: the streamer rebooted itself at 03:15 (boot reason `reboot`, no OTA). The controller did not come back, and the Mac's ADB key had been revoked by the 7-day authorization timeout, so a full re-pair was needed.
- A Python prototype of the ADB TLS handshake ran `id` as `uid=2000(shell)` with the Mac's paired key and was refused with `CERTIFICATE_UNKNOWN` with a fresh unpaired key.
- The TV app found the Wireless debugging port through local DNS-SD, completed the TLS handshake, and was refused with an unpaired test key. After pairing the app's own key, adbd logged `Matched auth_key` and the app ran shell commands.
- Killing the controller and calling `autoRestoreNow` restarted it, three times out of three, with parent PID 1.
- `adb reboot`: at boot Android logged "Not connected to any wireless network. Not enabling adbwifi." About 45 seconds after boot the app had enabled Wireless debugging on a new port (40685), started the controller, and SurfaceFlinger reported the saved `(44, 26)-(1876, 1054)` rectangle, with no computer involved.
- Updating the APK kept the app's key and the automatic restore setting.

ADB screenshots capture the logical scene before the physical output transform. They verify UI layout, while the SurfaceFlinger rectangle and the user's projector view verify the actual correction.

## Automated checks

Fifteen Python tests cover pairing the app key with a separate adb home and server, streaming the key only through stdin, polling for a fresh restore result, device selection, unauthorized devices, ambiguous targets, endpoint validation, shell argument quoting, distinct pairing and connection ports, private pairing-code entry, APK verification, reusing an existing helper, and rejecting unsupported Android versions. ADB framing tests check message encoding, magic words and payload limits. Geometry tests check the measured rectangle, clamping, integer overflow, resolution scaling, and 10,000 random remote adjustments against the visibility bound.

The workflow runs launcher tests on macOS, Linux, and Windows, plus the minimum supported Python version on Linux. It builds the APK and runs geometry tests on Linux. CI does not have a physical Android TV.

With the TV app already open, `python3 tests/device_preview.py --serial DEVICE_SERIAL` runs the opt-in hardware test. It toggles original preview, checks both the compositor and the UI, restores the initial calibration, then selects Done and checks that the app closed without losing the correction.

## Not yet verified

Fresh wireless pairing and ADB download were implemented from Google's documented flow; live testing reused an already-authorized ADB connection. Long standby, the daily key refresh over more than 7 days, Ethernet-only streamers (Wireless debugging needs Wi-Fi), HDMI hotplug, resolution switches, protected video paths, and other TV models need further hardware testing. Windows and Linux can run the console code, but live ADB access to a TV was tested from macOS only.
