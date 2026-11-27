# Fallen Leaves · 落叶

Fallen Leaves is a small, offline Android app for seeing what is using RAM and deciding when to reclaim it. Its native Java interface shows available memory, file cache, reclaimable kernel objects, swap use and memory pressure when the kernel exposes those counters. The Simplified Chinese interface uses the system light or dark theme by default, including the dashboard, charts and dialogs.

[Download the signed APK](https://github.com/RC-9246/fallen-leaves/releases/latest) · [简体中文](README.zh-CN.md)

## Using the app

Requires Android 8.0 or newer. Open **运存** to see the dashboard. You can read it and open Android's per-app settings without granting root. Grant access through a compatible root manager, such as KernelSU, only if you want the privileged actions below.

Tap **设置** at the top of any tab to open appearance and prompt settings. Choose System, Light or Dark and tap Save. You can also turn off success dialogs for manual memory reclaim, per-process trim requests and diagnostic cleanup. Results remain in **记录** (History). Errors and partial results still appear.

| Control | What it does | Access |
| --- | --- | --- |
| Memory dashboard | Three independently collapsible groups remember their layout. RAM and swap show used, available, total and percentage. The cache section shows estimated file cache, reclaimable slab, their share of total RAM and memory pressure if exposed. RAM and swap change color at 70% and 90%. The blue cache indicator reflects size. | No root |
| Available RAM trend | Samples every second while the memory page is visible. Shows up to two minutes of local readings, a graph and a text summary. Sampling gaps are visible. | No root |
| Storage ranking | In **存储 → 应用占用排行**, grant Usage Access in Android settings to rank eligible user apps by cache size or total disk usage. Tap an entry to open system settings. Total includes cache once. Unavailable values are labeled. | No root, optional Usage Access |
| Memory reclaim | Save a combination of clean file pages and reclaimable filesystem objects for the main button. The single-action menu runs one area without changing the combination. Select “Do not ask again” and confirm to skip future file-page prompts, including for the tile. The memory tab can turn the prompt back on. Operations that include kernel objects always show a pause warning. Results show before and after readings. | Root |
| Quick Settings tile | Add **回收文件页** in the system tile editor. Tapping it requests file-page reclaim. | Root |
| Background apps | Select an eligible cached user process and send a memory-trim request. The app rechecks its identity and cached state before sending. The recipient decides what to release. Android 14 and later may ignore the request. | Root |
| Automatic monitoring | Independently enable used RAM ≥ 1–99% (preset 90%) and an elapsed interval of 1–1440 minutes (preset 30). Both start off. Choose notification only or explicitly authorize root to reclaim clean file pages. If both are due on the same check there is one action. The ongoing notification can open the editor or stop the service. | No root for alerts, root for reclaim |
| App cache | Open the selected app's Android details page. To remove temporary files, choose **Storage → Clear cache** there. **Clear storage/data** erases that app's data. | No root |
| Old diagnostics | Preview eligible files, select individual entries and confirm permanent deletion. The preview reports allocated disk space. | Root |

The usage threshold requires three qualifying 30-second samples and has a 30-minute cooldown. Re-arming normally requires usage to fall three percentage points below the threshold. For settings of 1–3%, falling below the threshold is sufficient. The interval operates independently of this cooldown. If Android suspends the service, missed intervals are coalesced into one at the next opportunity. Changing active settings restarts the interval. Monitoring needs notifications and stops on reboot.

**Background monitoring (1.4.2 and later).** Enable monitoring under **运存 → 自动监控**. A foreground-service notification stays visible while using other apps, after removing the Fallen Leaves task from Recents, and while the screen is locked. If Android recreates the service after terminating its process, it restores the active mode and interval deadline. Sleep can delay checks, and missed intervals coalesce into one. Stopping monitoring from its notification, force-stopping the app, or rebooting requires a new manual start. Previously saved settings alone cannot start a session. [Android service restart behavior](https://developer.android.com/reference/android/app/Service#START_STICKY)

On a OnePlus 8T running HydrogenOS 11, if the monitoring notification stays visible but scheduled actions stop appearing in History while the screen is locked, choose **Settings → Battery → Battery optimization → 落叶 (Fallen Leaves) → Don’t optimize**.

## Root-free tools in 1.5.0

| Open | What it does |
| --- | --- |
| Memory → Automatic monitoring | Select cleanup of Fallen Leaves itself and independently enable Java GC, SQLite memory release, or a native allocator purge (Android 9+). Use a timer or an occupancy threshold and optionally require Android’s low-memory signal. History records the SQLite byte count and the outcome of the other requests. Reminder and root modes are still available. |
| Memory → Root-free diagnostics | Inspect Android’s low-memory threshold and headroom, swap usage, major faults and memory-stall counters. Mark a baseline, run a workload in another app, then refresh for device-wide deltas. On Android 11+, recent exit reasons cover Fallen Leaves’ own processes. Unavailable counters are labeled. |
| Memory → App usage and background management | With optional Usage Access, compare cache and storage usage with last use, filter by 7 or 30 days or no record in 90 days, and check cache size again after visiting system settings. On Android 8–13, a separate switch lets you request that Android end one selected user app’s eligible background processes. Its next launch may be cold. The API returns no process count or reclaimed bytes. Android 14+ restricts the call to the caller’s own processes. [Android process management](https://developer.android.com/reference/android/app/ActivityManager#killBackgroundProcesses(java.lang.String)) |
| Memory → File pages and app cooperation | Use the file and cooperation switches from one root-free menu. Inspect or advise clean-page eviction for up to 32 selected local documents, with a result per file. File contents are preserved. A shared storage view can conceal backing-page residency. |
| Storage → Review a folder | Choose a local folder using the system picker, see its largest files, and optionally list old temporary files or cache files. Deletion requires individual selection and confirmation. Eligible files have a single configured extension (`.tmp`, `.temp`, or `.cache`), are at least seven days old and lie under a recognized cache or temporary directory. APKs and recognized ZIP archives are excluded. Reported bytes are file lengths. [Android directory access](https://developer.android.com/training/data-storage/shared/documents-files) |
| Storage → Volumes and storage management | Inspect available and total space on the data partition and each accessible shared volume, then open the system storage manager. Shared views can refer to the same disk and are displayed separately. |

Android 11 tests used ordinary app UIDs and synthetic files. A confirmed background-process request ended the selected test process. A cache comparison measured a 2 MiB decrease after a fixture cleared its own cache. Folder cleanup removed one selected 10-byte file, skipped a file changed after preview, and preserved disguised ZIPs and APKs.

## Extended reclaim scopes

Open **运存 → 扩展回收范围**. Each of the five new scopes has its own saved switch, initially off. Turn on a scope and use its individual action. The app asks you to pick a cached app each time a target is needed. The tile and automatic monitor retain their file-page scope. Four further switches control the original global file pages, kernel objects, voluntary app trim and old diagnostics, including their existing entry points. These four start enabled on upgrade.

| Scope | Action and measurement |
| --- | --- |
| Anonymous pages of one cached app | Asks the kernel to reclaim eligible anonymous mappings through each selected process's `/proc/PID/reclaim`. Java and native heaps may contain eligible pages. The operation requires an accessible per-process interface and at least one active swap device. All active swap devices must be zRAM. Returning to the app may fault pages back in. |
| File mappings of one cached app | Requests reclaim of eligible file-backed pages belonging to the selected app. Mapped resources and shared libraries can be read again. Shared pages may remain resident elsewhere. Drivers that select by mapping type may also page out private copy-on-write pages in those mappings. |
| Accept a cold launch | With a separate switch and confirmation, Android may terminate the selected app's eligible cached processes through `am kill --user`. The result records how many original processes exited and whether processes remain or restart. Unsaved in-memory state may be lost. Apps retain their installed files and data. |
| Compact zRAM | Moves compressed objects within an initialized zRAM device to make whole allocator pages available. Reports physical allocator memory before and after and the kernel's compacted-page counter when exposed. Zero is a valid result. |
| Discardable ashmem | Probes the legacy ashmem driver's unpin behavior using a private test region. A supported privileged request covers purgeable ranges system-wide. Apps decide which ranges may be discarded. Kernels with a compatibility stub or only memfd cannot perform this operation. |

For target reclaim, the report separates the change in summed **resident set size (RSS)** for the same surviving processes from the change in **physical memory used by zRAM** (`mm_stat.mem_used_total`). A 300 MiB RSS decrease and 110 MiB zRAM increase give a rough 190 MiB difference. Other system activity and shared mappings affect both readings. A partial operation, changed process group or missing counter limits what can be compared. Requested pageout may leave some or all pages resident. [Android 11 cached-process optimizer](https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/services/core/java/com/android/server/am/CachedAppOptimizer.java) · [zRAM statistics](https://docs.kernel.org/admin-guide/blockdev/zram.html) · [Legacy ashmem implementation](https://android.googlesource.com/kernel/common/+/e515b9902f5fa362ca66db9b01e7b2161c324c06/drivers/staging/android/ashmem.c)

## Advanced controls in 1.4.1

Open **运存 → 高级回收与设备复核**. The 50 independent switches in this screen start off. Enabling one allows its manual action. Opening a category, the Quick Settings tile or the automatic monitor does not execute these actions. Advanced actions that can change state require a choice or confirmation. Results stay in local history.

| Category | Controls and their scope |
| --- | --- |
| File cache (4) | Inspect one selected local document, advise eviction of its clean pages, sync one selected write-authorized document, or use a separate root picker for a regular file under the current user's real Download directory. The residency sample covers at most the first 128 MiB. Android's shared-storage view can accept eviction without evicting the underlying pages. The root picker verifies the backing file's identity. Neither eviction mode removes the file. |
| Cached apps (6) | Select one eligible mapping and request anonymous or file-backed range reclaim, mark the range cold, or request pageout. Choose a 4, 16, 32 or 64 MiB budget. Also available are the Android 11 detailed-memory-report GC request and a read-only LazyFree tally. The range operations require a compatible kernel path. |
| Kernel and drivers (19) | Probe dentry, generic inode, F2FS inode and ext4 inode allocators, ION, Binder, KGSL, per-app GPU accounting, per-app memory cgroups, MGLRU, DAMON, KSM, zRAM idle marking, recompression, writeback and deduplication, Android mmd, physical memory compaction, and locked/CMA accounting. Controls offer an operation only with sufficient interfaces and prerequisites. GPU counters, mmd policy, deduplication settings and locked/CMA are observations. Idle marking releases zero bytes by itself. |
| Owner cooperation (14) | The owner can offer managed GC, native allocator purge, object pools, image caches, idle ImageReader buffers (Android 9+), GPU resources, SQLite, discardable private pages, off-screen activities, tabs, WebView renderers, a private worker, or session save and restore. Fallen Leaves implements GC, allocator purge on compatible devices and SQLite for its own process. Other apps must explicitly implement their own operations. Opening an owner's UI can start it. [Cooperation protocol](docs/COOPERATION.md) |
| Device review (3) | Probe all 19 kernel features, compare before/after/return samples within one boot, or inspect recent HWUI frame times for a selected app’s main process (median and P95). You can mark a starting point and include only completed frames drawn since then; the system retains a bounded frame history. The comparison includes available RAM, zRAM physical use, swap and fault counters, and PSI when available. HWUI may not capture a native game's frames. |
| Lifecycle (4) | Force-stop a selected cached package, temporarily freeze one cached process through Android when supported, set a rare standby bucket, or suspend an eligible app. Force-stop or suspension can interrupt notifications. Standby and suspension have separate restore actions that remain accessible with their switches off. Restore a suspended app before uninstalling Fallen Leaves so its local recovery record is available. |

On a OnePlus 8T with Android 11 and the Horizon 4.19 kernel, selecting 2 MiB of an 8 MiB synthetic anonymous mapping reduced resident memory by 2 MiB. Data was intact when read back. The root picker evicted the 1,024 resident pages of a 4 MiB disposable download with identical file contents afterward. Those observations apply to the test files and mappings. This device exposed six actionable interfaces among the 19 kernel probes, plus per-app GPU accounting. It did not expose the MGLRU, DAMON or zRAM writeback controls used here. An additional read-only check found no per-buffer DMA-BUF statistics interface or transparent huge-page split control on this build. The integration and safety boundaries follow the [kernel zRAM documentation](https://docs.kernel.org/admin-guide/blockdev/zram.html), [DMA-BUF ownership rules](https://docs.kernel.org/driver-api/dma-buf.html) and [Android mmd lifecycle](https://source.android.com/docs/core/perf/mmd).

The optional **仅在 Android 报告低内存时自动回收** switch in **自动监控** gates automatic root reclaim on Android’s current low-memory signal. An expired interval with no confirmed low-memory state skips the action. A high-usage threshold remains armed while waiting for that signal. Alerts and manual actions keep their own behavior. [Android MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo#lowMemory)

**Interpreting the results.** A file range with anonymous copies, dirty pages, swapped pages or an unlinked backing file is excluded from scoped file reclaim. Range reports separate the selected mapping’s RSS and logical swap from physical zRAM use across the device. A partial-range request cannot be assigned the entire mapping’s measured gain. On the 8T, the F2FS/ext4 probes confirmed dedicated, unaliased allocators with writable shrink interfaces. The device did not provide an isolated measure of physical RAM that those allocators would return. The ImageReader owner test retained the acquired frame, queued frame and subsequent output; the Android API does not report released bytes. [Linux process accounting](https://docs.kernel.org/filesystems/proc.html) · [ImageReader.discardFreeBuffers](https://developer.android.com/reference/android/media/ImageReader#discardFreeBuffers())

## What gets touched

**Memory.** The cold-launch and force-stop actions can end cached processes. Process selection excludes system apps, itself, detectable VPN service apps and shared app identities. Target reclaim compares the selected process group. Pool and dashboard readings are system-wide. A change in available RAM cannot be attributed solely to a reclaim request. Frequently discarding useful file pages can make the next launch slower. Swap usage is logical space and does not measure the physical RAM consumed by compressed swap.

**Root diagnostic cleanup.** Eligible files have recognized filenames in Android native crash dumps, ANR traces, vendor WLAN dumps and numbered OnePlus diagnostic directories. Candidates must be regular files with one hard link and a modification time at least seven days old. Before removal, the app rechecks the canonical path, file type, age and file metadata against the preview and skips entries that fail those checks. Removing a report also removes evidence that could help diagnose a crash. The exact filename rules are in [Diagnostics.java](app/src/main/java/dev/pagetrim/app/Diagnostics.java).

Downloads, saved APKs, photos, chats and other apps' private data are outside the diagnostic cleaner's scope. It does not recursively empty app caches. The app requests package visibility to build local app lists. It has no Internet permission, third-party runtime dependencies or ads. The last 30 actions are kept locally.

## Build and test

Use JDK 17, Android SDK Platform 34, Build Tools 34.0.0, Android NDK r27c (27.2.12479018), Bash, zip and OpenSSL. Set `ANDROID_NDK_HOME` if the NDK is outside the SDK’s `ndk/27.2.12479018` directory. The NDK builds the native helpers for arm64-v8a, armeabi-v7a, x86_64 and x86. For the test suite, export `ANDROID_HOME` to your SDK directory and install Python 3 and a host C compiler. Run from the project root:

```sh
./tests/test-policy.sh
python3 tests/test-public-memory.py
python3 tests/test-target.py
python3 tests/test-kernel-scopes.py
python3 tests/test-suspension.py
python3 tests/test-frames.py
./tests/test-pools.sh
./build.sh
```

The release build produces `build/Fallen-Leaves.apk`. Use `./build.sh debug` for a debug build. The script keeps signing material outside the project. Set `FALLEN_LEAVES_SIGNING_DIR` to reuse your own signing identity across builds. Published builds retain PageTrim's application ID, `dev.pagetrim.app`, and signing identity for in-place upgrades. An independent build needs the matching key to update an existing installation. Minimum SDK 26, target SDK 33.

The policy tests cover path and filename limits, age, link count, disk block accounting, empty selection and invalid reclaim modes. The 1.0.1 to 1.1.0 upgrade, foreground memory trend, notification-only monitor start/stop, usage-access round trip, both ranking orders and app settings navigation were exercised on an Android 11 emulator. Two test apps had different data and cache footprints. Alert thresholds, cooldown and mode isolation were also checked with simulated readings. The underlying controls were also exercised on an Android 11 AOSP emulator, including all three kernel modes and selected diagnostic-file deletion.

For 1.3.0, the target tests exercise process identity, changing foreground state, missing interfaces and RSS/zRAM reporting. Pool tests cover driver behavior, zero gains, counter resets and partial results. On an Android 11 AOSP emulator, upgrading 1.2.1 preserved the original defaults. UI tests verified all nine switches, persistence after restart, the manual combination guard and both system themes. The unprivileged app showed a root access error. A privileged test runner exercised cold launch, zRAM compaction and the legacy ashmem driver. On a OnePlus 8T running a 4.19.160 kernel, disposable processes exercised both per-process reclaim modes and read back their data. The phone's ashmem driver reported the test range as pinned even after an unpin request. Those 1.3.0 physical tests used a root shell. The signed 1.4.0 APK was subsequently installed and opened on the 8T. Android 11 emulator tests covered an in-place upgrade from 1.3.0, persistence of all 47 switches across process restarts in both positions, owner receipts and suspension rollback with a second suspender still active.

## Development and license

GPT led development in OpenAI Codex. It wrote the native interface, memory controls, diagnostic-file safeguards, build scripts, tests and documentation. The project owner set the goals, including a lightweight GUI and preservation of saved installers, and requested this public release. The Android app runs entirely on the device and does not call an AI service.

Released under the [MIT License](LICENSE).
