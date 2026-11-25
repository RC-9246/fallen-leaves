#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "$0")" && pwd -P)
source_dir="$project_dir/app/src/main"
build_dir="$project_dir/build"
mode=${1:-release}

die() {
    printf 'Fallen Leaves build: %s\n' "$*" >&2
    exit 1
}

if [[ $# -gt 1 || ( $mode != release && $mode != debug ) ]]; then
    die "usage: $0 [release|debug]"
fi

sdk_dir=
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
    /opt/homebrew/share/android-commandlinetools /opt/homebrew/share/android-sdk; do
    if [[ -n $candidate && -f $candidate/platforms/android-34/android.jar && \
        -x $candidate/build-tools/34.0.0/aapt2 ]]; then
        sdk_dir=$candidate
        break
    fi
done
[[ -n $sdk_dir ]] || die 'set ANDROID_HOME to an SDK with platform android-34 and build-tools 34.0.0'

android_jar="$sdk_dir/platforms/android-34/android.jar"
build_tools="$sdk_dir/build-tools/34.0.0"
lambda_stubs="$build_tools/core-lambda-stubs.jar"
for tool in aapt2 d8 zipalign apksigner; do
    [[ -x $build_tools/$tool ]] || die "missing $build_tools/$tool"
done
[[ -f $lambda_stubs ]] || die "missing $lambda_stubs"

javac=javac
jar=jar
keytool=keytool
if [[ -n ${JAVA_HOME:-} ]]; then
    javac="$JAVA_HOME/bin/javac"
    jar="$JAVA_HOME/bin/jar"
    keytool="$JAVA_HOME/bin/keytool"
fi
for tool in "$javac" "$jar" "$keytool" openssl zip; do
    command -v "$tool" >/dev/null || die "missing $tool"
done
[[ -f $source_dir/AndroidManifest.xml && -d $source_dir/java && -d $source_dir/res ]] || \
    die 'expected app/src/main/AndroidManifest.xml, java/ and res/'

mkdir -p -- "$build_dir"
stage=$(mktemp -d "$build_dir/.stage.XXXXXXXX")
signing_stage=
cleanup() {
    rm -rf -- "$stage"
    if [[ -n $signing_stage ]]; then
        rm -rf -- "$signing_stage"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -- "$stage/generated" "$stage/classes" "$stage/dex"
"$build_tools/aapt2" compile --dir "$source_dir/res" -o "$stage/resources.zip"
link_args=(
    -I "$android_jar" --manifest "$source_dir/AndroidManifest.xml"
    --java "$stage/generated" -o "$stage/unsigned.apk"
    --min-sdk-version 26 --target-sdk-version 32
)
if [[ $mode == debug ]]; then
    link_args+=(--debug-mode)
fi
if [[ -d $source_dir/assets ]]; then
    link_args+=(-A "$source_dir/assets")
fi
"$build_tools/aapt2" link "${link_args[@]}" "$stage/resources.zip"

badging=$("$build_tools/aapt2" dump badging "$stage/unsigned.apk")
[[ $badging == "package: name='dev.pagetrim.app'"* ]] || die 'expected manifest package dev.pagetrim.app'
min_sdk=$(printf '%s\n' "$badging" | sed -n "s/^sdkVersion:'\([0-9][0-9]*\)'$/\1/p")
[[ -n $min_sdk ]] || die 'unable to read the minimum SDK from the linked manifest'

find "$source_dir/java" "$stage/generated" -type f -name '*.java' -print0 > "$stage/sources"
[[ -s $stage/sources ]] || die 'no Java source files found'
sources=()
while IFS= read -r -d '' source; do
    sources+=("$source")
done < "$stage/sources"

debug_info=-g:none
if [[ $mode == debug ]]; then
    debug_info=-g
fi
"$javac" -encoding UTF-8 -source 8 -target 8 "$debug_info" \
    -bootclasspath "$android_jar:$lambda_stubs" -d "$stage/classes" "${sources[@]}"
"$jar" cf "$stage/classes.jar" -C "$stage/classes" .
"$build_tools/d8" "--$mode" --min-api "$min_sdk" --lib "$android_jar" \
    --output "$stage/dex" "$stage/classes.jar"
zip -q -j "$stage/unsigned.apk" "$stage/dex/"*.dex
"$build_tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"

signing_dir=${FALLEN_LEAVES_SIGNING_DIR:-${PAGETRIM_SIGNING_DIR:-"${XDG_DATA_HOME:-$HOME/.local/share}/pagetrim/signing"}}
(umask 077 && mkdir -p -- "$signing_dir")
signing_dir=$(cd -- "$signing_dir" && pwd -P)
case "$signing_dir/" in
    "$project_dir/"*) die 'the signing directory must be outside the project' ;;
esac

password="$signing_dir/$mode.password"
keystore="$signing_dir/$mode.p12"
if [[ ! -f $password || ! -f $keystore ]]; then
    signing_stage=$(mktemp -d "$signing_dir/.signer.XXXXXXXX")
    if [[ ! -f $password ]]; then
        [[ ! -e $keystore ]] || die 'existing keystore password is missing'
        (umask 077 && openssl rand -hex 32 > "$signing_stage/password")
        ln -- "$signing_stage/password" "$password" 2>/dev/null || [[ -f $password ]] || \
            die 'could not store the signing password'
    fi
    if [[ ! -f $keystore ]]; then
        (umask 077 && "$keytool" -genkeypair -noprompt -storetype PKCS12 \
            -keystore "$signing_stage/key.p12" -storepass:file "$password" \
            -alias pagetrim -keyalg RSA -keysize 3072 -validity 10000 \
            -dname "CN=Fallen Leaves $mode")
        ln -- "$signing_stage/key.p12" "$keystore" 2>/dev/null || [[ -f $keystore ]] || \
            die 'could not store the signing keystore'
    fi
fi

debuggable=false
apk="$build_dir/Fallen-Leaves.apk"
if [[ $mode == debug ]]; then
    debuggable=true
    apk="$build_dir/Fallen-Leaves-debug.apk"
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 --ks-key-alias pagetrim \
    --ks-pass "file:$password" --debuggable-apk-permitted "$debuggable" \
    --v4-signing-enabled false --out "$stage/signed.apk" "$stage/aligned.apk"
"$build_tools/apksigner" verify "$stage/signed.apk"
"$build_tools/zipalign" -c 4 "$stage/signed.apk"

mv -f -- "$stage/signed.apk" "$apk"
printf '%s\n' "$apk"
