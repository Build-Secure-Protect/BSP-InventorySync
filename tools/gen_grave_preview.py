"""Writes tools/preview/grave_block_render.html: the grave block as the game draws it, from the mod's own block state
and models (src/main/resources/assets) and the vanilla 1.20.1 textures they name, read from the Minecraft client jar in
the Gradle cache, and the mod's own textures (the sigil). Faces are lit as Minecraft lights blocks (top 1.0, north and
south 0.8, east and west 0.6, bottom 0.5), except elements with Forge's full-bright forge_data (the sigil), which keep
their colour by night; the owner's head (Steve's skin here) as client/GraveRenderer draws it from GraveShape's numbers
(HEAD below); the floating text is drawn as the mod's renderer draws it for the owner. three.js r128 from cdnjs.

    python3 tools/gen_grave_preview.py
"""
import base64
import json
import zipfile
from pathlib import Path

from gen_grave_assets import HEAD  # one place for the head's numbers

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/bsp_inventorysync"
OUT = ROOT / "tools/preview/grave_block_render.html"
CLIENT = Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar"
KINDS = ["stone", "deepslate", "blackstone", "end_stone"]
# what each kind stands on in the preview: where it would be found
FLOORS = {"stone": "grass", "deepslate": "minecraft:block/deepslate", "blackstone": "minecraft:block/netherrack", "end_stone": "minecraft:block/end_stone"}
LABELS = {"stone": "Overworld", "deepslate": "Below y 0", "blackstone": "The Nether", "end_stone": "The End"}
SKIN = "minecraft:entity/player/wide/steve"


def texture(jar, ref):
    space, name = ref.split(":", 1)
    if space == "minecraft":
        data = jar.read("assets/minecraft/textures/" + name + ".png")
    else:
        data = (ASSETS / "textures" / (name + ".png")).read_bytes()
    return "data:image/png;base64," + base64.b64encode(data).decode()


def main():
    models = {f"grave_{k}": json.loads((ASSETS / f"models/block/grave_{k}.json").read_text()) for k in KINDS}  # keyed as the block state names them
    blockstate = json.loads((ASSETS / "blockstates/grave.json").read_text())
    lang = json.loads((ASSETS / "lang/en_us.json").read_text())
    refs = set()
    for m in models.values():
        refs.update(v for v in m["textures"].values())
    refs.update(v for v in FLOORS.values() if v != "grass")
    refs.update({"minecraft:block/grass_block_top", "minecraft:block/dirt", "minecraft:block/grass_block_side", SKIN})
    with zipfile.ZipFile(CLIENT) as jar:
        textures = {r: texture(jar, r) for r in sorted(refs)}
    data = {"models": models, "blockstate": blockstate, "textures": textures, "kinds": KINDS, "floors": FLOORS, "labels": LABELS,
            "name": lang["block.bsp_inventorysync.grave"], "head": HEAD, "skin": SKIN}
    OUT.write_text(PAGE.replace("/*DATA*/", json.dumps(data)), encoding="ascii")
    print(OUT.relative_to(ROOT), f"{OUT.stat().st_size // 1024} KB")


PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Grave Block Render</title>
<style>
  :root { --bg: #0B0F14; --panel: #10151C; --line: #2A2F3A; --accent: #19D3B0; --gold: #FFD23A; --text: #E6EAF2; --muted: #9AA3B5; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text); font-family: "IBM Plex Sans", system-ui, sans-serif; }
  header { padding: 18px 20px 6px; }
  h1 { margin: 0; font-size: 22px; color: var(--accent); letter-spacing: 0.5px; }
  p { margin: 6px 0; color: var(--muted); font-size: 14px; max-width: 900px; }
  #view { width: 100%; height: 70vh; display: block; cursor: grab; }
  .bar { display: flex; gap: 8px; flex-wrap: wrap; padding: 8px 20px 20px; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 7px 12px; font: inherit; font-size: 13px; cursor: pointer; }
  button.on { border-color: var(--accent); color: var(--accent); }
</style>
</head>
<body>
<header>
  <h1>Grave block, as built (0.6.0)</h1>
  <p>Drawn from the mod's own block state and models with the real vanilla 1.20.1 textures; faces lit the way Minecraft
     lights blocks. Drag to turn, scroll to zoom. The floating text is what the owner sees; everyone else sees
     "Public in 58:12" in grey for the first hour.</p>
</header>
<canvas id="view"></canvas>
<div class="bar">
  <button data-face="north" class="on">Facing north</button><button data-face="east">Facing east</button>
  <button data-face="south">Facing south</button><button data-face="west">Facing west</button>
  <button id="text" class="on">Floating text</button><button id="night">Night</button><button id="lean">Head leans left</button>
</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const D = /*DATA*/;
const canvas = document.getElementById("view");
const renderer = new THREE.WebGLRenderer({canvas, antialias: true});
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 100);
const loader = new THREE.TextureLoader();
const texCache = {};
function tex(ref) {
  if (!texCache[ref]) {
    const t = loader.load(D.textures[ref], () => draw());
    t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter;
    texCache[ref] = t;
  }
  return texCache[ref];
}
const SHADE = {up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6};
let night = false;
const materials = [];
function mat(ref, face, tint, bright) {
  const cutout = !ref.startsWith("minecraft:");
  const m = new THREE.MeshBasicMaterial({map: tex(ref), side: THREE.DoubleSide, transparent: false, alphaTest: cutout ? 0.5 : 0});
  m.userData = {face, tint: tint || [1, 1, 1], bright};
  materials.push(m);
  return m;
}
function shadeAll() {
  for (const m of materials) {
    const s = m.userData.bright ? 1 : SHADE[m.userData.face] * (night ? (m.userData.glow ? 0.55 : 0.18) : 1);
    m.color.setRGB(m.userData.tint[0] * s, m.userData.tint[1] * s, m.userData.tint[2] * s);
  }
  scene.background = new THREE.Color(night ? 0x05070c : 0x8fb5de);
}
// vanilla's default UVs per face, from the element's position (BlockElement.uvsByDirection)
function uvFor(face, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  return {down: [x1, 16 - z2, x2, 16 - z1], up: [x1, z1, x2, z2], north: [16 - x2, 16 - y2, 16 - x1, 16 - y1],
          south: [x1, 16 - y2, x2, 16 - y1], west: [z1, 16 - y2, z2, 16 - y1], east: [16 - z2, 16 - y2, 16 - z1, 16 - y1]}[face];
}
// the four corners of a face, top-left, top-right, bottom-right, bottom-left, as seen from outside
function corners(face, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  return {north: [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]], south: [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]],
          west: [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]], east: [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]],
          up: [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]], down: [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]]}[face];
}
function quad(face, f, t, material, uv) {
  const c = corners(face, f, t).map(p => p.map(v => v / 16));
  const [u1, v1, u2, v2] = (uv || uvFor(face, f, t)).map(v => v / 16);
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute([...c[0], ...c[3], ...c[2], ...c[0], ...c[2], ...c[1]], 3));
  g.setAttribute("uv", new THREE.Float32BufferAttribute([u1, 1 - v1, u1, 1 - v2, u2, 1 - v2, u1, 1 - v1, u2, 1 - v2, u2, 1 - v1], 2));
  return new THREE.Mesh(g, material);
}
// the owner's head, as GraveRenderer draws it: its middle on the ground at (x, z), leaning about that point, sunk
let leanSign = -1;
function headMesh() {
  const h = D.head, half = h.size / 2;
  const S = (u1, v1, u2, v2) => [u1, v1, u2, v2].map(v => v / 4); // the skin is 64 wide: quarter-pixels of a 16 grid
  const uv = {north: S(8, 8, 16, 16), south: S(24, 8, 32, 16), east: S(0, 8, 8, 16), west: S(16, 8, 24, 16), up: S(8, 0, 16, 8), down: S(16, 0, 24, 8)};
  const g = new THREE.Group();
  for (const face of ["up", "down", "north", "south", "east", "west"]) {
    g.add(quad(face, [-half, -h.sink, -half], [half, h.size - h.sink, half], mat(D.skin, face), uv[face]));
  }
  g.position.set(h.x / 16, 0, h.z / 16);
  g.rotation.z = leanSign * h.degrees * Math.PI / 180;
  return g;
}
// a model JSON as meshes, in a group turned like the block state's variant
function model(m, y, glow) {
  const inner = new THREE.Group();
  for (const el of m.elements) {
    for (const [face, def] of Object.entries(el.faces)) {
      const ref = m.textures[def.texture.slice(1)];
      const material = mat(ref, face, undefined, !!(el.forge_data && el.forge_data.block_light === 15) && el.shade === false);
      material.userData.glow = glow;
      inner.add(quad(face, el.from, el.to, material, def.uv));
    }
  }
  inner.add(headMesh());
  inner.position.set(-0.5, 0, -0.5);
  const turn = new THREE.Group();
  turn.add(inner);
  turn.rotation.y = -(y || 0) * Math.PI / 180; // a block state's y turns clockwise seen from above
  return turn;
}
function cube(ref, sideRef, tint) {
  const full = {from: [0, 0, 0], to: [16, 16, 16]};
  const g = new THREE.Group();
  for (const face of ["up", "down", "north", "south", "east", "west"]) {
    const r = face === "up" ? ref : (face === "down" ? (sideRef === undefined ? ref : "minecraft:block/dirt") : (sideRef || ref));
    g.add(quad(face, full.from, full.to, mat(r, face, face === "up" ? tint : undefined)));
  }
  g.position.set(-0.5, -1, -0.5);
  return g;
}
function label(lines) {
  const c = document.createElement("canvas");
  c.width = 1024; c.height = 160;
  const x = c.getContext("2d");
  x.font = "bold 46px monospace";
  const w = Math.max(...lines.map(l => x.measureText(l[0]).width)) + 40;
  x.fillStyle = "rgba(0,0,0,0.25)";
  x.fillRect((1024 - w) / 2, 10, w, 140);
  x.textAlign = "center";
  lines.forEach((l, i) => { x.fillStyle = l[1]; x.fillText(l[0], 512, 64 + i * 64); });
  const t = new THREE.CanvasTexture(c);
  t.minFilter = THREE.LinearFilter;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({map: t, depthTest: false}));
  s.scale.set(3.2, 0.5, 1);
  s.position.y = 19 / 16 + 0.51 + 0.25; // GraveRenderer.TEXT_Y, plus half the two lines
  return s;
}
const graves = [], labels = [];
let facing = "north";
function build() {
  while (scene.children.length) scene.remove(scene.children[0]);
  materials.length = 0; graves.length = 0; labels.length = 0;
  D.kinds.forEach((kind, i) => {
    const spot = new THREE.Group();
    spot.position.x = (i - 1.5) * 4;
    for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
      const floor = D.floors[kind];
      const c = floor === "grass" ? cube("minecraft:block/grass_block_top", "minecraft:block/grass_block_side", [0.49, 0.74, 0.42]) : cube(floor);
      c.position.x += dx; c.position.z += dz;
      spot.add(c);
    }
    const variant = D.blockstate.variants["facing=" + facing + ",kind=" + kind];
    const m = D.models[variant.model.split("/").pop()];
    spot.add(model(m, variant.y, true));
    const l = label([["Steve", "#ffffff"], ["Right-click to open", "#FFD23A"]]);
    spot.add(l); labels.push(l);
    const tag = label([[D.labels[kind], "#9AA3B5"]]);
    tag.scale.set(2.8, 0.44, 1); tag.position.set(0, -1.35, 1.6);
    spot.add(tag);
    scene.add(spot);
  });
  shadeAll();
  labels.forEach(l => l.visible = document.getElementById("text").classList.contains("on"));
}
let yaw = 0.35, pitch = 0.42, dist = 17;
function draw() {
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== w * devicePixelRatio || canvas.height !== h * devicePixelRatio) {
    renderer.setPixelRatio(devicePixelRatio); renderer.setSize(w, h, false);
    camera.aspect = w / h; camera.updateProjectionMatrix();
  }
  camera.position.set(Math.sin(yaw) * Math.cos(pitch) * dist, 0.6 + Math.sin(pitch) * dist, Math.cos(yaw) * Math.cos(pitch) * dist);
  camera.lookAt(0, 0.6, 0);
  renderer.render(scene, camera);
}
let drag = null;
canvas.addEventListener("pointerdown", e => { drag = [e.clientX, e.clientY]; canvas.setPointerCapture(e.pointerId); });
canvas.addEventListener("pointermove", e => {
  if (!drag) return;
  yaw -= (e.clientX - drag[0]) * 0.008; pitch = Math.max(-0.2, Math.min(1.4, pitch + (e.clientY - drag[1]) * 0.006));
  drag = [e.clientX, e.clientY]; draw();
});
canvas.addEventListener("pointerup", () => drag = null);
canvas.addEventListener("wheel", e => { e.preventDefault(); dist = Math.max(3, Math.min(20, dist * (1 + e.deltaY * 0.001))); draw(); }, {passive: false});
document.querySelectorAll("[data-face]").forEach(b => b.addEventListener("click", () => {
  document.querySelectorAll("[data-face]").forEach(x => x.classList.remove("on")); b.classList.add("on");
  facing = b.dataset.face; build(); draw();
}));
document.getElementById("text").addEventListener("click", e => { e.target.classList.toggle("on"); build(); draw(); });
document.getElementById("lean").addEventListener("click", e => { leanSign = -leanSign; e.target.textContent = "Head leans " + (leanSign < 0 ? "left" : "right"); build(); draw(); });
document.getElementById("night").addEventListener("click", e => { e.target.classList.toggle("on"); night = e.target.classList.contains("on"); shadeAll(); draw(); });
window.addEventListener("resize", draw);
build(); draw();
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
