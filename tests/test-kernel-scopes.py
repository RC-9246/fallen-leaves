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

    def call(key, *, run=False, prelude="", want=None, page=4096):
        # These fixtures cannot create real block devices without privileges.
        stat_fixture = "stat() { [ \"$3\" = '%t:%T:%f' ] && [ \"$4\" = '/dev/block/active-swap' ] && echo fe:0:61a0; }"
        cmds = stat_fixture + "\nuname() { echo 6.12.1; }\n" + prelude + "\n" + f"page={page}\nscope={key}\napply={int(run)}\n" + source
        p = subprocess.run(["sh"], input=redirect(cmds), text=True, capture_output=True)
        assert p.returncode == 0, (key, p.stdout, p.stderr)
        if want: assert p.stdout.startswith(want + "\n"), (key,p.stdout,p.stderr)
        return p.stdout

    dentry = put("/sys/kernel/slab/dentry/shrink", "sentinel")
    put("/sys/kernel/slab/dentry/aliases", "0\n")
    put("/sys/kernel/slab/dentry/partial", "12 N0=12\n")
    assert "12" in call("DENTRY", want="READY") and dentry.read_text() == "sentinel"
    call("DENTRY", run=True, want="DONE")
    assert dentry.read_text()=="1\n"
    call("INODE", want="INFO")

    mount_table = put("/proc/mounts", redirect("none /sys/kernel/debug debugfs rw 0 0\n"))
    for key, fs in [("F2FS_INODE", "f2fs"), ("EXT4_INODE", "ext4")]:
        cache = "/sys/kernel/slab/" + fs + "_inode_cache/"
        shrink = put(cache + "shrink", "untouched")
        aliases = put(cache + "aliases", "0\n")
        call(key, run=True, want="INFO")
        assert shrink.read_text() == "untouched"
        with mount_table.open("a") as mounted:
            mounted.write("device /example " + fs + " rw 0 0\n")
        aliases.write_text("1\n")
        call(key, run=True, want="INFO")
        assert shrink.read_text() == "untouched"
        aliases.write_text("0\n")
        call(key, want="READY")
        assert shrink.read_text() == "untouched"
        call(key, run=True, want="DONE")
        assert shrink.read_text() == "1\n"
    put("/proc/vmstat", "nr_ion_heap_pool 1048576\n")
    assert "4294967296 B" in call("ION_POOL",want="INFO")
    ion = put("/sys/kernel/debug/ion/heaps/system_shrink","0\n")
    assert "为 0" in call("ION_POOL",run=True,want="DONE") and ion.read_text()=="0\n"
    ion.write_text("1000\n")
    call("ION_POOL",run=True,want="DONE")
    assert ion.read_text()=="256\n"
    ion.write_text("4294967297\n")
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

    swaps = put("/proc/swaps","Filename Type Size Used Priority\n/data/zram0 file 4194304 22 5\n")
    put("/sys/block/zram0/dev","254:0\n")
    put("/sys/block/zram0/disksize","4294967296\n")
    put("/sys/block/zram0/mm_stat","99 10 4096 0 4096 0 1\n")
    idle=put("/sys/block/zram0/idle","unchanged")
    call("ZRAM_MARK_IDLE",run=True,want="INFO")
    assert idle.read_text()=="unchanged"
    swaps.write_text(redirect("Filename Type Size Used Priority\n/dev/block/active-swap partition 4194304 22 5\n"))
    for wrong in ["fe:1:61a0", "fe:0:21a0"]:
        call("ZRAM_MARK_IDLE",run=True,prelude="stat() { echo "+wrong+"; }",want="INFO")
        assert idle.read_text()=="unchanged"
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
    call("ZRAM_RECOMPRESS",run=True,prelude="uname() { echo 6.10.0; }",want="DONE")
    assert recomp.read_text()=="type=huge_idle algo=zstd max_pages=256\n"
    call("ZRAM_RECOMPRESS",run=True,prelude="uname() { echo 6.12.1-android; }",want="DONE")
    assert recomp.read_text()=="type=huge_idle priority=1 max_pages=256\n"
    writeback=put("/sys/block/zram0/writeback","unchanged")
    put("/sys/block/zram0/backing_dev","/dev/block/storage\n")
    put("/sys/block/zram0/writeback_limit_enable","1\n")
    credit=put("/sys/block/zram0/writeback_limit","4294967297\n")
    call("ZRAM_WRITEBACK",run=True,want="INFO")
    assert writeback.read_text()=="unchanged" and credit.read_text()=="4294967297\n"
    credit.write_text("64\n")
    for key, node in [("ZRAM_WRITEBACK", writeback), ("ZRAM_RECOMPRESS", recomp)]:
        node.write_text("untouched")
        call(key, run=True, prelude="getprop() { echo running; }", want="INFO")
        assert node.read_text() == "untouched"
        stopped = "getprop() { case $1 in init.svc.mmd) echo stopped;; mmd.zram.enabled) echo true;; esac; }"
        call(key, run=True, prelude=stopped, want="INFO")
        assert node.read_text() == "untouched"
    call("ZRAM_WRITEBACK",run=True,want="DONE")
    assert credit.read_text()=="64\n" and writeback.read_text()=="huge_idle\n"
    writeback.write_text("untouched")
    credit.write_text("3\n")
    call("ZRAM_WRITEBACK", run=True, page=16384, want="INFO")
    assert writeback.read_text()=="untouched"
    credit.write_text("4\n")
    call("ZRAM_WRITEBACK", run=True, page=16384, prelude="uname() { echo 5.18.0; }", want="INFO")
    assert writeback.read_text()=="untouched"
    call("ZRAM_WRITEBACK", run=True, page=16384, prelude="uname() { echo 6.16.2; }", want="DONE")
    assert writeback.read_text()=="type=huge_idle\n"
    put("/sys/block/zram0/bd_stat", "1 2 3\n")
    rejection = "printf() { if [ \"$2\" = huge_idle ]; then echo '1 2 4' > bd_stat; return 1; fi; command printf \"$@\"; }"
    assert "3 → 4" in call("ZRAM_WRITEBACK", run=True, prelude=rejection, want="PARTIAL")

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
    request.write_text("untouched")
    cohort = pre.replace("pids=101; count=1", "pids='101 102'; count=2")
    members.write_text("101\n101\n")
    call("MEMCG", run=True, prelude=cohort, want="INFO")
    assert request.read_text() == "untouched"
    members.write_text("102\n101\n")
    membership = put("/proc/102/cgroup", "0::/uid_10234/pid_102\n")
    call("MEMCG", run=True, prelude=cohort, want="INFO")
    assert request.read_text() == "untouched"
    membership.write_text("0::/uid_10234\n")
    call("MEMCG", run=True, prelude=cohort, want="DONE")
    assert request.read_text() == "1048576\n"

print("Kernel scope fixtures passed: SLUB, ION, Binder, MGLRU, zRAM limits, KSM restore and exclusive memcg.")
