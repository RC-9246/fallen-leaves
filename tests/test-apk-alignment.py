#!/usr/bin/env python3
"""Check every packaged helper and JNI library against Android's 16 KiB load rules."""
import argparse
import struct
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

PAGE = 16 * 1024
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def verify_elf(data, name):
    if data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] != 1:
        raise ValueError(f"{name}: expected a little-endian Android ELF")
    is64 = data[4] == 2
    header = struct.unpack_from("<HHIQQQIHHHHHH" if is64 else "<HHIIIIIHHHHHH", data, 16)
    phoff, entry_size, count = header[4], header[8], header[9]
    layout = "<IIQQQQQQ" if is64 else "<IIIIIIII"
    if entry_size < struct.calcsize(layout) or phoff + count * entry_size > len(data):
        raise ValueError(f"{name}: invalid program header table")
    segments = 0
    for index in range(count):
        p = struct.unpack_from(layout, data, phoff + entry_size * index)
        if p[0] != 1:  # PT_LOAD
            continue
        offset, address = (p[2], p[3]) if is64 else (p[1], p[2])
        alignment = p[7]
        if alignment < PAGE or alignment & (alignment - 1) or (address - offset) % alignment:
            raise ValueError(f"{name}: incompatible PT_LOAD alignment {alignment:#x}")
        segments += 1
    if not segments:
        raise ValueError(f"{name}: no loadable segments")


def check(apk):
    abis = set()
    count = 0
    with apk.open("rb") as raw, ZipFile(raw) as archive:
        for entry in archive.infolist():
            path = entry.filename.split("/")
            if len(path) != 3 or path[0] != "lib" or not path[-1].endswith(".so"):
                continue
            verify_elf(archive.read(entry), entry.filename)
            if entry.compress_type == ZIP_STORED:
                raw.seek(entry.header_offset + 26)
                name_len, extra_len = struct.unpack("<HH", raw.read(4))
                if (entry.header_offset + 30 + name_len + extra_len) % PAGE:
                    raise ValueError(f"{entry.filename}: unaligned uncompressed ZIP entry")
            abis.add(path[1])
            count += 1
    if abis != ABIS:
        raise ValueError(f"Expected packaged ABIs {sorted(ABIS)}, found {sorted(abis)}")
    print(f"Verified 16 KiB alignment of {count} packaged native components across {len(abis)} ABIs.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    check(parser.parse_args().apk)
