# Architecture

The Python launcher owns computer-side setup. It discovers ADB, guides the user through TV settings, chooses an authorized device, installs the APK, launches its activity, and starts an `app_process` helper with shell UID 2000. The APK contains both the ordinary app and the helper's bytecode.

Before executing installed APK bytecode as shell, the launcher checks that its SHA-256 matches the APK supplied with the launcher. A package-name match alone is not sufficient. Android enforces the signing key when an existing app is updated.

The Android app owns the calibration UI and private preferences. Margins are stored per physical display ID, together with the dimensions at which they were saved. When the output mode changes, the profile scales the margins from those reference dimensions. The first run imports an existing compositor rectangle when it can read one.

The public name is Android TV Overscan. The Android application ID remains `com.skipstery.projectorcalibrator` from version 0.1.0 so signed upgrades preserve saved profiles. The build script also retains the original signing-key path for compatible updates.

The helper owns privileged display access. It obtains the physical display token through Android 14's `DisplayControl` and applies a `SurfaceControl.Transaction.setDisplayProjection` transaction. The source is the full logical display. The destination is the physical display minus the four margins. It does not change resolution, density, system settings, or the HDMI signal mode.

Android 14 declares `ACCESS_SURFACE_FLINGER` as a signature permission, and the shell package holds it. An ordinary app cannot acquire it with `pm grant`. See the AOSP [permission declaration](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/res/AndroidManifest.xml) and [Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/packages/Shell/AndroidManifest.xml).

## Binder connection

The app exposes a bootstrap provider protected by `INTERACT_ACROSS_USERS_FULL`. The provider additionally checks that the caller is UID 2000. The helper obtains this provider through `ActivityManager.getContentProviderExternal`, sends its Binder, and receives the app UID and saved profile.

The app uses that Binder for status and margin changes. The helper accepts only the installed app's UID, enforces an interface descriptor, validates all dimensions and margins, and clears Binder calling identity before invoking the compositor. It accepts no arbitrary shell commands and opens no TCP listener.

An external-provider reference keeps the app process available while the helper runs. A Binder token lets Android release that reference if the helper dies. The helper links to the app's lifetime Binder and exits if the app dies. Stopping or replacing the app therefore also stops its old helper.

See Android 14's [SurfaceControl implementation](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/view/SurfaceControl.java) and [DisplayControl implementation](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayControl.java).

## Lifetime and recovery

The helper checks the physical display mode and logical display state every two seconds. It reapplies the rectangle after a change and retries failed display updates. It reloads a display's stored profile when its ID or dimensions change, avoiding cumulative rounding during repeated mode switches. Opening the UI reapplies the current profile as well.

This controller is a transient ADB shell process, not a boot service. A full reboot stops it. Preferences survive the reboot. Normal app updates preserve preferences when the signing key matches. Without automatic restore the launcher must run again; with it, the app restarts the controller itself, as described below.

Margins must leave at least half of each display dimension visible. Both the app model and the privileged Binder endpoint enforce the bound. Reset restores the full picture, and Show original temporarily previews it without overwriting the saved margins. Done reapplies and saves the calibrated margins, closes the activity, and returns to the TV home screen.

## On-device setup and automatic restore

An ordinary app cannot obtain shell UID 2000, but `adbd` can start shell processes for any authorized client, including a client running on the streamer itself. The app includes an ADB client (`AdbConnection` and `AdbKey`) that supports both legacy ADB on TCP port `5555` and Wireless debugging over TLS 1.3.

### Primary path: computer-free setup and restore via legacy ADB on port 5555

On devices where `adbd` listens on TCP port `5555` when **USB debugging** is enabled (such as Google TV Streamer, which sets `service.adb.tcp.port=5555` in `build.prop`), setup and reboot recovery require no computer, no `WRITE_SECURE_SETTINGS`, and no Wi-Fi (working on Ethernet as well):

