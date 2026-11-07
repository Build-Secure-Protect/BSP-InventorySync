"""Writes the grave block's assets and data (D25 17 to 20): the block state (four facings by four stone kinds), one model
per stone kind (an upright headstone on a low base, vanilla textures only), the lang line, and the block and item tags.
Never edit the files it writes; change this script and run it again:

    python3 tools/gen_grave_assets.py

Model, in sixteenths of a block, facing north (the other facings turn it): base 2..14 x 0..2 x 4..12, stone
3..13 x 2..13 x 6.5..9.5, cap 5..11 x 13..15 x 6.5..9.5. GraveBlock's shapes are the same boxes.
"""
import json
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
BOXES = [  # (from, to, texture key)
    ([2, 0, 4], [14, 2, 12], "base"),
    ([3, 2, 6.5], [13, 13, 9.5], "stone"),
    ([5, 13, 6.5], [11, 15, 9.5], "stone"),
]


def element(frm, to, tex):
    faces = {}
    for face in ("north", "east", "south", "west", "up", "down"):
        f = {"texture": "#" + tex}
        if face == "down" and frm[1] == 0:
            f["cullface"] = "down"
        faces[face] = f
    return {"from": frm, "to": to, "faces": faces}


def model(stone, base):
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": {"particle": stone, "stone": stone, "base": base},
        "elements": [element(f, t, k) for f, t, k in BOXES],
    }


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
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
            variants[f"facing={facing},kind={kind}"] = v
    write(ASSETS / "blockstates" / "grave.json", {"variants": variants})
    for kind, (stone, base) in KINDS.items():
        write(ASSETS / "models" / "block" / f"grave_{kind}.json", model(stone, base))
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
