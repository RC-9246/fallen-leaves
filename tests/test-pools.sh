#!/usr/bin/env bash
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
# The test intercepts capget before entering the kernel; provide its ABI on macOS.
if [[ $(uname -s) == Darwin ]]; then
    mkdir -p "$tmp/linux"
    cat > "$tmp/linux/capability.h" <<'CAPS'
#pragma once
#include <stdint.h>
#define _LINUX_CAPABILITY_VERSION_3 0x20080522
#define CAP_SYS_ADMIN 21
#define __NR_capget 0
struct __user_cap_header_struct { uint32_t version; int pid; };
struct __user_cap_data_struct { uint32_t effective, permitted, inheritable; };
CAPS
fi
"${CC:-cc}" -D_GNU_SOURCE -I "$tmp" -Wall -Wextra -Werror "$base/tests/ashmem_driver_test.c" -o "$tmp/driver"
[[ $("$tmp/driver" probe legacy 0) == READY ]]
[[ $("$tmp/driver" purge legacy 1) == 'SCANNED 9' ]]
[[ $("$tmp/driver" purge empty 1) == 'SCANNED 0' ]]
[[ $("$tmp/driver" probe stub 0) == ALWAYS_PINNED ]]
[[ $("$tmp/driver" purge stub 0) == ALWAYS_PINNED ]]
[[ $("$tmp/driver" purge denied 1) == 'ERROR PURGE 1' ]]
[[ $("$tmp/driver" purge nocap 0) == NO_CAP ]]
echo 'ashmem probe and purge driver cases passed'

# Only the renderer runs on the host. Supply its byte formatter and compile-time
# Android API without loading device-only classes at test time.
sdk=
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools /opt/homebrew/share/android-sdk; do
    if [[ -f "$candidate/platforms/android-37.2/android.jar" ]]; then sdk=$candidate; break; fi
done
[[ -n $sdk ]] || { echo 'Set ANDROID_HOME to an SDK with platform 37.2' >&2; exit 1; }
jar="$sdk/platforms/android-37.2/android.jar"
cat > "$tmp/MainActivity.java" <<'JAVA'
package dev.pagetrim.app;
class MainActivity { static String fmt(long value) { return value + " B"; } }
JAVA
sources="$base/app/src/main/java/dev/pagetrim/app"
javac -encoding UTF-8 -cp "$jar" -d "$tmp" "$tmp/MainActivity.java" "$sources/ZramPools.java" \
    "$sources/Root.java" "$base/tests/ZramPoolTest.java"
java -cp "$tmp:$jar" dev.pagetrim.app.ZramPoolTest