1. When `MainActivity` opens without a running controller, it checks `127.0.0.1:5555`. If the port is closed, it guides the user to enable **Developer options** and **USB debugging**.
2. Once port `5555` is listening, `AdbKey.loadOrGenerate` loads or generates a 2048-bit RSA keypair and persists its PKCS#8 PEM in the app's no-backup files directory (`adbkey`).
3. `AdbConnection` connects to `127.0.0.1:5555` and runs the legacy ADB `AUTH` handshake:
   - Sends `CNXN` and receives `AUTH` (`AUTH_TOKEN`) with a 20-byte token.
   - Signs the token with `NONEwithRSA` over the 15-byte DER SHA-1 `DigestInfo` prefix (`30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14`) concatenated with the 20-byte token, matching `adbd`'s `RSA_sign(NID_sha1)` without double-hashing, and sends `AUTH_SIGNATURE`.
   - If the key is already authorized, `adbd` replies with `CNXN`.
   - If the key is not yet recognized, `adbd` sends a second `AUTH_TOKEN`. During interactive onboarding, `AdbConnection` sends `AUTH_RSAPUBLICKEY` containing the 524-byte little-endian Android `mincrypt` `RSAPublicKey` struct (`len=64`, `n0inv`, `n[64]`, `rr[64]`, `exponent`) formatted as `<base64> <identity>\0`.
4. Sending `AUTH_RSAPUBLICKEY` causes Android TV to display the system **Allow USB debugging?** dialog over `MainActivity`. Because the dialog pauses the activity, `MainActivity` keeps the in-flight socket connection alive across `onPause()` (cancelling only in `onDestroy()`). When the user checks **Always allow** and selects **Allow** with the remote, `adbd` records the key and replies with `CNXN` on the open connection.

### Fallback path: Wireless debugging (ADB over TLS) and computer launcher

For devices that do not listen on port `5555`, the Python launcher pairs a dedicated key over Wireless debugging:

1. `pm grant` gives the app `WRITE_SECURE_SETTINGS`, a development permission that ADB shell may grant. The app uses it only to set `adb_wifi_enabled`.
2. A second adb server, on a free local port with a temporary `HOME`, generates a fresh RSA key and pairs it with the streamer using a new pairing code. The computer's own `~/.android/adbkey` is never used or copied.
3. `content write` streams that private key through stdin into the shell-only provider (`openFile` on `/adbkey`, caller UID 2000). The provider validates it and stores it in the app's no-backup files directory. The temporary directory and server are removed.
4. `autoRestoreNow` schedules an immediate attempt, and the launcher polls `autoRestoreStatus` for the result.

### Runtime restore and key refresh

At runtime, `AutoRestoreJob` runs after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, when the UI opens without a controller, and once a day:

1. `AutoRestore` tries `127.0.0.1:5555` first with `sendPublicKey=false` (so background jobs do not pop up an unsolicited authorization dialog). Both generated keys and launcher-imported keys expose the legacy `mincrypt` public key and a self-signed X.509 certificate.
2. If port `5555` is closed or unauthorized, `AutoRestore` falls back to Wireless debugging: it turns on `adb_wifi_enabled` if needed, browses DNS-SD `_adb-tls-connect._tcp` for ports open on `127.0.0.1`, and runs the `STLS` TLS 1.3 handshake presenting the self-signed X.509 certificate built from the stored RSA key.
3. Once connected over either transport, if no controller process exists, the app runs the helper command. The legacy `shell:` service kills a background `app_process` if the session closes while ART is still starting, so the command keeps the session open until the helper logs `READY`.
4. Every successful connection updates `adbd`'s last-use time for the key. The daily job therefore stops Android's 7-day ADB authorization timeout (`adb_allowed_connection_time`) from revoking either a legacy RSA key or a TLS-paired key while the streamer stays on.

Work runs in a JobService rather than a thread started from a provider call: Android 14 freezes a cached app process as soon as the call returns.

Trade-off: always-allowed legacy ADB over port `5555` is LAN-reachable (`*:5555`) for any key the user approves, and Wireless debugging stays enabled when used as the fallback. In both modes, the app's private key is equivalent to ADB shell access (`uid=2000`), so anyone who could read the app's private storage could use it. The launcher option `--no-auto-restore` deletes the key and cancels the jobs; **Revoke debugging authorizations**, where the TV offers it, invalidates all approved keys.
