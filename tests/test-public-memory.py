#!/usr/bin/env python3
"""Exercise the shipped unprivileged memory comparison and compile its API-gated UI."""
import os
from pathlib import Path
import subprocess
import tempfile

tests = Path(__file__).resolve().parent
src = tests.parent / "app/src/main/java/dev/pagetrim/app"
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
android = sdk / "platforms/android-34/android.jar"
with tempfile.TemporaryDirectory() as tmp:
    work = Path(tmp)
    formatter = work / "MainActivity.java"
    formatter.write_text("""package dev.pagetrim.app;
class MainActivity { static String fmt(long bytes) { return String.valueOf(bytes); } }
""")
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", android, "-d", work, formatter,
                    src/"MemStats.java", src/"PublicMemorySample.java", src/"MemoryInspection.java",
                    tests/"PublicMemoryTest.java"], check=True)
    subprocess.run(["java", "-cp", os.pathsep.join(map(str, [work, android])),
                    "dev.pagetrim.app.PublicMemoryTest"], check=True)
