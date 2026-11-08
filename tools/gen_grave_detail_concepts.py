"""Writes tools/preview/grave_detail_concepts.html (round 1, D26) and grave_size_concepts.html (round 2, D27): mock-ups of more detail on the grave block (the owner's request of
2026-11-08): glowing golden runes on the front and back, the owner's head sticking out of the ground in front, and
golden and neon green particles. Drawn with the real vanilla 1.20.1 textures (stone, grass, Steve's skin, particles)
read from the Minecraft client jar in the Gradle cache; the rune patterns are pixel art at the block's own scale (one
pixel = 1/16 block, the stone's face is 10 by 11) and are what a later generator would write as the rune texture.
three.js r128 from cdnjs.

    python3 tools/gen_grave_detail_concepts.py
"""
import base64
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools/preview/grave_detail_concepts.html"
CLIENT = Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar"
TEXTURES = ["block/stone", "block/smooth_stone", "block/grass_block_top", "block/grass_block_side", "block/dirt",
            "entity/player/wide/steve", "particle/glow", "particle/spark_2"]

# rune patterns, 10 wide by 11 tall, as seen from in front: '#' glows gold
RUNES = {
    "columns": ["..........",
                ".#.#..#.#.",
                ".###..###.",
                "..#....#..",
                "..#....#..",
                "..........",
                ".##....##.",
                ".#.#..#.#.",
                ".##....##.",
                ".#......#.",
                ".........."],
    "sigil": ["....##....",
              "...#..#...",
              "...#..#...",
              "....##....",
              "..######..",
              "....##....",
              "...####...",
              "..#.##.#..",
              "....##....",
              "...#..#...",
              "..#....#.."],
    "sigil_l": ["....####....",
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
                "..#......#.."],
    "lines": ["..######..",
              ".#......#.",
              "..........",
              "#.#..#..#.",
              ".#..###.##",
              "#.#..#..#.",
              "..........",
              "##..#..#.#",
              "#..###.##.",
              "..........",
              "..######.."],
}


# Round 1 (D26): runes, head and particles. Sizes in sixteenths of a block; the head's x and z are its centre, sink how deep it is in the ground.
ROUND1 = {
    "file": "grave_detail_concepts.html",
    "title": "Grave Detail Concepts",
    "intro": """  <h1>Grave Detail Concepts</h1>
  <p class="lede">Three ways to add glowing golden runes (front and back), the owner's head sticking out of the ground in front,
    and golden and neon green particles. Each is drawn with the real vanilla textures; the runes are pixel art at the block's own
    scale (the stone's face is 10 by 11 pixels), exactly what the rune texture would be. Drag to turn, scroll to zoom, press Night
    to see the glow. The head shows Steve's skin; in game it is the owner's own. To make room for a full-size head, options A and C
    move the headstone to the back of its block.</p>""",
    "decisions": """  <section class="option decisions">
    <h2>Decisions (D26, answered 2026-11-08: runes B, head A leaning, particles A)</h2>
    <ol>
      <li><b>Runes</b>: the columns of A, the sigil of B or the inscription of C.</li>
      <li><b>Head</b>: A full size and out of the ground to the chin, B smaller and sitting on the grass, C full size and only out to the eyes.</li>
      <li><b>Particles</b>: A drifting motes, B a rising spiral of sparks, C soft wisps.</li>
    </ol>
  </section>""",
    "options": [
        {"id": "A", "title": "A: Engraved columns", "pick": True,
         "base": [[2, 0, 7], [14, 2, 14]], "stone": [[3, 2, 9], [13, 13, 12]], "cap": [[5, 13, 9], [11, 15, 12]],
         "head": {"size": 8, "x": 8, "z": 4, "sink": 2}, "runes": "columns", "particles": "motes",
         "pros": [["good", "Runes read as writing even from a distance; the head is unmistakable, out of the ground to the chin."],
                  ["meh", "Motes are subtle in daylight; they show best at dusk and at night."]]},
        {"id": "B", "title": "B: A sigil and a spiral",
         "base": [[2, 0, 7], [14, 2, 13]], "stone": [[3, 2, 8.5], [13, 13, 11.5]], "cap": [[5, 13, 8.5], [11, 15, 11.5]],
         "head": {"size": 6, "x": 8, "z": 3.5, "sink": 0}, "runes": "sigil", "particles": "spiral",
         "pros": [["good", "One bold sigil is the clearest shape at this size; the spiral of sparks is lively and easy to spot."],
                  ["meh", "The smaller head sits on the grass rather than coming out of it; the spiral is busier around many graves."]]},
        {"id": "C", "title": "C: Inscription and wisps",
         "base": [[2, 0, 7], [14, 2, 14]], "stone": [[3, 2, 9], [13, 13, 12]], "cap": [[5, 13, 9], [11, 15, 12]],
         "head": {"size": 8, "x": 8, "z": 4, "sink": 3}, "runes": "lines", "particles": "wisps",
         "pros": [["good", "Wisps rising from the runes and the head look the most magical; the head only out to its eyes is eerie."],
                  ["meh", "The inscription is busier at 10 by 11 pixels; a head buried that deep shows less of the skin."]]},
    ],
}

