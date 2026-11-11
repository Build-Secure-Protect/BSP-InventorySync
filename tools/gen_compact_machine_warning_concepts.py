"""Writes tools/preview/compact_machine_warning_concepts.html: three mock-ups of the confirm screen shown before a player
leaves a server carrying a Compact Machine whose room is on that server (D31, answer 2 d: "a confirm screen with Cancel
and Go"). Also shows, for reference, the two lines already decided in D31: the action-bar line when a machine is placed
away from home (6 a) and the tooltip line on a marked machine (8 a). Plain HTML and CSS, ASCII only, no scripts.

    python3 tools/gen_compact_machine_warning_concepts.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools/preview/compact_machine_warning_concepts.html"

HOME = "BSP-Spawn"
TARGET = "BSP-Frostwarf"
TRIP = "/home base"

# the machines Steve carries in the example: name, size colour, where it is
MACHINES = [
    ("Compact Machine (Normal)", "#7A8FA6", "Inventory"),
    ("Compact Machine (Large)", "#9C7AA6", "Backpack (in Curios)"),
    ("Compact Machine (Tiny)", "#A6967A", "Ender chest"),
]

WARNING = (f"You are carrying a Compact Machine whose room is on {HOME}. It will not work on {TARGET}; "
           f"the room and everything in it stay on {HOME}.")
WARNING_MANY = (f"You are carrying {len(MACHINES)} Compact Machines whose rooms are on {HOME}. They will not work on "
                f"{TARGET}; the rooms and everything in them stay on {HOME}.")

CSS = """
  :root {
    --bg: #0B0F14; --panel: #10151C; --line: #2A2F3A;
    --accent: #19D3B0; --violet: #B58CFF; --gold: #FFD23A; --bad: #FF6B5C;
    --text: #E6EAF2; --muted: #9AA3B5;
    --gui: #C6C6C6; --slot: #8B8B8B;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--text); font-family: "IBM Plex Sans", system-ui, sans-serif; line-height: 1.5; }
  main { max-width: 1180px; margin: 0 auto; padding: 32px 16px 64px; }
  h1, h2, h3 { font-family: "Silkscreen", monospace; font-weight: 700; letter-spacing: 0.5px; margin: 0; }
  h1 { font-size: 28px; color: var(--accent); }
  h2 { font-size: 19px; margin-bottom: 4px; }
  h3 { font-size: 13px; color: var(--muted); text-transform: uppercase; margin: 16px 0 6px; }
  p { margin: 8px 0; }
  .lede { color: var(--muted); max-width: 860px; }
  code { font-family: ui-monospace, Menlo, monospace; font-size: 0.92em; color: var(--violet); }
  .option { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 20px; margin-top: 22px; }
  .option.pick { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset; }
  .tag { display: inline-block; font-family: "Silkscreen", monospace; font-size: 11px; padding: 2px 8px; border-radius: 4px; margin-left: 8px; vertical-align: middle; background: var(--accent); color: var(--bg); }
  .row { display: flex; gap: 22px; flex-wrap: wrap; align-items: flex-start; margin-top: 12px; }
  .caption { font-size: 13px; color: var(--muted); max-width: 520px; }
  ul.pros { padding-left: 18px; margin: 6px 0 0; font-size: 14px; }
  ul.pros li { margin: 3px 0; }
  .good { color: var(--accent); } .meh { color: var(--gold); }
  .decisions ol { padding-left: 22px; }
  .decisions li { margin: 10px 0; }
  .decisions .d { color: var(--accent); font-weight: 600; }
  .foot { color: var(--muted); font-size: 13px; margin-top: 28px; border-top: 1px solid var(--line); padding-top: 14px; }

  /* the game behind a screen, dimmed */
  .game { position: relative; width: 640px; max-width: 100%; height: 380px; border-radius: 6px; overflow: hidden;
          background: linear-gradient(#5B8FC9, #8FB5DE 52%, #5E8A3A 52%, #4B6F2E); }
  .game::before { content: ""; position: absolute; inset: 0; background: #000A; }
  .game > .center { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
  .mc { font-family: "Silkscreen", monospace; color: #fff; text-shadow: 2px 2px 0 #3F3F3F; }
  .mc.grey { color: #A0A0A0; text-shadow: 2px 2px 0 #282828; }

  /* A: vanilla confirm screen */
  .vconfirm { width: 560px; max-width: 94%; text-align: center; display: flex; flex-direction: column; gap: 10px; align-items: center; }
  .vconfirm .t { font-size: 15px; }
  .vconfirm .m { font-size: 11px; line-height: 1.7; max-width: 520px; }
  .vbtns { display: flex; gap: 8px; margin-top: 14px; }
  .vbtn { width: 150px; height: 26px; display: flex; align-items: center; justify-content: center; font-size: 11px;
          background: #6F6F6F; border: 2px solid; border-color: #A8A8A8 #3C3C3C #3C3C3C #A8A8A8; outline: 1px solid #000; }
  .vbtn.focus { outline: 2px solid #fff; }

  /* B: the mod's own panel */
  .panel { width: 470px; max-width: 94%; background: #10151CF2; border: 1px solid #2A2F3A; border-radius: 6px; padding: 14px 16px;
           font-family: "Silkscreen", monospace; font-size: 11px; color: var(--text); box-shadow: 0 0 0 1px #000; }
  .panel .head { color: var(--gold); font-size: 13px; }
  .panel .route { color: var(--muted); margin: 6px 0 10px; }
  .panel .route b { color: var(--accent); font-weight: 400; }
  .panel .list { border-top: 1px solid #2A2F3A; border-bottom: 1px solid #2A2F3A; padding: 6px 0; }
  .panel .it { display: flex; align-items: center; gap: 10px; padding: 3px 0; }
  .panel .ic { width: 22px; height: 22px; border-radius: 3px; border: 2px solid #0006; flex: none; }
  .panel .nm { flex: 1; }
  .panel .wh { color: var(--muted); }
  .panel .msg { margin: 10px 0 12px; line-height: 1.7; color: #C9CFDA; }
  .panel .btns { display: flex; justify-content: flex-end; gap: 8px; }
  .pbtn { padding: 5px 14px; border-radius: 4px; border: 1px solid #2A2F3A; background: #18202A; }
  .pbtn.focus { border-color: var(--accent); color: var(--accent); }
  .pbtn.go { color: var(--gold); border-color: #6B5A1A; }

  /* C: a chest-style menu */
  .gui { background: var(--gui); border: 3px solid; border-color: #FFFFFF #555555 #555555 #FFFFFF; border-radius: 4px; padding: 7px 7px 8px; width: max-content; position: relative; }
  .gui .title { font-family: "Silkscreen", monospace; font-size: 11px; color: #404040; margin: 0 0 4px 1px; }
  .grid { display: grid; grid-template-columns: repeat(9, 30px); }
  .slot { width: 30px; height: 30px; background: var(--slot); border: 2px solid; border-color: #373737 #FFFFFF #FFFFFF #373737; display: flex; align-items: center; justify-content: center; }
  .slot .ic { width: 20px; height: 20px; border-radius: 3px; display: flex; align-items: center; justify-content: center; font-family: "Silkscreen", monospace; font-size: 7px; color: #fff; text-shadow: 1px 1px 0 #0008; }
  .gap { height: 6px; }
  .own .title { color: #6b6b6b; } .own .grid { opacity: 0.55; }
  .tip { position: absolute; left: 150px; top: 6px; width: 300px; background: #100010F0; border: 2px solid #28007F; border-radius: 3px;
         padding: 5px 7px; font-family: "Silkscreen", monospace; font-size: 10px; line-height: 1.6; color: #fff; z-index: 2; }
  .tip .g { color: #AAAAAA; } .tip .y { color: #FFFF55; } .tip .a { color: #55FFFF; }

  /* the two decided lines */
  .actionbar { width: 640px; max-width: 100%; height: 110px; border-radius: 6px; position: relative;
               background: linear-gradient(#5B8FC9, #8FB5DE 60%, #5E8A3A 60%); }
  .actionbar .bar { position: absolute; left: 0; right: 0; bottom: 18px; text-align: center; font-size: 11px; }
  .tipbox { background: #100010F0; border: 2px solid #28007F; border-radius: 3px; padding: 6px 8px; font-family: "Silkscreen", monospace;
            font-size: 11px; line-height: 1.7; color: #fff; width: max-content; }
  .tipbox .g { color: #AAAAAA; } .tipbox .new { color: #AAAAAA; outline: 1px dashed var(--accent); outline-offset: 2px; }
"""


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def option_a():
    return f"""
  <section class="option">
    <h2>A. Minecraft's own confirm screen</h2>
    <p>The game's plain yes/no screen (the one used for "Open this link?"), opened by the server when the teleport is asked
    for. A title, the warning, two buttons. No list of which machines or where they are.</p>
    <div class="row">
      <div class="game"><div class="center"><div class="vconfirm">
        <div class="mc t">Leave with your Compact Machines?</div>
        <div class="mc grey m">{esc(WARNING_MANY)}</div>
        <div class="vbtns"><div class="mc vbtn focus">Cancel</div><div class="mc vbtn">Go anyway</div></div>
      </div></div></div>
    </div>
    <ul class="pros">
      <li class="good">The least new client code: the vanilla screen class with two answers.</li>
      <li class="good">Looks like every other Minecraft question, so players know what it is.</li>
      <li class="meh">Does not say which machines or where they are (a machine in a backpack is easy to forget).</li>
    </ul>
  </section>"""


def option_b():
    rows = "".join(
        f'<div class="it"><span class="ic" style="background:{c}"></span><span class="nm">{esc(n)}</span>'
        f'<span class="wh">{esc(w)}</span></div>' for n, c, w in MACHINES)
    return f"""
  <section class="option pick">
    <h2>B. The mod's own panel, with the machines listed <span class="tag">default</span></h2>
    <p>A small dark panel in this mod's look over the dimmed game. It names the trip, lists each machine that will stop
    working and where it is (inventory, armour, offhand, Curios, ender chest, a backpack or dank, a shulker box), then the
    warning and the two buttons. Up to six rows; more show as "and 3 more".</p>
    <div class="row">
      <div class="game"><div class="center"><div class="panel">
        <div class="head">Your Compact Machines stay on {HOME}</div>
        <div class="route">{esc(TRIP)}: {HOME} -&gt; <b>{TARGET}</b></div>
        <div class="list">{rows}</div>
        <div class="msg">{esc(WARNING_MANY)}</div>
        <div class="btns"><span class="pbtn focus">Cancel</span><span class="pbtn go">Go anyway</span></div>
      </div></div></div>
    </div>
    <ul class="pros">
      <li class="good">Says exactly what will stop working and where it is, so the player can put it in a chest first.</li>
      <li class="good">Stands out from vanilla screens, so it reads as a warning about this network, not a game question.</li>
      <li class="meh">The mod's first screen of its own: a small screen class and two packets (already client-required, exact version).</li>
    </ul>
  </section>"""


def option_c():
    icons = [f'<div class="slot"><div class="ic" style="background:{c}">CM</div></div>' for _, c, _ in MACHINES]
    top = "".join(icons) + '<div class="slot"></div>' * (9 - len(MACHINES))
    mid = '<div class="slot"></div>' * 4 + '<div class="slot"><div class="ic" style="background:#E8E2C8;color:#333;text-shadow:none">?</div></div>' + '<div class="slot"></div>' * 4
    bottom = ('<div class="slot"><div class="ic" style="background:#B03030">NO</div></div>' + '<div class="slot"></div>' * 7
              + '<div class="slot"><div class="ic" style="background:#3C9A3C">GO</div></div>')
    own = '<div class="slot"></div>' * 27
    hot = '<div class="slot"></div>' * 9
    return f"""
  <section class="option">
    <h2>C. A chest-style menu, like the waiting-items popup</h2>
    <p>A 9x3 menu drawn by the server, as <code>/invsync give</code>'s popup is: the top row shows copies of the machines
    (cannot be taken), the paper in the middle carries the warning as its tooltip, red Cancel bottom left, green Go anyway
    bottom right. Shown here with the paper hovered.</p>
    <div class="row">
      <div class="game" style="height:420px"><div class="center"><div class="gui">
        <div class="title">Leaving {HOME}?</div>
        <div class="grid">{top}{mid}{bottom}</div>
        <div class="gap"></div>
        <div class="own"><div class="title">Inventory</div><div class="grid">{own}</div><div class="gap"></div><div class="grid">{hot}</div></div>
        <div class="tip"><span class="y">Your Compact Machines stay on {HOME}</span><br>
          <span class="g">{esc(WARNING_MANY)}</span><br><span class="a">Green: go anyway. Red or Esc: stay.</span></div>
      </div></div></div>
    </div>
    <ul class="pros">
      <li class="good">The same pattern as the waiting-items popup: no new screen code on the client.</li>
      <li class="meh">The warning is only read on hover; the buttons are item icons, easy to misread.</li>
      <li class="meh">Shows which machines but not where they are, unless each copy's tooltip says it.</li>
    </ul>
  </section>"""


def decided():
    return f"""
  <section class="option">
    <h2>Already decided in D31 (for reference)</h2>
    <h3>6 a: a machine placed away from home: nothing placed, the item stays, this action-bar line</h3>
    <div class="actionbar"><div class="mc bar">This Compact Machine's room is on {HOME}. It only works there.</div></div>
    <h3>8 a: one grey line under Compact Machines' own lines (outlined here)</h3>
    <div class="tipbox">Compact Machine (Normal)<br><span class="g">Bound to room: [24, 0]</span><br><span class="new">Room on {HOME}</span></div>
    <p class="caption">Compact Machines' own lines are shown roughly; only the outlined line is new.</p>
  </section>"""


def decisions():
    return """
  <section class="option decisions">
    <h2>Decisions</h2>
    <ol>
      <li>Which look. <span class="d">Default B</span>: the mod's own panel listing each machine and where it is.
        A is Minecraft's plain confirm screen with no list. C is a chest-style menu drawn by the server.</li>
      <li>What Go anyway does. <span class="d">Default a</span>: the same teleport carries on (warm-up, costs and checks as
        usual), which needs R-4 to let a listener resume the move it held. b: Go anyway only opens a 60-second window and
        the player asks for the teleport again themself.</li>
      <li>What counts as Cancel. <span class="d">Default a</span>: the Cancel button, Esc, or the player being hurt while the
        screen is open. b: only the Cancel button and Esc.</li>
      <li>Which button has focus. <span class="d">Default a</span>: Cancel, so Enter or a stray click never sends the player
        off. b: Go anyway.</li>
      <li>How often it asks. <span class="d">Default a</span>: every time a player leaves this server carrying a machine whose
        room is here. b: not again for 10 minutes after Go anyway. c: a "do not ask again until I log out" tick box.</li>
      <li>Walking across a world border into the next server (once R-4 is in). <span class="d">Default a</span>: the same
        screen; the player waits at the border, and Cancel leaves them on this side. b: no screen for border crossings, only
        the refusal at placement.</li>
      <li>In a portal. <span class="d">Default a</span>: while the screen is open the portal does not try again, and Go anyway
        carries on with the portal's own warm-up. b: the player has to step out and walk back in after Go anyway.</li>
    </ol>
  </section>"""


def page():
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Compact Machine Warning</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Silkscreen:wght@400;700&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>{CSS}</style>
</head>
<body>
<main>
  <h1>Compact Machine Warning</h1>
  <p class="lede">D31, answer 2 d: before a player leaves a server carrying a Compact Machine whose room is on that server,
    a screen asks them to confirm. The teleport is held until they choose; Cancel keeps them here. The example: Steve on
    {HOME} uses <code>{esc(TRIP)}</code>, a home on {TARGET}, with three machines on him: one in his inventory, one in a
    backpack worn in Curios, one in his ender chest (it travels too). Text is the D31 1 a wording.</p>
{option_a()}
{option_b()}
{option_c()}
{decided()}
{decisions()}
  <p class="foot">Written by <code>tools/gen_compact_machine_warning_concepts.py</code>. Mock-ups only: nothing is built.</p>
</main>
</body>
</html>
"""


if __name__ == "__main__":
    text = page()
    text.encode("ascii")  # the rule: ASCII only
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)} ({len(text)} bytes)")
