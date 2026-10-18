#!/usr/bin/env python3
"""Writes the structure templates the Forge GameTests use (src/main/java/com/mrgregles/bsp_inventorysync/gametest): an empty
box of air that each test fills itself. A template is a gzipped NBT compound: size, palette, blocks, entities, DataVersion.
Generated files are never edited by hand; rerun this script instead.

  python3 tools/gen_gametest_structures.py
"""
import gzip
import struct
from pathlib import Path

DATA = Path(__file__).resolve().parent.parent / "src/main/resources/data/bsp_inventorysync"
DATA_VERSION = 3465  # Minecraft 1.20.1


def tag_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(tag_type, name, payload):
    return bytes([tag_type]) + tag_string(name) + payload


def compound(children):
    return b"".join(children) + b"\x00"


def int_list(name, values):
    return named(9, name, bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values))


def compound_list(name, items):
    return named(9, name, bytes([10]) + struct.pack(">i", len(items)) + b"".join(items))


def empty_structure(w, h, d):
    root = compound([
        int_list("size", [w, h, d]),
        compound_list("palette", [compound([named(8, "Name", tag_string("minecraft:air"))])]),
        compound_list("blocks", []),
        compound_list("entities", []),
        named(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
    ])
    return named(10, "", root)


def main():
    out = DATA / "structures"
    out.mkdir(parents=True, exist_ok=True)
    for name, dims in (("empty", (8, 4, 8)),):
        (out / f"{name}.nbt").write_bytes(gzip.compress(empty_structure(*dims)))
        print(f"{name}.nbt {dims}")


if __name__ == "__main__":
    main()