# Round 2 (D27): the size of the headstone, with what D26 chose (the sigil, a full-size head leaning in the dirt, motes).
LEAN = 18
ROUND2 = {
    "file": "grave_size_concepts.html",
    "title": "Grave Size Concepts",
    "intro": """  <h1>Grave Size Concepts</h1>
  <p class="lede">The headstone at three sizes, each with what you chose: one glowing sigil front and back, your head out of the
    ground to the chin and leaning """ + str(LEAN) + """ degrees to one side in the dirt, and drifting gold and neon green motes. A plain
    stone block stands beside it to compare. Today's headstone is 10 pixels wide and 15 tall (a block is 16 by 16). Drag to turn,
    scroll to zoom, Night for the glow.</p>""",
    "decisions": """  <section class="option decisions">
    <h2>Decisions</h2>
    <ol>
      <li><b>Size</b>: A, B or C. <span class="d">Default: B, a block wide and a little over a block tall</span>: next to a normal block it reads as a proper headstone, and a player can still jump onto it.</li>
      <li><b>Which way the head leans</b>: <span class="d">Default: left or right, chosen per grave (from its id), so a row of graves does not look stamped out</span>. The other choice: always the same way.</li>
      <li><b>How far it leans</b>: <span class="d">Default: """ + str(LEAN) + """ degrees</span>, enough to read as fallen into the dirt without hiding the face.</li>
      <li><b>Its shape</b>: <span class="d">Default: what you can bump into and click follows the headstone, its base and the head</span>; with C the stone is taller than a player can jump onto.</li>
    </ol>
  </section>""",
    "options": [
        {"id": "A", "title": "A: A full block tall", "ref": True, "dist": 3.4, "yaw": -0.55, "lookX": 0.45, "lookY": 0.55,
         "base": [[1, 0, 8], [15, 2, 15]], "stone": [[2, 2, 10], [14, 14, 14]], "cap": [[4, 14, 10], [12, 16, 14]],
         "head": {"size": 8, "x": 8, "z": 4.5, "sink": 2, "lean": LEAN}, "runes": "sigil", "particles": "motes",
         "pros": [["good", "Slightly larger, as asked: 12 wide and exactly a block tall; the closest to today's look."],
                  ["meh", "Still clearly smaller than a full block beside it."]]},
        {"id": "B", "title": "B: A block wide, a little taller", "pick": True, "ref": True, "dist": 3.4, "yaw": -0.55, "lookX": 0.45, "lookY": 0.55,
         "base": [[0, 0, 8], [16, 2, 16]], "stone": [[1, 2, 10], [15, 16, 14]], "cap": [[3, 16, 10], [13, 19, 14]],
         "head": {"size": 8, "x": 8, "z": 4.5, "sink": 2, "lean": -LEAN}, "runes": "sigil_l", "particles": "motes",
         "pros": [["good", "Stands as tall as a block with its cap above: easy to spot from afar, and the large sigil fills the face."],
                  ["meh", "Its base fills the block's width, so two graves side by side touch."]]},
        {"id": "C", "title": "C: A tall headstone", "ref": True, "dist": 3.4, "yaw": -0.55, "lookX": 0.45, "lookY": 0.55,
         "base": [[0, 0, 8.5], [16, 3, 16]], "stone": [[1, 3, 10], [15, 20, 14.5]], "cap": [[3, 20, 10], [13, 23, 14.5]],
         "head": {"size": 8, "x": 8, "z": 4.5, "sink": 2, "lean": LEAN}, "runes": "sigil_l", "particles": "motes",
         "pros": [["good", "A proper monument, nearly a block and a half tall; visible over hedges and crops."],
                  ["meh", "Taller than a player can jump onto; the floating text sits higher; it can look out of place in a small room."]]},
    ],
}


