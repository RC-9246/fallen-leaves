#!/usr/bin/env python3
"""Opt-in real-device test for the actual root helper shipped in an APK.

python3 tests/test-download-cache.py --serial SERIAL --apk build/Fallen-Leaves.apk

Requires an already authorized root shell and an existing Download directory on
ext4 or F2FS. All listings and eviction requests use disposable test files only.
"""
import argparse
import hashlib
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]

    def shell(command, *, root=False, check=True):
        command = 'su -c ' + shlex.quote(command) if root else command
        return subprocess.run(adb + ['shell', command], capture_output=True, text=True,
                              timeout=90, check=check)

    abi = shell('getprop ro.product.cpu.abi').stdout.strip()
    user = shell('am get-current-user').stdout.strip()
    assert abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64') and re.fullmatch(r'[0-9]{1,5}', user)
    assert shell('id -u', root=True).stdout.strip() == '0'
    name = 'leaf-cache-test-' + secrets.token_hex(16)
    data = '/data/local/tmp/' + name
    folder = '/data/media/' + user + '/Download/' + name
    parent = name.encode().hex()
    binary = data + '/tool'
    shell('umask 077 && mkdir ' + data)
    created = False
    try:
        shell('umask 077 && mkdir ' + folder, root=True)
        created = True
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp)
            with zipfile.ZipFile(args.apk) as apk:
                helper = apk.read('lib/' + abi + '/libfallenrootfile.so')
            (local / 'tool').write_bytes(helper)
            contents = secrets.token_bytes(4 * 1024 * 1024)
            (local / 'bytes').write_bytes(contents)
            for item in ('bytes','tool'):
                subprocess.run(adb + ['push', local / item, data + '/' + item], check=True, capture_output=True)
        shell('chmod 700 ' + binary)
        shell(f'cd {folder} && mkdir pages && dd if={data}/bytes of=file bs=32768 conv=fsync'
              ' && ln -s file link && touch h1 && ln h1 h2', root=True)

        def invoke(operation, path, *parts, check=True):
            command = shlex.join([binary, operation, user, path, *parts])
            return shell(command, root=True, check=check)

        rows = [line.split() for line in invoke('list', parent, '-').stdout.splitlines()]
        by_name = {bytes.fromhex(row[1]).decode(): row for row in rows}
        assert set(by_name) == {'pages','file'}
        item = by_name['file']
        shell('cat ' + folder + '/file >/dev/null', root=True)
        fields = invoke('evict', parent, item[1], item[3]).stdout.split()
        assert fields[0] == 'DONE'
        size, page, span, before, after = map(int, fields[1:])
        assert size == span == len(contents) and before == (span + page - 1) // page and 0 <= after <= before
        digest = shell('sha256sum ' + folder + '/file', root=True).stdout.split()[0]
        assert digest == hashlib.sha256(contents).hexdigest()
        assert shell('stat -c %s ' + folder + '/file', root=True).stdout.strip() == str(size)
        assert invoke('evict', parent, 'link'.encode().hex(), item[3], check=False).returncode
        assert invoke('list', parent + '/2e2e', '-', check=False).returncode
        shell(f'cd {folder} && mv file original && cp {data}/bytes file', root=True)
        assert invoke('evict', parent, item[1], item[3], check=False).returncode
        shell(f'cd {folder}/pages && i=1; while [ "$i" -le 99 ]; do touch "p$i"; i=$((i+1)); done', root=True)
        sub = parent + '/' + 'pages'.encode().hex()
        first = invoke('list', sub, '-').stdout.splitlines()
        assert first[-1].startswith('N ')
        cursor = first[-1].split()[1]
        second = invoke('list', sub, cursor).stdout.splitlines()
        assert len({l.split()[1] for l in first[:-1]+second}) == 99
        shell('touch ' + folder + '/pages/changed', root=True)
        assert invoke('list', sub, cursor, check=False).returncode
        print(f'Download-cache guards passed. Measured {before} → {after} pages of {page} bytes, file intact.')
    finally:
        if created:
            shell(f'cd {folder} && rm -f bytes file original h1 h2 link && if [ -d pages ]; then '
                  'i=1; while [ "$i" -le 99 ]; do rm -f "pages/p$i"; i=$((i+1)); done; '
                  'rm -f pages/changed && rmdir pages; fi && rmdir ' + folder, root=True)
        shell('cd ' + data + ' && rm -f bytes tool && rmdir ' + data)


if __name__ == '__main__':
    main()
