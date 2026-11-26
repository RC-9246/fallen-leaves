# Fallen Leaves · 落叶

Fallen Leaves is a small, offline Android app for seeing what is using RAM and deciding when to reclaim it. Its native Java interface shows available memory, file cache, reclaimable kernel objects, swap use and memory pressure when the kernel exposes those counters. The interface is in Simplified Chinese.

[Download the signed APK](https://github.com/RC-9246/fallen-leaves/releases/latest) · [简体中文](README.zh-CN.md)

## Using the app

Requires Android 8.0 or newer. Open **运存** to see the dashboard. You can read it and open Android's per-app settings without granting root. Grant access through a compatible root manager, such as KernelSU, only if you want the privileged actions below.

| Control | What it does | Access |
| --- | --- | --- |
| Memory dashboard | Three independently collapsible groups remember their layout. RAM and swap show used, available, total and percentage. The cache section shows estimated file cache, reclaimable slab, their share of total RAM and memory pressure if exposed. RAM and swap change color at 70% and 90%. The blue cache indicator reflects size. | No root |
| Available RAM trend | Samples every second while the memory page is visible. Shows up to two minutes of local readings, a graph and a text summary. Sampling gaps are visible. | No root |
| Storage ranking | In **存储 → 应用占用排行**, grant Usage Access in Android settings to rank eligible user apps by cache size or total disk usage. Tap an entry to open system settings. Total includes cache once. Unavailable values are labeled. | No root, optional Usage Access |
| Memory reclaim | Save a combination of clean file pages and reclaimable filesystem objects for the main button. The single-action menu runs one area without changing the combination. A switch controls confirmation for file pages, including the tile. Operations that include kernel objects always show a pause warning. Results show before and after readings. | Root |
| Quick Settings tile | Add **回收文件页** in the system tile editor. Tapping it requests file-page reclaim. | Root |
| Background apps | Select an eligible cached user process and send a memory-trim request. The app rechecks its identity and cached state before sending. The recipient decides what to release. Android 14 and later may ignore the request. | Root |
| Automatic monitoring | Independently enable used RAM ≥ 1–99% (preset 90%) and an elapsed interval of 1–1440 minutes (preset 30). Both start off. Choose notification only or explicitly authorize root to reclaim clean file pages. If both are due on the same check there is one action. The ongoing notification can open the editor or stop the service. | No root for alerts, root for reclaim |
| App cache | Open the selected app's Android details page. To remove temporary files, choose **Storage → Clear cache** there. **Clear storage/data** erases that app's data. | No root |
| Old diagnostics | Preview eligible files, select individual entries and confirm permanent deletion. The preview reports allocated disk space. | Root |

The usage threshold requires three qualifying 30-second samples and has a 30-minute cooldown. Re-arming normally requires usage to fall three percentage points below the threshold. For settings of 1–3%, falling below the threshold is sufficient. The interval operates independently of this cooldown. If Android suspends the service, missed intervals are coalesced into one at the next opportunity. Changing active settings restarts the interval. Monitoring needs notifications and stops on reboot.

## What gets touched

**Memory.** The app does not force-stop processes. Process selection excludes system apps, itself, detectable VPN service apps and shared app identities. All memory figures are system-wide except the per-process RSS, which includes shared pages. A change in available RAM cannot be attributed solely to a reclaim request. Frequently discarding useful file pages can make the next launch slower. Swap usage is logical space and does not measure the physical RAM consumed by compressed swap.

**Storage.** Direct deletion is limited to recognized filenames in Android native crash dumps, ANR traces, vendor WLAN dumps and numbered OnePlus diagnostic directories. Candidates must be regular files with one hard link and a modification time at least seven days old. Before removal, the app rechecks the canonical path, file type, age and file metadata against the preview and skips entries that fail those checks. Removing a report also removes evidence that could help diagnose a crash. The exact filename rules are in [Diagnostics.java](app/src/main/java/dev/pagetrim/app/Diagnostics.java).

Downloads, saved APKs, photos, chats and other apps' private data are outside the diagnostic cleaner's scope. It does not recursively empty app caches. The app requests package visibility to build local app lists. It has no Internet permission, third-party runtime dependencies or ads. The last 30 actions are kept locally.

## Build and test

Use JDK 17, Android SDK Platform 34, Build Tools 34.0.0, Bash, zip and OpenSSL. Set `ANDROID_HOME` if the Android SDK is outside the locations detected by the script. Run from the project root:

```sh
./tests/test-policy.sh
./build.sh
```

The release build produces `build/Fallen-Leaves.apk`. Use `./build.sh debug` for a debug build. The script keeps signing material outside the project. Set `FALLEN_LEAVES_SIGNING_DIR` to reuse your own signing identity across builds. Published builds retain PageTrim's application ID, `dev.pagetrim.app`, and signing identity for in-place upgrades. An independent build needs the matching key to update an existing installation. Minimum SDK 26, target SDK 33.

The policy tests cover path and filename limits, age, link count, disk block accounting, empty selection and invalid reclaim modes. The 1.0.1 to 1.1.0 upgrade, foreground memory trend, notification-only monitor start/stop, usage-access round trip, both ranking orders and app settings navigation were exercised on an Android 11 emulator. Two test apps had different data and cache footprints. Alert thresholds, cooldown and mode isolation were also checked with simulated readings. The underlying controls were also exercised on an Android 11 AOSP emulator, including all three kernel modes and selected diagnostic-file deletion. Privileged tests used a test root shell. They do not establish compatibility with every kernel or with a physical KernelSU phone.

## Development and license

GPT led development in OpenAI Codex. It wrote the native interface, memory controls, diagnostic-file safeguards, build scripts, tests and documentation. The project owner set the goals, including a lightweight GUI and preservation of saved installers, and requested this public release. The Android app runs entirely on the device and does not call an AI service.

Released under the [MIT License](LICENSE).