def main():
    with zipfile.ZipFile(CLIENT) as jar:
        tex = {t: "data:image/png;base64," + base64.b64encode(jar.read(f"assets/minecraft/textures/{t}.png")).decode() for t in TEXTURES}
    for page in (ROUND1, ROUND2):
        out = OUT.parent / page["file"]
        html = (PAGE.replace("/*DATA*/", json.dumps({"tex": tex, "runes": RUNES})).replace("/*OPTIONS*/", json.dumps(page["options"]))
                .replace("/*INTRO*/", page["intro"]).replace("/*DECISIONS*/", page["decisions"]).replace("/*TITLE*/", page["title"]))
        out.write_text(html, encoding="ascii")
        print(out.relative_to(ROOT), f"{out.stat().st_size // 1024} KB")


PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>/*TITLE*/</title>
<style>
  :root { --bg: #0B0F14; --panel: #10151C; --line: #2A2F3A; --accent: #19D3B0; --gold: #FFD23A; --text: #E6EAF2; --muted: #9AA3B5; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text); font-family: "IBM Plex Sans", system-ui, sans-serif; line-height: 1.5; }
  main { max-width: 1100px; margin: 0 auto; padding: 28px 16px 60px; }
  h1 { margin: 0; font-size: 26px; color: var(--accent); }
  h2 { margin: 0 0 4px; font-size: 19px; }
  p { margin: 8px 0; }
  .lede { color: var(--muted); }
  code { color: #B58CFF; font-family: ui-monospace, Menlo, monospace; font-size: 0.92em; }
  .option { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 18px; margin-top: 22px; }
  .option.pick { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
  .tag { display: inline-block; font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 4px; margin-left: 8px; vertical-align: middle; background: var(--accent); color: var(--bg); }
  canvas.view { width: 100%; height: 420px; display: block; border-radius: 6px; cursor: grab; margin-top: 10px; }
  .row { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
  button { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 12px; font: inherit; font-size: 13px; cursor: pointer; }
  button.on { border-color: var(--accent); color: var(--accent); }
  ul { padding-left: 18px; margin: 8px 0 0; font-size: 14px; }
  li { margin: 3px 0; }
  .good { color: var(--accent); } .meh { color: var(--gold); }
  .decisions ol { padding-left: 22px; } .decisions li { margin: 10px 0; } .d { color: var(--accent); font-weight: 600; }
  .foot { color: var(--muted); font-size: 13px; margin-top: 24px; border-top: 1px solid var(--line); padding-top: 12px; }
</style>
</head>
<body>
<main>
/*INTRO*/
  <div id="opts"></div>

/*DECISIONS*/
  <p class="foot">Mock-ups: the shape, runes and particles are as they would be built; Minecraft's own lighting, its smooth fading of particles and the
    font of the floating text differ a little in game. Every option needs a new client jar (the exact-version rule), so it goes out with the next version.</p>
</main>

<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const D = /*DATA*/;
const GOLD = [1.0, 0.82, 0.23], GREEN = [0.22, 1.0, 0.08];
const SHADE = {up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6};

const OPTIONS = /*OPTIONS*/;

const loader = new THREE.TextureLoader();
function pix(url, cb) { const t = loader.load(url, cb); t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; return t; }
function runeCanvas(rows, glow, fw, fh) {
  const scale = glow ? 8 : 1, pad = glow ? 16 : 0, c = document.createElement("canvas");
  c.width = fw * scale + 2 * pad; c.height = fh * scale + 2 * pad;
  const x = c.getContext("2d");
  if (glow) { x.filter = "blur(5px)"; }
  x.fillStyle = "#FFD23A";
  const ox = Math.floor((fw - rows[0].length) / 2), oy = Math.floor((fh - rows.length) / 2); // the pattern centred on the face
  rows.forEach((r, j) => [...r].forEach((ch, i) => { if (ch === "#") x.fillRect(pad + (ox + i) * scale, pad + (oy + j) * scale, scale, scale); }));
  return c;
}

function view(o, canvas, nightBtn) {
  const renderer = new THREE.WebGLRenderer({canvas, antialias: true});
  const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(38, 1, 0.01, 50);
  const mats = [];
  let night = false;
  const redraw = () => {};
  function mat(map, face, opts) {
    const m = new THREE.MeshBasicMaterial(Object.assign({map, side: THREE.DoubleSide}, opts || {}));
    m.userData = {face, tint: (opts && opts.tintRGB) || [1, 1, 1], bright: opts && opts.bright};
    delete m.tintRGB;
    mats.push(m);
    return m;
  }
  function shade() {
    for (const m of mats) {
      const s = m.userData.bright ? 1 : SHADE[m.userData.face] * (night ? 0.16 : 1);
      m.color.setRGB(m.userData.tint[0] * s, m.userData.tint[1] * s, m.userData.tint[2] * s);
    }
    scene.background = new THREE.Color(night ? 0x04060b : 0x8fb5de);
  }
  function corners(face, f, t) {
    const [x1, y1, z1] = f, [x2, y2, z2] = t;
    return {north: [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]], south: [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]],
            west: [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]], east: [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]],
            up: [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]], down: [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]]}[face];
  }
  function autoUV(face, f, t) {
    const [x1, y1, z1] = f, [x2, y2, z2] = t;
    return {down: [x1, 16 - z2, x2, 16 - z1], up: [x1, z1, x2, z2], north: [16 - x2, 16 - y2, 16 - x1, 16 - y1],
            south: [x1, 16 - y2, x2, 16 - y1], west: [z1, 16 - y2, z2, 16 - y1], east: [16 - z2, 16 - y2, 16 - z1, 16 - y1]}[face].map(v => v / 16);
  }
  function quad(face, f, t, material, uv) {
    const c = corners(face, f, t).map(p => p.map(v => v / 16));
    const [u1, v1, u2, v2] = uv || autoUV(face, f, t);
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute([...c[0], ...c[3], ...c[2], ...c[0], ...c[2], ...c[1]], 3));
    g.setAttribute("uv", new THREE.Float32BufferAttribute([u1, 1 - v1, u1, 1 - v2, u2, 1 - v2, u1, 1 - v1, u2, 1 - v2, u2, 1 - v1], 2));
    return new THREE.Mesh(g, material);
  }
  const FACES = ["up", "down", "north", "south", "east", "west"];
  function box(f, t, map, offset) {
    const g = new THREE.Group();
    FACES.forEach(face => g.add(quad(face, f, t, mat(map, face))));
    return g;
  }
  const T = {};
  const ready = () => { shade(); };
  for (const k of Object.keys(D.tex)) T[k] = pix(D.tex[k], () => ready());
  const world = new THREE.Group();
  // ground: 3 by 3 grass blocks, the grave's block in the middle
  for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
    const g = new THREE.Group();
    FACES.forEach(face => {
      const map = face === "up" ? T["block/grass_block_top"] : face === "down" ? T["block/dirt"] : T["block/grass_block_side"];
      g.add(quad(face, [0, 0, 0], [16, 16, 16], mat(map, face, face === "up" ? {tintRGB: [0.49, 0.74, 0.42]} : {})));
    });
    g.position.set(dx - 0.5, -1, dz - 0.5);
    world.add(g);
  }
  const grave = new THREE.Group();
  grave.position.set(-0.5, 0, -0.5);
  grave.add(box(o.base[0], o.base[1], T["block/smooth_stone"]));
  grave.add(box(o.stone[0], o.stone[1], T["block/stone"]));
  grave.add(box(o.cap[0], o.cap[1], T["block/stone"]));
  // runes, front (north) and back (south), drawn at full brightness, with a soft halo
  const [sf, st] = o.stone;
  const fw = st[0] - sf[0], fh = st[1] - sf[1];
  const runeTex = new THREE.CanvasTexture(runeCanvas(D.runes[o.runes], false, fw, fh));
  runeTex.magFilter = THREE.NearestFilter; runeTex.minFilter = THREE.NearestFilter;
  const haloTex = new THREE.CanvasTexture(runeCanvas(D.runes[o.runes], true, fw, fh));
  for (const [face, z] of [["north", sf[2] - 0.04], ["south", st[2] + 0.04]]) {
    const f = [sf[0], sf[1], z], t = [st[0], st[1], z];
    grave.add(quad(face, f, t, mat(runeTex, face, {transparent: true, alphaTest: 0.5, bright: true}), [0, 0, 1, 1]));
    const pad = 2, hz = face === "north" ? z - 0.02 : z + 0.02;
    const halo = mat(haloTex, face, {transparent: true, opacity: 0.0, blending: THREE.AdditiveBlending, depthWrite: false, bright: true});
    halo.userData.halo = true;
    grave.add(quad(face, [sf[0] - pad, sf[1] - pad, hz], [st[0] + pad, st[1] + pad, hz], halo, [0, 0, 1, 1]));
  }
  // the owner's head, facing out of the front, sunk into the ground by `sink` pixels
  const h = o.head, s = h.size, hf = [h.x - s / 2, -h.sink, h.z - s / 2], ht = [h.x + s / 2, s - h.sink, h.z + s / 2];
  const skin = T["entity/player/wide/steve"];
  const S = (u1, v1, u2, v2) => [u1 / 64, v1 / 64, u2 / 64, v2 / 64];
  const headUV = {north: S(8, 8, 16, 16), south: S(24, 8, 32, 16), east: S(0, 8, 8, 16), west: S(16, 8, 24, 16), up: S(8, 0, 16, 8), down: S(16, 0, 24, 8)};
  const headGroup = new THREE.Group();
  const rel = v => [v[0] - h.x, v[1], v[2] - h.z];
  FACES.forEach(face => headGroup.add(quad(face, rel(hf), rel(ht), mat(skin, face), headUV[face])));
  headGroup.position.set(h.x / 16, 0, h.z / 16);
  headGroup.rotation.z = (h.lean || 0) * Math.PI / 180;
  grave.add(headGroup);
  // a plain stone block beside it, to compare sizes
  if (o.ref) {
    const ref = box([0, 0, 0], [16, 16, 16], T["block/stone"]);
    ref.position.set(1, 0, 0);
    grave.add(ref);
  }
  world.add(grave);
  scene.add(world);

  // particles
  const parts = [];
  const sparkTex = pix(D.tex["particle/spark_2"]), glowTex = pix(D.tex["particle/glow"]);
  function spawn(t) {
    const green = Math.random() < 0.5, c = green ? GREEN : GOLD;
    let p;
    if (o.particles === "motes") {
      p = {pos: new THREE.Vector3((Math.random() * 12 + 2) / 16 - 0.5, Math.random() * 0.9, (Math.random() * 12 + 2) / 16 - 0.5),
           vel: new THREE.Vector3((Math.random() - 0.5) * 0.05, 0.22 + Math.random() * 0.1, (Math.random() - 0.5) * 0.05), life: 2.6, size: 0.05, map: null};
    } else if (o.particles === "spiral") {
      p = {angle: t * 2.2 + (green ? Math.PI : 0), r: 0.5, y: 0.05, rise: 0.42, life: 2.4, size: 0.08, map: sparkTex, spiral: true};
    } else {
      const fromHead = Math.random() < 0.3;
      const zc = (fromHead ? h.z : (Math.random() < 0.5 ? sf[2] - 1 : st[2] + 1)) / 16 - 0.5;
      p = {pos: new THREE.Vector3((fromHead ? h.x : 4 + Math.random() * 8) / 16 - 0.5, fromHead ? (s - h.sink) / 16 : (3 + Math.random() * 9) / 16, zc),
           vel: new THREE.Vector3(0, 0.14, 0), wobble: Math.random() * 6, life: 3.2, size: 0.13 + Math.random() * 0.06, map: glowTex};
    }
    const m = new THREE.SpriteMaterial({map: p.map, color: new THREE.Color(...c), transparent: true, depthWrite: false});
    p.sprite = new THREE.Sprite(m);
    p.age = 0;
    p.sprite.scale.set(p.size, p.size, 1);
    world.add(p.sprite);
    parts.push(p);
  }
  let yaw = o.yaw !== undefined ? o.yaw : 0.55, pitch = 0.32, dist = o.dist || 2.1, last = performance.now(), acc = 0, clock = 0;
  const rate = {motes: 3, spiral: 6, wisps: 3}[o.particles];
  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now; clock += dt; acc += dt * rate;
    while (acc >= 1) { spawn(clock); acc -= 1; }
    for (let i = parts.length - 1; i >= 0; i--) {
      const p = parts[i];
      p.age += dt;
      const k = p.age / p.life;
      if (k >= 1) { world.remove(p.sprite); parts.splice(i, 1); continue; }
      if (p.spiral) {
        p.angle += dt * 2.6; p.y += dt * p.rise;
        p.sprite.position.set(Math.cos(p.angle) * p.r * (1 - k * 0.4), p.y, Math.sin(p.angle) * p.r * (1 - k * 0.4) + 0.12);
      } else {
        p.pos.addScaledVector(p.vel, dt);
        const wob = p.wobble !== undefined ? Math.sin(clock * 3 + p.wobble) * 0.04 : 0;
        p.sprite.position.set(p.pos.x + wob, p.pos.y, p.pos.z);
      }
      p.sprite.material.opacity = Math.min(1, k * 6) * (1 - k) * (o.particles === "wisps" ? 0.9 + 0.1 * Math.sin(clock * 9 + i) : 1);
      p.sprite.material.blending = night ? THREE.AdditiveBlending : THREE.NormalBlending;
    }
    for (const m of mats) if (m.userData.halo) m.opacity = night ? 0.85 : 0.25;
    const w = canvas.clientWidth, hh = canvas.clientHeight;
    if (canvas.width !== Math.round(w * devicePixelRatio)) { renderer.setPixelRatio(devicePixelRatio); renderer.setSize(w, hh, false); camera.aspect = w / hh; camera.updateProjectionMatrix(); }
    camera.position.set(Math.sin(yaw) * Math.cos(pitch) * dist, 0.45 + Math.sin(pitch) * dist, -Math.cos(yaw) * Math.cos(pitch) * dist);
    camera.lookAt(o.lookX || 0, o.lookY || 0.38, 0);
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
  let drag = null;
  canvas.addEventListener("pointerdown", e => { drag = [e.clientX, e.clientY]; canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener("pointermove", e => { if (!drag) return; yaw += (e.clientX - drag[0]) * 0.008; pitch = Math.max(-0.1, Math.min(1.3, pitch + (e.clientY - drag[1]) * 0.006)); drag = [e.clientX, e.clientY]; });
  canvas.addEventListener("pointerup", () => drag = null);
  canvas.addEventListener("wheel", e => { e.preventDefault(); dist = Math.max(0.9, Math.min(6, dist * (1 + e.deltaY * 0.001))); }, {passive: false});
  nightBtn.addEventListener("click", () => { night = !night; nightBtn.classList.toggle("on", night); shade(); });
  shade();
  requestAnimationFrame(frame);
  return {setNight(v) { night = v; nightBtn.classList.toggle("on", v); shade(); }, look(y, p, d) { yaw = y; pitch = p; dist = d; }};
}

const opts = document.getElementById("opts");
window.views = {};
for (const o of OPTIONS) {
  const sec = document.createElement("section");
  sec.className = "option" + (o.pick ? " pick" : "");
  sec.innerHTML = `<h2>${o.title}${o.pick ? '<span class="tag">Default</span>' : ""}</h2>
    <canvas class="view"></canvas>
    <div class="row"><button class="night">Night</button></div>
    <ul>${o.pros.map(([c, t]) => `<li class="${c}">${t}</li>`).join("")}</ul>`;
  opts.appendChild(sec);
  window.views[o.id] = view(o, sec.querySelector("canvas"), sec.querySelector(".night"));
}
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
