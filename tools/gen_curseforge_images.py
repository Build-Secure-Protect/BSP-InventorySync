#!/usr/bin/env python3
"""Draws the images of the CurseForge description (docs/curseforge/DESCRIPTION.md) as SVG in the BSP "Holo HUD" look and
renders them to PNG with macOS Quick Look (qlmanage), cropped with sips. Generated files are never edited by hand.

  python3 tools/gen_curseforge_images.py            # writes docs/curseforge/images/*.png (and keeps the .svg next to them)
"""
import subprocess
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs/curseforge/images"
SCALE = 2
BG, PANEL, INSET, LINE = "#0B0F14", "#10151C", "#0C0E12", "#2A2F3A"
ACCENT, VIOLET, GOLD, BAD, TEXT, MUTED = "#19D3B0", "#B58CFF", "#FFD23A", "#FF6B5C", "#E6EAF2", "#9AA3B5"
SANS = "Helvetica Neue, Helvetica, Arial, sans-serif"
MONO = "Menlo, Monaco, monospace"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
                      f'<rect width="{w}" height="{h}" fill="{BG}"/>']

    def rect(self, x, y, w, h, fill=PANEL, stroke=None, r=6, sw=1.5, opacity=1):
        s = f' stroke="{stroke}" stroke-width="{sw}"' if stroke else ''
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"{s} opacity="{opacity}"/>')

    def text(self, x, y, s, size=18, fill=TEXT, weight="normal", anchor="start", family=SANS, style="normal", spacing=0):
        ls = f' letter-spacing="{spacing}"' if spacing else ''
        self.parts.append(f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" font-style="{style}" fill="{fill}" text-anchor="{anchor}"{ls}>{esc(s)}</text>')

    def line(self, x1, y1, x2, y2, stroke=LINE, sw=2, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ''
        self.parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" stroke-width="{sw}"{d} stroke-linecap="round"/>')

    def arrow(self, x1, y1, x2, y2, stroke=ACCENT, sw=2.5):
        self.line(x1, y1, x2, y2, stroke, sw)
        import math
        a = math.atan2(y2 - y1, x2 - x1)
        for d in (-0.5, 0.5):
            self.line(x2, y2, x2 - 14 * math.cos(a + d), y2 - 14 * math.sin(a + d), stroke, sw)

    def circle(self, cx, cy, r, fill, stroke=None):
        s = f' stroke="{stroke}" stroke-width="2"' if stroke else ''
        self.parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"{s}/>')

    def panel(self, x, y, w, h, title, color=ACCENT):
        self.rect(x, y, w, h, PANEL, color, r=8)
        self.text(x + 16, y + 30, title.upper(), 15, color, "bold", spacing=1.5)
        self.line(x + 16, y + 42, x + w - 16, y + 42, LINE, 1)

    def badge(self, x, y, s, color=ACCENT, size=15):
        w = len(s) * size * 0.62 + 28
        self.rect(x, y, w, size + 16, INSET, color, r=(size + 16) / 2)
        self.text(x + w / 2, y + size + 4, s, size, color, "bold", "middle")
        return w

    def save(self, name):
        # Quick Look renders onto a square canvas anchored at the top, while sips crops from the centre; so the drawing is
        # centred in a square SVG and the centred crop takes exactly the drawing.
        side = max(self.w, self.h)
        body = self.parts[2:]
        doc = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{side}" height="{side}" viewBox="0 0 {side} {side}">',
               f'<rect width="{side}" height="{side}" fill="{BG}"/>',
               f'<g transform="translate({(side - self.w) // 2}, {(side - self.h) // 2})">',
               f'<rect width="{self.w}" height="{self.h}" fill="{BG}"/>'] + body + ['</g>', '</svg>']
        OUT.mkdir(parents=True, exist_ok=True)
        svg = OUT / f"{name}.svg"
        svg.write_text("\n".join(doc), encoding="utf-8")
        render(svg, self.w, self.h)


def render(svg, w, h):
    png = svg.with_suffix(".png")
    side = max(w, h) * SCALE
    subprocess.run(["qlmanage", "-t", "-s", str(side), "-o", str(OUT), str(svg)], check=True, capture_output=True)
    produced = OUT / (svg.name + ".png")
    subprocess.run(["sips", "-c", str(h * SCALE), str(w * SCALE), str(produced), "--out", str(png)], check=True, capture_output=True)
    produced.unlink()
    print(f"{png.name} {w * SCALE}x{h * SCALE}")


# ------------------------------------------------------------------ 1 banner

def banner():
    s = Svg(1200, 400)
    s.rect(0, 0, 1200, 400, BG, r=0)
    for i in range(0, 1200, 40):
        s.line(i, 0, i, 400, "#111821", 1)
    for j in range(0, 400, 40):
        s.line(0, j, 1200, j, "#111821", 1)
    s.rect(60, 60, 1080, 280, PANEL, ACCENT, r=12)
    s.text(100, 150, "BSP-InventorySync", 58, TEXT, "bold")
    s.text(100, 196, "Your inventory follows you across the Build Secure Protect network.", 24, MUTED)
    x = 100
    for label, color in (("Server-side only", ACCENT), ("Totem-safe", GOLD), ("Instant switches with Redis", VIOLET), ("Rollback with /invsync", ACCENT)):
        x += s.badge(x, 240, label, color) + 14
    s.text(100, 312, "Minecraft 1.20.1  ·  Forge 47.4  ·  requires BSP-Core  ·  MIT", 16, MUTED, family=MONO)
    s.save("banner")


# ------------------------------------------------------------------ 2 how a switch works

def switch():
    s = Svg(1200, 600)
    s.text(40, 48, "How a server switch works", 30, TEXT, "bold")
    s.text(40, 78, "The server you leave saves first; the server you reach waits for that save, then hands you your things. You are held still for a few milliseconds.", 16, MUTED)
    s.panel(40, 110, 420, 330, "Server you leave", GOLD)
    s.panel(740, 110, 420, 330, "Server you reach", ACCENT)
    s.rect(500, 230, 200, 90, INSET, VIOLET, r=10)
    s.text(600, 268, "Velocity proxy", 18, VIOLET, "bold", "middle")
    s.text(600, 294, "moves the player", 14, MUTED, "normal", "middle")
    steps_left = [("1", "BSP-Core takes your Shatter Totem", "it travels its own way, never in the snapshot"),
                  ("2", "Snapshot taken", "inventory, XP, health, effects, mod data"),
                  ("3", "Written to MySQL, cached in Redis", "one compressed record"),
                  ("4", "Released", "the checkout says: this player is free")]
    steps_right = [("5", "You log in, frozen", "no moving, no clicks, no damage"),
                   ("6", "Waits for the release", "polled every tick; usually already there"),
                   ("7", "Snapshot applied", "from Redis, MySQL as the safety net"),
                   ("8", "Totem handed back, you are free", "BSP-Core's hand-back is kept, never overwritten")]
    for i, (n, t, sub) in enumerate(steps_left):
        y = 180 + i * 62
        s.circle(72, y - 6, 14, GOLD)
        s.text(72, y - 1, n, 15, BG, "bold", "middle")
        s.text(100, y - 4, t, 17, TEXT, "bold")
        s.text(100, y + 16, sub, 13, MUTED)
    for i, (n, t, sub) in enumerate(steps_right):
        y = 180 + i * 62
        s.circle(772, y - 6, 14, ACCENT)
        s.text(772, y - 1, n, 15, BG, "bold", "middle")
        s.text(800, y - 4, t, 17, TEXT, "bold")
        s.text(800, y + 16, sub, 13, MUTED)
    s.arrow(460, 275, 500, 275, VIOLET)
    s.arrow(700, 275, 740, 275, VIOLET)
    # the shared stores
    s.rect(300, 470, 600, 100, INSET, LINE, r=10)
    s.text(600, 500, "shared by every server", 13, MUTED, "normal", "middle", spacing=1)
    s.badge(340, 518, "MySQL: snapshots, presence, history", GOLD, 14)
    s.badge(700, 518, "Redis: checkout + newest snapshot", ACCENT, 14)
    s.line(250, 440, 420, 470, LINE, 1.5, "6 6")
    s.line(950, 440, 780, 470, LINE, 1.5, "6 6")
    s.save("how-a-switch-works")


# ------------------------------------------------------------------ 3 what travels

def travels():
    s = Svg(1200, 640)
    s.text(40, 48, "What travels with you", 30, TEXT, "bold")
    s.text(40, 78, "Everything a player owns or is, from vanilla and from the pack's mods. A few things stay put on purpose.", 16, MUTED)
    cols = [
        (40, "Vanilla", ACCENT, ["Inventory, armour, offhand", "Selected slot and cursor item", "Ender chest", "Experience and score", "Health, hunger, saturation, air", "Potion effects", "Game mode and flight", "Respawn point (bed or anchor)", "Advancements"]),
        (420, "Mods", VIOLET, ["Curios slots (rings, elytra slot, jetpack)", "Sophisticated Backpacks contents", "Dank Storage contents", "Waystones you know", "Mekanism radiation", "Starter Kit's \"kit given\" flag", "Any mod's persistent player data", "Any mod's player capabilities", "Item NBT: Apotheosis, Botania, Tinkers..."]),
        (800, "Stays on each server", BAD, ["Shatter Totems (BSP-Core carries them)", "Statistics", "Recipe book", "BSP-Core's last-position markers", "Anything you list in the config", "", "Items from a mod a server lacks become", "a named sheet of paper, and turn back", "into the item where the mod exists."]),
    ]
    for x, title, color, lines in cols:
        s.panel(x, 110, 360, 500, title, color)
        for i, line in enumerate(lines):
            y = 186 + i * 44
            if line:
                if i < 6 or title != "Stays on each server":
                    s.circle(x + 28, y - 6, 5, color)
                    s.text(x + 46, y, line, 16, TEXT)
                else:
                    s.text(x + 24, y, line, 14, MUTED, style="italic")
    s.save("what-travels")


# ------------------------------------------------------------------ 4 the /invsync status mock

def status():
    s = Svg(1200, 660)
    s.text(40, 48, "/invsync status and /invsync snapshots", 30, TEXT, "bold")
    s.text(40, 78, "Operators see the whole picture in chat: the store, Redis, who is waiting, and every snapshot a player has.", 16, MUTED)
    s.rect(40, 110, 1120, 520, "#1C1C1Ccc", None, r=4)
    lines = [
        ("> /invsync status", GOLD),
        ("BSP-InventorySync on \"BSP-Spawn\", api 1", TEXT),
        ("store: mysql 192.168.1.25:3306/bsp as bsp, tables bsp_is_* (settings from this mod's", TEXT),
        ("  bsp_inventorysync-server.toml); redis 192.168.1.25:6379: connected; sent 1204, failed 0", TEXT),
        ("waiting for their data: 0; auto-saves written 37, skipped 412; store timeouts 0; snapshots kept 16", TEXT),
        ("arrival: timeout 10 s, then apply the last known", TEXT),
        ("modules: inventory, ender_chest, experience, game, vitals, advancements, forge, curios, backpacks, dank", TEXT),
        ("BSP-Core: isTotem=internal, giveBack=internal", TEXT),
        ("store ping: ok", ACCENT),
        ("", TEXT),
        ("> /invsync snapshots Mrgregles", GOLD),
        ("Mrgregles: 5 snapshot(s)", TEXT),
        ("  v12  transfer  from BSP-SafeRoom  40 s ago  6.1 KB", TEXT),
        ("  v11  autosave  from BSP-SafeRoom  2 min ago  6.1 KB", TEXT),
        ("  v10  death  from BSP-SafeRoom  9 min ago  1.3 KB", TEXT),
        ("  v9  logout  from BSP-Spawn  31 min ago  6.0 KB  pinned", GOLD),
        ("  v8  autosave  from BSP-Spawn  33 min ago  6.0 KB", TEXT),
        ("", TEXT),
        ("> /invsync restore Mrgregles 9", GOLD),
        ("rolled Mrgregles back to v9 (logout from BSP-Spawn)", ACCENT),
    ]
    for i, (line, color) in enumerate(lines):
        s.text(60, 142 + i * 24, line, 15.5, color, family=MONO)
    s.save("invsync-status")


# ------------------------------------------------------------------ 5 snapshots and rollback

def rollback():
    s = Svg(1200, 420)
    s.text(40, 48, "Snapshots, pins and rollback", 30, TEXT, "bold")
    s.text(40, 78, "Every save is kept as a numbered snapshot with its cause. The last 16 stay; a pinned one stays for ever. An operator can put any of them back.", 16, MUTED)
    s.line(80, 230, 1120, 230, LINE, 3)
    snaps = [("v7", "logout", MUTED), ("v8", "autosave", MUTED), ("v9", "logout", GOLD), ("v10", "death", BAD), ("v11", "autosave", MUTED), ("v12", "transfer", ACCENT)]
    for i, (v, cause, color) in enumerate(snaps):
        x = 130 + i * 190
        s.rect(x - 50, 180, 100, 100, PANEL, color, r=8)
        s.text(x, 215, v, 20, TEXT, "bold", "middle", MONO)
        s.text(x, 245, cause, 14, color, "normal", "middle", MONO)
        if v == "v9":
            s.text(x, 158, "pinned", 13, GOLD, "bold", "middle")
            s.line(x, 164, x, 180, GOLD, 2)
        if v == "v10":
            s.text(x, 300, "inventory lost to a grave bug", 13, BAD, "normal", "middle")
    s.arrow(1080, 345, 560, 305, VIOLET, 3)
    s.text(820, 350, "/invsync restore <player> 9", 16, VIOLET, "bold", "middle", MONO)
    s.text(1080, 170, "newest", 13, MUTED, "normal", "middle")
    s.text(130, 170, "oldest kept", 13, MUTED, "normal", "middle")
    s.text(600, 400, "/invsync pin  ·  /invsync unpin  ·  /invsync resync (back to the newest)  ·  /invsync save", 15, MUTED, "normal", "middle", MONO)
    s.save("snapshots-and-rollback")


# ------------------------------------------------------------------ 6 the totem rule

def totem():
    s = Svg(1200, 470)
    s.text(40, 48, "Built for BSP-Core's Shatter Totems", 30, TEXT, "bold")
    s.text(40, 78, "A totem must never be duplicated or lost. The sync and BSP-Core agree on who carries what, in every order the events can fire.", 16, MUTED)
    panels = [
        (40, "Leaving", GOLD, ["BSP-Core removes the totem from", "your hands and stores it for the", "journey (or places it in the world", "on an ordinary logout).", "", "The sync's snapshot never holds a", "live totem, whatever runs first."]),
        (420, "Travelling", VIOLET, ["Two separate records move:", "", "  BSP-Core: the totem itself", "  InventorySync: everything else", "", "A totem nobody collects goes back", "to where it left after 48 hours."]),
        (800, "Arriving", ACCENT, ["BSP-Core hands the totem back a", "few ticks after login. The sync", "notices it, applies the snapshot", "around it, and gives it back", "through BSP-Core: never dropped,", "never overwritten.", "Then BSP-Core delivers the rest."]),
    ]
    for x, title, color, lines in panels:
        s.panel(x, 110, 360, 330, title, color)
        for i, line in enumerate(lines):
            s.text(x + 24, 180 + i * 34, line, 16, TEXT if not line.startswith("  ") else color, family=MONO if line.startswith("  ") else SANS)
    s.arrow(400, 275, 420, 275, VIOLET)
    s.arrow(780, 275, 800, 275, VIOLET)
    s.save("totem-rule")


# ------------------------------------------------------------------ 7 missing items

def missing():
    s = Svg(1200, 380)
    s.text(40, 48, "Items from a mod a server lacks", 30, TEXT, "bold")
    s.text(40, 78, "Nothing is thrown away. The item becomes a named sheet of paper that keeps the original data, and turns back into the item on a server that has the mod.", 16, MUTED)
    # tooltip mock
    s.rect(80, 130, 460, 150, "#100010f0", "#28007B", r=4)
    s.rect(84, 134, 452, 142, "none", "#5000FF", r=3, sw=1.5)
    s.text(104, 172, "Missing item: mekanism:meka_tool", 20, BAD, "normal", style="italic")
    s.text(104, 204, "Comes back when its mod is installed", 16, MUTED)
    s.text(104, 250, "minecraft:paper", 14, "#555566", family=MONO)
    s.arrow(560, 205, 660, 205, ACCENT, 3)
    s.text(610, 190, "server with the mod", 13, MUTED, "normal", "middle")
    s.rect(680, 130, 460, 150, "#100010f0", "#28007B", r=4)
    s.rect(684, 134, 452, 142, "none", "#5000FF", r=3, sw=1.5)
    s.text(704, 172, "Meka-Tool", 20, TEXT)
    s.text(704, 204, "Energy: 1.2 MFE / 1.2 MFE", 16, ACCENT)
    s.text(704, 232, "all original NBT kept", 14, MUTED, style="italic")
    s.text(600, 340, "The paper stacks only with itself and keeps its count; splitting it keeps the data on every sheet.", 15, MUTED, "normal", "middle")
    s.save("missing-items")


if __name__ == "__main__":
    for fn in (banner, switch, travels, status, rollback, totem, missing):
        fn()
    if "--keep-svg" not in sys.argv:
        for f in OUT.glob("*.svg"):
            f.unlink()
