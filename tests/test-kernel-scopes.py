"""Exercise the shipped root script against private mock kernel trees, never a live control."""
import re
import subprocess
import tempfile
import time
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
source = (repo / "app/src/main/assets/kernel-scopes.sh").read_text()

with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    def put(path, text):
        result = root / path.lstrip("/")
        result.parent.mkdir(parents=True, exist_ok=True)
        result.write_text(text)
        return result

    # Redirect absolute pseudo-filesystem paths in both scripts and mock mount tables.
    def redirect(script):
        return re.sub(r'(?<![\w/])/(sys|proc|dev|data)(?=[/"\s])',
                      lambda match: str(root) + match[0], script).replace(str(root) + "/dev/null", "/dev/null").replace("/system/bin/sleep", "/bin/sleep")

    def call(key, *, run=False, prelude="", want=None):
        cmds = prelude + "\n" + f"page=4096\nscope={key}\napply={int(run)}\n" + source
        p = subprocess.run(["sh"], input=redirect(cmds), text=True, capture_output=True)
        assert p.returncode == 0, (key, p.stdout, p.stderr)
        if want: assert p.stdout.startswith(want + "\n"), (key,p.stdout,p.stderr)
        return p.stdout

    dentry = put("/sys/kernel/slab/dentry/shrink", "sentinel")
    put("/sys/kernel/slab/dentry/partial", "12 N0=12\n")
    assert "12" in call("DENTRY", want="READY") and dentry.read_text() == "sentinel"
    call("DENTRY", run=True, want="DONE")
    assert dentry.read_text()=="1\n"
    call("INODE", want="INFO")

    put("/proc/mounts", redirect("none /sys/kernel/debug debugfs rw 0 0\n"))
    put("/proc/vmstat", "nr_ion_heap_pool 1048576\n")
    assert "4294967296 B" in call("ION_POOL",want="INFO")
    ion = put("/sys/kernel/debug/ion/heaps/system_shrink","0\n")
    assert "为 0" in call("ION_POOL",run=True,want="DONE") and ion.read_text()=="0\n"
    ion.write_text("1000\n")
    call("ION_POOL",run=True,want="DONE")
    assert ion.read_text()=="256\n"

    binder = put("/sys/kernel/debug/shrinker/android-binder-7/scan","unchanged")
    put("/sys/kernel/debug/shrinker/android-binder-7/count","0 8\n")
    call("BINDER_POOL",want="READY")
    assert binder.read_text()=="unchanged"
    call("BINDER_POOL",run=True,want="DONE")
    assert binder.read_text()=="0 0 8\n"

    lru = put("/sys/kernel/debug/lru_gen","memcg 3 /\nnode 0\n1 1000 0 2000\n2 200 3 20\n3 100 2 50\n")
    put("/sys/kernel/mm/lru_gen/enabled","0x0007\n")
    call("MGLRU",run=True,want="DONE")
    assert lru.read_text()=="- 3 0 1 0 1024\n"

    put("/proc/swaps","Filename Type Size Used Priority\n/dev/block/zram0 partition 4194304 22 5\n")
    put("/sys/block/zram0/disksize","4294967296\n")
    put("/sys/block/zram0/mm_stat","99 10 4096 0 4096 0 1\n")
    idle=put("/sys/block/zram0/idle","unchanged")
    call("ZRAM_MARK_IDLE",run=True,prelude="getprop() { echo running; }",want="INFO")
    assert idle.read_text()=="unchanged"
    call("ZRAM_MARK_IDLE",run=True,prelude="getprop() { :; }",want="DONE")
    assert idle.read_text()=="all\n"
    recomp=put("/sys/block/zram0/recompress","unchanged")
    call("ZRAM_RECOMPRESS",run=True,want="INFO")
    assert recomp.read_text()=="unchanged"
    put("/sys/block/zram0/recomp_algorithm","#1: lz4 [zstd]\n")
    call("ZRAM_RECOMPRESS",run=True,prelude="uname() { echo 6.8.12; }",want="INFO")
    assert recomp.read_text()=="unchanged"
    call("ZRAM_RECOMPRESS",run=True,prelude="uname() { echo 6.12.1-android; }",want="DONE")
    assert recomp.read_text()=="type=huge_idle priority=1 max_pages=256\n"
    writeback=put("/sys/block/zram0/writeback","unchanged")
    put("/sys/block/zram0/backing_dev","/dev/block/storage\n")
    put("/sys/block/zram0/writeback_limit_enable","1\n")
    credit=put("/sys/block/zram0/writeback_limit","4294967297\n")
    call("ZRAM_WRITEBACK",run=True,want="INFO")
    assert writeback.read_text()=="unchanged" and credit.read_text()=="4294967297\n"
    credit.write_text("64\n")
    call("ZRAM_WRITEBACK",run=True,want="DONE")
    assert credit.read_text()=="64\n" and writeback.read_text()=="huge_idle\n"

    (root / "data/local/tmp").mkdir(parents=True, exist_ok=True)
    ksm = put("/sys/kernel/mm/ksm/run","0\n")
    call("KSM",run=True,prelude="sleep() { :; }",want="DONE")
    assert ksm.read_text()=="0\n"
    ksm.write_text("1\n")
    call("KSM",run=True,prelude="sleep() { :; }",want="DONE")
    assert ksm.read_text()=="1\n"
    ksm.write_text("2\n")
    call("KSM",run=True,want="INFO")
    assert ksm.read_text()=="2\n"

    # Killing the controlling shell must not leave the kernel scanner enabled.
    ksm.write_text("0\n")
    trial = subprocess.Popen(["sh"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, text=True)
    trial.stdin.write(redirect("scope=KSM; apply=1; page=4096\n" + source))
    trial.stdin.close()
    for _ in range(80):
        if ksm.read_text() == "1\n":
            break
        time.sleep(0.02)
    assert ksm.read_text() == "1\n"
    trial.kill()
    trial.wait(timeout=3)
    for _ in range(160):
        if ksm.read_text() == "0\n":
            break
        time.sleep(0.05)
    assert ksm.read_text() == "0\n", "detached fallback failed"
    # No touching cluster-wide control if a target is shared or has descendants.
    put("/sys/class/kgsl/kgsl/proc/101/gpumem_mapped", "8192\n")
    call("GPU_PROCESS",want="READY")
    guarded="pids=101; process(){ :; }; same_members(){ :; }; cached_uid(){ :; }"
    assert "8192 B" in call("GPU_PROCESS",run=True,prelude=guarded,want="DONE")
    mount="/sys/fs/cgroup"
    put("/proc/self/mountinfo", redirect("99 1 0:11 / "+mount+" rw - cgroup2 cgroup rw\n"))
    put(mount+"/cgroup.controllers","memory cpu\n")
    put("/proc/101/cgroup","0::/uid_10234\n")
    group=mount+"/uid_10234/"
    stat=put(group+"cgroup.stat","nr_descendants 1\n")
    members=put(group+"cgroup.procs","101\n")
    request=put(group+"memory.reclaim","untouched")
    for f in ["memory.current","memory.swap.current"]:put(group+f,"100\n")
    pre="uid=10234; pids=101; count=1; budget=1048576; same_members(){ :; }; cached_uid(){ :; }; process(){ :; }"
    call("MEMCG",run=True,prelude=pre,want="INFO")
    assert request.read_text()=="untouched"
    stat.write_text("nr_descendants 0\n")
    members.write_text("101\n200\n")
    call("MEMCG",run=True,prelude=pre,want="INFO")
    assert request.read_text()=="untouched"
    members.write_text("101\n")
    call("MEMCG",run=True,prelude=pre,want="DONE")
    assert request.read_text()=="1048576\n"

print("Kernel scope fixtures passed: SLUB, ION, Binder, MGLRU, zRAM limits, KSM restore and exclusive memcg.")
