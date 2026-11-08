"""Writes the grave block's assets and data (D25 17 to 20, D26, D27): the block state (four facings by four stone kinds),
one model per stone kind (an upright headstone on a base, vanilla textures, and a glowing golden sigil on its front and
back), the sigil texture, the lang line, and the block and item tags. Never edit the files it writes; change this
script and run it again:

    python3 tools/gen_grave_assets.py

Model, in sixteenths of a block, facing north (the other facings turn it), size B of tools/preview/grave_size_concepts.html:
base 0..16 x 0..2 x 8..16, stone 1..15 x 2..16 x 10..14, cap 3..13 x 16..19 x 10..14. The sigil is a flat element just
in front of the stone's front (north) face and just behind its back (south) face, drawn full-bright (Forge's forge_data
block_light and sky_light 15, no shading) in the cutout layer (not mipmapped: mipmaps would fade the 1-pixel strokes away within a few blocks, as
the mipped shader drops what is under half opaque); the block's own light stays 4. The owner's head in front
is drawn by client/GraveRenderer, not by the model. GraveBlock's shapes are the same boxes (plus the head).
"""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
MOD = "bsp_inventorysync"
ASSETS = ROOT / "assets" / MOD
DATA = ROOT / "data"

# kind -> (stone texture, base texture), all vanilla
KINDS = {
    "stone": ("minecraft:block/stone", "minecraft:block/smooth_stone"),
    "deepslate": ("minecraft:block/polished_deepslate", "minecraft:block/deepslate_tiles"),
    "blackstone": ("minecraft:block/polished_blackstone", "minecraft:block/polished_blackstone_bricks"),
    "end_stone": ("minecraft:block/end_stone", "minecraft:block/end_stone_bricks"),
}
FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}
BASE = ([0, 0, 8], [16, 2, 16])
STONE = ([1, 2, 10], [15, 16, 14])
CAP = ([3, 16, 10], [13, 19, 14])
BOXES = [(BASE, "base"), (STONE, "stone"), (CAP, "stone")]
# the owner's head, drawn by client/GraveRenderer (not by the model), as grave/GraveShape and grave/GraveLean have it:
# sixteenths, its middle on the ground at (x, z), sunk, leaning this many degrees (left negative); for the preview scripts
HEAD = {"size": 8, "x": 8, "z": 4.5, "sink": 2, "degrees": 18}
SIGIL_GAP = 0.1  # how far the sigil stands off the stone's faces, in sixteenths (less flickers against the stone from afar)
GOLD = (0xFF, 0xD2, 0x3A)

# the sigil, as seen from in front: '#' glows gold. `sigil_l` of tools/gen_grave_detail_concepts.py (D27 5a), 12 by 13,
# centred on the stone's 14 by 14 face as the mock-up centres it (one column in from each side, from the top row).
SIGIL = ["....####....",
         "...#....#...",
         "...#....#...",
         "....####....",
         "..########..",
         ".....##.....",
         "....####....",
         "...#.##.#...",
         "..#..##..#..",
         ".....##.....",
         "....#..#....",
         "...#....#...",
         "..#......#.."]


def element(frm, to, tex):
    faces = {}
    for face, axis, edge in (("west", 0, 0), ("east", 0, 16), ("down", 1, 0), ("up", 1, 16), ("north", 2, 0), ("south", 2, 16)):
        f = {"texture": "#" + tex}
        if (frm if edge == 0 else to)[axis] == edge:
            f["cullface"] = face  # on the block's edge: hidden when a full block stands there
        faces[face] = f
    return {"from": frm, "to": to, "faces": faces}


def sigil_elements():
    """The sigil on the stone's front and back: flat, full-bright, unshaded, the texture's 14 by 14 middle on the face."""
    (x1, y1, z1), (x2, y2, z2) = STONE
    out = []
    for face, z in (("north", z1 - SIGIL_GAP), ("south", z2 + SIGIL_GAP)):
        out.append({"from": [x1, y1, z], "to": [x2, y2, z], "shade": False,
                    "forge_data": {"block_light": 15, "sky_light": 15},
                    "faces": {face: {"texture": "#sigil", "uv": [1, 1, 15, 15]}}})
    return out


def model(stone, base):
    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": {"particle": stone, "stone": stone, "base": base, "sigil": f"{MOD}:block/grave_sigil"},
        "elements": [element(f, t, k) for (f, t), k in BOXES] + sigil_elements(),
    }


def sigil_png():
    """16 by 16 RGBA: gold where the sigil glows, transparent elsewhere; the face's 14 by 14 sits at 1..15."""
    face = STONE[1][0] - STONE[0][0], STONE[1][1] - STONE[0][1]
    ox, oy = 1 + (face[0] - len(SIGIL[0])) // 2, 1 + (face[1] - len(SIGIL)) // 2
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    for j, row in enumerate(SIGIL):
        for i, ch in enumerate(row):
            if ch == "#":
                px[oy + j][ox + i] = GOLD + (255,)
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in px)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(obj, bytes):
        path.write_bytes(obj)
    else:
        path.write_text(json.dumps(obj, indent=2) + "\n")
    print(path.relative_to(ROOT))


def tag(values):
    return {"replace": False, "values": values}


def main():
    variants = {}
    for kind in KINDS:
        for facing, y in FACINGS.items():
            v = {"model": f"{MOD}:block/grave_{kind}"}
            if y:
                v["y"] = y
            variants[f"facing={facing},kind={kind}"] = v  # any lean: the head is the renderer's
    write(ASSETS / "blockstates" / "grave.json", {"variants": variants})
    for kind, (stone, base) in KINDS.items():
        write(ASSETS / "models" / "block" / f"grave_{kind}.json", model(stone, base))
    write(ASSETS / "textures" / "block" / "grave_sigil.png", sigil_png())
    write(ASSETS / "lang" / "en_us.json", {f"block.{MOD}.grave": "Grave"})

    grave = [f"{MOD}:grave"]
    # nothing in the game may remove a grave: the wither, the dragon, world features (trees, ores) growing into it
    write(DATA / "minecraft" / "tags" / "blocks" / "wither_immune.json", tag(grave))
    write(DATA / "minecraft" / "tags" / "blocks" / "dragon_immune.json", tag(grave))
    write(DATA / "minecraft" / "tags" / "blocks" / "features_cannot_replace.json", tag(grave))
    # claims never keep a grave out of reach (D25 15a), and Botania's Gaia fight never breaks one; unused without those mods
    write(DATA / "ftbchunks" / "tags" / "blocks" / "interact_whitelist.json", tag(grave))
    write(DATA / "botania" / "tags" / "blocks" / "gaia_break_blacklist.json", tag(grave))
    # item mods that lift blocks with their block entity (unused without those mods)
    write(DATA / "carryon" / "tags" / "blocks" / "block_blacklist.json", tag(grave))
    write(DATA / "mekanism" / "tags" / "blocks" / "cardboard_blacklist.json", tag(grave))
    # items that never go into a grave (they drop as usual): none to start with
    write(DATA / MOD / "tags" / "items" / "no_grave.json", tag([]))


if __name__ == "__main__":
    main()
