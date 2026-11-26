#!/usr/bin/env python3
"""Run target-reclaim shell fixtures on the host with an SDK android.jar, without a phone or Root."""
import os
from pathlib import Path
import subprocess
import tempfile

TESTS = Path(__file__).resolve().parent
SRC = TESTS.parent / 'app/src/main/java/dev/pagetrim/app'
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
ANDROID_JAR = SDK / 'platforms/android-34/android.jar'


def stub(folder, name, body):
    path = folder / name
    path.write_text('#!/bin/sh\n' + body + '\n')
    path.chmod(0o755)


def check(script, mode, case, root):
    proc = root / 'proc'
    proc.mkdir()
    (proc / 'mounts').write_text('proc /proc proc rw 0 0\n')
    swap = 'dm-0' if case == 'disk swap' else 'zram0'
    (proc / 'swaps').write_text(f'Filename Type Size Used Priority\n/dev/block/{swap} partition 40000 2000 -2\n')
    device = root / 'sys/block/zram0'
    device.mkdir(parents=True)
    (device / 'mm_stat').write_text('2048 1024 1536 0 2000 0 0 0 0\n')
    for pid, start in [(123, 4913), (124, 4925)]:
        directory = proc / str(pid)
        directory.mkdir()
        if case == 'reused pid' and pid == 123:
            start += 1
        name = 'dev.leaves.fixture' + (':worker' if pid == 124 else '')
        (directory / 'stat').write_text(f'{pid} (name) S ' + '0 ' * 18 + f'{start} 0\n')
        (directory / 'status').write_text('Uid:\t10200\t10200\t10200\t10200\nVmRSS:\t2048 kB\n')
        (directory / 'cmdline').write_bytes(name.encode() + b'\0')
        (directory / 'oom_score_adj').write_text('0\n' if case == 'foreground' and pid == 124 else '975\n')
        if case != 'missing node' or pid != 124:
            (directory / 'reclaim').touch()

    tools = root / 'bin'
    tools.mkdir()
    ps = 'echo PID UID; for p in "$TEST"/proc/[0-9]*; do test -d "$p" && echo "${p##*/} 10200"; done; '
    stub(tools, 'ps', ps + ('echo 131 10200; ' if case == 'new sibling' else '') + 'exit 0')
    state = '4 (FOREGROUND_SERVICE)' if case == 'service' else '19 (CACHED_EMPTY)'
    stub(tools, 'cmd', f'echo "{state}"')
    stub(tools, 'am', 'test "$*" = "kill --user 0 dev.leaves.fixture" || exit 2\n'
         'mv "$TEST/proc/123" "$TEST/ended123"; mv "$TEST/proc/124" "$TEST/ended124"')

    # Keep /proc as the reported mountpoint; redirect all actual I/O to the private fixture.
    script = script.replace('/proc/', str(proc) + '/').replace('/sys/block/', str(root / 'sys/block') + '/')
    env = dict(os.environ, TEST=str(root), PATH=str(tools) + os.pathsep + os.environ['PATH'])
    result = subprocess.run(['/bin/sh', '-c', script], env=env, capture_output=True, text=True, timeout=15)
    ok = case == 'normal' or (case == 'disk swap' and mode != 'ANON') or (case == 'missing node' and mode == 'COLD')
    assert (result.returncode == 0) == ok, (mode, case, result.stdout, result.stderr)
    if not ok:
        assert all(not f.read_text() for f in proc.glob('*/reclaim'))
    elif mode == 'COLD':
        assert 'COLD 0 0 0' in result.stdout
    else:
        assert 'Z0 /zram0 1536' in result.stdout and 'DONE 2' in result.stdout and 'AFTER 4096' in result.stdout
        assert all((proc / str(pid) / 'reclaim').read_text().strip() == mode.lower() for pid in (123, 124))


def main():
    with tempfile.TemporaryDirectory() as stage:
        work = Path(stage)
        # Only human-readable units are stubbed. The tested parser returns exact byte values.
        ui = work / 'MainActivity.java'
        ui.write_text('package dev.pagetrim.app; class MainActivity { static String fmt(long b){return b+" B";} }')
        sources = ['TargetReclaim', 'ProcessTrim', 'Root', 'StorageController', 'LegacyScope', 'ManualReclaim']
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(ANDROID_JAR), '-d', stage, ui,
                        TESTS / 'TargetReclaimTest.java', *(SRC / (s + '.java') for s in sources)], check=True)
        runner = ['java', '-cp', os.pathsep.join((stage, str(ANDROID_JAR))), 'dev.pagetrim.app.TargetReclaimTest']
        subprocess.run(runner, check=True)
        for mode in ('ANON', 'FILE', 'COLD'):
            script = subprocess.check_output(runner + [mode], text=True)
            for case in ('normal', 'foreground', 'service', 'reused pid', 'new sibling', 'disk swap', 'missing node'):
                with tempfile.TemporaryDirectory() as root:
                    check(script, mode, case, Path(root))
    print('Target reclaim metrics and 21 process fixtures passed.')


if __name__ == '__main__':
    main()
