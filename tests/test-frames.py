#!/usr/bin/env python3
"""Run the production HWUI parser and marker validation using a local Android SDK and JDK."""
import os
from pathlib import Path
import subprocess
import tempfile

tests = Path(__file__).resolve().parent
src = tests.parent / "app/src/main/java/dev/pagetrim/app"
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
android = sdk / "platforms/android-34/android.jar"

with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    stubs = work / "Dependencies.java"
    stubs.write_text("""package dev.pagetrim.app;
import android.content.Context;
import java.util.List;
class Root { static String run(String s, int t) { throw new AssertionError(); } }
class Cooperative { static String signer(Context c,String p) { throw new AssertionError(); } }
class MainActivity { static String fmt(long bytes) { throw new AssertionError(); } }
class StorageController {
 static class Entry { String pkg; }
 static List<Entry> list(Context c) { throw new AssertionError(); }
}
class KernelScopes {
 enum Key { GPU_PROCESS; String label; }
 static class Probe { boolean actionable; String detail; }
 static Probe probe(Context c, Key k) { throw new AssertionError(); }
}
""")
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", android, "-d", work, stubs,
                    src/"DeviceReview.java", tests/"DeviceReviewFramesTest.java"], check=True)
    subprocess.run(["java", "-cp", os.pathsep.join(map(str, [work, android])),
                    "dev.pagetrim.app.DeviceReviewFramesTest"], check=True)

    cmd = ["java", "-cp", os.pathsep.join(map(str, [work, android])),
           "dev.pagetrim.app.DeviceReviewFramesTest", "command"]
    script = subprocess.check_output(cmd, text=True).replace("/proc/", str(work / "proc") + "/")
    proc = work / "proc/301"
    proc.mkdir(parents=True)
    (proc/"status").write_text("Uid: 10302 10302 10302 10302\n")
    (proc/"cmdline").write_bytes(b"dev.fallen.frame\0")
    ps = work/"ps"
    ps.write_text("#!/bin/sh\nprintf 'PID UID NAME\\n301 10302 dev.fallen.frame\\n302 110302 dev.fallen.frame\\n'\n")
    ps.chmod(0o755)
    system = work/"dumpsys"
    system.write_text("""#!/bin/sh
[ "$*" = '-t 6 gfxinfo 301 framestats' ] || exit 2
echo "** Graphics info for pid ${P:-301} [dev.fallen.frame] **"
awk 'BEGIN {for(i=0;i<3000;i++)print "confidential_window_title_and_extra_diagnostic_metadata_0123456789012345678901"}'
printf '%s\\n' ---PROFILEDATA--- 'Flags,Vsync,FrameCompleted,IntendedVsync,' '0,0,1234567890123450159,1234567890123449000,' ---PROFILEDATA---
exit ${RC:-0}
""")
    system.chmod(0o755)
    env = dict(os.environ, PATH=str(work) + os.pathsep + os.environ["PATH"])
    expected = "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n0,1234567890123449000,1234567890123450159\n---PROFILEDATA---\n"
    assert subprocess.check_output(["/bin/sh", "-c", script], env=env, text=True) == expected
    for extra in [{"P": "302"}, {"RC": "1"}]:
        assert subprocess.run(["/bin/sh", "-c", script], env={**env, **extra},
                              capture_output=True).returncode != 0
    print("Streamed >200 KiB HWUI dump, excluded other user, kept exact 64-bit timestamps and dropped window metadata.")
