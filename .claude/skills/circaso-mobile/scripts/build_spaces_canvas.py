#!/usr/bin/env python3
"""Builds Circaso-Spaces.html: the phase 0a design frames for Spaces phase 1 (Spaces tab, Moments) and
phase 2 (groups with wallets, Paying from, the fee line, group daily caps), and the phase 0b frames for phase 3, Spaces core
(pages SD, SS, SN, SM, SI, SH, SR, SW: discover, the space page, create, paid entry, invites, space chat, roles and
moderation, the space wallet).

Same format as Circaso-Offline-States.html: the token and component CSS and the portrait photos are copied
from that file, so the frames use exactly the canvas's design system. Run from the repo root:

    python3 .claude/skills/circaso-mobile/scripts/build_spaces_canvas.py

It writes Circaso-Spaces.html at the root and a copy in the skill's assets/.
"""
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "Circaso-Offline-States.html"
OUT = ROOT / "Circaso-Spaces.html"
COPY = ROOT / ".claude/skills/circaso-mobile/assets/Circaso-Spaces.html"

src = SRC.read_text()
head_css = src[: src.index("</style>")]
# Drop the per-frame G3/G4 rules at the end of the style block; ours are generic below.
head_css = head_css[: head_css.index("#s-G3b-Chats-waiting{margin:0}")]
head_css = head_css.replace("<title>Circaso Offline States</title>", "<title>Circaso Spaces Design</title>").replace("CoinX", "Circaso")
photos = re.search(r"var P=(\{.*?\});", src, re.S).group(1)

EXTRA_CSS = r"""
/* Shared frame helpers (the main canvas repeats these per frame). */
.scr a{color:inherit}
.scr .sw{appearance:none;border:0;padding:2px;width:51px;height:31px;border-radius:999px;background:var(--fill-strong);display:inline-flex;align-items:center;cursor:pointer;flex-shrink:0}
.scr .sw::after{content:"";width:27px;height:27px;border-radius:50%;background:#ffffff;box-shadow:0 2px 6px rgba(0,0,0,.18)}
.scr .sw-on{background:var(--ink);justify-content:flex-end}
.scr .sw-on::after{background:var(--bg)}
.scr .chip{appearance:none;border:0;height:34px;padding:0 14px;border-radius:999px;background:var(--fill);color:var(--ink);font:500 15px/20px var(--font-sans);white-space:nowrap;cursor:pointer;display:inline-flex;align-items:center;gap:6px}
.scr .chip-on{background:var(--action);color:var(--on-action);font-weight:600}
.scr .h1{margin:0;font-size:28px;line-height:34px;font-weight:700;letter-spacing:0.36px}
.scr .lead{margin:0;font-size:17px;line-height:22px;letter-spacing:-0.43px;color:var(--ink-secondary)}
.scr .small{margin:0;font-size:13px;line-height:18px;color:var(--ink-secondary)}
.scr .sec{font:400 13px/18px var(--font-sans);letter-spacing:-0.08px;color:var(--ink-secondary);padding:0 4px}
.scr .sec-row{display:flex;align-items:baseline;justify-content:space-between;padding:0 4px}
.scr .sec-row .sec{padding:0}
.scr .link{font:600 15px/20px var(--font-sans);color:var(--ink);background:none;border:0;padding:0;cursor:pointer}

/* Moments ring: unseen is an ink ring, seen is a quiet one. Monochrome, like every accent in Circaso. */
.scr .mo{display:flex;flex-direction:column;align-items:center;gap:6px;width:72px;flex:none;font:400 12px/16px var(--font-sans);color:var(--ink);text-align:center}
.scr .mo-name{width:72px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.scr .mo-ring{position:relative;width:68px;height:68px;border-radius:50%;padding:3px;box-sizing:border-box;border:2.5px solid var(--ink);display:flex}
.scr .mo-ring .cx-avatar{width:100%;height:100%;font-size:20px}
.scr .mo-seen .mo-ring{border-color:var(--fill-strong)}
.scr .mo-seen .mo-name{color:var(--ink-secondary)}
.scr .mo-none .mo-ring{border-color:transparent}
.scr .mo-add{position:absolute;right:-2px;bottom:-2px;width:22px;height:22px;border-radius:50%;background:var(--action);color:var(--on-action);display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 2.5px var(--bg)}
.scr .mo-row{display:flex;gap:6px;overflow:hidden;padding:2px 12px 4px}
.scr .mo-prog{position:absolute;inset:-2.5px;border-radius:50%;background:conic-gradient(var(--ink) 0 62%,var(--fill-strong) 62% 100%);-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - 2.5px),#000 calc(100% - 2.5px));mask:radial-gradient(farthest-side,transparent calc(100% - 2.5px),#000 calc(100% - 2.5px))}
.scr .mo-nodl .cx-avatar img{opacity:.4;filter:grayscale(1)}
.scr .mo-fail{position:absolute;right:-2px;bottom:-2px;width:22px;height:22px;border-radius:50%;background:var(--critical);color:var(--bg);display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 2.5px var(--bg);font:700 13px/1 var(--font-sans)}

/* Skeletons for loading states; the shimmer stops under reduced motion. */
.scr .sk{background:var(--fill);border-radius:var(--radius-sm);position:relative;overflow:hidden}
.scr .sk::after{content:"";position:absolute;inset:0;background:linear-gradient(100deg,transparent 30%,var(--fill-strong) 50%,transparent 70%);transform:translateX(-100%);animation:sk 1.4s ease-in-out infinite}
@keyframes sk{to{transform:translateX(100%)}}
@media (prefers-reduced-motion: reduce){.scr .sk::after{animation:none;display:none}}

/* Space rows */
.scr .sp-img{width:52px;height:52px;border-radius:var(--radius-md);background:var(--fill);flex:none;display:flex;align-items:center;justify-content:center;font:600 18px/1 var(--font-rounded);color:var(--ink);overflow:hidden}
.scr .sp-img img{width:100%;height:100%;object-fit:cover}
.scr .live{display:inline-flex;align-items:center;gap:5px;height:20px;padding:0 7px;border-radius:999px;background:var(--action);color:var(--on-action);font:700 11px/13px var(--font-sans);letter-spacing:0.4px}
.scr .live::before{content:"";width:6px;height:6px;border-radius:50%;background:var(--on-action);animation:livepulse 1.6s ease-in-out infinite}
@keyframes livepulse{50%{opacity:.35}}
@media (prefers-reduced-motion: reduce){.scr .live::before{animation:none}}

/* Full-screen media (camera, viewer): always on the dark stage, as the app's photo viewer. */
.scr .stage{position:absolute;inset:0;background:#000;overflow:hidden}
.scr .stage img.bleed{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.scr .shade-top{position:absolute;left:0;right:0;top:0;height:200px;background:linear-gradient(rgba(0,0,0,.55),transparent)}
.scr .shade-bot{position:absolute;left:0;right:0;bottom:0;height:260px;background:linear-gradient(transparent,rgba(0,0,0,.65))}
.scr .on-media{color:#fff}
.scr .glass-btn{appearance:none;border:0;width:44px;height:44px;border-radius:50%;background:rgba(0,0,0,.36);-webkit-backdrop-filter:blur(20px);backdrop-filter:blur(20px);color:#fff;display:inline-flex;align-items:center;justify-content:center;cursor:pointer;flex:none}
.scr .segs{display:flex;gap:4px}
.scr .segs i{flex:1;height:2.5px;border-radius:2px;background:rgba(255,255,255,.38);overflow:hidden;position:relative}
.scr .segs i.done{background:#fff}
.scr .segs i.now::after{content:"";position:absolute;inset:0;width:45%;background:#fff}
.scr .shutter{width:78px;height:78px;border-radius:50%;border:4px solid #fff;display:flex;align-items:center;justify-content:center}
.scr .shutter::after{content:"";width:62px;height:62px;border-radius:50%;background:#fff}
.scr .shutter.rec::after{width:30px;height:30px;border-radius:8px;background:var(--critical)}
.scr .modes{display:flex;gap:6px;justify-content:center;font:600 14px/18px var(--font-sans);color:rgba(255,255,255,.72)}
.scr .modes>*{padding:13px 8px;min-width:44px;text-align:center}
.scr .modes b{color:#fff}
.scr .wave{display:flex;align-items:center;gap:3px;height:48px}
.scr .wave i{width:3px;border-radius:2px;background:currentColor}

/* Text moment backgrounds: the light theme's own token values, fixed, so a moment looks the same to every viewer whatever their theme. */
.scr .bg-ink{background:#0b0b0c;color:#ffffff}
.scr .bg-metal{background:linear-gradient(140deg,#4a4c52 0%,#2b2c30 38%,#2b2c30 62%,#4a4c52 100%);color:#ffffff}
.scr .bg-paper{background:#e9e9eb;color:#0b0b0c}
.scr .bg-graphite{background:#636366;color:#ffffff}
.scr .swatch{width:30px;height:30px;border-radius:50%;box-shadow:0 0 0 2px rgba(255,255,255,.9);flex:none}
.scr .swatch-on{box-shadow:0 0 0 2px #000,0 0 0 4.5px #fff}

/* Radio and checkbox rows */
.scr .radio{width:22px;height:22px;border-radius:50%;border:2px solid var(--fill-strong);box-sizing:border-box;flex:none}
.scr .radio-on{border:7px solid var(--ink)}
.scr .check{width:24px;height:24px;border-radius:50%;border:2px solid var(--fill-strong);box-sizing:border-box;flex:none;display:flex;align-items:center;justify-content:center}
.scr .check-on{background:var(--ink);border-color:var(--ink);color:var(--bg)}

/* Wallet chip: who you pay from. */
.scr .wchip{display:inline-flex;align-items:center;gap:6px;height:34px;padding:0 10px 0 4px;border-radius:999px;background:var(--fill);font:600 15px/20px var(--font-sans);color:var(--ink);border:0}
.scr .wchip .cx-avatar{width:22px;height:22px;font-size:9px}
.scr .card{background:var(--surface);border-radius:var(--radius-lg);box-shadow:inset 0 0 0 1px var(--separator)}
.scr .quote-out{background:color-mix(in srgb, var(--on-action) 14%, transparent)}
.scr .act{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:13px;line-height:18px;font-weight:500;color:var(--ink);background:none;border:0;cursor:pointer;padding:0;font-family:var(--font-sans);min-width:64px}
.scr .act-c{width:58px;height:58px;border-radius:50%;background:var(--surface);display:flex;align-items:center;justify-content:center}
.scr .rid{font:400 12px/16px ui-monospace,Menlo,monospace;color:var(--ink-tertiary)}
.scr .sysprompt{background:var(--surface-raised);border-radius:28px;padding:24px;display:flex;flex-direction:column;align-items:center;gap:10px;text-align:center;box-shadow:var(--shadow-float)}
"""

GALLERY_CSS = """
.sup{align-self:flex-start;font-size:11px;font-weight:600;color:#a05a00;background:#fff3e0;border-radius:6px;padding:1px 6px}
.tag{display:inline-block;font-size:11px;font-weight:600;border-radius:6px;padding:1px 6px;background:var(--g-line);color:var(--g-ink);margin-left:6px;vertical-align:1px}
.decisions{max-width:860px;color:var(--g-ink2);font-size:15px;line-height:1.55}
.decisions li{margin:4px 0}
.decisions h3{color:var(--g-ink);font-size:17px;margin:26px 0 6px}
"""

# ---------------------------------------------------------------- icons (24 px grid, stroke, from the canvas)
def svg(d, size=18, sw=2, fill=False, extra=""):
    paint = 'fill="currentColor"' if fill else f'fill="none" stroke="currentColor" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"'
    return f'<svg class="cx-icon" width="{size}" height="{size}" viewBox="0 0 24 24" aria-hidden="true" focusable="false"{extra}><path d="{d}" {paint}></path></svg>'

I = {
    "back": "M15 5l-7 7l7 7",
    "close": "M6 6l12 12M18 6L6 18",
    "plus": "M12 5v14M5 12h14",
    "search": "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 0 0 0-13zM15.5 15.5L20 20",
    "camera": "M4 8.5A2 2 0 0 1 6 6.5h2l1.5-2h5l1.5 2h2a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 9.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 0 0 0-7z",
    "image": "M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5zM4 16l4.5-4.5l3.5 3.5l2.5-2.5L20 18M15 8.5h.01",
    "mic": "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5v3",
    "text": "M5 6h14M12 6v13M8.5 19h7",
    "send": "M12 19V5M5.5 11.5L12 5l6.5 6.5",
    "heart": "M12 19.5s-7.5-4.4-7.5-10A4.3 4.3 0 0 1 12 6.6a4.3 4.3 0 0 1 7.5 2.9c0 5.6-7.5 10-7.5 10z",
    "flip": "M4 8.5h11.5l-3-3M20 15.5H8.5l3 3",
    "flash": "M13 3L5.5 13.5H12L11 21l7.5-10.5H12z",
    "check": "M5 12.5l4.5 4.5L19 7.5",
    "chev_down": "M7 10l5 5l5-5",
    "chev_right": "M10 6l6 6l-6 6",
    "offline": "M2.5 8.8a14 14 0 0 1 19 0M5.8 12.3a9 9 0 0 1 12.4 0M9.2 15.6a4.2 4.2 0 0 1 5.6 0M12 19h.01M4 4l16 16",
    "clock": "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17zM12 7.5V12l3 2",
    "warning": "M12 4l9 15.5H3zM12 10v4M12 17h.01",
    "eye": "M2.5 12S6 5.5 12 5.5S21.5 12 21.5 12S18 18.5 12 18.5S2.5 12 2.5 12zM12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6z",
    "lock": "M7 11V8a5 5 0 0 1 10 0v3M5.5 11h13v9h-13z",
    "more": "M6 12h.01M12 12h.01M18 12h.01",
    "pencil": "M12 20h8M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z",
    "sparkle": "M12 2.5c.5 0 .8.3.9.8l.9 3.7a4.5 4.5 0 0 0 3.2 3.2l3.7.9c.5.1.8.4.8.9s-.3.8-.8.9l-3.7.9a4.5 4.5 0 0 0-3.2 3.2l-.9 3.7c-.1.5-.4.8-.9.8s-.8-.3-.9-.8l-.9-3.7a4.5 4.5 0 0 0-3.2-3.2l-3.7-.9c-.5-.1-.8-.4-.8-.9s.3-.8.8-.9l3.7-.9A4.5 4.5 0 0 0 10.2 7l.9-3.7c.1-.5.4-.8.9-.8z",
    "wallet": "M3.5 8A2.5 2.5 0 0 1 6 5.5h12A2.5 2.5 0 0 1 20.5 8v8a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 16zM15.5 12h2",
    "target": "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17zM12 7.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 0 0 0-9zM12 11.5v1",
    "info": "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17zM12 11v5M12 8h.01",
    "fingerprint": "M7.5 19.5c1.5-2 2-4.5 2-7.5a2.5 2.5 0 0 1 5 0c0 1.2-.1 2.4-.3 3.5M12 12c0 3.5-.8 6.3-2.5 8.5M5 16c.6-1.2 1-2.6 1-4a6 6 0 0 1 12 0c0 2.6-.4 5-1.2 7M4.5 9A8 8 0 0 1 19.4 8.9M17.5 19.8l.4-1",
    "faceid": "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M9 9v1.5M15 9v1.5M12 9v4.5h-1M9.5 16a4 4 0 0 0 5 0",
    "people": "M9 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7zM2.5 19.5c.3-3.3 3-5.2 6.5-5.2s6.2 1.9 6.5 5.2M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14.6c2 .6 3.3 2.2 3.5 4.9",
    "trash": "M5 7h14M10 11v6M14 11v6M6.5 7l1 12.5h9l1-12.5M9.5 7V4.5h5V7",
    "forward": "M14 5l6 6l-6 6M20 11H9a5 5 0 0 0-5 5v3",
    "mute": "M4 4l16 16M9 5.5A3 3 0 0 1 15 6.5v4M15 14a3 3 0 0 1-6-2.5V9M5.5 11a6.5 6.5 0 0 0 10.4 5.2M18.5 11c0 .8-.1 1.5-.4 2.2M12 17.5v3",
    "play": "M8 5.5v13l10.5-6.5z",
    "pause": "M8 5.5v13M16 5.5v13",
    "refresh": "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4",
}
TAB = {
    "Chats": "M4 6.2A3.2 3.2 0 0 1 7.2 3h9.6A3.2 3.2 0 0 1 20 6.2v7.1a3.2 3.2 0 0 1-3.2 3.2H11l-4.6 3.9c-.5.4-1.2 0-1.2-.6v-3.4A3.2 3.2 0 0 1 4 13.3z",
    # New: Spaces. A ring around a centre (the Circaso mark's idea) with two smaller circles: people around a place.
    "Spaces": "M12 7.2a4.8 4.8 0 1 1 0 9.6a4.8 4.8 0 0 1 0-9.6zM12 9.9a2.1 2.1 0 1 0 0 4.2a2.1 2.1 0 0 0 0-4.2zM5 2.6a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 0 1 0-5.2zM19 16.2a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 0 1 0-5.2z",
    "Wallet": "M2.5 7.5A3 3 0 0 1 5.5 4.5h13a3 3 0 0 1 3 3v1h-19zM2.5 11h19v5.5a3 3 0 0 1-3 3h-13a3 3 0 0 1-3-3zM6 15h4v1.6H6z",
    "You": "M12 3.5a4.3 4.3 0 1 1 0 8.6a4.3 4.3 0 0 1 0-8.6zM3.8 19.6c.4-3.9 3.9-6 8.2-6s7.8 2.1 8.2 6c0 .5-.4.9-.9.9H4.7a.9.9 0 0 1-.9-.9z",
}

def ic(name, size=18, sw=2):
    return svg(I[name], size, sw, fill=name in ("play",))

# ---------------------------------------------------------------- people
NAMES = {"mum": "Mum", "otieno": "Otieno", "achieng": "Achieng", "wanjiru": "Wanjiru", "juma": "Juma", "njeri": "Njeri",
         "kevin": "Kevin", "pasaka": "Pasaka", "baraka": "Baraka", "faith": "Faith"}
FULL = {"mum": "Mum Wanjiku", "otieno": "Otieno Odhiambo", "achieng": "Achieng Akinyi", "wanjiru": "Wanjiru Kamau",
        "juma": "Juma Kariuki", "njeri": "Njeri Mwangi", "kevin": "Kevin Ochieng", "pasaka": "Pasaka Mutuku",
        "baraka": "Baraka Mwita", "faith": "Faith Chebet"}

def av(who, size="md", style=""):
    st = f' style="{style}"' if style else ""
    if who in FULL:
        return f'<span class="cx-avatar cx-avatar-{size}" role="img" aria-label="{FULL[who]}"{st}><img data-ph="{who}" src="" alt=""></span>'
    initials = "".join(w[0] for w in who.split()[:2]).upper()
    return f'<span class="cx-avatar cx-avatar-{size}" role="img" aria-label="{who}"{st}>{initials}</span>'

# ---------------------------------------------------------------- frame chrome
def screen(body, bg="var(--bg)", theme="light", fixed=False, pad_top=59):
    tx = "" if fixed else ' data-tx="1"'
    return (f'<div data-theme="{theme}"{tx} style="width: 390px; height: 844px; box-sizing: border-box; background: {bg}; '
            f'color: var(--ink); font-family: var(--font-sans); position: relative; overflow: hidden; padding-top: {pad_top}px;">\n{body}\n</div>')

FRAMES = []  # (page, id, title, desc, html, tag)

def frame(page, fid, title, desc, body, tag=None, **kw):
    FRAMES.append((page, fid, title, desc, screen(body, **kw), tag))

def bar(title="", left="back", right="", sub="", avatar=""):
    lefts = {"back": f'<button type="button" class="cx-icon-btn" aria-label="Back">{ic("back")}</button>',
             "close": f'<button type="button" class="cx-icon-btn" aria-label="Close">{ic("close", 16)}</button>',
             "cancel": '<button type="button" class="cx-btn cx-btn-plain cx-btn-sm" style="padding: 0 4px;">Cancel</button>',
             "": '<span style="width: 36px;"></span>'}
    mid = (f'<div style="flex-grow: 1; min-width: 0; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 2px;">{avatar}'
           f'<div style="font-size: 17px; line-height: 22px; font-weight: 600; letter-spacing: -0.43px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 240px;">{title}</div>'
           + (f'<div style="font-size: 12px; line-height: 16px; color: var(--ink-secondary);">{sub}</div>' if sub else "") + "</div>")
    return f'<div style="min-height: 44px; display: flex; align-items: center; gap: 8px; padding: 0 16px;">{lefts[left]}{mid}{right or "<span style=&quot;width: 36px;&quot;></span>".replace("&quot;", chr(34))}</div>'

def icon_btn(name, label, action=False, size=18):
    cls = "cx-icon-btn cx-icon-btn-action" if action else "cx-icon-btn"
    return f'<button type="button" class="{cls}" aria-label="{label}">{ic(name, size)}</button>'

def large_title(title, buttons):
    return (f'<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 6px;"><div style="height: 44px; display: flex; justify-content: flex-end; align-items: center; gap: 8px;">{buttons}</div>'
            f'<h1 style="margin: 0; font-size: 34px; line-height: 41px; font-weight: 700; letter-spacing: 0.37px;">{title}</h1></div>')

def tabbar(active="Spaces", dot=None):
    tabs = []
    for name, d in TAB.items():
        on = name == active
        cls = "cx-tab cx-tab-on" if on else "cx-tab"
        cur = ' aria-current="page"' if on else ""
        badge = '<span class="cx-tab-dot" aria-label="New moments"></span>' if dot == name else ""
        tabs.append(f'<button type="button" class="{cls}"{cur}>{svg(d, 24, fill=True)}{badge}{name}</button>')
    return f'<div style="position: absolute; left: 16px; right: 16px; bottom: 30px; z-index: 4;"><nav class="cx-tabbar" aria-label="Main">{"".join(tabs)}</nav></div>'

def banner(text, kind="offline", icon="offline"):
    return (f'<div class="cx-banner cx-banner-{kind}" role="status"><span class="cx-banner-icon">{ic(icon)}</span>'
            f'<span class="cx-banner-text">{text}</span></div>')

def sheet(inner, label, scrim=True, pb=34, gap=None):
    g = f" gap: {gap}px;" if gap else ""
    s = '<div style="position: absolute; inset: 0; background: var(--scrim); z-index: 5;"></div>' if scrim else ""
    return (s + f'<section class="cx-sheet" role="dialog" aria-label="{label}" style="position: absolute; left: 0; right: 0; bottom: 0; padding-bottom: {pb}px; z-index: 6;{g}">'
            f'<div class="cx-sheet-grabber"></div>{inner}</section>')

def sheet_head(title, close=True):
    btn = f'<button type="button" class="cx-icon-btn" aria-label="Close">{ic("close", 16)}</button>' if close else ""
    return f'<div class="cx-sheet-head"><div class="cx-sheet-title">{title}</div>{btn}</div>'

def btn(text, kind="primary", size="lg", block=True, disabled=False, busy=False, icon=None):
    cls = f"cx-btn cx-btn-{kind} cx-btn-{size}" + (" cx-btn-block" if block else "")
    attrs = (" disabled" if disabled else "") + (' aria-busy="true"' if busy else "")
    inner = ('<span class="cx-spin" aria-hidden="true"></span>' if busy else "") + (ic(icon) if icon else "") + text
    return f'<button type="button" class="{cls}"{attrs}>{inner}</button>'

def row(title, sub=None, lead="", end="", tap=True):
    cls = "cx-row cx-row-tap" if tap else "cx-row"
    subh = f'<div class="cx-row-sub">{sub}</div>' if sub else ""
    return (f'<div role="button" tabindex="0" class="{cls}">{lead}<div class="cx-row-body"><div class="cx-row-text"><div class="cx-row-title">{title}</div>{subh}</div>'
            f'<div class="cx-row-end">{end}</div></div></div>')

def chev():
    return f'<span style="color: var(--ink-tertiary); display: flex;">{ic("chev_right", 16)}</span>'

def details(rows):
    out = []
    for k, v in rows:
        out.append(f'<div class="cx-detail"><span class="cx-detail-k">{k}</span><span class="cx-detail-v">{v}</span></div>')
    return f'<div class="cx-details">{"".join(out)}</div>'

def field(label, value="", placeholder="", prefix="", fid="f", invalid=False, hint=""):
    p = f'<span class="cx-field-prefix">{prefix}</span>' if prefix else ""
    val = f' value="{value}"' if value else ""
    ph = f' placeholder="{placeholder}"' if placeholder else ""
    h = f'<div class="cx-field-hint">{hint}</div>' if hint else ""
    inv = " cx-field-invalid" if invalid else ""
    return (f'<div class="cx-field{inv}"><label class="cx-field-label" for="{fid}">{label}</label><div class="cx-field-box">{p}'
            f'<input id="{fid}" class="cx-field-input" type="text"{val}{ph}></div>{h}</div>')

def pin(filled, total=6, label=None):
    dots = "".join(f'<span class="cx-pin-dot{" cx-pin-dot-on" if i < filled else ""}"></span>' for i in range(total))
    keys = "".join(f'<button type="button" class="cx-pin-key">{k}</button>' for k in "123456789")
    keys += '<span></span><button type="button" class="cx-pin-key">0</button>'
    keys += f'<button type="button" class="cx-pin-key cx-pin-key-ghost" aria-label="Delete">{svg("M9 6h10.5a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H9l-5.5-6zM12.5 9.5l5 5M17.5 9.5l-5 5", 24, 1.8)}</button>'
    return (f'<div class="cx-pin"><div class="cx-pin-dots" role="img" aria-label="{label or f"{filled} of {total} digits entered"}">{dots}</div>'
            f'<div class="cx-pin-keys">{keys}</div></div>')

def moment(who, name=None, state="unseen", add=False, label=None):
    cls = {"unseen": "mo", "seen": "mo mo-seen", "none": "mo mo-none", "nodl": "mo mo-seen mo-nodl"}[state]
    if label is None and who in NAMES:
        label = f"{name or NAMES[who]}, " + {"unseen": "new moment", "seen": "seen", "none": "", "nodl": "not downloaded, needs a connection"}[state]
    extra = f'<span class="mo-add" aria-hidden="true">{ic("plus", 13, 2.6)}</span>' if add else ""
    return (f'<div class="{cls}" role="button" aria-label="{label or (name or NAMES.get(who, who))}"><span class="mo-ring">{av(who, "lg")}{extra}</span>'
            f'<span class="mo-name">{name or NAMES.get(who, who)}</span></div>')

def space_row(name, sub, img, end="", live=False):
    imgh = f'<span class="sp-img">{img}</span>'
    l = '<span class="live">LIVE</span>' if live else ""
    return (f'<div role="button" tabindex="0" class="cx-row cx-row-tap">{imgh}<div class="cx-row-body"><div class="cx-row-text">'
            f'<div class="cx-row-title" style="display: flex; align-items: center; gap: 8px;"><span style="overflow: hidden; text-overflow: ellipsis;">{name}</span>{l}</div>'
            f'<div class="cx-row-sub">{sub}</div></div><div class="cx-row-end">{end}</div></div></div>')

def toast(text, icon="check", top=126):
    return (f'<div role="status" style="position: absolute; top: {top}px; left: 50%; transform: translateX(-50%); z-index: 7; display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 999px; background: var(--glass); -webkit-backdrop-filter: blur(24px); backdrop-filter: blur(24px); border: 1px solid var(--glass-edge); box-shadow: var(--shadow-float); font-size: 15px; line-height: 20px; font-weight: 600; white-space: nowrap;">{ic(icon, 16, 2.4)}{text}</div>')

def wave(n=38, seed=3, h=40):
    out, x = [], seed
    for i in range(n):
        x = (x * 37 + 11) % 97
        out.append(f'<i style="height: {8 + (x % h)}px"></i>')
    return f'<div class="wave">{"".join(out)}</div>'

def err_box(title, text, rid, action="Try Again"):
    return (f'<div style="display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; padding: 28px 24px;">'
            f'<span style="color: var(--ink-secondary); display: flex;">{ic("warning", 32, 1.8)}</span>'
            f'<div style="font: 600 17px/22px var(--font-sans);">{title}</div><p class="small" style="max-width: 280px;">{text}</p>'
            f'{btn(action, "secondary", "sm", block=False)}<div class="rid">Ref {rid}</div></div>')

def my_ring(state="add"):
    """Your own ring. States: add (no moment), seen (has one, plus to add), posting, waiting, failed."""
    if state == "add":
        return moment("pasaka", "My moment", "none", add=True, label="Add to my moment")
    if state == "seen":
        return moment("pasaka", "My moment", "seen", add=True, label="My moment, add to it")
    extra, sub, label = {
        "posting": ('<span class="mo-prog" role="progressbar" aria-valuenow="62" aria-valuemin="0" aria-valuemax="100"></span>', "Posting…", "My moment, posting"),
        "waiting": (f'<span class="mo-add" style="background: var(--fill-strong); color: var(--ink);">{ic("clock", 13, 2.4)}</span>', "Waiting", "My moment, waiting for a connection"),
        "failed": ('<span class="mo-fail">!</span>', "Not posted", "My moment, not posted"),
    }[state]
    return (f'<div class="mo" role="button" aria-label="{label}"><span class="mo-ring" style="border-color: transparent;">{av("pasaka", "lg")}{extra}</span>'
            f'<span class="mo-name">{sub}</span></div>')

# ================================================================ PAGE S · Spaces tab
P_S = "S"
spaces_buttons = icon_btn("sparkle", "Ask Asha") + icon_btn("search", "Search moments and spaces") + icon_btn("plus", "New moment or space")

def moments_row(items):
    return (f'<div style="padding: 14px 0 0;"><div class="sec-row" style="padding: 0 20px 8px;"><span class="sec">Moments</span></div>'
            f'<div class="mo-row">{"".join(items)}</div></div>')

ROW_FULL = [my_ring("seen"), moment("wanjiru"), moment("baraka"),
            moment("faith"), moment("mum", state="seen"), moment("juma", state="seen")]

coming = (f'<div class="card" style="margin: 0 16px; padding: 18px 16px; display: flex; flex-direction: column; gap: 8px;">'
          f'<div style="display: flex; align-items: center; gap: 10px;"><span style="display: flex;">{svg(TAB["Spaces"], 22, fill=True)}</span>'
          f'<span style="font: 600 17px/22px var(--font-sans);">Spaces are coming</span></div>'
          f'<p class="small" style="font-size: 15px; line-height: 20px;">Communities and small shops, each with its own chat, shop and wallet. Moments are here now.</p></div>')

frame(P_S, "S1-Spaces", "S1 · Spaces tab, phase 1",
      "What ships first. Moments on top, unseen rings in ink, seen ones quiet, your own ring first with a plus. The spaces "
      "section is an honest card until phase 3. Seen from another tab, a dot on Spaces means new moments from your contacts.",
      large_title("Spaces", spaces_buttons) + moments_row(ROW_FULL)
      + '<div style="padding: 22px 0 0; display: flex; flex-direction: column; gap: 10px;"><div class="sec-row" style="padding: 0 20px;"><span class="sec">Spaces</span></div>'
      + coming + "</div>" + tabbar("Spaces", dot=None), tag="Phase 1")

SPACE_IMG = {"sneaker": "ND", "chama": "KC", "coffee": "CF", "books": "NB", "runners": "KR"}
your_spaces = (space_row("Nairobi Sneaker Drop", "Amina: New Jordans in at 6 pm · 1.2k members", "ND", live=True, end='<span class="cx-row-meta">18:02</span>')
               + space_row("Kilimani Chama Hub", "Weekly meet moved to Saturday · 340 members", "KC", end='<span class="cx-row-meta">Mon</span>'))
discover = (space_row("Coffee Farmers Kenya", "Farming · 4.8k members", "CF", end=btn("Follow", "secondary", "sm", block=False))
            + space_row("Nairobi Book Club", "Members only · 5 USDC a month (≈ KES 646)", "NB", end=btn("Join · 5 USDC", "secondary", "sm", block=False))
            + space_row("Karura Runners", "Sport · 910 members", "KR", end=btn("Follow", "secondary", "sm", block=False)))
frame(P_S, "S1b-Spaces-full", "S1 · Spaces tab, with spaces (phase 3)",
      "The same tab once spaces ship. Your spaces first, newest activity on top, a red LIVE pill when a room is open. "
      "Discover lists public and members-only spaces. Follow acts in place. LIVE (phase 4) is monochrome with a soft pulse, not the error red. "
      "Superseded for phase 3 by SD1 (page SD); joining is the money card SM1, not Send.",
      large_title("Spaces", spaces_buttons) + moments_row(ROW_FULL[:5])
      + '<div style="padding: 18px 0 0; display: flex; flex-direction: column; gap: 4px;">'
      + '<div class="sec-row" style="padding: 0 20px;"><span class="sec">Your spaces</span><button type="button" class="link">See All</button></div>'
      + f'<div class="cx-list cx-list-plain">{your_spaces}</div>'
      + '<div class="sec-row" style="padding: 12px 20px 0;"><span class="sec">Discover</span><button type="button" class="link">See All</button></div>'
      + f'<div class="cx-list cx-list-plain">{discover}</div></div>' + tabbar("Spaces"), tag="Phase 3 preview")

frame(P_S, "S1c-Spaces-empty", "S1 · Empty, no moments yet",
      "Nobody in your contacts has posted. Your own ring still invites you to post, and the line says who will see it. "
      "If you have no contacts on Circaso, the line becomes \"Find your people to see their moments\" with Find People.",
      large_title("Spaces", spaces_buttons) + moments_row([my_ring("add")])
      + '<div style="padding: 40px 32px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;">'
      + f'<span style="color: var(--ink-tertiary); display: flex;">{svg(TAB["Spaces"], 44, fill=True)}</span>'
      + '<div style="font: 600 17px/22px var(--font-sans);">No moments yet</div>'
      + '<p class="small" style="font-size: 15px; line-height: 20px;">When your contacts share a moment, it shows here for 24 hours. Yours go to people who have your number saved, and whose number you have saved.</p>'
      + btn("Share a Moment", "primary", "md", block=False, icon="camera") + "</div>"
      + '<div style="padding: 30px 0 0;">' + coming + "</div>" + tabbar("Spaces"))

sk_ring = '<div class="mo"><span class="sk" style="width: 68px; height: 68px; border-radius: 50%;"></span><span class="sk" style="width: 48px; height: 10px;"></span></div>'
frame(P_S, "S1d-Spaces-loading", "S1 · Loading, first open",
      "Only on the very first open, before anything is cached: skeleton rings, no spinner. The spaces card is static, so it "
      "shows at once. After that the tab always opens from what is on the phone and refreshes quietly.",
      large_title("Spaces", spaces_buttons)
      + moments_row([my_ring("add")] + [sk_ring] * 4).replace('<div class="mo-row">', '<div class="mo-row" aria-busy="true" aria-label="Loading moments">')
      + '<div style="padding: 22px 0 0; display: flex; flex-direction: column; gap: 10px;"><div class="sec-row" style="padding: 0 20px;"><span class="sec">Spaces</span></div>'
      + coming + "</div>" + tabbar("Spaces"))

frame(P_S, "S1e-Spaces-offline", "S1 · Offline",
      "Moments already downloaded still open; a ring whose media isn't on the phone yet has a greyed photo (the name stays readable) and opens V6. "
      "Posting still works: the moment waits on the phone and goes out when the connection is back (your ring shows Waiting).",
      large_title("Spaces", spaces_buttons)
      + f'<div style="padding: 10px 16px 0;">{banner("You’re offline · Showing moments as of 10:42")}</div>'
      + moments_row([my_ring("waiting"), moment("wanjiru"), moment("baraka"), moment("faith", state="nodl"), moment("mum", state="seen")])
      + '<div style="padding: 22px 0 0; display: flex; flex-direction: column; gap: 10px;"><div class="sec-row" style="padding: 0 20px;"><span class="sec">Spaces</span></div>'
      + coming + "</div>" + tabbar("Spaces"))

frame(P_S, "S1f-Spaces-error", "S1 · Couldn't load",
      "The server failed (5xx after retries) on a phone with nothing cached. Plain words, Try Again, and the request "
      "reference for support. With a cache, the cached moments show and a small banner says they may be out of date.",
      large_title("Spaces", spaces_buttons) + moments_row([my_ring("add")])
      + err_box("Moments didn’t load", "Something went wrong on our side. Your own moments are safe.", "req_7Hq2kD") + tabbar("Spaces"))

new_sheet = (sheet_head("New")
             + '<div class="cx-list" style="background: var(--surface-raised); box-shadow: inset 0 0 0 1px var(--separator);">'
             + row("Moment", "Text, photo, video or voice for 24 hours", f'<span class="cx-icon-btn">{ic("camera")}</span>', chev())
             + row("Space", "A community or shop with a chat and a wallet", f'<span class="cx-icon-btn">{svg(TAB["Spaces"], 18, fill=True)}</span>', '<span class="cx-badge" style="background: var(--fill); color: var(--ink-secondary);">Soon</span>')
             + "</div>")
frame(P_S, "S2-New-sheet", "S2 · New (the + button)",
      "Plus in the bar opens this. In phase 1 Space shows Soon and opens the honest coming sheet; Moment goes to the camera.",
      large_title("Spaces", spaces_buttons) + moments_row(ROW_FULL[:5]) + tabbar("Spaces") + sheet(new_sheet, "New"))

# ================================================================ PAGE M · Moments composer
P_M = "M"
def cam_top(right_extra=""):
    return (f'<div style="position: absolute; left: 16px; right: 16px; top: 59px; display: flex; justify-content: space-between; align-items: center; z-index: 2;">'
            f'<button type="button" class="glass-btn" aria-label="Close">{ic("close", 18)}</button>'
            f'<div style="display: flex; gap: 10px;">{right_extra}<button type="button" class="glass-btn" aria-label="Flash off">{ic("flash", 18, 1.8)}</button></div></div>')

def cam_bottom(mode="Photo", rec=False):
    modes = "".join(f'<b role="radio" aria-checked="true">{m}</b>' if m == mode else f'<span role="radio" aria-checked="false">{m}</span>' for m in ("Text", "Photo", "Video", "Voice"))
    strip = "".join(f'<span style="width: 46px; height: 46px; border-radius: 10px; overflow: hidden; flex: none; display: flex;"><img data-ph="{p}" src="" alt="" style="width: 100%; height: 100%; object-fit: cover;"></span>'
                    for p in ("faith", "juma", "njeri", "kevin", "achieng", "otieno"))
    return (f'<div style="position: absolute; left: 0; right: 0; bottom: 0; padding: 0 0 34px; display: flex; flex-direction: column; gap: 18px; z-index: 2;">'
            f'<div style="display: flex; gap: 6px; padding: 0 16px; overflow: hidden;">{strip}</div>'
            f'<div style="display: flex; align-items: center; justify-content: space-between; padding: 0 34px;">'
            f'<button type="button" class="glass-btn" aria-label="Gallery" style="width: 46px; height: 46px; border-radius: 12px;">{ic("image", 22, 1.8)}</button>'
            f'<span class="shutter{" rec" if rec else ""}" role="button" aria-label="{"Stop recording" if rec else "Take photo, hold for video"}"></span>'
            f'<button type="button" class="glass-btn" aria-label="Flip camera" style="width: 46px; height: 46px;">{ic("flip", 22, 1.8)}</button></div>'
            f'<div class="modes" role="radiogroup" aria-label="Mode">{modes}</div></div>')

frame(P_M, "M1-Camera", "M1 · Camera",
      "Opens from your ring or New → Moment. Full-screen viewfinder (CameraX on Android, AVFoundation on iOS), recent "
      "photos above the shutter. Tap for a photo, hold for video up to 60 s; swipe the mode row for Text or Voice.",
      '<div class="stage"><img class="bleed" data-ph="baraka" src="" alt="Camera preview"><div class="shade-top"></div><div class="shade-bot"></div></div>'
      + cam_top() + cam_bottom(), theme="dark", fixed=True, pad_top=0)

frame(P_M, "M1b-Recording", "M1 · Recording video",
      "Holding the shutter records; the ring fills over 60 s and the time shows at the top. Let go to stop; slide up to "
      "lock and record hands-free.",
      '<div class="stage"><img class="bleed" data-ph="baraka" src="" alt="Camera preview"><div class="shade-top"></div><div class="shade-bot"></div></div>'
      + '<div role="timer" style="position: absolute; top: 66px; left: 50%; transform: translateX(-50%); z-index: 3; display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 999px; background: #fff; color: #000; font: 600 15px/20px var(--font-sans); font-variant-numeric: tabular-nums;"><span style="width: 8px; height: 8px; border-radius: 50%; background: #c8102e;"></span>0:14 / 1:00</div>'
      + cam_bottom("Video", rec=True), theme="dark", fixed=True, pad_top=0)

frame(P_M, "M1c-Camera-denied", "M1 · Camera not allowed",
      "If camera access is off, the stage explains and offers Settings; the gallery and text still work, so the person "
      "is never stuck.",
      '<div class="stage"></div>' + cam_top()
      + '<div class="on-media" style="position: absolute; left: 32px; right: 32px; top: 300px; display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; z-index: 2;">'
      + f'<span style="display: flex; opacity: 0.8;">{ic("camera", 40, 1.6)}</span><div style="font: 600 20px/25px var(--font-sans);">Allow camera to take moments</div>'
      + '<p style="margin: 0; font-size: 15px; line-height: 20px; color: rgba(255,255,255,.72);">Circaso uses the camera only while this screen is open.</p>'
      + '<button type="button" class="cx-btn cx-btn-sm" style="background: #fff; color: #000;">Open Settings</button></div>'
      + cam_bottom(), theme="dark", fixed=True, pad_top=0)

text_tools = (f'<div style="position: absolute; left: 16px; right: 16px; top: 59px; display: flex; justify-content: space-between; align-items: center; z-index: 2;">'
              f'<button type="button" class="glass-btn" aria-label="Close">{ic("close", 18)}</button>'
              f'<div style="display: flex; gap: 10px;"><button type="button" class="glass-btn" aria-label="Text style: Bold" style="font: 700 17px/1 var(--font-sans);">Aa</button>'
              f'<button type="button" class="glass-btn" aria-label="Voice moment">{ic("mic", 18, 1.8)}</button></div></div>')
swatches = (f'<div style="display: flex; gap: 14px; justify-content: center;" role="radiogroup" aria-label="Background">'
            f'<span class="swatch bg-ink swatch-on" role="radio" aria-checked="true" aria-label="Ink"></span>'
            f'<span class="swatch bg-metal" role="radio" aria-checked="false" aria-label="Metal"></span>'
            f'<span class="swatch bg-paper" role="radio" aria-checked="false" aria-label="Paper"></span>'
            f'<span class="swatch bg-graphite" role="radio" aria-checked="false" aria-label="Graphite"></span></div>')
def audience_bar(dark=True, text="My contacts · 48 people", send_label="Share moment"):
    ink = "#fff" if dark else "var(--ink)"
    return (f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; align-items: center; gap: 10px; z-index: 3;">'
            f'<button type="button" class="glass-btn" aria-label="Who can see this: {text}" style="width: auto; padding: 0 14px; border-radius: 999px; gap: 6px; font: 600 15px/20px var(--font-sans);">{ic("people", 16, 1.8)}{text}{ic("chev_down", 14)}</button>'
            f'<span style="flex: 1"></span><button type="button" class="cx-icon-btn" aria-label="{send_label}" style="width: 52px; height: 52px; background: #fff; color: #000;">{ic("send", 22, 2.2)}</button></div>')

frame(P_M, "M2-Text", "M2 · Text moment",
      "Big centred text on a background picked from the token surfaces: Ink, Metal (the money-card metal), Paper and Graphite. "
      "No new colours; the brand stays monochrome. Aa switches Bold and Regular (the rounded face stays for money). The chip "
      "names the audience before sharing. Typing shows the keyboard; the empty state reads \"Type a moment\". Swipe the mode row to go back to Photo.",
      '<div class="stage bg-ink" style="background: #0b0b0c;"></div>' + text_tools
      + '<div style="position: absolute; left: 28px; right: 28px; top: 280px; z-index: 2; text-align: center; color: #fff; font: 700 34px/41px var(--font-sans); letter-spacing: 0.37px;">Chama meeting moved to Saturday, 10 am at Mama Njeri’s 🙏</div>'
      + '<div style="position: absolute; left: 0; right: 0; bottom: 112px; z-index: 2;">' + swatches + "</div>"
      + audience_bar(), theme="dark", fixed=True, pad_top=0)

frame(P_M, "M3-Gallery", "M3 · Gallery",
      "Pick up to 10 photos or videos; each becomes its own segment of your moment. Numbers show the order. Videos over 60 s "
      "open the trimmer.",
      bar("Recents", left="close", right=btn("Next", "primary", "sm", block=False))
      + '<div style="padding: 6px 16px 10px;"><div class="cx-seg" role="tablist"><button type="button" class="cx-seg-btn cx-seg-btn-on">All</button><button type="button" class="cx-seg-btn">Photos</button><button type="button" class="cx-seg-btn">Videos</button></div></div>'
      + '<div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 2px;">'
      + "".join(f'<div style="position: relative; aspect-ratio: 1; overflow: hidden;"><img data-ph="{p}" src="" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block;">'
                + (f'<span style="position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border-radius: 50%; background: var(--action); color: var(--on-action); font: 600 13px/24px var(--font-sans); text-align: center; box-shadow: 0 0 0 2px #fff;">{n}</span>' if n else
                   '<span style="position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border-radius: 50%; border: 2px solid #fff; box-sizing: border-box; background: rgba(0,0,0,.18);"></span>')
                + ('<span style="position: absolute; left: 6px; bottom: 5px; color: #fff; font: 600 12px/16px var(--font-sans); text-shadow: 0 1px 2px rgba(0,0,0,.5);">0:42</span>' if v else "")
                + "</div>"
                for p, n, v in [("faith", 1, False), ("juma", 0, True), ("njeri", 2, False), ("kevin", 0, False), ("achieng", 0, False), ("otieno", 0, True),
                                ("wanjiru", 0, False), ("baraka", 0, False), ("mum", 0, False), ("pasaka", 0, False), ("faith", 0, False), ("juma", 0, False)])
      + "</div>", bg="var(--bg)")

frame(P_M, "M4-Caption", "M4 · Caption and share",
      "Before sharing: the photo, a caption field, the audience chip and Share. Two picked items show as a strip; tap one to "
      "edit it. Moments are end-to-end encrypted; the line under the caption says so once.",
      '<div class="stage"><img class="bleed" data-ph="faith" src="" alt="Photo"><div class="shade-top"></div><div class="shade-bot" style="height: 340px;"></div></div>'
      + cam_top().replace('<button type="button" class="glass-btn" aria-label="Flash off">' + ic("flash", 18, 1.8) + "</button>", "")
      + '<div style="position: absolute; left: 16px; right: 16px; bottom: 104px; z-index: 3; display: flex; flex-direction: column; gap: 10px;">'
      + '<div style="display: flex; gap: 6px;"><span style="width: 44px; height: 44px; border-radius: 10px; overflow: hidden; box-shadow: 0 0 0 2px #fff; display: flex;"><img data-ph="faith" src="" alt="" style="width: 100%; object-fit: cover;"></span><span style="width: 44px; height: 44px; border-radius: 10px; overflow: hidden; opacity: .7; display: flex;"><img data-ph="njeri" src="" alt="" style="width: 100%; object-fit: cover;"></span></div>'
      + '<div style="display: flex; align-items: center; min-height: 44px; padding: 0 16px; border-radius: 22px; background: rgba(0,0,0,.4); -webkit-backdrop-filter: blur(20px); backdrop-filter: blur(20px); color: #fff; font: 400 17px/22px var(--font-sans);">Graduation day! 🎓</div>'
      + f'<div style="display: flex; align-items: center; gap: 6px; color: rgba(255,255,255,.78); font: 400 13px/18px var(--font-sans);">{ic("lock", 13, 2)}End-to-end encrypted. Gone after 24 hours.</div></div>'
      + audience_bar(), theme="dark", fixed=True, pad_top=0)

frame(P_M, "M5-Voice", "M5 · Voice moment",
      "Hold the mic to record up to 60 s on the chosen background; let go to review. Play to listen back, the bin to start "
      "again. The waveform is the person's own voice level.",
      '<div class="stage" style="background: linear-gradient(140deg,#4a4c52 0%,#2b2c30 38%,#2b2c30 62%,#4a4c52 100%);"></div>' + text_tools
      + '<div class="on-media" style="position: absolute; left: 24px; right: 24px; top: 300px; z-index: 2; display: flex; flex-direction: column; align-items: center; gap: 18px;">'
      + av("pasaka", "xl", "box-shadow: 0 0 0 3px rgba(255,255,255,.9);")
      + wave() + '<div role="timer" style="font: 600 20px/25px var(--font-sans); font-variant-numeric: tabular-nums;">0:23 / 1:00</div>'
      + f'<div style="display: flex; gap: 18px;"><button type="button" class="glass-btn" aria-label="Delete recording" style="width: 52px; height: 52px;">{ic("trash", 22, 1.8)}</button>'
      + f'<button type="button" class="glass-btn" aria-label="Play" style="width: 52px; height: 52px; background: #fff; color: #000;">{ic("play", 22)}</button></div></div>'
      + audience_bar(), theme="dark", fixed=True, pad_top=0)

def my_ring_state(label, inner_extra, sub):
    return (f'<div class="mo" role="button" aria-label="{label}"><span class="mo-ring" style="border-color: transparent;">{av("pasaka", "lg")}{inner_extra}</span>'
            f'<span class="mo-name">{sub}</span></div>')
frame(P_M, "M6-Posting", "M6 · Posting, waiting and failed",
      "After Share you are back on the tab at once (optimistic, it's not money). Your ring shows upload progress; offline it "
      "says Waiting and posts on reconnect (its 24 hours start when it posts; a queued moment older than 20 hours asks first, "
      "per the outbox rule). If it fails for good: a red mark and a banner with Retry or Delete. Nothing is lost silently.",
      large_title("Spaces", spaces_buttons)
      + f'<div style="padding: 14px 0 0;"><div class="sec-row" style="padding: 0 20px 8px;"><span class="sec">Moments</span></div><div class="mo-row">'
      + my_ring("posting") + my_ring("waiting") + my_ring("failed")
      + moment("wanjiru") + "</div></div>"
      + '<p class="small" style="padding: 8px 20px 0;">Three states of your own ring, shown side by side for review.</p>'
      + '<div style="position: absolute; left: 16px; right: 16px; bottom: 112px; z-index: 5;"><div class="cx-banner cx-banner-critical" role="alert"><span class="cx-banner-icon">'
      + ic("warning") + '</span><span class="cx-banner-text">Your moment didn’t post.</span><button type="button" class="link">Retry</button><button type="button" class="link" style="color: var(--critical); margin-left: 12px;">Delete</button></div></div>'
      + tabbar("Spaces"))

frame(P_M, "M3b-Trim", "M3 · Trim a video",
      "A video longer than 60 s opens here: drag the handles on the filmstrip; the selected length shows above. Done keeps the "
      "trimmed copy only on the phone until it's shared.",
      '<div class="stage"><img class="bleed" data-ph="juma" src="" alt="Video frame"><div class="shade-top"></div><div class="shade-bot"></div></div>'
      + f'<div style="position: absolute; left: 16px; right: 16px; top: 59px; display: flex; justify-content: space-between; align-items: center; z-index: 2;"><button type="button" class="glass-btn" aria-label="Cancel">{ic("close", 18)}</button>'
      + '<button type="button" class="cx-btn cx-btn-sm" style="background: #fff; color: #000;">Done</button></div>'
      + '<div class="on-media" style="position: absolute; left: 16px; right: 16px; bottom: 60px; z-index: 3; display: flex; flex-direction: column; gap: 12px; align-items: center;">'
      + '<div role="status" style="font: 600 15px/20px var(--font-sans); font-variant-numeric: tabular-nums;">0:58 selected of 2:14</div>'
      + '<div style="position: relative; width: 100%; height: 56px; border-radius: 10px; overflow: hidden; display: flex;">'
      + "".join('<img data-ph="juma" src="" alt="" style="flex: 1; min-width: 0; height: 100%; object-fit: cover; opacity: .45;">' for _ in range(8))
      + '<div role="slider" aria-label="Trim" aria-valuetext="0:21 to 1:19" style="position: absolute; left: 16%; width: 44%; top: 0; bottom: 0; border: 3px solid #fff; border-left-width: 12px; border-right-width: 12px; border-radius: 10px; box-sizing: border-box;"></div></div></div>',
      theme="dark", fixed=True, pad_top=0)

frame(P_M, "M3c-Photos-limited", "M3 · Photo access limited or off",
      "iPhone's limited library: only the photos the person chose show, with Manage to pick more. With access off, the grid is "
      "replaced by one line and Open Settings; the camera and text moments still work.",
      bar("Recents", left="close", right=btn("Next", "primary", "sm", block=False, disabled=True))
      + '<div style="padding: 6px 16px 12px;"><div class="cx-banner" role="status"><span class="cx-banner-icon">' + ic("info") + '</span><span class="cx-banner-text">Circaso can see 3 photos you chose.</span><button type="button" class="link">Manage</button></div></div>'
      + '<div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 2px;">'
      + "".join(f'<div style="position: relative; aspect-ratio: 1; overflow: hidden;"><img data-ph="{q}" src="" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block;"><span style="position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border-radius: 50%; border: 2px solid #fff; box-sizing: border-box; background: rgba(0,0,0,.18);"></span></div>' for q in ("faith", "njeri", "kevin"))
      + "</div>"
      + '<div style="padding: 60px 32px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;">'
      + f'<span style="color: var(--ink-tertiary); display: flex;">{ic("image", 40, 1.6)}</span><p class="small" style="font-size: 15px; line-height: 20px;">If photo access is off, this is all the screen shows: “Allow photo access to share from your gallery.”</p>'
      + btn("Open Settings", "secondary", "sm", block=False) + "</div>")

frame(P_M, "M5b-Voice-recording", "M5 · Recording a voice moment",
      "While the mic is held: the live level, the time counting to 1:00, and slide left to cancel. Let go to review (M5). At "
      "1:00 it stops by itself.",
      '<div class="stage" style="background: linear-gradient(140deg,#4a4c52 0%,#2b2c30 38%,#2b2c30 62%,#4a4c52 100%);"></div>' + text_tools
      + '<div class="on-media" style="position: absolute; left: 24px; right: 24px; top: 300px; z-index: 2; display: flex; flex-direction: column; align-items: center; gap: 18px;">'
      + av("pasaka", "xl", "box-shadow: 0 0 0 3px rgba(255,255,255,.9);") + wave(24, seed=5)
      + '<div role="timer" style="font: 600 20px/25px var(--font-sans); font-variant-numeric: tabular-nums;">0:09 / 1:00</div></div>'
      + '<div class="on-media" style="position: absolute; left: 0; right: 0; bottom: 60px; z-index: 3; display: flex; flex-direction: column; align-items: center; gap: 14px;">'
      + f'<span style="width: 84px; height: 84px; border-radius: 50%; background: #fff; color: #c8102e; display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 10px rgba(255,255,255,.18);" role="button" aria-label="Recording, release to stop">{ic("mic", 30, 2)}</span>'
      + f'<span style="font: 400 15px/20px var(--font-sans); color: rgba(255,255,255,.8); display: flex; align-items: center; gap: 4px;">{ic("back", 14)}Slide to cancel</span></div>',
      theme="dark", fixed=True, pad_top=0)

frame(P_M, "M5c-Mic-denied", "M5 · Microphone not allowed",
      "Voice needs the microphone. If it's off: one line and Open Settings; text and photo still work.",
      '<div class="stage" style="background: #0b0b0c;"></div>' + text_tools
      + '<div class="on-media" style="position: absolute; left: 32px; right: 32px; top: 320px; display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; z-index: 2;">'
      + f'<span style="display: flex; opacity: 0.8;">{ic("mic", 40, 1.6)}</span><div style="font: 600 20px/25px var(--font-sans);">Allow the microphone for voice moments</div>'
      + '<p style="margin: 0; font-size: 15px; line-height: 20px; color: rgba(255,255,255,.72);">Circaso only listens while you hold the mic button.</p>'
      + '<button type="button" class="cx-btn cx-btn-sm" style="background: #fff; color: #000;">Open Settings</button></div>',
      theme="dark", fixed=True, pad_top=0)

# ================================================================ PAGE V · Viewer
P_V = "V"
def viewer_top(who, time, segs, mine=False):
    seg_html = "".join(f'<i class="{c}"></i>' for c in segs)
    right = (f'<button type="button" class="glass-btn" aria-label="More">{ic("more", 18, 3)}</button>'
             f'<button type="button" class="glass-btn" aria-label="Close">{ic("close", 18)}</button>')
    return (f'<div class="on-media" style="position: absolute; left: 12px; right: 12px; top: 54px; z-index: 3; display: flex; flex-direction: column; gap: 10px;">'
            f'<div class="segs" role="progressbar" aria-label="Moment {segs.index("now") + 1 if "now" in segs else len(segs)} of {len(segs)}">{seg_html}</div>'
            f'<div style="display: flex; align-items: center; gap: 10px;">{av(who, "sm", "width: 36px; height: 36px;")}'
            f'<div style="flex: 1; min-width: 0;"><div style="font: 600 15px/20px var(--font-sans);">{"My moment" if mine else FULL[who]}</div>'
            f'<div style="font: 400 13px/18px var(--font-sans); color: rgba(255,255,255,.75);">{time}</div></div>{right}</div></div>')

def reply_bar(name):
    return (f'<div style="position: absolute; left: 12px; right: 12px; bottom: 34px; z-index: 3; display: flex; align-items: center; gap: 10px;">'
            f'<div style="flex: 1; height: 46px; border-radius: 23px; border: 1px solid rgba(255,255,255,.55); display: flex; align-items: center; padding: 0 18px; color: rgba(255,255,255,.8); font: 400 17px/22px var(--font-sans);">Reply to {name}</div>'
            f'<button type="button" class="glass-btn" aria-label="Love this moment" style="width: 46px; height: 46px;">{ic("heart", 22, 1.8)}</button></div>')

frame(P_V, "V1-Viewer-photo", "V1 · Viewer, photo",
      "Full screen. Segments at the top, one per post; tap right or left to move, hold to pause, swipe down to close, swipe "
      "left for the next person. Heart sends a quiet reaction; a reply goes to your private chat with them.",
      '<div class="stage"><img class="bleed" data-ph="wanjiru" src="" alt="Wanjiru’s photo"><div class="shade-top"></div><div class="shade-bot"></div></div>'
      + viewer_top("wanjiru", "34 minutes ago", ["done", "now", ""])
      + '<div class="on-media" style="position: absolute; left: 20px; right: 20px; bottom: 100px; z-index: 3; text-align: center; font: 400 17px/22px var(--font-sans); text-shadow: 0 1px 3px rgba(0,0,0,.5);">Sunday market haul 🥭🍍</div>'
      + reply_bar("Wanjiru"), theme="dark", fixed=True, pad_top=0)

frame(P_V, "V2-Viewer-text", "V2 · Viewer, text",
      "A text moment fills the screen on its chosen background. Long text shrinks to fit, never scrolls.",
      '<div class="stage" style="background: linear-gradient(140deg,#4a4c52 0%,#2b2c30 38%,#2b2c30 62%,#4a4c52 100%);"></div>'
      + viewer_top("baraka", "2 hours ago", ["now"])
      + '<div style="position: absolute; left: 28px; right: 28px; top: 300px; z-index: 2; text-align: center; color: #fff; font: 700 32px/39px var(--font-sans); letter-spacing: 0.2px;">Started my first shop today. Thank you all for the support ❤️</div>'
      + reply_bar("Baraka"), theme="dark", fixed=True, pad_top=0)

frame(P_V, "V3-Viewer-voice", "V3 · Viewer, voice",
      "A voice moment plays on its own; the waveform moves with it and the poster's photo sits in the middle. Captions under "
      "the waveform if the poster added one.",
      '<div class="stage" style="background: #0b0b0c;"></div>' + viewer_top("mum", "Today, 07:12", ["done", "done", "now"])
      + '<div class="on-media" style="position: absolute; left: 24px; right: 24px; top: 290px; z-index: 2; display: flex; flex-direction: column; align-items: center; gap: 18px;">'
      + av("mum", "xl", "box-shadow: 0 0 0 3px rgba(255,255,255,.9);") + wave(seed=7)
      + '<div style="font: 400 15px/20px var(--font-sans); color: rgba(255,255,255,.8);">0:09 / 0:31 · Morning prayer for the family</div></div>'
      + reply_bar("Mum"), theme="dark", fixed=True, pad_top=0)

viewers = "".join(row(FULL[w], t, av(w), (f'<span style="color: var(--ink); display: flex;">{ic("heart", 16, 2)}</span>' if h else ""), tap=False)
                  for w, t, h in [("wanjiru", "Today, 09:41", True), ("otieno", "Today, 09:20", False), ("achieng", "Today, 08:57", True), ("juma", "Today, 08:31", False)])
frame(P_V, "V4-My-moment", "V4 · My moment, viewers",
      "Your own moment shows Seen by at the bottom instead of a reply field; swipe up for the list with time and reaction. "
      "More has Delete and Forward to a chat. View receipts are on for everyone; there is no hidden viewing.",
      '<div class="stage"><img class="bleed" data-ph="pasaka" src="" alt="My photo"><div class="shade-top"></div></div>'
      + viewer_top("pasaka", "1 hour ago", ["now", ""], mine=True)
      + sheet(sheet_head(f"Seen by 12", close=False)
              + '<div style="display: flex; gap: 8px;">' + btn("Forward", "secondary", "sm", block=False, icon="forward")
              + btn("Delete", "destructive", "sm", block=False, icon="trash") + "</div>"
              + f'<div class="cx-list" style="background: var(--surface-raised);">{viewers}</div>', "Viewers", scrim=False), theme="light", pad_top=0)

frame(P_V, "V5-Viewer-loading", "V5 · Loading a moment",
      "Media downloads when the ring is tapped (or ahead of time on Wi-Fi). The blurred thumbnail, which travels in the "
      "encrypted post, holds the place; the segment does not start until the media is ready.",
      '<div class="stage"><img class="bleed" data-ph="faith" src="" alt="" style="filter: blur(24px) brightness(.8); transform: scale(1.2);"><div class="shade-top"></div></div>'
      + viewer_top("faith", "3 hours ago", [""])
      + '<div class="on-media" style="position: absolute; left: 0; right: 0; top: 400px; z-index: 3; display: flex; flex-direction: column; align-items: center; gap: 10px;"><span class="cx-spin" style="width: 28px; height: 28px; border-width: 2.5px;"></span><span style="font: 500 15px/20px var(--font-sans);">Loading</span></div>'
      + reply_bar("Faith"), theme="dark", fixed=True, pad_top=0)

frame(P_V, "V6-Viewer-offline", "V6 · Offline, not downloaded",
      "Offline and the media isn't on the phone: say so and keep the blurred thumbnail. Next moves on to the next one that "
      "is downloaded. Replies typed here wait and send with the other messages.",
      '<div class="stage"><img class="bleed" data-ph="faith" src="" alt="" style="filter: blur(24px) brightness(.7); transform: scale(1.2);"><div class="shade-top"></div></div>'
      + viewer_top("faith", "3 hours ago", [""])
      + f'<div class="on-media" style="position: absolute; left: 40px; right: 40px; top: 360px; z-index: 3; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;"><span style="display: flex;">{ic("offline", 32, 1.8)}</span>'
      + '<div style="font: 600 17px/22px var(--font-sans);">This moment needs a connection</div><div style="font: 400 15px/20px var(--font-sans); color: rgba(255,255,255,.75);">It opens as soon as you’re back online.</div></div>'
      + reply_bar("Faith"), theme="dark", fixed=True, pad_top=0)

frame(P_V, "V7-Viewer-gone", "V7 · No longer available",
      "The poster deleted it, or the 24 hours ran out while it was open. One calm line; the viewer moves on after 2 s.",
      '<div class="stage" style="background: #0b0b0c;"></div>' + viewer_top("kevin", "Yesterday, 21:04", ["now"])
      + f'<div class="on-media" style="position: absolute; left: 40px; right: 40px; top: 380px; z-index: 3; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;"><span style="display: flex; opacity: .8;">{ic("clock", 32, 1.8)}</span>'
      + '<div style="font: 600 17px/22px var(--font-sans);">This moment is no longer available</div></div>', theme="dark", fixed=True, pad_top=0)

quote = (f'<div style="align-self: flex-end; max-width: 75%; display: flex; flex-direction: column; gap: 4px;">'
         f'<div class="cx-bubble cx-bubble-out" style="max-width: none; padding: 6px 6px 9px;"><div class="quote-out" style="display: flex; gap: 8px; align-items: center; padding: 6px 8px; border-radius: 14px; margin-bottom: 6px;">'
         f'<span style="width: 36px; height: 48px; border-radius: 6px; overflow: hidden; flex: none; display: flex;"><img data-ph="wanjiru" src="" alt="" style="width: 100%; object-fit: cover;"></span>'
         f'<div style="font: 400 13px/18px var(--font-sans);"><div style="font-weight: 600;">Wanjiru · Moment</div>Sunday market haul 🥭🍍</div></div>'
         f'<div style="padding: 0 8px;">Those mangoes! Where did you get them?</div></div><div class="cx-bubble-time cx-bubble-time-out">09:44</div></div>')
frame(P_V, "V8-Reply-in-chat", "V8 · Reply lands in the chat",
      "A reply is an ordinary encrypted chat message with the moment quoted (thumbnail and caption). The quote stays after "
      "the moment expires, as \"Moment · no longer available\" without the picture.",
      bar("Wanjiru", sub="Online", avatar=av("wanjiru", "sm"))
      + f'<div class="cx-thread" style="padding: 8px 16px 0;"><div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">Today</div>'
      + '<div class="cx-bubble cx-bubble-in">See you at the meeting</div>' + quote + "</div>")

frame(P_V, "V1b-More", "V1 · More, someone else's moment",
      "More on another person's moment: mute their moments (they move to Muted, at the end of the row; they aren't told), or "
      "open the chat. Muted rings stay reachable there, greyed.",
      '<div class="stage"><img class="bleed" data-ph="wanjiru" src="" alt="Wanjiru’s photo"><div class="shade-top"></div></div>'
      + viewer_top("wanjiru", "34 minutes ago", ["done", "now", ""])
      + sheet('<div class="cx-list" style="background: var(--surface-raised); box-shadow: inset 0 0 0 1px var(--separator);">'
              + row("Mute Wanjiru’s Moments", None, f'<span class="cx-icon-btn">{ic("mute", 18, 1.8)}</span>')
              + row("Message Wanjiru", None, f'<span class="cx-icon-btn">{svg(TAB["Chats"], 18, fill=True)}</span>') + "</div>"
              + btn("Cancel", "secondary"), "Moment options"), theme="dark", fixed=True, pad_top=0)

frame(P_V, "V4b-Delete-confirm", "V4 · Delete, confirm",
      "Delete asks once. It removes the moment for everyone at once, including the copy on the server.",
      '<div class="stage"><img class="bleed" data-ph="pasaka" src="" alt="My photo"><div class="shade-top"></div></div>'
      + viewer_top("pasaka", "1 hour ago", ["now", ""], mine=True)
      + sheet('<div style="display: flex; flex-direction: column; gap: 8px; text-align: center; padding: 8px 8px 4px;"><div class="cx-sheet-title" style="flex: none;">Delete this moment?</div>'
              + '<p class="small" style="font-size: 15px; line-height: 20px;">People who haven’t seen it won’t be able to. This can’t be undone.</p></div>'
              + btn("Delete Moment", "destructive") + btn("Cancel", "plain"), "Delete this moment"),
      theme="light", pad_top=0)

frame(P_V, "V5b-Viewer-error", "V5 · Couldn't load",
      "Download or decryption failed after retries. Say so plainly with Try Again and the reference; Next still moves on. "
      "A decryption failure never shows partial media.",
      '<div class="stage"><img class="bleed" data-ph="faith" src="" alt="" style="filter: blur(24px) brightness(.7); transform: scale(1.2);"><div class="shade-top"></div></div>'
      + viewer_top("faith", "3 hours ago", [""])
      + f'<div class="on-media" role="alert" style="position: absolute; left: 40px; right: 40px; top: 340px; z-index: 3; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;"><span style="display: flex;">{ic("warning", 32, 1.8)}</span>'
      + '<div style="font: 600 17px/22px var(--font-sans);">This moment didn’t load</div>'
      + '<button type="button" class="cx-btn cx-btn-sm" style="background: #fff; color: #000;">Try Again</button><div class="rid" style="color: rgba(255,255,255,.72);">Ref req_V7bq1T</div></div>'
      + reply_bar("Faith"), theme="dark", fixed=True, pad_top=0)

# ================================================================ PAGE P · Privacy
P_P = "P"
def radio_row(title, sub, on):
    return row(title, sub, f'<span class="radio{" radio-on" if on else ""}" role="radio" aria-checked="{str(on).lower()}"></span>', "", tap=True)
frame(P_P, "P1-Moments-privacy", "P1 · Moments privacy",
      "You → Privacy → Moments. The audience is chosen on your phone from your Circaso contacts; the server never learns who "
      "your contacts are. Changes apply to moments you share from now on. Works offline (it's a setting on the phone).",
      bar("Moments") + '<div style="padding: 12px 16px 0; display: flex; flex-direction: column; gap: 8px;">'
      + '<div class="sec">Who can see my moments</div><div class="cx-list">'
      + radio_row("My contacts", "People who have your number saved, and whose number you have saved", True)
      + radio_row("My contacts except…", "None excluded", False)
      + radio_row("Only share with…", "Nobody chosen", False) + "</div>"
      + '<p class="small" style="padding: 0 4px;">Changes apply to moments you share from now on. Moments are end-to-end encrypted; only the people you share with can see them, for 24 hours.</p>'
      + '<p class="small" style="padding: 0 4px;">People can see that you viewed their moment, as you can see who viewed yours.</p>'
      + '<div class="sec" style="padding-top: 14px;">Muted</div><div class="cx-list">'
      + row("Muted moments", "2 people", f'<span class="cx-icon-btn">{ic("mute", 18, 1.8)}</span>', chev()) + "</div>"
      + '<p class="small" style="padding: 0 4px;">Muted people’s moments move to Muted, at the end of the row. They aren’t told.</p></div>', bg="var(--bg-grouped)")

def pick_row(who, on):
    return row(FULL[who], None, av(who), f'<span class="check{" check-on" if on else ""}" role="checkbox" aria-checked="{str(on).lower()}">{ic("check", 14, 3) if on else ""}</span>')
frame(P_P, "P2-Except", "P2 · My contacts except…",
      "Search and tick. The count in Done updates as you go. \"Only share with…\" is the same screen with the opposite meaning "
      "and its own title.",
      bar("Hide my moments from", left="cancel", right=btn("Done (2)", "plain", "sm", block=False))
      + f'<div style="padding: 8px 16px;"><div class="cx-field-box"><span style="color: var(--ink-secondary); display: flex;">{ic("search", 16)}</span><input class="cx-field-input" placeholder="Search contacts"></div></div>'
      + '<div class="cx-list cx-list-plain">' + "".join(pick_row(w, on) for w, on in [("achieng", False), ("baraka", False), ("faith", False), ("juma", True), ("kevin", True), ("mum", False), ("njeri", False), ("otieno", False)]) + "</div>")

frame(P_P, "P3-No-contacts", "P3 · No contacts on Circaso",
      "Empty state for the picker: nobody to choose yet. Find People opens contacts (A9).",
      bar("Only share with", left="cancel", right=btn("Done", "plain", "sm", block=False, disabled=True))
      + '<div style="padding: 120px 32px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;">'
      + f'<span style="color: var(--ink-tertiary); display: flex;">{ic("people", 44, 1.6)}</span><div style="font: 600 17px/22px var(--font-sans);">None of your contacts are on Circaso yet</div>'
      + '<p class="small" style="font-size: 15px; line-height: 20px;">Moments go only to people who have your number saved, and whose number you have saved.</p>'
      + btn("Find People", "primary", "md", block=False) + "</div>")

# ================================================================ PAGE GW · Groups with wallets
P_G = "GW"
members = "".join(f'<div style="display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 12px;">{av(w)}{n}</div>'
                  for w, n in [("pasaka", "You"), ("otieno", "Otieno"), ("wanjiru", "Wanjiru"), ("achieng", "Achieng")])
members += f'<div style="display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 12px; color: var(--ink-secondary);"><button type="button" class="cx-avatar cx-avatar-md" aria-label="Add members" style="border: 0;">{ic("plus")}</button>Add</div>'

def seg(options, on=0, label=""):
    b = "".join(f'<button type="button" role="radio" aria-checked="{str(i == on).lower()}" class="cx-seg-btn{" cx-seg-btn-on" if i == on else ""}">{o}</button>' for i, o in enumerate(options))
    return f'<div class="cx-seg" role="radiogroup" aria-label="{label}">{b}</div>'

def new_group(wallet_on, offline=False, failed=False):
    sw_attrs = ' disabled style="opacity: 0.4;"' if offline else ""
    card = ('<div style="border-radius: var(--radius-md); background: var(--surface); box-shadow: inset 0 0 0 1px var(--separator); padding: 14px 16px; display: flex; flex-direction: column; gap: 14px;">'
            '<div style="display: flex; align-items: center; gap: 12px;"><div style="flex-grow: 1;"><div style="font-size: 17px; line-height: 22px; font-weight: 600;">Group wallet</div>'
            + ('<div style="font-size: 13px; line-height: 18px; color: var(--ink-secondary);">Members pay into it. It’s separate from everyone’s personal money, with its own PIN.</div>' if wallet_on else
               '<div style="font-size: 13px; line-height: 18px; color: var(--ink-secondary);">For chamas, savings and small businesses. You can add it later from group info.</div>')
            + f'</div><button type="button" class="sw{" sw-on" if wallet_on else ""}" role="switch" aria-checked="{str(wallet_on).lower()}" aria-label="Group wallet"{sw_attrs}></button></div>')
    if wallet_on:
        card += ('<div style="display: flex; gap: 12px;"><div style="flex-grow: 1; min-width: 0;">' + field("Target (optional)", "50,000", prefix="KES", fid="t1")
                 + '</div><div style="width: 120px; flex-shrink: 0;">' + field("By (optional)", "14 Dec", fid="t2") + "</div></div>"
                 + '<div style="display: flex; flex-direction: column; gap: 6px;"><div class="cx-field-label">Who approves payouts</div>' + seg(["2 admins", "Any admin"], 0, "Who approves payouts") + "</div>"
                 + f'<div style="display: flex; gap: 8px; align-items: flex-start; font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);"><span style="display: flex; padding-top: 1px;">{ic("lock", 14)}</span>'
                 + "<span>Only you can send from this wallet, and only after 2 admins approve each payout (the admin who asks counts as one). Up to 10,000 USDC can leave it in 24 hours. Everyone sees every payment.</span></div>")
    card += "</div>"
    if offline:
        cta = btn("Create Group", disabled=True) + '<div class="cx-footnote" style="padding-top: 8px;">Needs a connection</div>'
        extra = f'<div style="padding-top: 2px;">{banner("You’re offline. Creating a group needs a connection.")}</div>'
    else:
        cta = btn("Create Group and Set Up Wallet" if wallet_on else "Create Group")
        extra = ""
    if failed:
        extra = (f'<div class="cx-banner cx-banner-critical" role="alert"><span class="cx-banner-icon">{ic("warning")}</span><span class="cx-banner-text">The group wasn’t created. Nothing was saved. '
                 '<span class="rid">Ref req_G4mx2Q</span></span></div>')
        cta = btn("Try Again")
    return (bar("New Group", left="cancel")
            + '<div style="padding: 12px 16px 0; display: flex; flex-direction: column; gap: 16px;">' + extra
            + f'<div style="display: flex; align-items: center; gap: 14px;"><button type="button" class="cx-avatar cx-avatar-lg" aria-label="Add group photo" style="border: 0; color: var(--ink-secondary);">{ic("camera", 24, 1.8)}</button>'
            + f'<div style="flex-grow: 1; min-width: 0;">{field("Group name", "Kilimani Chama", fid="gn")}</div></div>'
            + f'<div style="display: flex; flex-direction: column; gap: 8px;"><div class="sec">4 members</div><div style="display: flex; gap: 14px;">{members}</div></div>'
            + card + "</div>"
            + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px;">{cta}</div>')

frame(P_G, "GW1-New-group", "GW1 · New group, wallet off",
      "The D1 flow with one switch. Off by default: an ordinary encrypted group, no money actions, no target. The line says "
      "it can be added later, so nobody feels forced to decide now.",
      new_group(False), bg="var(--bg-grouped)")
frame(P_G, "GW1b-New-group-wallet", "GW1 · New group, wallet on",
      "On: the target fields and D1's approval control appear (a target needs a wallet), and the trust line says plainly who "
      "can move the money, after whose approval, and the daily cap. The button says what comes next: create, then set the PIN.",
      new_group(True), bg="var(--bg-grouped)")
frame(P_G, "GW1c-New-group-offline", "GW1 · Offline",
      "Creating a group is online-only (the server makes it and the members' keys are needed), and so is a wallet. Offline, "
      "both are disabled before the tap with the reason; nothing fails after it. The form keeps what was typed.",
      new_group(True, offline=True), bg="var(--bg-grouped)")
frame(P_G, "GW1d-New-group-failed", "GW1 · Couldn't create",
      "The create call failed (5xx after retries, same Idempotency-Key). Nothing was made, the form is kept, Try Again reuses "
      "the key so a slow first attempt can't make two groups.",
      new_group(True, failed=True), bg="var(--bg-grouped)")

gav = lambda size="lg", style="": av("Kilimani Chama", size, style)
def pin_screen(title, lead, filled, error=None, top=""):
    err = (f'<div role="alert" style="display: flex; gap: 6px; align-items: center; justify-content: center; color: var(--critical); font: 400 15px/20px var(--font-sans);">{ic("warning", 16)}{error}</div>' if error else '<div style="height: 20px;"></div>')
    return (bar("", left="back")
            + '<div style="display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 18px 24px 0; text-align: center;">'
            + top + f'<h1 class="h1" style="font-size: 22px; line-height: 28px;">{title}</h1><p class="lead" style="font-size: 15px; line-height: 20px;">{lead}</p>{err}</div>'
            + f'<div style="position: absolute; left: 0; right: 0; bottom: 48px; display: flex; justify-content: center;">{pin(filled)}</div>')

wallet_badge = lambda: (f'<div style="position: relative;">{gav("lg")}<span style="position: absolute; right: -4px; bottom: -4px; width: 28px; height: 28px; border-radius: 50%; '
                        f'background: var(--action); color: var(--on-action); display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 3px var(--bg);">{ic("wallet", 15, 2)}</span></div>')
frame(P_G, "GW2-Set-PIN", "GW2 · Set the group wallet's PIN",
      "Circle's PIN screen in Circaso's skin, with the group's photo and name on it, so it can't be mistaken for the personal "
      "PIN. The app sets Circle's texts before each call, so they can name the wallet.",
      pin_screen("Set a PIN for Kilimani Chama", "This PIN approves money leaving the group wallet. Use a different one from your personal PIN.", 2, top=wallet_badge()))
frame(P_G, "GW3-Confirm-PIN", "GW3 · Confirm, PINs don't match",
      "Second entry. On a mismatch the dots shake once, clear, and the line says what happened; the person retypes only the "
      "confirmation. A PIN equal to the personal one is allowed but discouraged (Circle can't compare them).",
      pin_screen("Confirm Kilimani Chama’s PIN", "Type it once more.", 0, error="Those PINs didn’t match. Try again.", top=wallet_badge()))

def bio_offer(kind):
    icon = svg(I["fingerprint"] if kind == "fp" else I["faceid"], 84, 1.4)
    word = "your fingerprint" if kind == "fp" else "Face ID"
    btnw = "Turn On Fingerprint" if kind == "fp" else "Turn On Face ID"
    return (bar("", left="")
            + '<div style="display: flex; flex-direction: column; align-items: center; gap: 26px; text-align: center; padding: 120px 32px 0;">'
            + f'<div style="position: relative;"><span style="display: flex;">{icon}</span><span style="position: absolute; right: -14px; bottom: -10px;">{gav("md", "box-shadow: 0 0 0 3px var(--bg);")}</span></div>'
            + f'<div style="display: flex; flex-direction: column; gap: 8px;"><h1 class="h1">Approve Kilimani Chama payments with {word}?</h1>'
            + '<p class="lead">Only for this group wallet. Your personal wallet keeps its own setting. If it doesn’t work, the group PIN takes over.</p></div></div>'
            + f'<div style="position: absolute; left: 20px; right: 20px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn(btnw)}{btn("Not Now", "plain")}'
            + '<p class="small" style="text-align: center; padding-top: 8px;">You can change this in Kilimani Chama’s group info.</p></div>')
frame(P_G, "GW4-Fingerprint", "GW4 · Fingerprint for this wallet (Android)",
      "ID-11's offer, per wallet: the group's photo sits on the fingerprint so it's clear which wallet this is for. Each "
      "group wallet is its own Circle user, so its biometrics turn on and off on their own.",
      bio_offer("fp"))
frame(P_G, "GW4b-Face-ID", "GW4 · Face ID for this wallet (iPhone)", "The iPhone wording of GW4.", bio_offer("face"))

frame(P_G, "GW5-Setting-up", "GW5 · Setting up the wallet",
      "While the server creates the group's Circle user and its addresses on every network. Usually a second or two; the "
      "steps tick as they finish (announced politely to screen readers) so a slow network never looks stuck.",
      '<div style="display: flex; flex-direction: column; align-items: center; gap: 22px; padding: 200px 32px 0; text-align: center;">'
      + wallet_badge() + '<h1 class="h1" style="font-size: 22px; line-height: 28px;">Setting up Kilimani Chama’s wallet</h1>'
      + '<div role="status" aria-live="polite" style="display: flex; flex-direction: column; gap: 10px; text-align: left; font: 400 15px/20px var(--font-sans);">'
      + f'<div style="display: flex; gap: 10px; align-items: center;">{ic("check", 18, 2.4)}PIN set</div>'
      + f'<div style="display: flex; gap: 10px; align-items: center;">{ic("check", 18, 2.4)}Wallet created</div>'
      + '<div style="display: flex; gap: 10px; align-items: center; color: var(--ink-secondary);"><span class="cx-spin" style="width: 16px; height: 16px;"></span>Adding networks (3 of 5)</div></div></div>')

frame(P_G, "GW5b-Setup-failed", "GW5 · Couldn't finish the wallet",
      "The group exists; only the wallet step failed. The group works as a chat now, and its info shows Finish Wallet Setup "
      "(GW6b). Copy keyed on the error code; only the reference is shown.",
      bar("Kilimani Chama", left="close")
      + '<div style="display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 140px 32px 0; text-align: center;">'
      + wallet_badge() + '<h1 class="h1" style="font-size: 22px; line-height: 28px;">The group is ready. The wallet isn’t yet.</h1>'
      + '<p class="lead" style="font-size: 15px; line-height: 20px;">We couldn’t finish setting up the wallet. Try again; if its PIN was already set, you won’t be asked for it again.</p>'
      + '<div class="rid">Ref req_P3kq9X</div></div>'
      + f'<div style="position: absolute; left: 20px; right: 20px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Try Again")}{btn("Open the Group", "plain")}</div>')

frame(P_G, "GW5c-Setup-offline", "GW5 · Connection lost during set-up",
      "Only when the connection drops mid-set-up (the group was already created online). Nothing is retried silently in the "
      "background: the wallet waits for the person, with Finish Wallet Setup in the group.",
      bar("Kilimani Chama", left="close")
      + '<div style="display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 150px 32px 0; text-align: center;">'
      + f'<span style="color: var(--ink-secondary); display: flex;">{ic("offline", 44, 1.6)}</span><h1 class="h1" style="font-size: 22px; line-height: 28px;">The connection dropped</h1>'
      + '<p class="lead" style="font-size: 15px; line-height: 20px;">Kilimani Chama is created. Its wallet isn’t finished; you can finish it from the group when you’re back online.</p></div>'
      + f'<div style="position: absolute; left: 20px; right: 20px; bottom: 34px;">{btn("Open the Group", "secondary")}</div>')

def info_head(sub):
    return (f'<div style="display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 8px 0 18px;">{gav("xl")}'
            f'<div style="font: 700 22px/28px var(--font-sans);">Kilimani Chama</div><div style="font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);">{sub}</div></div>')
def members_list():
    return '<div class="cx-list">' + row("Members", "8 · 2 admins", f'<span class="cx-icon-btn">{ic("people")}</span>', chev()) + "</div>"
def no_wallet_rows(off=False):
    sub_w = "Needs a connection" if off else "Members pay into it; it has its own PIN"
    sub_t = "Needs a connection" if off else "Save towards a goal together"
    o = ' style="opacity: 0.45;"' if off else ""
    r = (row("Add a Group Wallet", sub_w, f'<span class="cx-icon-btn">{ic("wallet")}</span>', chev()) + row("Add a Target", sub_t, f'<span class="cx-icon-btn">{ic("target")}</span>', chev()))
    if off:
        r = r.replace('class="cx-row cx-row-tap"', 'class="cx-row" aria-disabled="true"').replace('<span class="cx-icon-btn">', '<span class="cx-icon-btn"' + o + '>')
    return f'<div class="cx-list">{r}</div>'
frame(P_G, "GW6-Info-no-wallet", "GW6 · Group info, no wallet",
      "A group without a wallet shows no money anywhere. The owner sees Add a Group Wallet and Add a Target; members see "
      "neither. Add a Target opens GW7 first.",
      bar("Group Info") + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 14px;">' + info_head("8 members")
      + no_wallet_rows() + members_list() + "</div>", bg="var(--bg-grouped)")
frame(P_G, "GW6b-Finish-setup", "GW6 · Finish Wallet Setup",
      "After GW5b or GW5c the owner sees one row that carries on where set-up stopped; members see \"Wallet being set up\" "
      "and no money actions until it's done.",
      bar("Group Info") + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 14px;">' + info_head("8 members")
      + '<div class="cx-list">' + row("Finish Wallet Setup", "Kilimani Chama’s wallet isn’t ready yet", f'<span class="cx-icon-btn" style="color: var(--warning);">{ic("clock")}</span>', chev()) + "</div>"
      + members_list() + "</div>", bg="var(--bg-grouped)")
frame(P_G, "GW6c-Info-offline", "GW6 · Group info, offline",
      "Offline the info reads from the phone; adding a wallet or a target is disabled before the tap with \"Needs a "
      "connection\". Members, mute and the chat itself keep working.",
      bar("Group Info") + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 14px;">'
      + f'<div>{banner("You’re offline · Showing the group as of 10:42")}</div>' + info_head("8 members")
      + no_wallet_rows(off=True) + members_list() + "</div>", bg="var(--bg-grouped)")

frame(P_G, "GW7-Target-needs-wallet", "GW7 · A target needs a wallet",
      "Adding a target to a group without a wallet explains why and walks straight into the wallet set-up (GW2), then on "
      "to the target form (GW7b). No dead end.",
      bar("Group Info") + '<div style="padding: 0 16px;">' + info_head("8 members") + "</div>"
      + sheet(f'<div style="display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; padding: 8px 8px 4px;">{wallet_badge()}'
              + '<div class="cx-sheet-title" style="flex: none;">A target needs a group wallet</div>'
              + '<p class="small" style="font-size: 15px; line-height: 20px;">Contributions to the target go into Kilimani Chama’s own wallet, not anyone’s personal one. Set it up first; it takes a minute.</p></div>'
              + btn("Set Up Group Wallet") + btn("Not Now", "plain"), "A target needs a group wallet"), bg="var(--bg-grouped)")

frame(P_G, "GW7b-Target-form", "GW7 · Add a target",
      "Where GW7 lands once the wallet is ready (and the direct route for a group that already has one). Name, amount, an "
      "optional date. Everyone in the group sees it; it's saved online.",
      bar("Add a Target", left="cancel")
      + '<div style="padding: 12px 16px 0; display: flex; flex-direction: column; gap: 16px;">'
      + f'<div style="display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: var(--radius-md); background: var(--surface);">{ic("check", 18, 2.4)}<span style="font: 400 15px/20px var(--font-sans);">Kilimani Chama’s wallet is ready.</span></div>'
      + field("What it’s for", "December trip to the coast", fid="tn") + field("Target", "50,000", prefix="KES", fid="ta", hint="≈ 387.00 USDC")
      + field("By (optional)", "14 Dec", fid="tb") + "</div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px;">{btn("Save Target")}</div>', bg="var(--bg-grouped)")

def wallet_info(fp_on=True, lost=False):
    fp_sub = ("Stopped working for this wallet. Its PIN approves payments until you turn it on again." if lost else "Approve this wallet’s payments")
    return (bar("Group Info") + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 14px;">' + info_head("8 members · Group wallet")
            + '<div class="cx-list">' + row("Group Wallet", "KES 32,400 · only you can send, after 2 admins approve", f'<span class="cx-icon-btn">{ic("wallet")}</span>', chev())
            + row("Target", "KES 50,000 by 14 Dec · 65%", f'<span class="cx-icon-btn">{ic("target")}</span>', chev()) + "</div>"
            + '<div class="sec">Kilimani Chama wallet security</div><div class="cx-list">'
            + row("Fingerprint", fp_sub, f'<span class="cx-icon-btn"{" style=&quot;color: var(--warning);&quot;" if lost else ""}>{ic("fingerprint")}</span>'.replace("&quot;", '"'),
                  f'<button type="button" class="sw{" sw-on" if fp_on else ""}" role="switch" aria-checked="{str(fp_on).lower()}" aria-label="Fingerprint for Kilimani Chama"></button>', tap=False)
            + row("Change PIN", None, f'<span class="cx-icon-btn">{ic("lock")}</span>', chev()) + "</div>" + members_list() + "</div>")
frame(P_G, "GW8-Info-wallet", "GW8 · Group info, with wallet",
      "With a wallet: balance, target and the per-wallet security row. \"Fingerprint\" here is the GW4 setting for this wallet only.",
      wallet_info(), bg="var(--bg-grouped)")
frame(P_G, "GW8b-Biometrics-lost", "GW8 · Fingerprint lost for this wallet",
      "If Circle reports biometrics lost for this wallet (new fingerprint enrolled, for example), only this wallet falls back "
      "to its PIN and the row says so. The personal wallet and other wallets are untouched.",
      wallet_info(fp_on=False, lost=True), bg="var(--bg-grouped)")

# ---------------- Send sheet (C4) with Paying from, fee line, caps, results
RATE = "1 USDC = KES 129.20"
HELD = f"{RATE} · fee and rate held for 14:52"
def group_backdrop():
    return (bar("Kilimani Chama", sub="8 members", avatar=gav("sm"), right=icon_btn("more", "Group info"))
            + f'<div class="cx-thread" style="padding: 12px 16px 0;"><div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">Today</div>'
            + f'<div class="cx-msg">{av("otieno", "sm")}<div class="cx-bubble cx-bubble-in">Requesting the bus deposit now</div></div></div>'
            + composer(None, None, placeholder=True))
def person_backdrop(who, chip="#500", text="for lunch tomorrow"):
    return (bar(NAMES[who], sub="Online", avatar=av(who, "sm"))
            + f'<div class="cx-thread" style="padding: 8px 16px 0;"><div class="cx-bubble cx-bubble-in">Habari ya leo? Did you eat?</div><div class="cx-bubble cx-bubble-out">Not yet, still in a meeting</div></div>'
            + composer(chip, text))

def wchip(name):
    a = gav("sm") if name == "Kilimani Chama" else av("pasaka", "sm")
    return f'<button type="button" class="wchip" aria-label="Paying from {name}, change">{a}<span style="max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{name}</span>{ic("chev_down", 14)}</button>'

def send_sheet(title="Send", from_wallet="Kilimani Chama", to="Coast Bus", amount="12,000", usdc="92.88 USDC", fee="KES 120", fee_sub="1%",
               total="KES 12,120", receives="KES 12,000", button=None, note="Bus deposit", problem="", disabled=False, busy=False, footer=None,
               extra_rows=None, locked=False, show_from=True):
    who_av = av(to, "lg")
    who_name = NAMES.get(to, to)
    static_from = (f'<span style="display: inline-flex; align-items: center; gap: 6px;" aria-label="Paying from {from_wallet}, fixed by the approval">'
                   f'{gav("sm", "width: 22px; height: 22px; font-size: 9px;")}{from_wallet}<span style="color: var(--ink-secondary); display: flex;">{ic("lock", 13)}</span></span>')
    rows = [("Paying from", static_from if locked else wchip(from_wallet))] if show_from else [("From", "Personal")]
    rows += ([] if problem else [("Network", "USDC on Base")]) + ([("For", note)] if note and not problem else []) + (extra_rows or [])
    rows += [("Fee", fee if not fee_sub else f'{fee} <span style="color: var(--ink-secondary); font-weight: 400;">· {fee_sub}</span>'), ("Total", total), (f"{who_name} receives", receives)]
    lock = (f'<div class="cx-amount-sub" style="display: flex; gap: 4px; justify-content: center; align-items: center;">{ic("lock", 12)}The amount the admins approved</div>' if locked else "")
    foot = footer if footer is not None else f'<div class="cx-footnote">One Circaso fee, network cost included.<br>{HELD}</div>'
    inner = (sheet_head(title)
             + f'<div style="display: flex; align-items: center; gap: 12px;">{who_av}<div style="flex: 1; min-width: 0;"><div style="font: 600 17px/22px var(--font-sans);">{who_name}</div>'
             + f'<div style="font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);">{usdc}</div></div>'
             + f'<div class="cx-amount cx-amount-medium" style="align-items: flex-end;"><div class="cx-amount-main"><span class="cx-amount-cur">KES</span><span class="cx-amount-num">{amount}</span></div></div></div>'
             + lock + problem + details(rows)
             + btn(button or f"Send {total}", disabled=disabled, busy=busy) + foot)
    return sheet(inner, title, pb=34, gap=14)

def coin_chip(sym, held, on=False):
    ring = "box-shadow: 0 0 0 2px var(--ink);" if on else "box-shadow: var(--shadow-float);"
    return (f'<button type="button" aria-label="Pay in {sym}, you hold {held}" style="display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px 6px 6px; border-radius: 999px; border: 0; background: var(--surface); color: var(--ink); {ring}">'
            f'<span style="width: 28px; height: 28px; border-radius: 50%; background: var(--fill-strong); display: flex; align-items: center; justify-content: center; font: 700 10px/1 var(--font-sans);">{sym[:2]}</span>'
            f'<span style="display: flex; flex-direction: column; text-align: left;"><span style="font: 600 15px/20px var(--font-sans);">{sym}</span><span style="font: 400 11px/13px var(--font-sans); color: var(--ink-secondary);">{held}</span></span></button>')

def composer(chip, text, title=None, sub=None, sub_tone="ink-secondary", coins="", arrow_on=True, placeholder=False):
    prev = ""
    if title:
        dim = "" if arrow_on else ' style="opacity: 0.35; pointer-events: none;"'
        prev = (f'<div class="cx-preview" role="status" aria-live="polite"><div class="cx-preview-text"><div class="cx-preview-title">{title}</div>'
                f'<div class="cx-preview-sub" style="color: var(--{sub_tone});">{sub}</div></div>'
                f'<button type="button" class="cx-icon-btn cx-icon-btn-action" aria-label="Review {title}"{dim}>{ic("send")}</button></div>')
    coin_row = f'<div style="display: flex; gap: 8px; padding: 0 12px 8px; overflow: hidden;">{coins}</div>' if coins else ""
    field_html = (f'<span class="cx-chip-money">{chip}</span>' if chip else "") + (f'<span>{text}</span>' if text else "")
    if placeholder: field_html = '<span class="cx-composer-placeholder">Message, or #amount to send</span>'
    return (f'<div style="position: absolute; left: 0; right: 0; bottom: 0; padding-bottom: 26px; background: var(--glass); -webkit-backdrop-filter: blur(24px); backdrop-filter: blur(24px); z-index: 4;">'
            f'<div class="cx-composer">{coin_row}{prev}<div class="cx-composer-bar"><button type="button" class="cx-icon-btn" aria-label="Attach">{ic("plus")}</button>'
            f'<div class="cx-composer-field">{field_html}</div><button type="button" class="cx-icon-btn cx-icon-btn-action" aria-label="Send">{ic("send")}</button></div></div></div>')

def pay_frame(sheet_html, backdrop=None):
    return (backdrop or group_backdrop()) + sheet_html

APPROVED = [("Request", "Approved by Otieno and Wanjiru")]
frame(P_G, "GW9a-Chat-hash-amount", "GW9 · Start in the chat: #amount @asset",
      "Money always starts in the chat, as today (C3): \"#500 \" becomes the money chip, \"@\" offers the coins you hold "
      "(narrowing as you type), the rest of the line is the note. The glass preview names the payment; its arrow opens the review.",
      person_backdrop("mum", "#500", "@us for lunch tomorrow").replace(composer("#500", "@us for lunch tomorrow"),
          composer("#500", "@us for lunch tomorrow", "Send KES 500 to Mum", "3.87 USDC · for lunch tomorrow",
                   coins=coin_chip("USDC", "141.18") + coin_chip("USDT", "12.40"))))

frame(P_G, "GW9a2-Group-hash", "GW9 · # in a group with a wallet",
      "In a group that has a wallet, \"#1000\" pays into the group wallet (a contribution); the preview says so and shows "
      "where the target will stand. In a group without a wallet, # is just text. To pay a member, use their own chat.",
      bar("Kilimani Chama", sub="8 members", avatar=gav("sm"), right=icon_btn("more", "Group info"))
      + f'<div class="cx-thread" style="padding: 12px 16px 0;"><div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">Today</div>'
      + f'<div class="cx-msg">{av("wanjiru", "sm")}<div class="cx-bubble cx-bubble-in">November contributions are due Friday</div></div></div>'
      + composer("#1,000", "November", "Add KES 1,000 to Kilimani Chama", "7.74 USDC · target 67% after this"))

frame(P_G, "GW9-Pay-approved-payout", "GW9 · Pay an approved payout",
      "Money leaves a group wallet only through an approved request (option A): the owner taps Pay on the D5 card once the "
      "admins have approved. The review sheet opens with the approved amount and wallet locked, the approvals named and the one fee line.",
      pay_frame(send_sheet(title="Pay Payout", button="Pay KES 12,120", extra_rows=APPROVED, locked=True)))

frame(P_G, "GW9b-Pay-personal", "GW9 · Send, from personal (default)",
      "The arrow on the chat preview opens this review: nothing is typed here (amount, coin and note came from the chat). "
      "Personal is the default; between Circaso wallets the fee is the lowest rate (seed 0.5 %). The receiver gets exactly the "
      "amount, the sender pays the fee on top, and the button carries the total.",
      pay_frame(send_sheet(from_wallet="Personal", to="mum", amount="500", usdc="3.87 USDC", fee="KES 2.50", fee_sub="0.5%", total="KES 502.50", receives="KES 500", note="for lunch tomorrow"), person_backdrop("mum")))

frame(P_G, "GW9c-Request-payout", "GW9 · Paying from a group: request",
      "In Otieno's chat the owner typed \"#5000 Venue deposit\" and switched Paying from to the group wallet: the review "
      "becomes Request Payout and posts the D5 card for the admins. The fee is shown as it stands; the final one is quoted when paid.",
      pay_frame(send_sheet(title="Request Payout", to="otieno", amount="5,000", usdc="38.70 USDC", fee="about KES 50", fee_sub="1%, set when paid", total="about KES 5,050",
                           receives="KES 5,000", note="Venue deposit", extra_rows=[("Approvals", "2 admins")], button="Request KES 5,000 Payout",
                           footer='<div class="cx-footnote">Admins approve in the group chat. Then you pay it with Kilimani Chama’s PIN.</div>'), person_backdrop("otieno", "#5,000", "Venue deposit")))

frame(P_G, "GW9d-Contribute", "GW9 · Contribute to the group",
      "The review for \"#1,000 November\" in the group chat (GW9a2): the \"to groups\" rate (seed 1 %). The group receives "
      "exactly the contribution; the goal line shows where it will stand.",
      pay_frame(send_sheet(title="Contribute", from_wallet="Personal", to="Kilimani Chama", amount="1,000", usdc="7.74 USDC", fee="KES 10", fee_sub="1%",
                           total="KES 1,010", receives="KES 1,000", note="November", button="Pay KES 1,010",
                           problem='<div class="cx-goal" style="padding-bottom: 2px;"><div class="cx-goal-top"><span class="cx-goal-raised">KES 33,400</span><span class="cx-goal-of">of 50,000</span><span class="cx-goal-pct">67%</span></div><div class="cx-goal-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="67" aria-label="Target after your contribution"><div class="cx-goal-bar" style="width: 67%;"></div></div><div class="cx-goal-meta">After your contribution</div></div>'), group_backdrop().replace(composer(None, None, placeholder=True), composer("#1,000", "November"))))

def wallet_opt(name, sub, avh, on):
    return (f'<div role="radio" aria-checked="{str(on).lower()}" tabindex="0" class="cx-row cx-row-tap">{avh}<div class="cx-row-body"><div class="cx-row-text"><div class="cx-row-title">{name}</div><div class="cx-row-sub">{sub}</div></div>'
            f'<div class="cx-row-end"><span class="radio{" radio-on" if on else ""}"></span></div></div></div>')
def switch_sheet(offline=False):
    asof = " · as of 10:42" if offline else ""
    return (sheet_head("Pay from")
            + (f'<div>{banner("You’re offline. Money sends only while you’re connected.")}</div>' if offline else "")
            + '<div class="cx-list" role="radiogroup" aria-label="Pay from" style="background: var(--surface-raised); box-shadow: inset 0 0 0 1px var(--separator);">'
            + wallet_opt("Personal", f"KES 18,240 · your own money{asof}", av("pasaka"), True)
            + wallet_opt("Kilimani Chama", f"KES 32,400 · group wallet, payouts need approval{asof}", gav("md"), False)
            + wallet_opt("Nairobi Sneaker Drop", f"KES 210,600 · space wallet{asof}", av("Nairobi Sneaker Drop"), False) + "</div>"
            + '<p class="small" style="text-align: center;">Only wallets you can send from are here. Each one asks for its own PIN.</p>')
P_MUM = dict(from_wallet="Personal", to="mum", amount="500", usdc="3.87 USDC", fee="KES 2.50", fee_sub="0.5%", total="KES 502.50", receives="KES 500", note="for lunch tomorrow")
frame(P_G, "GW10-Switcher", "GW10 · Paying from, the switcher",
      "Tap the chip. Every wallet the person can send from, with its balance and kind. Picking a space wallet re-quotes the "
      "fee; picking a group wallet turns the sheet into Request Payout (GW9c). People with only a personal wallet see no chip.",
      pay_frame(send_sheet(**P_MUM), person_backdrop("mum")) + sheet(switch_sheet(), "Pay from").replace('z-index: 5;', 'z-index: 7;').replace('z-index: 6;', 'z-index: 8;'))

def approve_sheet(wallet, avh, amount, to, filled=3, pin_note="Kilimani Chama’s PIN, not your personal one.", bio="Use Fingerprint Instead"):
    return sheet(f'<div style="width: 100%; display: flex; justify-content: flex-end;"><button type="button" class="cx-icon-btn" aria-label="Cancel">{ic("close", 16)}</button></div>'
                 + f'<div style="display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center;">{avh}'
                 + f'<div style="font-size: 22px; line-height: 28px; font-weight: 700; letter-spacing: -0.26px;">Approve from {wallet}</div>'
                 + f'<div style="font-size: 15px; line-height: 20px; color: var(--ink-secondary);">{amount} to {to} · fee included</div>'
                 + f'<div style="font-size: 15px; line-height: 20px; color: var(--ink-secondary); font-weight: 600;">{pin_note}</div></div>'
                 + pin(filled) + (btn(bio, "plain", "sm", block=False) if bio else ""), f"Approve from {wallet}", pb=30, gap=16).replace('z-index: 5;', 'z-index: 7;').replace('z-index: 6;', 'z-index: 8;')
BUSY_PAYOUT = send_sheet(title="Pay Payout", button="Waiting for the PIN…", busy=True, extra_rows=APPROVED, locked=True)
frame(P_G, "GW11-Approve-PIN", "GW11 · Approve, named wallet",
      "C5 with the wallet named: its photo, \"Approve from Kilimani Chama\", and a line that it's that wallet's PIN. The "
      "server issues the challenge for that wallet's Circle user only. Fingerprint is offered when it's on for this wallet.",
      pay_frame(BUSY_PAYOUT) + approve_sheet("Kilimani Chama", gav("md"), "KES 12,120", "Coast Bus"))
frame(P_G, "GW11b-Approve-personal", "GW11 · Approve, personal",
      "When the person owns several wallets, the personal one is named too, so every approval says whose money it is. A person "
      "with only a personal wallet keeps today's \"Enter your PIN to send\".",
      pay_frame(send_sheet(**P_MUM, button="Waiting for the PIN…", busy=True), person_backdrop("mum"))
      + approve_sheet("Personal", av("pasaka", "md"), "KES 502.50", "Mum", 1, "Your personal PIN.", bio="Use Face ID Instead"))

bio_sys = ('<div style="position: absolute; inset: 0; background: var(--scrim); z-index: 7;"></div><div style="position: absolute; left: 16px; right: 16px; bottom: 24px; z-index: 8;" class="sysprompt" role="dialog" aria-label="Approve from Kilimani Chama">'
           '<div style="font: 600 20px/25px var(--font-sans);">Approve from Kilimani Chama</div>'
           '<div style="font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);">KES 12,120 to Coast Bus</div>'
           f'<span style="display: flex; padding: 14px 0;">{svg(I["fingerprint"], 56, 1.4)}</span>'
           '<div style="font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);">Touch the fingerprint sensor</div>'
           f'<div style="display: flex; justify-content: space-between; width: 100%; padding-top: 6px;">{btn("Use PIN", "plain", "sm", block=False)}{btn("Cancel", "plain", "sm", block=False)}</div></div>')
frame(P_G, "GW12-Approve-biometric", "GW12 · Approve with fingerprint (Android)",
      "Android's BiometricPrompt takes text only: the title names the wallet and the subtitle the total. If Circle's SDK "
      "fixes these strings, the Pay Payout sheet behind it (still visible) already names the wallet.",
      pay_frame(send_sheet(title="Pay Payout", button="Waiting for the fingerprint…", busy=True, extra_rows=APPROVED, locked=True)) + bio_sys)
faceid = ('<div style="position: absolute; left: 50%; top: 130px; transform: translateX(-50%); z-index: 9; width: 160px; height: 160px; border-radius: 34px; background: var(--glass); -webkit-backdrop-filter: blur(30px); backdrop-filter: blur(30px); border: 1px solid var(--glass-edge); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px;">'
          f'{svg(I["faceid"], 64, 1.4)}<span style="font: 500 15px/20px var(--font-sans);">Face ID</span></div>')
frame(P_G, "GW12b-Approve-face-id", "GW12 · Approve with Face ID (iPhone)",
      "iOS shows only its Face ID glyph, no custom title. So the named approval stays on screen behind it: the wallet's photo, "
      "\"Approve from Kilimani Chama\" and the total are what the person sees while Face ID runs.",
      pay_frame(BUSY_PAYOUT) + approve_sheet("Kilimani Chama", gav("md"), "KES 12,120", "Coast Bus", 0, bio=None) + faceid)

frame(P_G, "GW13-Fee-quoting", "GW13 · Working out the fee",
      "The fee comes from the server (W4) for this exact payment: rate, minimum and gas estimate. While it loads the row "
      "says so and Send waits; typing again re-quotes after a short pause. Usually under half a second.",
      pay_frame(send_sheet(**{**P_MUM, "fee": '<span style="display: inline-flex; gap: 6px; align-items: center; color: var(--ink-secondary); font-weight: 400;"><span class="cx-spin" style="width: 14px; height: 14px;"></span>Working it out</span>', "fee_sub": "", "total": "—"},
                           button="Send", disabled=True, footer=f'<div class="cx-footnote">{RATE}</div>'), person_backdrop("mum")))

frame(P_G, "GW13b-Fee-minimum", "GW13 · A small payment, minimum fee",
      "When the rate is less than the network cost needs (gas kept under a quarter of the fee), the fee is the minimum. Still "
      "one fee, never a separate gas line; \"minimum\" in place of the rate tells the person why it isn't 0.5 %.",
      pay_frame(send_sheet(from_wallet="Personal", to="juma", amount="50", usdc="0.39 USDC", fee="KES 5", fee_sub="minimum", total="KES 55", receives="KES 50", note="mandazi",
                           footer=f'<div class="cx-footnote">Small payments have a minimum fee that covers the network cost.<br>{HELD}</div>'), person_backdrop("juma")))

frame(P_G, "GW13c-Quote-expired", "GW13 · Quote expired",
      "A quote holds for 15 minutes (the challenge's life). If the person comes back later, the sheet shows what changed "
      "before they can sign; they never sign an amount they haven't seen.",
      pay_frame(send_sheet(title="Pay Payout", fee="KES 125", fee_sub="1%", total="KES 12,125", button="Pay KES 12,125", extra_rows=APPROVED, locked=True,
                           problem=banner("The fee changed after 15 minutes: KES 120 → KES 125. New total KES 12,125.", "warning", "clock"))))

frame(P_G, "GW13d-Fee-error", "GW13 · Fee couldn't load",
      "If the quote fails (5xx after retries), nothing can be signed. One line with Try Again and the reference. No guessed "
      "fee, ever.",
      pay_frame(send_sheet(**{**P_MUM, "fee": '<button type="button" class="link" style="font-size: 15px;">Try Again</button>', "fee_sub": "", "total": "—"}, button="Send", disabled=True,
                           footer='<div class="cx-footnote">We couldn’t work out the fee. <span class="rid">Ref req_F9a2Lm</span></div>'), person_backdrop("mum")))

def note_box(title, text, action=None, icon="clock", tone="warning"):
    return (f'<div role="status" style="display: flex; flex-direction: column; gap: 8px;"><div style="display: flex; gap: 8px; align-items: center;"><span style="color: var(--{tone}); display: flex;">{ic(icon, 18)}</span>'
            f'<span style="font: 600 16px/21px var(--font-sans);">{title}</span></div><p class="small" style="font-size: 15px; line-height: 20px;">{text}</p>'
            + (btn(action, "secondary", "sm", block=False) if action else "") + "</div>")
frame(P_G, "GW14-Cap-request", "GW14 · A request over the daily limit",
      "The cap counts what leaves the wallet, fee included, over a rolling 24 hours. Asking for more than fits today is "
      "allowed (it waits once approved); the sheet says how much fits now and offers it in one tap.",
      pay_frame(send_sheet(title="Request Payout", to="otieno", amount="150,000", usdc="1,160.99 USDC", fee="about KES 1,500", fee_sub="1%, set when paid", total="about KES 151,500",
                           receives="KES 150,000", note="Stock for December", extra_rows=[("Approvals", "2 admins")], button="Request KES 150,000 Payout",
                           problem=note_box("Kilimani Chama can send KES 76,752 more today", "With the fee, that’s what fits under the group limit of 10,000 USDC in 24 hours. More is available from 14:30.", "Request KES 76,752 Instead"),
                           footer=""), person_backdrop("otieno", "#150,000", "Stock for December")))

frame(P_G, "GW14b-Cap-reached", "GW14 · Paying when the limit is used",
      "The server refuses at W4 with wallet.limit_exceeded; resetsAt is when this payout fits again. Nothing to offer, "
      "just when. Receiving and contributions are never limited.",
      pay_frame(send_sheet(title="Pay Payout", amount="150,000", usdc="1,160.99 USDC", fee="—", fee_sub="", total="—", receives="KES 150,000", button="Pay After 14:30", disabled=True,
                           extra_rows=APPROVED, locked=True, footer="",
                           problem=note_box("This payout fits the daily limit from 14:30", "Kilimani Chama can send up to 10,000 USDC in 24 hours, fee included. Members can still pay into the group."))))

payout_card = ('<div role="group" aria-label="Payment request" style="align-self: stretch; padding: 16px; border-radius: var(--radius-lg); background: var(--surface); box-shadow: inset 0 0 0 1px var(--separator); display: flex; flex-direction: column; gap: 12px;">'
               f'<div style="display: flex; align-items: center; justify-content: space-between; gap: 8px;"><span style="font-size: 13px; line-height: 18px; font-weight: 600; color: var(--ink-secondary);">Payment request</span><span class="cx-badge cx-badge-warning">{ic("clock", 14)}Approved · waits for the limit</span></div>'
               '<div style="font: 600 28px/32px var(--font-rounded); letter-spacing: -0.3px; font-variant-numeric: tabular-nums;">KES 150,000</div>'
               '<div style="font-size: 16px; line-height: 21px;">Otieno wants to pay <strong>Coast Bus</strong> for the December trip.</div>'
               '<div style="font-size: 13px; line-height: 18px; color: var(--ink-secondary);">Approved by Otieno and Wanjiru. It fits Kilimani Chama’s daily limit from 14:30; Pasaka can pay it then.</div>'
               f'<div style="display: flex; gap: 8px;">{btn("Pay After 14:30", "primary", "md", disabled=True)}</div></div>')
frame(P_G, "GW14c-Cap-in-thread", "GW14 · The limit on a payout request",
      "The D5 request card when an approved payout doesn't fit today. Everyone sees why it hasn't been paid, so nobody "
      "suspects the owner. The button wakes up at the time the server gives.",
      bar("Kilimani Chama", sub="8 members", avatar=gav("sm"), right=icon_btn("more", "Group info"))
      + f'<div class="cx-thread" style="padding: 12px 16px 0;"><div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">Today</div>'
      + f'<div class="cx-msg">{av("otieno", "sm")}<div class="cx-bubble cx-bubble-in">Requesting the bus money for December</div></div>' + payout_card + "</div>")

frame(P_G, "GW15-Pay-offline", "GW15 · Offline",
      "As today (G3): the chip and the preview stay, the preview says why and its arrow is dimmed, so the review (and with it "
      "Paying from and Request Payout) never opens offline. Money never queues.",
      person_backdrop("mum").replace(composer("#500", "for lunch tomorrow"),
          composer("#500", "for lunch tomorrow", "Send KES 500 to Mum", "You’re offline. Money sends only while you’re connected.", "warning", arrow_on=False))
      .replace('<div class="cx-thread"', f'<div style="padding: 6px 16px 4px;">{banner("You’re offline. Messages send when you reconnect.")}</div><div class="cx-thread"', 1))

# ---- after signing
frame(P_G, "GW17-Sending", "GW17 · Sending",
      "After the PIN: the sheet stays, the button shows progress and the wallet is named. Nothing says sent until the server "
      "does; closing the sheet is safe (the payment continues and its card updates in the chat).",
      pay_frame(send_sheet(title="Pay Payout", button="Sending from Kilimani Chama…", busy=True, extra_rows=APPROVED, locked=True,
                           footer='<div class="cx-footnote" role="status" aria-live="polite">This takes a few seconds. You can close this; the card in the chat updates.</div>')))

sent_card = ('<div class="cx-money cx-money-out" role="group" aria-label="Paid KES 12,000 from Kilimani Chama to Coast Bus, fee KES 120" style="width: 280px;">'
             '<div class="cx-money-head">Paid from Kilimani Chama</div><div class="cx-money-amount">KES 12,000</div>'
             '<div class="cx-money-note">to Coast Bus · Bus deposit</div><div class="cx-money-foot">Fee KES 120 · 92.88 USDC · 14:02</div></div>')
frame(P_G, "GW18-Sent-card", "GW18 · Paid, in the group chat",
      "The confirmed payout as a money card in the group (C6's moment sweep plays once). It names the wallet it came from and "
      "the fee; the request card above it turns to Paid.",
      bar("Kilimani Chama", sub="8 members", avatar=gav("sm"), right=icon_btn("more", "Group info"))
      + f'<div class="cx-thread" style="padding: 12px 16px 0;"><div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">Today</div>'
      + '<div role="group" style="align-self: stretch; padding: 12px 16px; border-radius: var(--radius-lg); background: var(--surface); box-shadow: inset 0 0 0 1px var(--separator); display: flex; justify-content: space-between; align-items: center;">'
      + f'<span style="font: 400 15px/20px var(--font-sans);">Bus deposit · KES 12,000</span><span class="cx-badge cx-badge-money">{ic("check", 14, 2.4)}Paid</span></div>'
      + sent_card.replace('class="cx-money cx-money-out"', 'class="cx-money cx-money-out cx-moment"') + '<div class="cx-bubble-time cx-bubble-time-out">14:02</div>'
      + f'<div class="cx-msg">{av("wanjiru", "sm")}<div class="cx-bubble cx-bubble-in">Asante 🙏</div></div></div>')

frame(P_G, "GW19-Not-sent", "GW19 · Not sent",
      "If the payment didn't reach the chain (expired challenge, network gone, Circle error), G2's rule: say Not sent and that "
      "nothing was taken from the named wallet, keep the approval, show the reference.",
      pay_frame(send_sheet(title="Pay Payout", button="Try Again", extra_rows=APPROVED, locked=True, footer='<div class="cx-footnote"><span class="rid">Ref req_N2sX8c</span></div>',
                           problem=f'<div class="cx-banner cx-banner-critical" role="alert"><span class="cx-banner-icon">{ic("warning")}</span><span class="cx-banner-text">Not sent. Nothing was taken from Kilimani Chama. The approval still stands.</span></div>')))

frame(P_G, "GW20-Insufficient", "GW20 · Not enough with the fee",
      "The amount fits the balance but the total doesn't. Say so with the numbers and offer the largest amount whose total "
      "fits (\"Send all\" quotes the same).",
      pay_frame(send_sheet(from_wallet="Personal", to="mum", amount="18,240", usdc="141.18 USDC", fee="KES 91.20", fee_sub="0.5%", total="KES 18,331.20", receives="KES 18,240", note="rent",
                           button="Send", disabled=True, footer="",
                           problem=note_box("You have KES 18,240", "With the fee, the most you can send is KES 18,149.", "Send KES 18,149 Instead", icon="info", tone="ink-secondary")), person_backdrop("mum")))

# Wallet tab (E1 structure) switched to the group wallet
acts = "".join(f'<button type="button" class="act"><span class="act-c">{svg(d, 20, 1.9)}</span>{n}</button>'
               for d, n in [(I["plus"], "Add"), (I["send"], "Payout"), ("M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M4 12h16", "Receive")])
frame(P_G, "GW16-Wallet-tab-switch", "GW16 · Wallet tab, switched to a group",
      "E1's structure. The title is the switcher (\"Wallet ▾\" for someone with other wallets; it truncates long names), "
      "and the caption names whose money it is. For a group, Send becomes Payout (a request); a space wallet keeps Send.",
      '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 6px;"><div style="height: 44px;"></div>'
      + f'<button type="button" style="all: unset; display: flex; align-items: center; gap: 8px; cursor: pointer; max-width: 100%;" aria-label="Wallet: Kilimani Chama, change"><span style="font: 700 34px/41px var(--font-sans); letter-spacing: 0.37px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">Kilimani Chama</span><span style="display: flex; padding-top: 4px; flex: none;">{ic("chev_down", 22, 2.4)}</span></button></div>'
      + '<div style="padding: 18px 16px 0;"><div class="cx-amount cx-amount-hero cx-amount-left"><div style="font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);">Group wallet · only you can send, after approval</div>'
      + '<div class="cx-amount-main"><span class="cx-amount-cur">KES</span><span class="cx-amount-num">32,400</span></div><div class="cx-amount-sub">250.77 USDC</div></div></div>'
      + f'<div style="display: flex; justify-content: space-around; padding: 22px 32px 10px;">{acts}</div>'
      + '<div style="padding: 8px 16px 0;"><div class="sec-row" style="padding-bottom: 6px;"><span class="sec">Recent</span><button type="button" class="link">See All</button></div><div class="cx-list">'
      + row("Wanjiru", "Contribution · Yesterday", av("wanjiru"), '<span class="cx-row-amount cx-in">+KES 1,000</span>')
      + row("Coast Bus", "Payout · fee KES 120 · approved by 2", av("Coast Bus"), '<span class="cx-row-amount">−KES 12,120</span>') + "</div></div>"
      + tabbar("Wallet"), bg="var(--bg-grouped)")

# ================================================================ PHASE 3 · Spaces core (phase 0b)
# Discover and search, the space page, create a space, paid entry and membership, invites, space chat and the private
# chat with the owner, roles and moderation, the space wallet. Shop (3S) and live (4) are not here.
P3 = "Phase 3"
I.update({
    "globe": "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17zM3.5 12h17M12 3.5c2.4 2.5 3.5 5.4 3.5 8.5s-1.1 6-3.5 8.5c-2.4-2.5-3.5-5.4-3.5-8.5s1.1-6 3.5-8.5z",
    "ticket": "M4 7h16v3a2 2 0 0 0 0 4v3H4v-3a2 2 0 0 0 0-4zM14 7v10",
    "flag": "M5.5 20.5v-16M5.5 4.5h11l-2 4l2 4h-11",
    "share": "M12 14.5V4M8 7.5L12 4l4 3.5M6.5 11H5.5v9h13v-9h-1",
    "link": "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    "shield": "M12 3.5l7 2.5v5.5c0 4.5-3 7.8-7 9c-4-1.2-7-4.5-7-9V6z",
    "bell": "M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2h-14zM10 20.5a2 2 0 0 0 4 0",
    "ban": "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17zM6 6l12 12",
    "user_minus": "M9 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7zM2.5 19.5c.3-3.3 3-5.2 6.5-5.2s6.2 1.9 6.5 5.2M16 9h5.5",
    "user_plus": "M9 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7zM2.5 19.5c.3-3.3 3-5.2 6.5-5.2s6.2 1.9 6.5 5.2M18.5 6.5v5.5M15.75 9.25h5.5",
    "reply": "M10 6L4 12l6 6M4 12h10a6 6 0 0 1 6 6",
    "copy": "M9 9h10v11H9zM5.5 15V4.5h10",
    "mail": "M4 6.5h16v11H4zM4.5 7.5l7.5 5.5l7.5-5.5",
    "qr": "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM14 18h2v2h-2zM18 14h2v2h-2z",
    "bank": "M3.5 9.5L12 4l8.5 5.5M5 10v8M9.7 10v8M14.3 10v8M19 10v8M3.5 20h17",
    "down": "M12 5v14M5.5 12.5L12 19l6.5-6.5",
    "gear": "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6zM12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7",
})

EXTRA_CSS += r"""
/* Phase 3: spaces. Every value is a token or a canvas measure. */
.scr .sp-tile{border-radius:var(--radius-md);background:var(--fill-strong);color:var(--ink);display:inline-flex;align-items:center;justify-content:center;font-family:var(--font-rounded);font-weight:600;flex:none;overflow:hidden}
/* Default cover, drawn from tokens until the owner adds a photo: the brand's rings on a quiet fill. */
.scr .cover{position:relative;height:178px;overflow:hidden;background:
  radial-gradient(circle at 84% 34%,transparent 46px,var(--fill-strong) 47px,var(--fill-strong) 49px,transparent 50px),
  radial-gradient(circle at 84% 34%,transparent 78px,var(--fill-strong) 79px,var(--fill-strong) 80px,transparent 81px),
  radial-gradient(circle at 12% 110%,transparent 70px,var(--fill-strong) 71px,var(--fill-strong) 72px,transparent 73px),
  var(--fill)}
.scr .cover img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.scr .cover-btns{position:absolute;left:16px;right:16px;top:59px;display:flex;justify-content:space-between;z-index:2}
.scr .cover-btns .cx-icon-btn{background:var(--glass);-webkit-backdrop-filter:blur(20px);backdrop-filter:blur(20px);box-shadow:inset 0 0 0 1px var(--glass-edge)}
.scr .sp-head{padding:0 16px;margin-top:-44px;position:relative;display:flex;flex-direction:column;gap:6px}
.scr .sp-head .sp-tile{box-shadow:0 0 0 4px var(--bg)}
.scr .kind{display:inline-flex;align-items:center;gap:5px;font:400 15px/20px var(--font-sans);color:var(--ink-secondary)}
.scr .role{display:inline-flex;align-items:center;height:18px;padding:0 6px;border-radius:999px;background:var(--fill);color:var(--ink-secondary);font:600 11px/13px var(--font-sans);letter-spacing:.2px;flex:none}
.scr .sysline{align-self:center;max-width:300px;text-align:center;font:400 12px/16px var(--font-sans);color:var(--ink-secondary);display:flex;gap:5px;align-items:flex-start;justify-content:center;padding:2px 0}
.scr .sysline .cx-icon{margin-top:1px}
.scr .who{font:600 13px/18px var(--font-sans);display:flex;align-items:center;gap:6px;padding:0 0 2px 4px}
.scr .reacts{display:flex;gap:4px;padding:3px 0 0 4px}
.scr .reacts span{display:inline-flex;align-items:center;gap:3px;height:24px;padding:0 8px;border-radius:999px;background:var(--surface);box-shadow:inset 0 0 0 1px var(--separator);font:500 12px/16px var(--font-sans);font-variant-numeric:tabular-nums}
.scr .reacts .mine{box-shadow:inset 0 0 0 1.5px var(--ink)}
/* Popover: anchored to what opened it, never a sheet (Pasaka: don't overuse bottom sheets). */
.scr .pop{position:absolute;z-index:8;width:250px;background:var(--surface-raised);border-radius:var(--radius-md);box-shadow:var(--shadow-float),inset 0 0 0 1px var(--separator);overflow:hidden;display:flex;flex-direction:column}
.scr .pop-item{display:flex;align-items:center;gap:12px;min-height:44px;padding:0 16px;font:400 17px/22px var(--font-sans);letter-spacing:-0.43px;color:var(--ink)}
.scr .pop-item .cx-icon{margin-left:auto;color:var(--ink-secondary)}
.scr .pop-item+.pop-item,.scr .pop-sep{box-shadow:inset 0 .5px 0 var(--separator)}
.scr .pop-crit,.scr .pop-crit .cx-icon{color:var(--critical)}
.scr .pop-dim{color:var(--ink-tertiary)}
.scr .pop-note{padding:10px 16px;font:400 13px/18px var(--font-sans);color:var(--ink-secondary)}
.scr .pop-head{padding:12px 16px 4px;font:600 13px/18px var(--font-sans);color:var(--ink-secondary)}
.scr .dim-behind{position:absolute;inset:0;z-index:7;background:color-mix(in srgb,var(--bg) 40%,transparent)}
/* The chat money card: solid, the whole review, Pay goes straight to the PIN (D-M3). */
.scr .mcard{position:absolute;left:12px;right:12px;z-index:5;background:var(--surface-raised);border-radius:var(--radius-lg);box-shadow:var(--shadow-float);padding:12px 12px 12px 16px;display:flex;flex-direction:column;gap:8px}
.scr [data-theme$="-hc"] .mcard{box-shadow:var(--shadow-float),inset 0 0 0 1px var(--separator)}
.scr .mc-head{display:flex;align-items:center;gap:12px}
.scr .mc-title{font:400 15px/20px var(--font-sans);color:var(--ink-secondary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.scr .mc-amt{font:700 22px/28px var(--font-sans);letter-spacing:-0.26px;font-variant-numeric:tabular-nums}
.scr .mc-rows{display:flex;flex-direction:column;gap:4px}
.scr .crow{display:flex;justify-content:space-between;align-items:center;gap:12px;min-height:22px}
.scr .crow>span:first-child{font:400 13px/18px var(--font-sans);color:var(--ink-secondary);flex:none}
.scr .crow>span:last-child{font:600 15px/20px var(--font-sans);text-align:right;font-variant-numeric:tabular-nums;min-width:0}
.scr .mc-line{font:400 13px/18px var(--font-sans)}
.scr .mc-foot{font:400 12px/16px var(--font-sans);color:var(--ink-tertiary);font-variant-numeric:tabular-nums}
/* A locked preview: the shape of a chat behind a lock, never real posts. */
.scr .ghost{display:flex;flex-direction:column;gap:10px;padding:4px 16px}
.scr .ghost i{display:block;height:36px;border-radius:var(--radius-lg);background:var(--fill)}
/* Wallet tab (D-M9): no title, a corner switcher, metal cards per network. */
.scr .wpill{appearance:none;border:0;height:36px;padding:0 10px 0 12px;border-radius:999px;background:var(--fill);color:var(--ink);display:inline-flex;align-items:center;gap:6px;font:600 15px/20px var(--font-sans);max-width:200px}
.scr .ncard{position:relative;overflow:hidden;height:196px;border-radius:var(--radius-lg);padding:18px 20px;display:flex;flex-direction:column;justify-content:space-between;color:var(--on-money);
  background:linear-gradient(140deg,var(--money-card-sheen) 0%,var(--money-card) 38%,var(--money-card) 62%,var(--money-card-sheen) 100%);box-shadow:inset 0 1px 0 rgba(255,255,255,.24),inset 0 -1px 0 rgba(0,0,0,.22)}
.scr .ncard::before{content:"";position:absolute;inset:0;pointer-events:none;background:radial-gradient(120% 70% at 12% 0%,rgba(255,255,255,.20),transparent 60%)}
.scr .ncard>*{position:relative}
.scr .ncard .dim{opacity:.82}
.scr [data-theme$="-hc"] .ncard .dim{opacity:1}
.scr .ncard-peek{position:absolute;top:10px;bottom:10px;width:24px;border-radius:var(--radius-lg);background:var(--money-card);opacity:.55}
.scr .opt{border-radius:var(--radius-md);background:var(--surface);box-shadow:inset 0 0 0 1px var(--separator);padding:14px 16px;display:flex;flex-direction:column;gap:12px}
.scr .opt-on{box-shadow:inset 0 0 0 2px var(--ink)}
.scr .steps{display:flex;gap:6px}
.scr .steps i{flex:1;height:4px;border-radius:2px;background:var(--fill-strong)}
.scr .steps i.on{background:var(--ink)}
"""

# ---------------------------------------------------------------- spaces and people for phase 3
SPACES = {
    "sneaker": ("Nairobi Sneaker Drop", "@sneakerdrop", "ND"),
    "books": ("Nairobi Book Club", "@nairobibooks", "NB"),
    "founders": ("Nairobi Founders Circle", "@founderscircle", "FC"),
    "coffee": ("Coffee Farmers Kenya", "@coffeefarmers", "CF"),
    "runners": ("Karura Runners", "@karurarunners", "KR"),
    "chamahub": ("Kilimani Chama Hub", "@chamahub", "KC"),
    "yoga": ("Kilimani Yoga Collective", "@kilimaniyoga", "KY"),
    "swap": ("Kisumu Book Swap", "@kisumubooks", "KB"),
    "mama": ("Mama Mboga Traders", "@mamamboga", "MM"),
    "worship": ("Jubilee Youth Worship", "@jubileeyouth", "JY"),
}
AMINA = "Amina Wairimu"  # no photo in the canvas: initials

def tile(key, size=52, style=""):
    name, _, init = SPACES[key]
    return (f'<span class="sp-tile" role="img" aria-label="{name}" style="width: {size}px; height: {size}px; font-size: {round(size * 0.34)}px; '
            f'border-radius: {14 if size < 60 else 22}px;{style}">{init}</span>')

def sp_row(key, sub, end="", title_extra=""):
    name = SPACES[key][0]
    return (f'<div role="button" tabindex="0" class="cx-row cx-row-tap">{tile(key)}<div class="cx-row-body"><div class="cx-row-text">'
            f'<div class="cx-row-title" style="display: flex; align-items: center; gap: 8px;"><span style="overflow: hidden; text-overflow: ellipsis;">{name}</span>{title_extra}</div>'
            f'<div class="cx-row-sub">{sub}</div></div><div class="cx-row-end">{end}</div></div></div>')

def kind_icon(kind):
    return {"public": ic("globe", 15, 1.8), "members": ic("ticket", 15, 1.8), "invite": ic("lock", 15, 1.8)}[kind]

def meta(text):
    return f'<span class="cx-row-meta">{text}</span>'

def count(n):
    return f'<span class="cx-badge cx-badge-count" aria-label="{n} unread">{n}</span>'

def role(text):
    return f'<span class="role">{text}</span>'

def sysline(text, icon=None):
    return f'<div class="sysline" role="note">{ic(icon, 13, 2) if icon else ""}<span>{text}</span></div>'

def day(text="Today"):
    return f'<div style="align-self: center; font-size: 12px; line-height: 16px; font-weight: 600; color: var(--ink-secondary); padding: 4px 0;">{text}</div>'

def post(who, text, role_txt=None, reacts=None, name=None, quote=None):
    nm = name or NAMES.get(who, who)
    r = role(role_txt) if role_txt else ""
    rx = ""
    if reacts:
        rx = '<div class="reacts">' + "".join(f'<span role="button" tabindex="0" aria-pressed="{str(m).lower()}" class="{"mine" if m else ""}" aria-label="{e} {n}{", including you" if m else ""}">{e} {n}</span>' for e, n, m in reacts) + "</div>"
    q = (f'<div style="padding: 6px 10px; margin-bottom: 6px; border-radius: 12px; background: var(--fill-strong); font: 400 13px/18px var(--font-sans);">'
         f'<div style="font-weight: 600;">{quote[0]}</div>{quote[1]}</div>') if quote else ""
    return (f'<div class="cx-msg">{av(who, "sm")}<div style="display: flex; flex-direction: column; min-width: 0;">'
            f'<div class="who">{nm}{r}</div><div class="cx-bubble cx-bubble-in">{q}{text}</div>{rx}</div></div>')

def mine(text, time="", state=""):
    st = (f'<div class="cx-bubble-time cx-bubble-time-out" style="display: flex; gap: 4px; align-items: center;">{state}</div>' if state else
          (f'<div class="cx-bubble-time cx-bubble-time-out">{time}</div>' if time else ""))
    return f'<div class="cx-bubble cx-bubble-out">{text}</div>{st}'

def space_composer(placeholder, extra="", attach=True, money_hint=False):
    lead = f'<button type="button" class="cx-icon-btn" aria-label="Attach">{ic("plus")}</button>' if attach else ""
    return (f'<div style="position: absolute; left: 0; right: 0; bottom: 0; padding-bottom: 26px; background: var(--glass); -webkit-backdrop-filter: blur(24px); backdrop-filter: blur(24px); z-index: 4;">'
            f'<div class="cx-composer">{extra}<div class="cx-composer-bar">{lead}'
            f'<div class="cx-composer-field"><span class="cx-composer-placeholder">{placeholder}</span></div>'
            f'<button type="button" class="cx-icon-btn cx-icon-btn-action" aria-label="Send">{ic("send")}</button></div></div></div>')

def composer_note(text, action=None):
    a = btn(action, "secondary", "sm", block=False) if action else ""
    return (f'<div style="position: absolute; left: 0; right: 0; bottom: 0; padding: 12px 16px 34px; background: var(--glass); -webkit-backdrop-filter: blur(24px); backdrop-filter: blur(24px); border-top: 1px solid var(--glass-edge); z-index: 4; display: flex; align-items: center; gap: 12px;">'
            f'<span class="small" style="flex: 1; font-size: 15px; line-height: 20px;">{text}</span>{a}</div>')

def space_bar(key, sub, right=None):
    return bar(SPACES[key][0], sub=sub, avatar=tile(key, 28, " border-radius: 8px;"),
               right=right if right is not None else icon_btn("more", "Space info"))

def crow(k, v):
    return f'<div class="crow"><span>{k}</span><span>{v}</span></div>'

def mcard(title, amount, under, rows, button, lead, status="", tone="ink-secondary", foot="", bottom=34, close=True,
          disabled=False, busy=False, problem="", label=None):
    x = f'<button type="button" class="cx-icon-btn" aria-label="Close">{ic("close", 16)}</button>' if close and not busy else ""
    st = f'<div class="mc-line" role="status" aria-live="polite" style="color: var(--{tone});">{status}</div>' if status else ""
    ft = f'<div class="mc-foot">{foot}</div>' if foot else ""
    return (f'<section class="mcard" role="group" aria-label="{label or title}" style="bottom: {bottom}px;">'
            f'<div class="mc-head">{lead}<div style="flex: 1; min-width: 0;" role="heading" aria-level="2"><div class="mc-title">{title}</div>'
            f'<div class="mc-amt">{amount}</div><div class="cx-amount-sub" style="text-align: left;">{under}</div></div>{x}</div>'
            f'{problem}<div class="mc-rows">{"".join(crow(k, v) for k, v in rows)}</div>{st}'
            f'{btn(button, disabled=disabled, busy=busy)}{ft}</section>')

def pop(items, top, right=16, left=None, width=250, head=None, note=None):
    pos = f"top: {top}px; " + (f"left: {left}px;" if left is not None else f"right: {right}px;")
    out = []
    for it in items:
        if it == "-":
            out.append('<div class="pop-sep"></div>'); continue
        label, icon, cls = (tuple(it) + ("",))[:3]
        out.append(f'<div role="menuitem" tabindex="0" class="pop-item {cls}">{label}{ic(icon, 18, 1.8) if icon else ""}</div>')
    h = f'<div class="pop-head">{head}</div>' if head else ""
    n = f'<div class="pop-note pop-sep">{note}</div>' if note else ""
    return f'<div class="pop" role="menu" style="{pos} width: {width}px;">{h}{"".join(out)}{n}</div>'

def space_head(key, kind, kind_text, line=None, actions="", cover_extra="", right_btn=None, photo=None):
    name, handle, _ = SPACES[key]
    rb = right_btn or f'<button type="button" class="cx-icon-btn" aria-label="Share {name}">{ic("share", 17, 1.9)}</button>'
    img = f'<img data-ph="{photo}" src="" alt="">' if photo else ""
    return (f'<div class="cover">{img}{cover_extra}<div class="cover-btns"><button type="button" class="cx-icon-btn" aria-label="Back">{ic("back")}</button>'
            f'<div style="display: flex; gap: 8px;">{rb}<button type="button" class="cx-icon-btn" aria-label="More">{ic("more", 18, 3)}</button></div></div></div>'
            f'<div class="sp-head">{tile(key, 88)}<h1 style="margin: 6px 0 0; font: 700 22px/28px var(--font-sans); letter-spacing: -0.26px;">{name}</h1>'
            f'<div class="kind">{handle} · {kind_icon(kind)}{kind_text}</div>'
            + (f'<p style="margin: 2px 0 0; font: 400 15px/20px var(--font-sans);">{line}</p>' if line else "")
            + (f'<div style="display: flex; gap: 8px; padding-top: 8px;">{actions}</div>' if actions else "") + "</div>")

def tabs(on=0, items=("Chat", "About", "Members")):
    return f'<div style="padding: 14px 16px 6px;">{seg(list(items), on, "Space sections")}</div>'

def known(people, text):
    faces = "".join(av(p, "sm", f"box-shadow: 0 0 0 2px var(--bg); margin-left: {0 if i == 0 else -8}px;") for i, p in enumerate(people))
    return f'<div style="display: flex; align-items: center; gap: 8px; padding-top: 4px;"><span style="display: flex;">{faces}</span><span class="small">{text}</span></div>'

def asof_banner(t="10:42", what="spaces"):
    return banner(f"You’re offline · Showing {what} as of {t}")

def ref_banner(text, rid):
    return (f'<div class="cx-banner cx-banner-critical" role="alert"><span class="cx-banner-icon">{ic("warning")}</span>'
            f'<span class="cx-banner-text">{text} <span class="rid">Ref {rid}</span></span></div>')

def center(icon, title, text, action="", top=120, extra=""):
    return (f'<div style="padding: {top}px 32px 0; display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center;">'
            f'<span style="color: var(--ink-tertiary); display: flex;">{icon}</span><div style="font: 600 17px/22px var(--font-sans);">{title}</div>'
            f'<p class="small" style="font-size: 15px; line-height: 20px; max-width: 300px;">{text}</p>{action}{extra}</div>')

sk_row = ('<div class="cx-row" aria-hidden="true"><span class="sk" style="width: 52px; height: 52px; border-radius: 14px;"></span><div class="cx-row-body" style="flex-direction: column; align-items: flex-start; gap: 8px;">'
          '<span class="sk" style="width: 60%; height: 14px;"></span><span class="sk" style="width: 85%; height: 12px;"></span></div></div>')

RATE3 = "1 USDC = KES 129.20 · fee and rate held for 14:52"
PERSONAL_FIXED = '<span style="display: inline-flex; align-items: center; gap: 6px;">' + av("pasaka", "sm", "width: 22px; height: 22px;") + "Personal</span>"
def wchip3(name, key=None, me="pasaka"):
    a = tile(key, 22, " border-radius: 6px;") if key else av(me, "sm", "width: 22px; height: 22px; font-size: 9px;")
    return f'<button type="button" class="wchip" aria-label="Paying from {name}, change">{a}<span style="max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{name}</span>{ic("chev_down", 14)}</button>'

# ================================================================ PAGE SD · Discover and search
P_SD = "SD"
TOPIC_LIST = ["Business", "Shopping", "Farming", "Health", "Faith", "Sport", "Books", "Chamas", "Music", "Other"]
spaces_tab_btns = spaces_buttons
YOUR3 = (sp_row("sneaker", "Amina: Size 42 Jordans back in stock at 6 pm", f'{meta("18:02")}{count(12)}')
         + sp_row("books", "Renew by 4 Dec · Njeri: This month’s pick is Kintu", f'{meta("Mon")}', title_extra=f'<span class="cx-badge cx-badge-warning">{ic("clock", 13)}Renew</span>')
         + sp_row("chamahub", "Weekly meet moved to Saturday", meta("Sun")))
SUGG3 = (sp_row("coffee", "Public · Farming · 4.8k · Juma follows", btn("Follow", "secondary", "sm", block=False))
         + sp_row("runners", "Public · Sport · 910 · 3 contacts", btn("Follow", "secondary", "sm", block=False))
         + sp_row("worship", "Members only · 1 USDC (KES 129) a month", btn("Join", "secondary", "sm", block=False)))
def sec_head(text, link="See All", pad="12px 20px 0"):
    l = f'<button type="button" class="link">{link}</button>' if link else ""
    return f'<div class="sec-row" style="padding: {pad};"><span class="sec">{text}</span>{l}</div>'

frame(P_SD, "SD1-Spaces-tab", "SD1 · Spaces tab, with spaces",
      "Replaces S1b. Your spaces first, newest activity on top, unread counts as in Chats. A membership ending soon shows "
      "Renew on its row (amber, the only warning colour). Suggested is public and members-only spaces: ones your contacts "
      "follow first, then popular in Kenya. Follow acts in place; Join opens the space with its join card (SM1). Invite-only spaces never appear.",
      large_title("Spaces", spaces_tab_btns) + moments_row(ROW_FULL[:5])
      + '<div style="padding: 14px 0 0; display: flex; flex-direction: column; gap: 4px;">' + sec_head("Your spaces", pad="0 20px")
      + f'<div class="cx-list cx-list-plain">{YOUR3}</div>' + sec_head("Suggested for you")
      + f'<div class="cx-list cx-list-plain">{SUGG3}</div></div>' + tabbar("Spaces"), tag=P3)

frame(P_SD, "SD1b-No-spaces", "SD1 · Not in any space yet",
      "Empty: no space joined. Suggestions carry the section, and the one line under them says how to start one. "
      "Create a Space opens SN1 (the same as + → Space).",
      large_title("Spaces", spaces_tab_btns) + moments_row(ROW_FULL[:5])
      + '<div style="padding: 14px 0 0; display: flex; flex-direction: column; gap: 4px;">' + sec_head("Your spaces", link=None, pad="0 20px")
      + '<div style="margin: 6px 16px 4px; padding: 16px; border-radius: var(--radius-md); background: var(--fill); display: flex; flex-direction: column; gap: 10px;">'
      + '<div style="font: 600 17px/22px var(--font-sans);">Find your people</div><p class="small" style="font-size: 15px; line-height: 20px;">Follow a public space, join a members-only one, or start your own for your shop, church, chama or club.</p>'
      + f'<div>{btn("Create a Space", "primary", "sm", block=False)}</div></div>'
      + sec_head("Suggested for you") + f'<div class="cx-list cx-list-plain">{SUGG3}</div></div>' + tabbar("Spaces"), tag=P3)

frame(P_SD, "SD1c-Loading", "SD1 · Loading, first open",
      "Only before anything is cached: skeleton rows the size of the real ones, no spinner. Moments come from the phone at "
      "once. After the first load the spaces open from the phone and refresh quietly.",
      large_title("Spaces", spaces_tab_btns) + moments_row(ROW_FULL[:5])
      + '<div style="padding: 14px 0 0; display: flex; flex-direction: column; gap: 4px;" aria-busy="true" aria-label="Loading spaces">' + sec_head("Your spaces", link=None, pad="0 20px")
      + f'<div class="cx-list cx-list-plain">{sk_row * 2}</div>' + sec_head("Suggested for you", link=None) + f'<div class="cx-list cx-list-plain">{sk_row * 3}</div></div>' + tabbar("Spaces"), tag=P3)

frame(P_SD, "SD1d-Offline", "SD1 · Offline",
      "Your spaces open from the phone (their last posts and counts as of the time shown). Suggestions need the server, so "
      "the section says so instead of showing stale ones; Follow and Join are never offered offline.",
      large_title("Spaces", spaces_tab_btns) + f'<div style="padding: 10px 16px 0;">{asof_banner()}</div>' + moments_row(ROW_FULL[:5])
      + '<div style="padding: 14px 0 0; display: flex; flex-direction: column; gap: 4px;">' + sec_head("Your spaces", link=None, pad="0 20px")
      + f'<div class="cx-list cx-list-plain">{YOUR3}</div>' + sec_head("Suggested for you", link=None)
      + '<p class="small" style="padding: 8px 20px; font-size: 15px; line-height: 20px;">Suggestions show when you’re back online.</p></div>' + tabbar("Spaces"), tag=P3)

frame(P_SD, "SD1e-Error", "SD1 · Suggestions didn’t load",
      "The server failed after retries. Your spaces still show from the phone; only the failed section says so, with Try "
      "Again and the reference. Nothing else on the tab is blocked.",
      large_title("Spaces", spaces_tab_btns) + moments_row(ROW_FULL[:5])
      + '<div style="padding: 14px 0 0; display: flex; flex-direction: column; gap: 4px;">' + sec_head("Your spaces", pad="0 20px")
      + '<div class="cx-list cx-list-plain">' + sp_row("sneaker", "Amina: Size 42 Jordans back in stock at 6 pm", meta("18:02") + count(12)) + '</div>' + sec_head("Suggested for you", link=None)
      + '<div style="padding: 0 0 0;">' + err_box("Suggestions didn’t load", "Something went wrong on our side.", "req_D2kq7Z") + "</div></div>" + tabbar("Spaces"), tag=P3)

def search_bar(text="", placeholder="Spaces, @handles, people", cancel=True):
    val = f'<span style="flex: 1; font: 400 17px/22px var(--font-sans);">{text}</span>' if text else f'<span style="flex: 1; font: 400 17px/22px var(--font-sans); color: var(--ink-secondary);">{placeholder}</span>'
    x = f'<button type="button" class="cx-icon-btn" aria-label="Clear" style="width: 22px; height: 22px; background: var(--fill-strong);">{ic("close", 11, 2.6)}</button>' if text else ""
    c = btn("Cancel", "plain", "sm", block=False) if cancel else ""
    return (f'<div style="display: flex; align-items: center; gap: 8px; padding: 8px 16px 8px;"><div class="cx-field-box" role="search" style="flex: 1; height: 44px;">'
            f'<span style="color: var(--ink-secondary); display: flex;">{ic("search", 16)}</span>{val}{x}</div>{c}</div>')

TOPICS = ["All"] + TOPIC_LIST
def topic_chips(on=0):
    return '<div style="display: flex; gap: 8px; padding: 4px 16px 10px; overflow: hidden;" role="radiogroup" aria-label="Topic">' + "".join(
        f'<button type="button" role="radio" aria-checked="{str(i == on).lower()}" class="chip{" chip-on" if i == on else ""}">{t}</button>' for i, t in enumerate(TOPICS)) + "</div>"

frame(P_SD, "SD2-Search", "SD2 · Search, typing",
      "From the search button. Results update as you type (after a short pause), grouped: Spaces first (name, handle, topic), "
      "then People, whose moments you can see. Members-only rows show the price in your currency. Matching words are bold.",
      search_bar("book") + topic_chips(0)
      + '<div class="sec" style="padding: 6px 20px 0;">Spaces</div><div class="cx-list cx-list-plain">'
      + sp_row("books", "<b>Book</b>s · Members only · 5 USDC (KES 646) a month", btn("Join", "secondary", "sm", block=False))
      + sp_row("swap", "<b>Book</b>s · Public · Kisumu · 1.1k", btn("Follow", "secondary", "sm", block=False))
      + "</div>"
      + '<div class="sec" style="padding: 14px 20px 0;">People</div><div class="cx-list cx-list-plain">'
      + row("Faith Chebet", "Shared a moment: “<b>Book</b> haul from Prestige”", av("faith"), chev()) + "</div>"
      + '<p class="small" style="padding: 18px 20px 0;">Invite-only spaces never show in search.</p>', tag=P3)

frame(P_SD, "SD2b-Search-handle", "SD2 · Search by @handle",
      "Typing @ searches handles only and puts an exact match on top with its kind and size, so a link someone read out "
      "loud finds the right space, not a lookalike.",
      search_bar("@sneakerdrop") + '<div class="sec" style="padding: 6px 20px 0;">Exact match</div><div class="cx-list cx-list-plain">'
      + sp_row("sneaker", "@sneakerdrop · Public · Shopping · 1.2k", btn("Follow", "secondary", "sm", block=False)) + "</div>"
      + '<div class="sec" style="padding: 14px 20px 0;">Similar handles</div><div class="cx-list cx-list-plain">'
      + space_row("Sneaker Drop Mombasa", "@sneakerdropmsa · Public · 86", "SM", end=btn("Follow", "secondary", "sm", block=False)) + "</div>", tag=P3)

frame(P_SD, "SD2c-Search-empty", "SD2 · No results",
      "Nothing matches. Say so, say why an invite-only space can’t be found, and offer to start one with that name.",
      search_bar("mitumba thika") + topic_chips(0)
      + center(ic("search", 40, 1.6), "No spaces match “mitumba thika”", "Check the spelling or try a topic. Invite-only spaces never show in search; ask someone in it for an invite.",
               btn("Create “Mitumba Thika”", "secondary", "sm", block=False), top=90), tag=P3)

frame(P_SD, "SD2d-Search-offline", "SD2 · Search offline",
      "Offline, search still filters the spaces on your phone (yours) by name and handle; finding new ones says it needs a connection.",
      search_bar("chama") + f'<div style="padding: 0 16px 8px;">{banner("You’re offline. Searching your spaces only.")}</div>'
      + '<div class="sec" style="padding: 6px 20px 0;">Your spaces</div><div class="cx-list cx-list-plain">'
      + sp_row("chamahub", "@chamahub · Public · 340", chev()) + "</div>"
      + '<p class="small" style="padding: 14px 20px 0; font-size: 15px; line-height: 20px;">More spaces show when you’re back online.</p>', tag=P3)

frame(P_SD, "SD3-Suggested-all", "SD3 · Suggested, See All",
      "Suggested in full, filtered by topic chips. The sub line gives the reason it’s suggested (a contact follows it, popular "
      "near you). A followed space flips to Following in place, with no navigation.",
      bar("Suggested") + topic_chips(1) + '<div class="cx-list cx-list-plain">'
      + sp_row("mama", "Public · Business · 2.3k · Mum follows", btn("Following", "plain", "sm", block=False, icon="check"))
      + sp_row("sneaker", "Public · Shopping · 1.2k · Baraka’s space", btn("Follow", "secondary", "sm", block=False))
      + sp_row("coffee", "Public · Farming · 4.8k · Popular in Kenya", btn("Follow", "secondary", "sm", block=False))
      + space_row("Nyeri Dairy Co-op", "Members only · 10 USDC (KES 1,292) a year", "NY", end=btn("Join", "secondary", "sm", block=False))
      + space_row("Gikomba Wholesale", "Public · Business · 3.9k · Popular in Kenya", "GW", end=btn("Follow", "secondary", "sm", block=False)) + "</div>", tag=P3)

# ================================================================ PAGE SS · The space page
P_SS = "SS"
SNEAK_ABOUT = "Original sneakers in Nairobi CBD. New drops every Friday, M-Pesa-friendly, delivery in town."
def chat_preview_sneaker():
    return (f'<div class="cx-thread" style="padding: 4px 16px 0;">{day("Today")}'
            + post("baraka", "New Jordan 1s land Friday 6 pm. Sizes 40 to 45. 🔥", "Owner", [("🔥", 48, False), ("👟", 12, False)])
            + post(AMINA, "Size 42 is back in stock for those who asked", "Admin", [("🙌", 9, False)], name="Amina") + "</div>")

frame(P_SS, "SS1-Public-visitor", "SS1 · Public space, not following",
      "A public space before following: cover (token rings until the owner adds a photo), photo, name, handle, kind and size, "
      "one line about, people you know. Follow is the only primary. Visitors read the latest posts (read-only) so they know "
      "what they’re joining. Message (privately) is open to anyone who can see a public space.",
      space_head("sneaker", "public", "Public · 1.2k", SNEAK_ABOUT,
                 btn("Follow", "primary", "md", block=False, icon="plus") + btn("Message", "secondary", "md", block=False))
      + f'<div style="padding: 0 16px;">{known(["juma", "faith", "kevin"], "Juma, Faith and 1 other contact follow")}</div>'
      + tabs(0) + chat_preview_sneaker()
      + composer_note("Follow to post and react."), pad_top=0, tag=P3)

frame(P_SS, "SS1b-Public-following", "SS1 · Following",
      "After Follow (online): the button becomes Following with a chevron (its popover has Notifications and Unfollow), the "
      "composer opens and a toast confirms. Optimistic is not allowed here: membership is the server’s, so the button shows "
      "progress for the moment it takes.",
      space_head("sneaker", "public", "Public · 1.2k", SNEAK_ABOUT,
                 btn("Following", "secondary", "md", block=False, icon="check") + btn("Message", "secondary", "md", block=False))
      + tabs(0) + chat_preview_sneaker()
      + space_composer("Message Nairobi Sneaker Drop") + toast("You follow Nairobi Sneaker Drop", top=112), pad_top=0, tag=P3)

frame(P_SS, "SS1c-Following-menu", "SS1 · Following, the popover",
      "Following opens a popover under the button, not a sheet: notifications (All, Mentions and admins, Off) and Unfollow. "
      "Unfollow acts at once with a toast and Undo; a paid membership confirms first, in the popover (SM6).",
      space_head("sneaker", "public", "Public · 1.2k", SNEAK_ABOUT,
                 btn("Following", "secondary", "md", block=False, icon="chev_down") + btn("Message", "secondary", "md", block=False))
      + tabs(0) + chat_preview_sneaker() + space_composer("Message Nairobi Sneaker Drop")
      + '<div class="dim-behind"></div>'
      + pop([("All posts", "check"), ("Mentions and admins", None), ("Off", None), "-", ("Unfollow", "user_minus", "pop-crit")], top=424, left=16, head="Notifications"),
      pad_top=0, tag=P3)

BOOKS_ABOUT = "A monthly book, a Saturday meet-up at Alliance Française, and a chat that actually reads."
frame(P_SS, "SS2-Members-only-visitor", "SS2 · Members-only space, not a member",
      "A members-only space before paying: everything public about it (name, photo, about, price, what members get, who "
      "you know) and the shape of the chat behind a lock, never real posts. The button carries the price and period; it "
      "opens the join card (SM1) on this page.",
      space_head("books", "members", "Members only · 214", BOOKS_ABOUT,
                 btn("Join · KES 646 a month", "primary", "md", block=False) + btn("Message", "secondary", "md", block=False))
      + f'<div style="padding: 0 16px;">{known(["njeri", "achieng"], "Njeri (owner) and Achieng are here")}</div>'
      + '<div style="margin: 14px 16px 0; padding: 14px 16px; border-radius: var(--radius-md); background: var(--fill); display: flex; flex-direction: column; gap: 8px;">'
      + '<div style="font: 600 15px/20px var(--font-sans);">Members get</div>'
      + "".join(f'<div style="display: flex; gap: 8px; font: 400 15px/20px var(--font-sans);">{ic("check", 16, 2.4)}<span>{t}</span></div>' for t in ("The space chat and its history", "Monthly meet-ups and the reading list", "5.00 USDC a month, about KES 646. Never taken automatically"))
      + "</div>"
      + f'<div style="position: relative; padding-top: 14px;"><div class="ghost" aria-hidden="true"><i style="width: 70%;"></i><i style="width: 52%;"></i><i style="width: 64%; align-self: flex-end;"></i></div>'
      + f'<div style="position: absolute; inset: 14px 0 0; display: flex; align-items: center; justify-content: center;"><span style="display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 999px; background: var(--bg); box-shadow: var(--shadow-float); font: 600 15px/20px var(--font-sans);">{ic("lock", 16)}The chat is for members</span></div></div>',
      pad_top=0, tag=P3)

frame(P_SS, "SS3-Invite-only-link", "SS3 · Invite-only space, from a link",
      "Invite-only spaces are hidden from search, but a shared link or QR still opens this: the name, photo and size only, "
      "nothing inside. Without an invite the person can Request to Join; admins answer in their Invites list (SI4).",
      space_head("founders", "invite", "Invite only · 48", None, right_btn='<span></span>')
      + '<div style="padding: 18px 16px 0; display: flex; flex-direction: column; gap: 12px;">'
      + '<p class="small" style="font-size: 15px; line-height: 20px;">This space is private. Its posts and member list are only for people an admin lets in.</p>'
      + known(["wanjiru"], "Wanjiru is a member") + "</div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Request to Join")}'
      + '<p class="small" style="text-align: center;">The admins see your name, photo and @handle.</p></div>', pad_top=0, tag=P3)

frame(P_SS, "SS3b-Request-pending", "SS3 · Request sent, pending",
      "After Request to Join: the button is a quiet Requested state with the time, and Cancel Request. When an admin "
      "answers, the person gets a push and the page changes (accepted opens the space; declined goes back to SS3 after 30 days).",
      space_head("founders", "invite", "Invite only · 48", None, right_btn='<span></span>')
      + '<div style="padding: 18px 16px 0; display: flex; flex-direction: column; gap: 12px;">'
      + '<p class="small" style="font-size: 15px; line-height: 20px;">This space is private. Its posts and member list are only for people an admin lets in.</p>'
      + known(["wanjiru"], "Wanjiru is a member") + "</div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">'
      + f'<div role="status" style="display: flex; align-items: center; justify-content: center; gap: 8px; height: 50px; border-radius: 999px; background: var(--fill); font: 600 17px/22px var(--font-sans); color: var(--ink-secondary);">{ic("clock", 18)}Requested today, 09:12</div>'
      + btn("Cancel Request", "plain") + "</div>", pad_top=0, tag=P3)

about_rows = ('<div class="cx-list">'
              + row("Owner", "Baraka Mwita", av("baraka"), chev())
              + row("Topic", "Shopping · Nairobi", f'<span class="cx-icon-btn">{ic("globe")}</span>', "", tap=False)
              + row("Share", "circaso.app/s/sneakerdrop · QR code", f'<span class="cx-icon-btn">{ic("qr", 18, 1.8)}</span>', chev())
              + row("Notifications", "All posts", f'<span class="cx-icon-btn">{ic("bell", 18, 1.8)}</span>', chev()) + "</div>")
frame(P_SS, "SS4-About", "SS4 · About",
      "About: the full description, house rules, owner, topic, share link and QR, notifications. The trust line is here in "
      "plain words: space chats are not end-to-end encrypted; private messages to the owner are. Report and Leave sit at the end, quiet.",
      space_head("sneaker", "public", "Public · 1.2k", None, btn("Following", "secondary", "md", block=False, icon="check") + btn("Message", "secondary", "md", block=False))
      + tabs(1) + '<div style="padding: 4px 16px 0; display: flex; flex-direction: column; gap: 12px;">'
      + f'<p style="margin: 0; font: 400 15px/20px var(--font-sans);">{SNEAK_ABOUT} House rules: no fakes, no reselling posts, be kind.</p>'
      + about_rows
      + f'<div style="display: flex; gap: 8px; align-items: flex-start; font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);"><span style="display: flex; padding-top: 1px;">{ic("info", 14)}</span><span>Chats in spaces are not end-to-end encrypted. Circaso and the space’s admins can read them to keep the space safe. Private messages to the owner are end-to-end encrypted.</span></div>'
      + f'<div style="display: flex; gap: 20px; padding-top: 2px;">{btn("Report Space", "destructive", "sm", block=False, icon="flag")}{btn("Unfollow", "plain", "sm", block=False)}</div></div>',
      pad_top=0, tag=P3)

def member_row(who, sub=None, rl=None, end=""):
    t = FULL.get(who, who)
    title = f'{t}' + (f' {role(rl)}' if rl else "")
    return (f'<div role="button" tabindex="0" class="cx-row cx-row-tap">{av(who)}<div class="cx-row-body"><div class="cx-row-text">'
            f'<div class="cx-row-title" style="display: flex; align-items: center; gap: 6px;"><span style="overflow: hidden; text-overflow: ellipsis;">{t}</span>{role(rl) if rl else ""}</div>'
            + (f'<div class="cx-row-sub">{sub}</div>' if sub else "") + f'</div><div class="cx-row-end">{end}</div></div></div>')

frame(P_SS, "SS5-Members", "SS5 · Members",
      "Members, for everyone in the space: owner, admins and moderators first (with their role), then your contacts, then "
      "the count. A member list never shows phone numbers; @handles only. Admins get the same list with actions (SR1).",
      space_head("sneaker", "public", "Public · 1.2k", None, btn("Following", "secondary", "md", block=False, icon="check") + btn("Message", "secondary", "md", block=False))
      + tabs(2) + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 6px;"><div class="sec">Team</div><div class="cx-list">'
      + member_row("baraka", "@barakam", "Owner") + member_row(AMINA, "@amina.w", "Admin") + member_row("kevin", "@kevo", "Moderator") + "</div>"
      + '<div class="sec" style="padding-top: 8px;">Your contacts here</div><div class="cx-list">' + member_row("juma", "@jumak") + member_row("faith", "@faithc") + "</div>"
      + '<p class="small" style="padding: 4px;">And 1,199 more</p></div>', pad_top=0, tag=P3)

sk_head = ('<div class="cover" style="background: var(--fill);"><div class="cover-btns"><button type="button" class="cx-icon-btn" aria-label="Back">' + ic("back") + '</button><span></span></div></div>'
           '<div class="sp-head" aria-busy="true" aria-label="Opening the space"><span class="sk" style="width: 88px; height: 88px; border-radius: 22px; box-shadow: 0 0 0 4px var(--bg);"></span>'
           '<span class="sk" style="width: 62%; height: 22px; margin-top: 8px;"></span><span class="sk" style="width: 44%; height: 14px;"></span><span class="sk" style="width: 90%; height: 14px;"></span>'
           '<div style="display: flex; gap: 8px; padding-top: 8px;"><span class="sk" style="width: 110px; height: 44px; border-radius: 999px;"></span><span class="sk" style="width: 110px; height: 44px; border-radius: 999px;"></span></div></div>')
frame(P_SS, "SS6-Loading", "SS6 · Opening a space",
      "A space never opened before: the page’s own shape in skeletons, so nothing jumps when it arrives. Opened from a row, "
      "the name and photo are known already and show at once; only the rest loads.",
      sk_head + f'<div style="padding: 20px 16px 0;">{seg(["Chat", "About", "Members"], 0, "Space sections")}</div>'
      + '<div class="ghost" style="padding-top: 16px;" aria-hidden="true"><i class="sk" style="width: 70%;"></i><i class="sk" style="width: 50%;"></i><i class="sk" style="width: 62%;"></i></div>', pad_top=0, tag=P3)

frame(P_SS, "SS6b-Offline", "SS6 · Offline",
      "A space you’re in opens from the phone: its details and latest posts as of the time shown. Follow, Join, Request and "
      "Message to a new owner need the server, so they’re disabled with the reason, never failed after the tap.",
      space_head("coffee", "public", "Public · 4.8k", "Smallholders sharing prices, buyers and weather from Nyeri to Kericho.",
                 btn("Follow", "primary", "md", block=False, disabled=True) + btn("Message", "secondary", "md", block=False, disabled=True))
      + f'<div style="padding: 10px 16px 0;">{asof_banner("10:42", "this space")}</div>'
      + '<p class="small" style="padding: 8px 20px 0;">Following needs a connection.</p>'
      + tabs(0) + f'<div class="cx-thread" style="padding: 4px 16px 0;">{day("Yesterday")}' + post("juma", "AA grade is at KES 92 a kilo at the Karatina factory today", None, [("🙏", 31, False)]) + "</div>",
      pad_top=0, tag=P3)

frame(P_SS, "SS6c-Error", "SS6 · Couldn’t open",
      "The space failed to load after retries and nothing is cached. Plain words, Try Again, the reference.",
      '<div class="cover"><div class="cover-btns"><button type="button" class="cx-icon-btn" aria-label="Back">' + ic("back") + '</button><span></span></div></div>'
      + err_box("This space didn’t open", "Something went wrong on our side. Try again in a moment.", "req_S6p0Qa"), pad_top=0, tag=P3)

frame(P_SS, "SS6d-Unavailable", "SS6 · No longer available",
      "The owner deleted the space, or Circaso removed it for breaking the rules. One calm screen that says which, without "
      "detail; payments already made stay in the wallet history.",
      '<div class="cover"><div class="cover-btns"><button type="button" class="cx-icon-btn" aria-label="Back">' + ic("back") + '</button><span></span></div></div>'
      + center(ic("ban", 40, 1.6), "This space isn’t available", "Circaso removed Gikomba Deals for breaking the community rules. Your payments to it are still in your wallet history.",
               btn("Back to Spaces", "secondary", "sm", block=False), top=60), pad_top=0, tag=P3)

frame(P_SS, "SS6e-Banned", "SS6 · Removed from the space",
      "Permission denied: an admin banned this person. They see that they can’t take part, not who did it, and private "
      "messages to the owner stop too (a ban must end harassment). A removed (not banned) person sees the normal page and can follow again.",
      space_head("runners", "public", "Public · 910", None, right_btn='<span></span>')
      + center(ic("ban", 36, 1.6), "You can’t join Karura Runners", "An admin banned you from this space. You can’t follow, post, message its owner or be invited again. If you think this is a mistake, tell Circaso through Asha.",
               btn("Ask Asha", "secondary", "sm", block=False), top=36), pad_top=0, tag=P3)

# ================================================================ PAGE SN · Create a space
P_SN = "SN"
def create_bar(step, total=3, right=None):
    return (bar("New Space", left="cancel", right=right or "")
            + f'<div style="padding: 4px 16px 0;"><div class="steps" role="progressbar" aria-valuemin="1" aria-valuemax="{total}" aria-valuenow="{step}" aria-label="Step {step} of {total}">'
            + "".join(f'<i class="{"on" if i < step else ""}"></i>' for i in range(total)) + "</div></div>")

def create_details(handle_state="ok", offline=False, failed=False):
    hint = {"ok": f'<span style="display: inline-flex; gap: 4px; align-items: center;">{ic("check", 13, 2.6)}Available · circaso.app/s/kilimaniyoga</span>',
            "checking": '<span style="display: inline-flex; gap: 6px; align-items: center;"><span class="cx-spin" style="width: 12px; height: 12px; border-width: 1.5px;"></span>Checking…</span>',
            "taken": "@kilimaniyoga is taken. Try one of these:"}[handle_state]
    hf = field("Handle", "kilimaniyoga", prefix="@", fid="sh", invalid=handle_state == "taken", hint=hint)
    if handle_state == "taken":
        hf += '<div style="display: flex; gap: 8px; flex-wrap: wrap; padding: 2px 4px 0;">' + "".join(f'<button type="button" class="chip">@{s}</button>' for s in ("kilimaniyoga.ke", "yogakilimani", "kilimaniyogahub")) + "</div>"
    top = ""
    if offline:
        top = f'<div>{banner("You’re offline. Creating a space needs a connection.")}</div>'
    if failed:
        top = f'<div>{ref_banner("The space wasn’t created. Nothing was saved and no wallet was made.", "req_N8cv2E")}</div>'
    cta = (btn("Next", disabled=True) + '<div class="cx-footnote" style="padding-top: 8px;">Needs a connection</div>') if offline else btn("Try Again" if failed else "Next")
    return (create_bar(1) + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 16px;">' + top
            + f'<div style="display: flex; align-items: center; gap: 14px;"><button type="button" class="sp-tile" aria-label="Add a space photo" style="width: 72px; height: 72px; border: 0; border-radius: 22px; color: var(--ink-secondary); background: var(--fill);">{ic("camera", 26, 1.8)}</button>'
            + f'<div style="flex: 1; min-width: 0;">{field("Space name", "Kilimani Yoga Collective", fid="sn")}</div></div>'
            + hf
            + f'<div class="cx-field"><label class="cx-field-label" for="sa">About</label><div class="cx-field-box" style="height: 88px; align-items: flex-start; padding-top: 14px;"><span id="sa" style="font: 400 17px/22px var(--font-sans);">Sunrise yoga in Kilimani, Tuesdays and Saturdays. All levels, mats provided.</span></div><div class="cx-field-hint" style="justify-content: flex-end;">78 / 300</div></div>'
            + '<div class="cx-field"><div class="cx-field-label">Topic</div><div style="display: flex; gap: 8px; flex-wrap: wrap;">'
            + "".join(f'<button type="button" class="chip{" chip-on" if t == "Health" else ""}" role="radio" aria-checked="{str(t == "Health").lower()}">{t}</button>' for t in TOPIC_LIST) + "</div></div></div>"
            + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px;">{cta}</div>')

frame(P_SN, "SN1-Details", "SN1 · New space, details",
      "From + → Space, or Create a Space. Full screen, three short steps (details, who can join, roles), then the wallet PIN. "
      "Photo, name, a unique @handle checked as you type, about (300 characters), one topic. The handle becomes the link.",
      create_details(), bg="var(--bg-grouped)", tag=P3)
frame(P_SN, "SN1b-Handle-taken", "SN1 · Handle taken",
      "The check answers within a moment; a taken handle turns the field red with three free alternatives as chips. Next "
      "waits until the handle is free.",
      create_details("taken").replace(btn("Next"), btn("Next", disabled=True)), bg="var(--bg-grouped)", tag=P3)

def join_opt(kind, title, sub, on, body=""):
    return (f'<div role="radio" aria-checked="{str(on).lower()}" tabindex="0" class="opt{" opt-on" if on else ""}"><div style="display: flex; gap: 12px; align-items: flex-start;">'
            f'<span class="cx-icon-btn" style="flex: none;">{kind_icon(kind)}</span><div style="flex: 1;"><div style="font: 600 17px/22px var(--font-sans);">{title}</div>'
            f'<div class="small" style="font-size: 15px; line-height: 20px;">{sub}</div></div><span class="radio{" radio-on" if on else ""}" style="margin-top: 2px;"></span></div>{body}</div>')

price_body = ('<div style="display: flex; flex-direction: column; gap: 12px;">'
              + field("Price to join", "10.00", prefix="USDC", fid="pp", hint="About KES 1,292 today. Members see it in their own currency.")
              + '<div class="cx-field"><div class="cx-field-label">Members pay</div>' + seg(["One time", "Monthly", "Yearly"], 1, "Members pay") + "</div>"
              + f'<div style="display: flex; gap: 8px; align-items: flex-start; font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);"><span style="display: flex; padding-top: 1px;">{ic("info", 14)}</span>'
              + "<span>You receive the full 10.00 USDC; members pay Circaso’s 1% fee on top. Renewals are never taken automatically: members are reminded and pay with their PIN, with 3 days’ grace.</span></div></div>")
frame(P_SN, "SN2-Who-can-join", "SN2 · Who can join",
      "Three choices as cards, not a dropdown, each saying what it means. Members only opens the price in USDC (the stable "
      "unit members pay in, with KES beside it) and the period. Changeable later in settings; a price change applies from each member’s next period.",
      create_bar(2) + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 10px;"><div class="sec">Who can join Kilimani Yoga Collective</div>'
      + join_opt("public", "Public", "Anyone can find it and follow. For shops and open communities.", False)
      + join_opt("members", "Members only", "Shows in search with its price. People pay to join.", True, price_body)
      + join_opt("invite", "Invite only", "Hidden from search. Admins invite people or accept requests.", False) + "</div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px;">{btn("Next")}</div>', bg="var(--bg-grouped)", tag=P3)

def pick_admin(who, rl):
    return member_row(who, None, None, f'<button type="button" class="chip" aria-label="{FULL[who]} role: {rl}, change">{rl}{ic("chev_down", 14)}</button>')
frame(P_SN, "SN3-Roles", "SN3 · Chat and roles",
      "Who can post (Everyone, or Admins only for an announcement space), and the team: pick contacts and give each a role. "
      "Optional; more people can be added later. The trust line says plainly that only the owner moves the space’s money.",
      create_bar(3) + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 16px;">'
      + '<div class="cx-field"><div class="cx-field-label">Who can post in the chat</div>' + seg(["Everyone", "Admins only"], 0, "Who can post") + "</div>"
      + '<div style="display: flex; flex-direction: column; gap: 6px;"><div class="sec">Team (optional)</div><div class="cx-list">'
      + member_row("pasaka", "You", "Owner") + pick_admin("achieng", "Admin") + pick_admin("faith", "Moderator")
      + row("Add People", None, f'<span class="cx-icon-btn">{ic("user_plus", 18, 1.8)}</span>', chev()) + "</div>"
      + '<p class="small" style="padding: 0 4px;">Admins manage members, invites and reports. Moderators remove posts and mute people.</p></div>'
      + f'<div style="display: flex; gap: 8px; align-items: flex-start; font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);"><span style="display: flex; padding-top: 1px;">{ic("lock", 14)}</span>'
      + "<span>Every space has its own wallet. Only you can send from it, with its own PIN; admins can see it but not move it.</span></div></div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px;">{btn("Create Space and Set Up Wallet")}</div>', bg="var(--bg-grouped)", tag=P3)

space_badge = (f'<div style="position: relative;">{tile("yoga", 64)}<span style="position: absolute; right: -4px; bottom: -4px; width: 28px; height: 28px; border-radius: 50%; '
               f'background: var(--action); color: var(--on-action); display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 3px var(--bg);">{ic("wallet", 15, 2)}</span></div>')
frame(P_SN, "SN4-Wallet-PIN", "SN4 · Set the space wallet’s PIN",
      "GW2 for a space: Circle’s PIN screen in Circaso’s skin with the space’s tile and name, so it can’t be mistaken for the "
      "personal or a group PIN. The space goes live only once its wallet exists (plan §7). The biometrics offer (GW4) follows.",
      pin_screen("Set a PIN for Kilimani Yoga Collective", "This PIN approves money leaving the space wallet. Use a different one from your personal PIN.", 3, top=space_badge), tag=P3)

frame(P_SN, "SN5-Setting-up", "SN5 · Setting up",
      "The space exists from step 3; this is the wallet: Circle user, addresses on every network. Steps tick as they finish "
      "and are announced politely.",
      '<div style="display: flex; flex-direction: column; align-items: center; gap: 22px; padding: 200px 32px 0; text-align: center;">'
      + space_badge + '<h1 class="h1" style="font-size: 22px; line-height: 28px;">Setting up Kilimani Yoga Collective</h1>'
      + '<div role="status" aria-live="polite" style="display: flex; flex-direction: column; gap: 10px; text-align: left; font: 400 15px/20px var(--font-sans);">'
      + "".join(f'<div style="display: flex; gap: 10px; align-items: center;">{ic("check", 18, 2.4)}{t}</div>' for t in ("Space created", "PIN set", "Wallet created"))
      + '<div style="display: flex; gap: 10px; align-items: center; color: var(--ink-secondary);"><span class="cx-spin" style="width: 16px; height: 16px;"></span>Adding networks</div></div></div>', tag=P3)

frame(P_SN, "SN5b-Setup-failed", "SN5 · The wallet didn’t finish",
      "The space was created in step 3, but its wallet set-up failed (or the connection dropped). The space is kept and stays "
      "hidden: nobody can find, follow or pay it until its wallet exists (plan §7). Finish Wallet Setup carries on where it "
      "stopped (the PIN isn’t asked again if it was set); Space Settings shows the same row. Copy keyed on the error code, the reference shown.",
      bar("Kilimani Yoga Collective", left="close")
      + '<div style="display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 140px 32px 0; text-align: center;">'
      + space_badge + '<h1 class="h1" style="font-size: 22px; line-height: 28px;">The space is saved. Its wallet isn’t ready yet.</h1>'
      + '<p class="lead" style="font-size: 15px; line-height: 20px;">Kilimani Yoga Collective goes live once its wallet is set up. Until then nobody else can see it.</p>'
      + '<div class="rid">Ref req_W5sp3K</div></div>'
      + f'<div style="position: absolute; left: 20px; right: 20px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Finish Wallet Setup")}{btn("Later", "plain")}</div>', tag=P3)

frame(P_SN, "SN6-Live", "SN6 · Your space is live",
      "Done. The link and QR to share (a members-only link opens SS2 with the price), Invite People, and Open Space. The "
      "first post is the owner’s: the space opens with the composer focused and a suggested welcome.",
      '<div style="display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 150px 28px 0; text-align: center;">'
      + tile("yoga", 88) + '<h1 class="h1">Kilimani Yoga Collective is live</h1>'
      + '<p class="lead" style="font-size: 15px; line-height: 20px;">Members only · 10.00 USDC a month. Share the link so people can find and join it.</p>'
      + f'<div style="display: flex; align-items: center; gap: 10px; width: 100%; padding: 6px 6px 6px 16px; border-radius: var(--radius-md); background: var(--fill);"><span style="flex: 1; text-align: left; font: 400 15px/20px var(--font-sans);">circaso.app/s/kilimaniyoga</span>{btn("Copy", "secondary", "sm", block=False, icon="copy")}</div>'
      + f'<div style="display: flex; gap: 8px; width: 100%;">{btn("Share", "secondary", "md", icon="share")}{btn("QR Code", "secondary", "md", icon="qr")}</div></div>'
      + f'<div style="position: absolute; left: 20px; right: 20px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Open Space")}{btn("Invite People", "plain")}</div>', tag=P3)

frame(P_SN, "SN7-Offline", "SN7 · Offline",
      "Creating a space is online-only (the server checks the handle and makes the space and its wallet). Offline, Next is "
      "disabled before the tap with the reason; what was typed stays.",
      create_details(offline=True), bg="var(--bg-grouped)", tag=P3)
frame(P_SN, "SN7b-Failed", "SN7 · Couldn’t create",
      "The create call failed after retries (same Idempotency-Key). Nothing was made, no wallet, the form is kept; Try Again "
      "reuses the key so a slow first attempt can’t make two spaces.",
      create_details(failed=True), bg="var(--bg-grouped)", tag=P3)
frame(P_SN, "SN7c-Not-yet", "SN7 · Not allowed yet",
      "Permission denied by the spam rule: accounts younger than 7 days can’t create spaces (and anyone can create at most 3 "
      "a day). Said once, with the date, before any typing.",
      bar("New Space", left="cancel")
      + center(ic("clock", 40, 1.6), "You can create a space from 11 Nov", "New Circaso accounts wait 7 days before starting a space. It keeps spaces free of spam. You can follow and join spaces now.",
               btn("Find Spaces", "secondary", "sm", block=False), top=160), tag=P3)

settings_rows = ('<div class="cx-list">'
                 + row("Details", "Name, photo, @handle, about, topic", f'<span class="cx-icon-btn">{ic("pencil", 17, 1.8)}</span>', chev())
                 + row("Who can join", "Members only · 10.00 USDC a month", f'<span class="cx-icon-btn">{kind_icon("members")}</span>', chev())
                 + row("Who can post", "Everyone", f'<span class="cx-icon-btn">{svg(TAB["Chats"], 17, fill=True)}</span>', chev())
                 + row("Private messages", "From anyone who can see the space", f'<span class="cx-icon-btn">{ic("lock", 17, 1.8)}</span>', chev()) + "</div>"
                 + '<div class="cx-list">'
                 + row("Members and roles", "38 · 1 admin, 1 moderator", f'<span class="cx-icon-btn">{ic("people", 17, 1.8)}</span>', chev())
                 + row("Invites and requests", "2 waiting", f'<span class="cx-icon-btn">{ic("mail", 17, 1.8)}</span>', count(2))
                 + row("Reports", "None open", f'<span class="cx-icon-btn">{ic("flag", 17, 1.8)}</span>', chev())
                 + row("Space wallet", "KES 49,096 · only you send", f'<span class="cx-icon-btn">{ic("wallet", 17, 1.8)}</span>', chev()) + "</div>")
frame(P_SN, "SN8-Settings", "SN8 · Space settings (owner)",
      "Everything the owner changes later, in one list. Admins see the same screen without Who can join, the wallet’s sending "
      "and Delete; moderators see Reports only. Delete Space is last and needs the wallet empty first (as group wallets).",
      bar("Space Settings") + '<div style="padding: 10px 16px 0; display: flex; flex-direction: column; gap: 14px;">'
      + f'<div style="display: flex; align-items: center; gap: 12px;">{tile("yoga", 52)}<div><div style="font: 600 17px/22px var(--font-sans);">Kilimani Yoga Collective</div><div class="small">@kilimaniyoga · Members only</div></div></div>'
      + settings_rows + f'<div>{btn("Delete Space", "destructive", "sm", block=False, icon="trash")}</div></div>', bg="var(--bg-grouped)", tag=P3)

frame(P_SN, "SN8b-Price-change", "SN8 · Changing the price",
      "Who can join, edited: the new price applies from each member’s next period and every member is told before then; "
      "nobody pays a price they haven’t seen. The confirmation is inline under the field, not a sheet.",
      bar("Who Can Join", left="back", right=btn("Save", "plain", "sm", block=False))
      + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 10px;">'
      + join_opt("members", "Members only", "Shows in search with its price. People pay to join.", True,
                 '<div style="display: flex; flex-direction: column; gap: 12px;">' + field("Price to join", "12.00", prefix="USDC", fid="pc", hint="Was 10.00 USDC. About KES 1,550 today.")
                 + '<div class="cx-field"><div class="cx-field-label">Members pay</div>' + seg(["One time", "Monthly", "Yearly"], 1, "Members pay") + "</div></div>")
      + note_box("38 members will be told today", "They keep 10.00 USDC until their current month ends, then renew at 12.00 USDC. New members pay 12.00 USDC from now.", None, icon="bell", tone="ink-secondary")
      + "</div>", bg="var(--bg-grouped)", tag=P3)

# ================================================================ PAGE SM · Paid entry and membership
P_SM = "SM"
BOOK_ROWS = [("Paying from", PERSONAL_FIXED), ("Network", "USDC on Base"), ("For", "Membership · monthly"), ("Member until", "4 Dec"),
             ("Fee", 'KES 6.46 <span style="color: var(--ink-secondary); font-weight: 400;">· 1%</span>'), ("Total", "KES 652.46"), ("Nairobi Book Club receives", "KES 646")]
def books_page(button=None, banner_html="", extra="", about=None):
    # With the card open the page keeps only its head (no about line), so the card never covers the buttons.
    return (space_head("books", "members", "Members only · 214", about,
                       button if button is not None else btn("Join · KES 646 a month", "primary", "md", block=False) + btn("Message", "secondary", "md", block=False))
            + banner_html
            + f'<div style="position: relative; padding-top: 14px;"><div class="ghost" aria-hidden="true"><i style="width: 70%;"></i><i style="width: 52%;"></i></div></div>' + extra)
def join_card(**kw):
    base = dict(title="Join Nairobi Book Club", amount="KES 646", under="5.00 USDC a month · priced in USDC", rows=BOOK_ROWS,
                button="Pay KES 652.46", lead=tile("books", 44), foot=f"One Circaso fee, network cost included. {RATE3}",
                status="Never taken automatically.")
    base.update(kw)
    return mcard(**base)

frame(P_SM, "SM1-Join-card", "SM1 · Join, the money card",
      "Join opens the chat’s money card on the space page (D-M3): everything on one card, nothing to type, Pay goes straight "
      "to the PIN. The price in KES (USDC under it, since the owner set it in USDC), paid from Personal (a membership is "
      "personal), what renews and when, the 1% space fee on top, the total and what the space receives.",
      books_page(btn("Join · KES 646 a month", "primary", "md", block=False, busy=False) + btn("Message", "secondary", "md", block=False)) + join_card(), pad_top=0, tag=P3)

frame(P_SM, "SM1b-Approve-PIN", "SM1 · Approve with the PIN",
      "Pay goes straight to Circle’s PIN, which names the wallet (Pasaka has other wallets, so Personal is named). The card "
      "behind says Waiting for your PIN. Cancel the PIN and nothing happens; the card stays.",
      books_page() + join_card(button="Waiting for your PIN…", busy=True, status="")
      + approve_sheet("Personal", av("pasaka", "md"), "KES 652.46", "Nairobi Book Club", 2, "Your personal PIN.", bio="Use Fingerprint Instead"), pad_top=0, tag=P3)

frame(P_SM, "SM1c-Joining", "SM1 · Joining",
      "After the PIN the card shows progress and says nothing about membership until the payment is confirmed: the server "
      "makes the person a member from the confirmed transfer, never from the tap.",
      books_page(btn("Joining…", "primary", "md", block=False, busy=True)) + join_card(button="Paying Nairobi Book Club…", busy=True, status="This takes a few seconds. You can leave; you’ll be told when you’re in.", foot=""),
      pad_top=0, tag=P3)

frame(P_SM, "SM1d-Welcome", "SM1 · You’re a member",
      "Confirmed: the chat opens where the lock was, a system line (seen only by the person who joined) records the membership "
      "and its end date, and a toast welcomes. The receipt goes to the private chat with the owner (SM2), not to the 214 members.",
      space_head("books", "members", "Members only · 215", None, btn("Member", "secondary", "md", block=False, icon="check") + btn("Message", "secondary", "md", block=False))
      + tabs(0) + f'<div class="cx-thread" style="padding: 4px 16px 0;">{day("Today")}'
      + post("njeri", "November’s book is Kintu by Jennifer Makumbi. Meet-up Saturday 2 pm 📚", "Owner", [("📚", 22, False), ("❤️", 9, False)])
      + sysline("You joined · member until 4 Dec", "ticket") + "</div>"
      + space_composer("Message Nairobi Book Club") + toast("Welcome to Nairobi Book Club", top=112), pad_top=0, tag=P3)

frame(P_SM, "SM1e-Not-sent", "SM1 · Not sent",
      "Refused before the chain (W4 error, SDK failure, expired challenge): the card says Not sent and that nothing was taken, "
      "Try Again pays the same with a fresh key, and the reference is in the foot.",
      books_page() + join_card(button="Try Again", status="", foot="Ref req_M1x7Pw",
                               problem=f'<div class="cx-banner cx-banner-critical" role="alert"><span class="cx-banner-icon">{ic("warning")}</span><span class="cx-banner-text">Not sent. Nothing was taken from your wallet.</span></div>'),
      pad_top=0, tag=P3)

frame(P_SM, "SM1f-Offline", "SM1 · Offline",
      "Money never queues. The card still opens so the price is clear, with Pay disabled and G3’s line; it wakes up when the "
      "connection is back.",
      books_page() + join_card(disabled=True, button="Pay KES 652.46", status="You’re offline. Money sends only while you’re connected.", tone="warning", foot="1 USDC = KES 129.20 as of 10:42"),
      pad_top=0, tag=P3)

frame(P_SM, "SM1g-Not-enough", "SM1 · Not enough in the wallet",
      "The total doesn’t fit the balance: the line says what’s there, Pay is off. Add Money is the honest coming sheet until "
      "partners exist (D-M5); Receive shows the addresses.",
      books_page() + join_card(disabled=True, button="Pay KES 652.46", status="You have KES 410 (3.17 USDC). Add money or receive some first.", tone="critical",
                               foot="", problem="").replace('<div class="mc-foot"></div>', "")
      .replace(btn("Pay KES 652.46", disabled=True), btn("Pay KES 652.46", disabled=True) + f'<div style="display: flex; gap: 8px;">{btn("Add Money", "secondary", "sm", block=False)}{btn("Receive", "secondary", "sm", block=False)}</div>'),
      pad_top=0, tag=P3)

def private_bar(owner, key, sub="Private · via "):
    return bar(FULL[owner].split()[0], sub=f'{ic("lock", 11, 2.4)} Private · via {SPACES[key][0]}'.replace('<svg class="cx-icon"', '<svg class="cx-icon" style="display: inline; vertical-align: -1px;"'),
               avatar=f'<span style="position: relative; display: inline-flex;">{av(owner, "sm")}<span style="position: absolute; right: -6px; bottom: -4px;">{tile(key, 16, " border-radius: 5px; font-size: 7px; box-shadow: 0 0 0 1.5px var(--bg);")}</span></span>')

member_card = ('<div class="cx-money cx-money-out" role="group" aria-label="Membership paid, KES 646 to Nairobi Book Club, fee KES 6.46" style="width: 280px;">'
               '<div class="cx-money-head">Membership · Nairobi Book Club</div><div class="cx-money-amount">KES 646</div>'
               f'<div class="cx-money-note">{ic("ticket", 15, 1.8)}Monthly · member until 4 Dec</div><div class="cx-money-foot">Fee KES 6.46 · 5.00 USDC · 11:20</div></div>')
frame(P_SM, "SM2-Receipt", "SM2 · The receipt, in the private chat",
      "Every payment to a space lands in the payer’s private chat with the owner (end-to-end encrypted, like an order card "
      "later): the membership card names the space, period, end date and fee. Njeri sees the same card in her inbox.",
      private_bar("njeri", "books")
      + f'<div class="cx-thread" style="padding: 8px 16px 0;">{sysline("End-to-end encrypted. Only Njeri, the owner, can read this chat.", "lock")}{day("Today")}'
      + member_card + '<div class="cx-bubble-time cx-bubble-time-out">11:20</div>'
      + '<div class="cx-bubble cx-bubble-in">Karibu Pasaka! See you Saturday 📚</div></div>'
      + space_composer("Message Njeri privately"), tag=P3)

renew_banner = (f'<div style="padding: 6px 16px 4px;"><div class="cx-banner cx-banner-warning" role="status"><span class="cx-banner-icon">{ic("clock")}</span>'
                f'<span class="cx-banner-text">Your membership ends on 4 Dec.</span><button type="button" class="link">Renew</button></div></div>')
BOOK_CHAT = (f'<div class="cx-thread" style="padding: 4px 16px 0;">{day("Today")}'
             + post("achieng", "Chapter 7 had me in tears. Who else is done?", None, [("😭", 6, False)])
             + post("njeri", "Saturday’s meet-up is at 2 pm, Alliance Française garden", "Owner") + "</div>")
frame(P_SM, "SM3-Renewal-reminder", "SM3 · Renewal reminder",
      "Three days before the end (seven for a yearly membership) a push, a card in the private chat and this banner in the "
      "space. Renew opens the same money card. Nothing is ever deducted.",
      space_bar("books", "215 members") + renew_banner + BOOK_CHAT + space_composer("Message Nairobi Book Club"), tag=P3)

frame(P_SM, "SM3b-Renew-card", "SM3 · Renew, at a new price",
      "The renewal card, the same as joining. When the owner changed the price, the card says so before the person can pay: "
      "the new price is the one on the button.",
      space_bar("books", "215 members") + renew_banner + BOOK_CHAT
      + mcard("Renew Nairobi Book Club", "KES 775.20", "6.00 USDC a month · was 5.00 USDC", [
          ("Paying from", PERSONAL_FIXED), ("Network", "USDC on Base"), ("For", "Membership · 4 Dec to 4 Jan"),
          ("Fee", 'KES 7.75 <span style="color: var(--ink-secondary); font-weight: 400;">· 1%</span>'), ("Total", "KES 782.95"), ("Nairobi Book Club receives", "KES 775.20")],
          "Pay KES 782.95", tile("books", 44), status="Njeri changed the price to 6.00 USDC from this period. You were told on 12 Nov.", foot=f"One Circaso fee, network cost included. {RATE3}"), tag=P3)

frame(P_SM, "SM4-Grace", "SM4 · Grace period",
      "The period ended without a renewal: 3 days’ grace in which everything still works, with a warning banner that counts "
      "down. Renew opens the card; the new period starts from the day paid.",
      space_bar("books", "215 members")
      + f'<div style="padding: 6px 16px 4px;"><div class="cx-banner cx-banner-warning" role="status"><span class="cx-banner-icon">{ic("warning")}</span>'
      + '<span class="cx-banner-text">Your membership ended on 4 Dec. You can still read and post until 7 Dec.</span><button type="button" class="link">Renew</button></div></div>'
      + BOOK_CHAT + space_composer("Message Nairobi Book Club"), tag=P3)

frame(P_SM, "SM5-Expired", "SM5 · Membership ended",
      "After the grace: permission denied to the chat (and later shop and lives) until the person pays again. The page says "
      "what happened and when, keeps their place in the list of spaces, and Rejoin opens the card. Their own posts stay in the space.",
      space_head("books", "members", "Members only · 214", None, btn("Rejoin · KES 775 a month", "primary", "md", block=False) + btn("Message", "secondary", "md", block=False))
      + f'<div style="padding: 28px 32px 0; display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center;"><span style="display: flex; color: var(--ink-secondary);">{ic("lock", 28, 1.8)}</span>'
      + '<div style="font: 600 17px/22px var(--font-sans);">Your membership ended on 4 Dec</div><div class="small" style="font-size: 15px; line-height: 20px;">The 3 days’ grace ended on 7 Dec. Rejoin to read the chat and take part again; your own posts are still there.</div></div>'
      + '<div class="ghost" aria-hidden="true" style="padding-top: 22px; opacity: .6;"><i style="width: 70%;"></i><i style="width: 52%;"></i><i style="width: 64%; align-self: flex-end;"></i></div>', pad_top=0, tag=P3)

frame(P_SM, "SM6-Leave-confirm", "SM6 · Leaving a paid membership",
      "Member opens the same popover as Following (SS1c). Leave confirms inside it, not a sheet, with what is lost: access "
      "ends now and the rest of the period isn’t refunded. Online-only; offline the item is disabled with the reason.",
      space_head("books", "members", "Members only · 215", None, btn("Member", "secondary", "md", block=False, icon="chev_down") + btn("Message", "secondary", "md", block=False))
      + tabs(0) + BOOK_CHAT.replace('<div class="cx-thread" style="padding: 4px 16px 0;">', '<div class="cx-thread" style="padding: 4px 16px 0;">', 1)
      + space_composer("Message Nairobi Book Club") + '<div class="dim-behind"></div>'
      + '<div class="pop" role="alertdialog" aria-label="Leave Nairobi Book Club?" style="top: 380px; left: 16px; width: 290px; padding: 14px 16px; gap: 10px;">'
      + '<div style="font: 600 17px/22px var(--font-sans);">Leave Nairobi Book Club?</div><p class="small" style="font-size: 15px; line-height: 20px;">You paid until 4 Dec. Leaving ends your access now and doesn’t refund the rest of the month. Rejoining means paying again.</p>'
      + f'<div style="display: flex; gap: 8px;">{btn("Stay", "secondary", "sm", block=False)}{btn("Leave", "destructive", "sm", block=False)}</div></div>', pad_top=0, tag=P3)

# ================================================================ PAGE SI · Invites
P_SI = "SI"
def invite_row(who, state):
    end = {"invite": btn("Invite", "secondary", "sm", block=False), "invited": '<span class="cx-row-meta">Invited</span>',
           "member": '<span class="cx-row-meta">Member</span>', "busy": btn("Invite", "secondary", "sm", block=False, busy=True)}[state]
    return member_row(who, f"@{FULL[who].split()[0].lower()}", None, end)
frame(P_SI, "SI1-Invite-people", "SI1 · Invite people",
      "Admins invite from contacts or by typing an @username (the server finds the person; phone numbers are never searched "
      "here). Each row invites in place and turns to Invited. Share Link is for people to ask to join (SS3).",
      bar("Invite to Founders Circle", left="close")
      + search_bar("", "Name or @username", cancel=False)
      + '<div style="padding: 0 16px 10px;"><div class="cx-list">' + row("Share Link", "People who open it can ask to join", '<span class="cx-icon-btn">' + ic("link", 18, 1.8) + "</span>", chev()) + "</div></div>"
      + '<div class="sec" style="padding: 4px 20px 0;">Your contacts</div><div class="cx-list cx-list-plain">'
      + invite_row("pasaka", "invited").replace("Pasaka Mutuku", "Pasaka Mutuku") + invite_row("kevin", "invite") + invite_row("faith", "busy")
      + invite_row("otieno", "invite") + invite_row("wanjiru", "member") + invite_row("juma", "invite") + "</div>", tag=P3)

frame(P_SI, "SI1b-Username", "SI1 · By @username",
      "Typing @ looks people up by username. A found person shows with photo and name before inviting, so the wrong Kevin "
      "isn’t let in; a miss says so plainly and suggests contacts.",
      bar("Invite to Founders Circle", left="close") + search_bar("@kevo254", cancel=False)
      + center(ic("search", 36, 1.6), "No one is @kevo254 on Circaso", "Check the spelling, or invite them from your contacts. People join Circaso with their phone number, then choose a username.", "", top=40)
      + '<div class="sec" style="padding: 22px 20px 0;">Close matches</div><div class="cx-list cx-list-plain">' + member_row("kevin", "@kevo · Kevin Ochieng", None, btn("Invite", "secondary", "sm", block=False)) + "</div>", tag=P3)

frame(P_SI, "SI1c-Offline", "SI1 · Offline",
      "Inviting is online-only (the server checks the person and the space). The list of contacts still shows; Invite buttons "
      "are disabled with the reason.",
      bar("Invite to Founders Circle", left="close") + f'<div style="padding: 6px 16px 8px;">{banner("You’re offline. Inviting needs a connection.")}</div>'
      + '<div class="cx-list cx-list-plain">' + "".join(member_row(w, f"@{FULL[w].split()[0].lower()}", None, btn("Invite", "secondary", "sm", block=False, disabled=True)) for w in ("kevin", "faith", "otieno", "juma")) + "</div>", tag=P3)

def chats_title():
    return large_title("Chats", icon_btn("sparkle", "Ask Asha") + icon_btn("pencil", "New chat"))
chat_chips = '<div style="display: flex; gap: 8px; padding: 12px 16px 6px;"><button type="button" class="chip chip-on">All</button><button type="button" class="chip">Unread</button><button type="button" class="chip">Groups</button></div>'
def invite_card(state="open"):
    lock = ic("lock", 11, 2.4).replace('<svg class="cx-icon"', '<svg class="cx-icon" style="display: inline; vertical-align: -1px;"')
    head = (f'<div style="display: flex; gap: 12px; align-items: center;">{tile("founders", 44)}<div style="flex: 1; min-width: 0;"><div style="font: 600 16px/21px var(--font-sans);">Nairobi Founders Circle</div>'
            f'<div class="small">{lock} Invite only · 48 members</div></div></div>')
    if state == "open":
        body = (f'<div style="font: 400 15px/20px var(--font-sans);">{av("wanjiru", "sm", "width: 20px; height: 20px; vertical-align: -5px;")} Wanjiru invited you · expires in 14 days</div>'
                f'<div style="display: flex; gap: 8px;">{btn("Accept", "primary", "md")}{btn("Decline", "secondary", "md")}</div>')
    else:
        body = f'<div style="display: flex; align-items: center; gap: 8px; font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);"><span style="flex: 1;">Invite declined. Wanjiru isn’t told.</span>{btn("Undo", "plain", "sm", block=False)}</div>'
    return f'<div role="group" aria-label="Space invite" style="margin: 4px 16px 8px; padding: 14px 16px; border-radius: var(--radius-lg); background: var(--surface); box-shadow: inset 0 0 0 1px var(--separator); display: flex; flex-direction: column; gap: 12px;">{head}{body}</div>'
chat_rows = ('<div class="cx-list cx-list-plain">'
             + row("Mum", "Mum sent you KES 2,000 · for lunch", av("mum"), meta("21:14") + count(2))
             + row("Kilimani Chama", "Wanjiru: November contributions are due Friday", av("Kilimani Chama"), meta("19:02"))
             + row("Otieno", "See you at the venue", av("otieno"), meta("Mon")) + "</div>")
frame(P_SI, "SI2-Invite-in-chats", "SI2 · The invite, in Chats",
      "An invite arrives as a card at the top of Chats (the person’s inbox) with a push. Accept and Decline act in place; "
      "the card opens the space’s preview (SI2b). Several invites stack, newest first.",
      chats_title() + chat_chips + invite_card() + chat_rows + tabbar("Chats"), tag=P3)

frame(P_SI, "SI2b-Invite-preview", "SI2 · Invite, the preview",
      "Tapping the card: the space as an invited person sees it, with its about and who they know (more than SS3 shows a "
      "stranger), and Accept / Decline at the bottom.",
      space_head("founders", "invite", "Invite only · 48", "Founders building in Nairobi. Monthly dinners, honest numbers, no pitching.", right_btn='<span></span>')
      + '<div style="padding: 10px 16px 0; display: flex; flex-direction: column; gap: 12px;">' + known(["wanjiru", "otieno", "achieng"], "Wanjiru, Otieno and Achieng are members")
      + f'<div style="display: flex; gap: 8px; align-items: center; font: 400 15px/20px var(--font-sans);">{av("wanjiru", "sm")}<span>Wanjiru invited you on 2 Nov</span></div>'
      + f'<div style="display: flex; gap: 8px; align-items: flex-start; font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);"><span style="display: flex; padding-top: 1px;">{ic("info", 14)}</span><span>Chats in spaces are not end-to-end encrypted.</span></div></div>'
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; gap: 8px;">{btn("Decline", "secondary")}{btn("Accept")}</div>', pad_top=0, tag=P3)

frame(P_SI, "SI3-Accepted", "SI3 · Accepted",
      "Accept (online) opens the space at its chat with a system line naming who invited you; the inviter gets a quiet “Pasaka "
      "joined”. Accept is disabled offline with the reason.",
      space_bar("founders", "49 members") + f'<div class="cx-thread" style="padding: 8px 16px 0;">{day("Today")}'
      + post("otieno", "Dinner on the 14th is at Cultiva. Who’s in?", "Admin", [("🙋🏾‍♂️", 11, False)])
      + sysline("You joined · Wanjiru invited you", "user_plus") + "</div>"
      + space_composer("Message Nairobi Founders Circle") + toast("You joined Nairobi Founders Circle", top=112), tag=P3)

frame(P_SI, "SI3b-Declined", "SI3 · Declined",
      "Decline folds the card into one line with Undo for a few seconds, then it goes. The inviter is never told about a "
      "decline (the invite just shows as not accepted until it expires), so saying no costs nothing socially.",
      chats_title() + chat_chips + invite_card("declined") + chat_rows + tabbar("Chats"), tag=P3)

def req_row(who, when):
    return member_row(who, f"Asked to join · {when}", None, f'<div style="display: flex; gap: 6px;">{btn("Decline", "secondary", "sm", block=False)}{btn("Accept", "primary", "sm", block=False)}</div>')
frame(P_SI, "SI4-Invites-requests", "SI4 · Invites and requests (admins)",
      "For admins: people asking to join (from a link) with Accept and Decline in place, then the invites sent and their state: "
      "Joined, Invited (days left) or Expired after 14 days, with Invite Again.",
      bar("Invites and Requests", right=icon_btn("user_plus", "Invite people")) + '<div style="padding: 8px 16px 0; display: flex; flex-direction: column; gap: 6px;">'
      + '<div class="sec">Asking to join</div><div class="cx-list">' + req_row("njeri", "today, 09:12") + req_row("baraka", "yesterday") + "</div>"
      + '<div class="sec" style="padding-top: 10px;">Invited</div><div class="cx-list">'
      + member_row("kevin", "Joined yesterday", None, "")
      + member_row("faith", "Invited · 12 days left", None, btn("Cancel", "plain", "sm", block=False))
      + member_row("juma", "Expired on 1 Nov", None, btn("Invite Again", "secondary", "sm", block=False)) + "</div></div>", bg="var(--bg-grouped)", tag=P3)

frame(P_SI, "SI4b-Empty", "SI4 · Nothing waiting",
      "Empty: no requests and no invites yet. One line and the way to start.",
      bar("Invites and Requests") + center(ic("mail", 40, 1.6), "No invites or requests yet", "Invite people from your contacts or by @username, or share the link so they can ask.",
                                           btn("Invite People", "primary", "md", block=False, icon="user_plus"), top=160), bg="var(--bg-grouped)", tag=P3)

# ================================================================ PAGE SH · Space chat and the private chat
P_SH = "SH"
JUMA_POST = post("juma", "Will there be a size 44 in the Chicago colourway?")
LEARN = sysline('Chats in spaces are not end-to-end encrypted. <a href="#" style="font-weight: 600; text-decoration: underline;">Learn more</a>', "info")
SNEAK_CHAT = (f'<div class="cx-thread" style="padding: 4px 16px 0;">{LEARN}{day("Today")}'
              + post("baraka", "New Jordan 1s land Friday 6 pm. Sizes 40 to 45. 🔥", "Owner", [("🔥", 48, True), ("👟", 12, False)])
              + JUMA_POST
              + post(AMINA, "Yes, 3 pairs. Message Baraka privately to hold one", "Admin", [("🙏", 4, False)], name="Amina", quote=("Juma", "Will there be a size 44 in the Chicago…"))
              + mine("Holding a 42 for me? 🙏🏾", "18:04") + "</div>")
frame(P_SH, "SH1-Space-chat", "SH1 · Space chat",
      "How a space chat differs from a group: the header is the space (tile, member count), the first line says it isn’t "
      "end-to-end encrypted, every post carries the sender’s name and role badge, reactions are counted, there are no read "
      "receipts or typing names (a big room), and # is plain text: money to a space goes through Message privately.",
      space_bar("sneaker", "1,204 members") + SNEAK_CHAT + space_composer("Message Nairobi Sneaker Drop"), tag=P3)

frame(P_SH, "SH1b-Admins-only", "SH1 · Admins-only space",
      "When the owner chose Admins only, members read and react; the composer becomes one line with Message Privately, so "
      "there is always a way to talk to the space.",
      space_bar("worship", "3,180 members")
      + f'<div class="cx-thread" style="padding: 4px 16px 0;">{sysline("Only admins post here. Chats in spaces are not end-to-end encrypted.", "info")}{day("Today")}'
      + post("njeri", "Youth service moves to 4 pm this Sunday. Bring a friend! 🙌🏾", "Admin", [("🙌🏾", 214, True), ("❤️", 96, False)])
      + post("otieno", "Choir rehearsal: Thursday 6 pm, main hall", "Owner", [("🎶", 58, False)]) + "</div>"
      + composer_note("Only admins post in this space.", "Message Privately"), tag=P3)

msg_menu = (f'<div class="pop" role="dialog" aria-label="Reactions" style="top: 300px; left: 52px; width: auto; flex-direction: row; gap: 4px; padding: 6px 10px; border-radius: 999px;">'
            + "".join(f'<span style="font-size: 24px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center;" role="button" aria-label="React {e}">{e}</span>' for e in ("❤️", "🔥", "😂", "🙏", "👟"))
            + f'<span class="cx-icon-btn" role="button" aria-label="More reactions" style="width: 44px; height: 44px;">{ic("plus", 16)}</span></div>')
frame(P_SH, "SH1c-Message-menu", "SH1 · Long-press a post",
      "Long-press lifts the post with reactions above and a popover menu below it (no sheet). Members see Reply, Copy and "
      "Report; moderators and admins also see Remove Post and Mute; admins see Remove from Space. Destructive items are last, in red.",
      space_bar("sneaker", "1,204 members") + SNEAK_CHAT.replace(JUMA_POST, f'<div style="visibility: hidden;">{JUMA_POST}</div>') + space_composer("Message Nairobi Sneaker Drop")
      + '<div class="dim-behind"></div>'
      + f'<div style="position: absolute; z-index: 8; top: 358px; left: 16px; right: 60px;">{post("juma", "Will there be a size 44 in the Chicago colourway?")}</div>'
      + msg_menu
      + pop([("Reply", "reply"), ("Copy", "copy"), ("Report", "flag"), "-", ("Remove Post", "trash", "pop-crit"), ("Mute Juma", "mute", "pop-crit"), ("Remove from Space", "user_minus", "pop-crit")],
            top=446, left=52, head="Admin"), tag=P3)

frame(P_SH, "SH1d-Offline", "SH1 · Offline",
      "A space chat opens from the phone. Posts written offline wait (clock) and go in order on reconnect, as in chats: a "
      "space post is a safe write, not money. Reactions wait the same way.",
      space_bar("sneaker", "1,204 members")
      + f'<div style="padding: 6px 16px 4px;">{banner("You’re offline. Posts send when you reconnect.")}</div>'
      + f'<div class="cx-thread" style="padding: 4px 16px 0;">{day("Today")}'
      + post("baraka", "New Jordan 1s land Friday 6 pm. Sizes 40 to 45. 🔥", "Owner", [("🔥", 48, True), ("👟", 12, False)])
      + mine("Is the CBD shop open Sunday?", state=f'{ic("clock", 12, 2.2)}Waiting') + "</div>"
      + space_composer("Message Nairobi Sneaker Drop"), tag=P3)

frame(P_SH, "SH1e-Muted", "SH1 · Muted by an admin",
      "Permission denied to post: a muted member reads and reacts, and the composer says until when. Who muted them isn’t "
      "shown. Message Privately still works.",
      space_bar("sneaker", "1,204 members") + SNEAK_CHAT.replace(mine("Holding a 42 for me? 🙏🏾", "18:04"), "")
      + composer_note("You can’t post here until 20:00 today. An admin muted you.", "Message Privately"), tag=P3)

frame(P_SH, "SH1f-Removed-post", "SH1 · A removed post, and the chat failing",
      "A post removed by a moderator leaves a tombstone so replies still make sense. Below, a page of older posts that failed "
      "to load says so in place with Try Again and the reference; the rest of the chat keeps working.",
      space_bar("sneaker", "1,204 members")
      + f'<div class="cx-thread" style="padding: 4px 16px 0;">'
      + f'<div role="alert" style="align-self: stretch; display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 10px 0;"><span class="small">Older posts didn’t load.</span>{btn("Try Again", "secondary", "sm", block=False)}<span class="rid">Ref req_H5tq0B</span></div>'
      + day("Today")
      + f'<div class="cx-msg">{av("kevin", "sm")}<div style="display: flex; flex-direction: column;"><div class="who">Kevin</div><div class="cx-bubble" style="background: transparent; box-shadow: inset 0 0 0 1px var(--separator); color: var(--ink-secondary); font-style: italic; display: flex; gap: 6px; align-items: center;">{ic("ban", 15, 1.8)}Removed by a moderator</div></div></div>'
      + post(AMINA, "Reminder: no reselling posts here. Use your own Moments 🙏", "Admin", name="Amina") + "</div>"
      + space_composer("Message Nairobi Sneaker Drop"), tag=P3)

PRIV = (f'<div class="cx-thread" style="padding: 8px 16px 0;">{sysline("End-to-end encrypted. Only Baraka, the owner, can read this chat. Other members and admins never see it.", "lock")}{day("Today")}'
        + mine("Hi Baraka, can you hold the size 42 Jordans for me till Friday?", "18:06")
        + '<div class="cx-bubble cx-bubble-in">Sure. A KES 2,000 deposit holds them, the rest on pickup</div>')
frame(P_SH, "SH2-Private-chat", "SH2 · Message privately",
      "Message (or Message Privately) opens a one-to-one, end-to-end encrypted chat with the space’s owner. The header says "
      "whose and via which space; the first line says who can read it. Here # pays the space (the composer hint says so).",
      private_bar("baraka", "sneaker") + PRIV + "</div>" + space_composer("Message, or #amount to pay"), tag=P3)

SNEAK_ROWS = [("Paying from", wchip3("Personal")), ("Network", "USDC on Base"), ("For", "deposit, size 42 Jordans"),
              ("Fee", 'KES 20 <span style="color: var(--ink-secondary); font-weight: 400;">· 1%</span>'), ("Total", "KES 2,020"), ("Nairobi Sneaker Drop receives", "KES 2,000")]
frame(P_SH, "SH2b-Pay-the-space", "SH2 · Send money to the space",
      "“#2000 deposit, size 42 Jordans” makes the money card over the private chat: who (the space, not Baraka’s own wallet), "
      "the amount, Paying from, the note, the 1% space fee on top, the total and what the space receives. Pay goes to the PIN. "
      "Only members can pay a space (§8 Q7); a visitor’s card says Follow first.",
      private_bar("baraka", "sneaker") + PRIV + "</div>"
      + mcard("Pay Nairobi Sneaker Drop", "KES 2,000", "15.48 USDC", SNEAK_ROWS, "Pay KES 2,020", tile("sneaker", 44), foot=f"One Circaso fee, network cost included. {RATE3}", bottom=96, close=False)
      + composer("#2,000", "deposit, size 42 Jordans"), tag=P3)

paid_space = ('<div class="cx-money cx-money-out cx-moment" role="group" aria-label="Paid KES 2,000 to Nairobi Sneaker Drop, fee KES 20" style="width: 280px;">'
              '<div class="cx-money-head">Paid to Nairobi Sneaker Drop</div><div class="cx-money-amount">KES 2,000</div>'
              '<div class="cx-money-note">deposit, size 42 Jordans</div><div class="cx-money-foot">Fee KES 20 · 15.48 USDC · 18:09</div></div>')
frame(P_SH, "SH2c-Paid", "SH2 · Paid, in the private chat",
      "The card in the thread says Sending until the server confirms, then Paid to the space (the moment sweep plays once). "
      "Baraka sees it as Received into Nairobi Sneaker Drop; it appears in the space wallet’s Recent.",
      private_bar("baraka", "sneaker") + PRIV + paid_space + '<div class="cx-bubble-time cx-bubble-time-out">18:09</div>'
      + '<div class="cx-bubble cx-bubble-in">Got it, asante! Pair is yours 👟</div></div>' + space_composer("Message, or #amount to pay"), tag=P3)

owner_rows = ('<div class="cx-list cx-list-plain">'
              + row('Pasaka <span class="role" style="vertical-align: 2px;">Private · Sneaker Drop</span>', "Paid KES 2,000 · deposit, size 42 Jordans", av("pasaka"), meta("18:09") + count(1))
              + row('Juma <span class="role" style="vertical-align: 2px;">Private · Sneaker Drop</span>', "Is the 44 Chicago still there?", av("juma"), meta("17:40") + count(2))
              + row("Mum", "Did you eat?", av("mum"), meta("16:02"))
              + row("Nairobi Sneaker Drop", "Amina: Size 42 is back in stock", tile("sneaker", 44), meta("15:30")) + "</div>")
frame(P_SH, "SH2d-Owner-inbox", "SH2 · The owner’s inbox",
      "Baraka’s Chats: private chats from his space are marked with a small label so they never mix with family, and a "
      "Spaces filter chip gathers them. The space’s own chat sits in his list like a group.",
      chats_title() + '<div style="display: flex; gap: 8px; padding: 12px 16px 6px;"><button type="button" class="chip">All</button><button type="button" class="chip">Unread</button><button type="button" class="chip">Groups</button><button type="button" class="chip chip-on">Spaces</button></div>'
      + owner_rows + tabbar("Chats"), tag=P3)

frame(P_SH, "SH2e-Pay-not-member", "SH2 · Paying a space you’re not in",
      "Only members can pay a space (§8 Q7). A visitor messaging the owner of a public space who types # gets the card with "
      "the reason and the way in, instead of Pay: “Follow to pay” for a public space, “Join to pay” (opening SM1) for a "
      "members-only one. Nothing is quoted, so no fee shows.",
      private_bar("baraka", "sneaker") + PRIV + "</div>"
      + mcard("Pay Nairobi Sneaker Drop", "KES 2,000", "15.48 USDC", [("For", "deposit, size 42 Jordans")], "Follow to Pay", tile("sneaker", 44),
              status="Only people who follow Nairobi Sneaker Drop can pay it. Following is free.", bottom=96, close=False)
      + composer("#2,000", "deposit, size 42 Jordans"), tag=P3)

# ================================================================ PAGE SR · Roles and moderation
P_SR = "SR"
admin_members = ('<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 6px;"><div class="sec">Team</div><div class="cx-list">'
                 + member_row("baraka", "@barakam", "Owner") + member_row(AMINA, "@amina.w", "Admin", ic("more", 18, 3)) + member_row("kevin", "@kevo", "Moderator", ic("more", 18, 3)) + "</div>"
                 + '<div class="sec" style="padding-top: 8px;">Members · 1,201</div><div class="cx-list">'
                 + member_row("juma", "@jumak · muted until 20:00", None, ic("more", 18, 3)) + member_row("faith", "@faithc", None, ic("more", 18, 3)) + member_row("njeri", "@njerim", None, ic("more", 18, 3)) + "</div></div>")
frame(P_SR, "SR1-Members-admin", "SR1 · Members, as an admin",
      "Space Settings → Members and roles. Search, the team with roles, then members; each row’s more button opens its "
      "popover (SR1b). A muted member says until when. Filters: All, Muted, Banned.",
      bar("Members", right=icon_btn("user_plus", "Invite people")) + search_bar("", "Search members", cancel=False)
      + '<div style="display: flex; gap: 8px; padding: 0 16px 10px;"><button type="button" class="chip chip-on">All</button><button type="button" class="chip">Muted · 1</button><button type="button" class="chip">Banned · 2</button></div>'
      + admin_members, bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR1b-Member-menu", "SR1 · A member’s popover",
      "Anchored to the row: change role, mute for a while, remove, ban. Mute opens its durations inline (1 hour, 24 hours, "
      "7 days, until unmuted). What each does is in SR2; the person is told only that they were muted or removed.",
      bar("Members", right=icon_btn("user_plus", "Invite people")) + search_bar("", "Search members", cancel=False)
      + '<div style="display: flex; gap: 8px; padding: 0 16px 10px;"><button type="button" class="chip chip-on">All</button><button type="button" class="chip">Muted · 1</button><button type="button" class="chip">Banned · 2</button></div>'
      + admin_members + '<div class="dim-behind"></div>'
      + pop([("Message Faith", None), ("Make Moderator", "shield"), ("Make Admin", "shield"), "-", ("Mute…", "mute"), ("Remove from Space", "user_minus", "pop-crit"), ("Ban", "ban", "pop-crit")],
            top=214, right=16, head="Faith Chebet · member"), bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR1c-Remove-confirm", "SR1 · Remove, confirmed in place",
      "Remove and Ban confirm inside the popover, not a sheet, saying what happens. In a members-only space the line adds "
      "“She paid until 4 Dec; removing doesn’t refund it. You can pay her back from your private chat.” Removed can follow again; banned can’t follow, join, ask or be invited.",
      bar("Members") + search_bar("", "Search members", cancel=False)
      + '<div style="display: flex; gap: 8px; padding: 0 16px 10px;"><button type="button" class="chip chip-on">All</button><button type="button" class="chip">Muted · 1</button><button type="button" class="chip">Banned · 2</button></div>'
      + admin_members + '<div class="dim-behind"></div>'
      + f'<div class="pop" role="alertdialog" aria-label="Remove Faith?" style="top: 400px; right: 16px; width: 280px; padding: 14px 16px; gap: 10px;">'
      + '<div style="font: 600 17px/22px var(--font-sans);">Remove Faith?</div><p class="small" style="font-size: 15px; line-height: 20px;">She leaves the space and can follow it again. Her posts stay.</p>'
      + f'<div style="display: flex; gap: 8px;">{btn("Cancel", "secondary", "sm", block=False)}{btn("Remove", "destructive", "sm", block=False)}</div></div>', bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR1d-Moderator-view", "SR1 · As a moderator",
      "Permission denied, shown before the tap: a moderator’s popover has only what they may do (Remove Post from the chat, "
      "Mute here), and one line says who can do the rest.",
      bar("Members") + search_bar("", "Search members", cancel=False) + admin_members + '<div class="dim-behind"></div>'
      + pop([("Message Faith", None), ("Mute…", "mute")], top=384, right=16, head="Faith Chebet · member", note="Only admins can remove people or change roles."), bg="var(--bg-grouped)", tag=P3)

def can_list(title, rl, items):
    return (f'<div style="display: flex; flex-direction: column; gap: 6px;"><div class="sec" style="display: flex; gap: 6px; align-items: center;">{title} {role(rl) if rl else ""}</div>'
            f'<div class="cx-list" style="padding: 10px 16px; gap: 6px;">' + "".join(f'<div style="display: flex; gap: 8px; font: 400 15px/20px var(--font-sans);">{ic("check", 15, 2.4)}<span>{t}</span></div>' for t in items) + "</div></div>")
frame(P_SR, "SR2-Roles", "SR2 · What each role can do",
      "From Members and roles → Roles. Plain lists, each role adding to the one below it. Only the owner signs for the "
      "wallet and changes the price; one owner per space.",
      bar("Roles") + '<div style="padding: 8px 16px 0; display: flex; flex-direction: column; gap: 12px;">'
      + can_list("Owner", "One per space", ["Everything admins can do", "Send money from the space wallet, with its PIN", "Price and who can join, appoint admins", "Delete the space"])
      + can_list("Admins", None, ["Everything moderators can do", "Remove and ban members, invite, accept requests", "Edit details and who can post", "See the space wallet (not send)"])
      + can_list("Moderators", None, ["Remove posts, mute members", "Handle reports"])
      + can_list("Members", None, ["Post (if everyone can), react, report", "Message the owner privately, pay the space"]) + "</div>", bg="var(--bg-grouped)", tag=P3)

REASONS = ["Spam or a scam", "Fake or counterfeit goods", "Harassment or bullying", "Hate or violence", "Sexual content", "Something else"]
frame(P_SR, "SR3-Report", "SR3 · Report a post",
      "From Report on a post (or Report Space in About). A full screen, short: the post quoted, one reason, optional detail, "
      "Also block. The reporter’s name isn’t shown to the space’s team; Circaso’s team sees it for rule-breaking reports.",
      bar("Report Post", left="cancel")
      + '<div style="padding: 10px 16px 0; display: flex; flex-direction: column; gap: 12px;">'
      + f'<div style="padding: 10px 12px; border-radius: var(--radius-md); background: var(--fill); display: flex; gap: 10px; align-items: flex-start;">{av("kevin", "sm")}<div style="font: 400 15px/20px var(--font-sans);"><b>Kevin</b> · Today<br>DM me for Jordans at half price, pay to my till first 🔥</div></div>'
      + '<div class="sec">What’s wrong with it?</div><div class="cx-list" role="radiogroup" aria-label="Reason">'
      + "".join(radio_row(r, None, i == 0) for i, r in enumerate(REASONS)) + "</div>"
      + f'<div style="display: flex; align-items: center; gap: 12px; padding: 4px;"><span style="flex: 1; font: 400 17px/22px var(--font-sans);">Also block Kevin</span><button type="button" class="sw" role="switch" aria-checked="false" aria-label="Also block Kevin"></button></div></div>'
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Send Report")}<p class="small" style="text-align: center;">The space’s team won’t see your name.</p></div>', bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR3b-Report-sent", "SR3 · Report sent",
      "Done: what happens next, in two lines. Reports are online-only; offline, Send Report is disabled with the reason "
      "(a report that might never arrive is worse than a clear wait).",
      bar("", left="close") + center(ic("check", 40, 2), "Thanks, Pasaka", "Nairobi Sneaker Drop’s moderators will look at it. If it breaks Circaso’s rules, our team will act too. We’ll tell you what happened.",
                                     btn("Done", "primary", "md", block=False), top=170), tag=P3)

def report_card(who, text, reason, n, when):
    return (f'<div role="group" aria-label="Report" style="padding: 14px 16px; border-radius: var(--radius-lg); background: var(--surface); box-shadow: inset 0 0 0 1px var(--separator); display: flex; flex-direction: column; gap: 10px;">'
            f'<div style="display: flex; justify-content: space-between; align-items: center;"><span style="font: 600 13px/18px var(--font-sans); color: var(--ink-secondary);">{reason} · {n} report{"s" if n > 1 else ""}</span><span class="cx-row-meta">{when}</span></div>'
            f'<div style="display: flex; gap: 10px;">{av(who, "sm")}<div style="font: 400 15px/20px var(--font-sans);"><b>{NAMES[who]}</b><br>{text}</div></div>'
            f'<div style="display: flex; gap: 8px; flex-wrap: wrap;">{btn("Keep", "secondary", "sm", block=False)}{btn("Remove Post", "secondary", "sm", block=False)}{btn("Mute", "secondary", "sm", block=False)}{btn("Remove " + NAMES[who], "destructive", "sm", block=False)}</div></div>')
frame(P_SR, "SR4-Reports", "SR4 · Reports queue",
      "Moderators and admins: open reports, the most reported first, each with the post, the reason and count, and one-tap "
      "actions. Acting closes it and tells the reporters. Done keeps a record of who did what.",
      bar("Reports") + f'<div style="padding: 4px 16px 12px;">{seg(["Open · 2", "Done"], 0, "Reports")}</div>'
      + '<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 10px;">'
      + report_card("kevin", "DM me for Jordans at half price, pay to my till first 🔥", "Spam or a scam", 4, "18:20")
      + report_card("otieno", "Anyone selling fake AF1s? Need 20 pairs for resale", "Fake or counterfeit goods", 1, "16:02") + "</div>", bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR4b-Reports-empty", "SR4 · No reports",
      "Empty queue: calm, one line. Loading uses the card shapes; an error shows Try Again with the reference.",
      bar("Reports") + f'<div style="padding: 4px 16px 12px;">{seg(["Open", "Done"], 0, "Reports")}</div>'
      + center(ic("shield", 40, 1.6), "No open reports", "When someone reports a post in Nairobi Sneaker Drop, it shows here for your moderators and admins.", "", top=150), bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR3c-Report-offline", "SR3 · Report, offline",
      "Reports are online-only: the form stays filled in, Send Report is disabled before the tap with the reason, and it wakes "
      "up when the connection is back.",
      bar("Report Post", left="cancel")
      + f'<div style="padding: 10px 16px 0; display: flex; flex-direction: column; gap: 12px;"><div>{banner("You’re offline. Reports need a connection.")}</div>'
      + f'<div style="padding: 10px 12px; border-radius: var(--radius-md); background: var(--fill); display: flex; gap: 10px; align-items: flex-start;">{av("kevin", "sm")}<div style="font: 400 15px/20px var(--font-sans);"><b>Kevin</b> · Today<br>DM me for Jordans at half price, pay to my till first 🔥</div></div>'
      + '<div class="sec">What’s wrong with it?</div><div class="cx-list" role="radiogroup" aria-label="Reason">'
      + "".join(radio_row(r, None, i == 0) for i, r in enumerate(REASONS)) + "</div></div>"
      + f'<div style="position: absolute; left: 16px; right: 16px; bottom: 34px; display: flex; flex-direction: column; gap: 8px;">{btn("Send Report", disabled=True)}<div class="cx-footnote">Needs a connection</div></div>', bg="var(--bg-grouped)", tag=P3)

sk_report = ('<div aria-hidden="true" style="padding: 14px 16px; border-radius: var(--radius-lg); background: var(--surface); display: flex; flex-direction: column; gap: 10px;">'
             '<span class="sk" style="width: 50%; height: 12px;"></span><div style="display: flex; gap: 10px;"><span class="sk" style="width: 28px; height: 28px; border-radius: 50%;"></span>'
             '<div style="flex: 1; display: flex; flex-direction: column; gap: 6px;"><span class="sk" style="width: 40%; height: 12px;"></span><span class="sk" style="width: 90%; height: 12px;"></span></div></div>'
             '<div style="display: flex; gap: 8px;"><span class="sk" style="width: 60px; height: 34px; border-radius: 999px;"></span><span class="sk" style="width: 110px; height: 34px; border-radius: 999px;"></span></div></div>')
frame(P_SR, "SR4c-Reports-loading", "SR4 · Reports, loading",
      "First open: skeletons in the report cards’ own shape, no spinner. Afterwards the queue opens from the phone and refreshes.",
      bar("Reports") + f'<div style="padding: 4px 16px 12px;">{seg(["Open", "Done"], 0, "Reports")}</div>'
      + f'<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 10px;" aria-busy="true" aria-label="Loading reports">{sk_report * 2}</div>', bg="var(--bg-grouped)", tag=P3)

frame(P_SR, "SR4d-Reports-error", "SR4 · Reports didn’t load",
      "The queue failed after retries and nothing is cached: plain words, Try Again, the reference. Offline it shows the "
      "cached queue with actions disabled (“Needs a connection”).",
      bar("Reports") + f'<div style="padding: 4px 16px 12px;">{seg(["Open", "Done"], 0, "Reports")}</div>'
      + '<div style="padding-top: 90px;">' + err_box("Reports didn’t load", "Something went wrong on our side. No report is lost.", "req_R4q8Vn") + "</div>", bg="var(--bg-grouped)", tag=P3)

# ================================================================ PAGE SW · The space wallet
P_SW = "SW"
def wallet_top(name, key=None, open_=False):
    glyph = tile(key, 22, " border-radius: 6px;") if key else f'<span style="display: flex;">{svg(TAB["Wallet"], 18, fill=True)}</span>'
    return (f'<div style="height: 44px; display: flex; justify-content: flex-end; align-items: center; gap: 8px; padding: 0 16px;">'
            f'{icon_btn("eye", "Hide balances")}<button type="button" class="wpill" aria-label="Wallet: {name}. Switch wallet" aria-expanded="{str(open_).lower()}">{glyph}'
            f'<span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{name}</span>{ic("chev_down", 14, 2.4)}</button></div>')

def ncard(net, kes, usdc, owner_line):
    return (f'<div style="position: relative; padding: 0 16px;"><div class="ncard-peek" style="right: -12px;"></div><div class="ncard" role="group" aria-label="{net}, KES {kes}">'
            f'<div style="display: flex; justify-content: space-between; align-items: center;"><span style="font: 600 15px/20px var(--font-sans);">{net}</span><span class="dim" style="font: 400 13px/18px var(--font-sans);">{owner_line}</span></div>'
            f'<div><div class="dim" style="font: 400 13px/18px var(--font-sans);">USDC</div><div style="font: 600 40px/44px var(--font-rounded); letter-spacing: -0.5px; font-variant-numeric: tabular-nums;"><span style="font-size: 20px;">KES </span>{kes}</div>'
            f'<div class="dim" style="font: 400 13px/18px var(--font-sans); font-variant-numeric: tabular-nums;">{usdc} USDC</div></div></div></div>')

def w_acts(items, off=()):
    return ('<div style="display: flex; justify-content: space-between; padding: 18px 24px 4px;">'
            + "".join('<button type="button" class="act"' + (' disabled aria-disabled="true" style="opacity: .4;"' if n in off else "") + f'><span class="act-c">{ic(i, 20, 1.9)}</span>{n}</button>' for i, n in items) + "</div>")

SPACE_RECENT = ('<div class="cx-list">'
                + row("Pasaka", "Payment · deposit, size 42 Jordans", av("pasaka"), '<span class="cx-row-amount cx-in">+KES 2,000</span>' + meta("18:09"))
                + row("Faith", "Payment · Air Force 1, size 39", av("faith"), '<span class="cx-row-amount cx-in">+KES 6,500</span>' + meta("14:30"))
                + row("Juma", "Sent · stock for Friday · fee KES 500", av("juma"), '<span class="cx-row-amount">−KES 50,500</span>' + meta("Mon")) + "</div>")
def space_wallet(caption, acts_off=(), banner_html="", pop_html="", recent=SPACE_RECENT, kes="210,600", usdc="1,630.03"):
    return (wallet_top("Sneaker Drop", "sneaker", bool(pop_html)) + banner_html
            + f'<div style="padding: 8px 20px 10px; font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);">{caption}</div>'
            + ncard("Base", kes, usdc, "Space wallet")
            + w_acts([("plus", "Add"), ("send", "Send"), ("down", "Receive"), ("bank", "Cash Out")], acts_off)
            + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 8px;"><div class="sec-row"><span style="font: 600 20px/25px var(--font-sans); letter-spacing: -0.45px;">Recent</span><button type="button" class="link">See All</button></div>'
            + recent + "</div>" + tabbar("Wallet") + pop_html)

frame(P_SW, "SW1-Space-wallet", "SW1 · Wallet tab, the space wallet",
      "Baraka’s phone (he owns the space). The one wallet design (D-M9): no title, the corner switcher names the wallet, the same metal card per network, actions "
      "and Recent. For a space: Add, Send (no approvals and no daily cap for spaces, §7a), Receive, Cash Out (coming). The "
      "caption leads with whose money it is. Payments in are named by what they were (Payment, Membership, Renewal).",
      space_wallet("Nairobi Sneaker Drop · space wallet · only you send"), bg="var(--bg-grouped)", tag=P3)

wallet_pop = ('<div class="dim-behind"></div><div class="pop" role="menu" aria-label="Wallets" style="top: 103px; right: 16px; width: 300px;"><div class="pop-head" style="font: 600 15px/20px var(--font-sans); color: var(--ink);">Wallets</div>'
              + "".join(f'<div role="menuitemradio" aria-checked="{str(on).lower()}" class="pop-item" style="min-height: 60px; gap: 12px;">{a}<div style="flex: 1; min-width: 0;"><div style="font: 600 15px/20px var(--font-sans);">{n}</div><div style="font: 400 13px/18px var(--font-sans); color: var(--ink-secondary);">{s}</div></div>'
                        + (ic("check", 16, 2.6).replace('class="cx-icon"', 'class="cx-icon" style="color: var(--ink); margin-left: 0;"') if on else "") + "</div>"
                        for a, n, s, on in [(av("baraka", "sm"), "Personal", "Your own money · KES 9,870", False),
                                            (av("Mwita Family", "sm"), "Mwita Family", "View only · payouts need approval · KES 14,200", False),
                                            (tile("sneaker", 28, " border-radius: 8px;"), "Nairobi Sneaker Drop", "Space wallet · you send · KES 210,600", True),
                                            (tile("runners", 28, " border-radius: 8px;"), "Karura Runners", "Space wallet · being set up", False)])
              + "</div>")
frame(P_SW, "SW1b-Switcher", "SW1 · Switching wallets",
      "Baraka’s phone. The corner opens D-M9’s popover (not a sheet): every wallet he can see, with what they can do and the last "
      "balance, a tick on the one on screen. A space wallet still being set up says so and opens Finish Setup.",
      space_wallet("Nairobi Sneaker Drop · space wallet · only you send", pop_html=wallet_pop), bg="var(--bg-grouped)", tag=P3)

frame(P_SW, "SW1c-Admin-view", "SW1 · An admin’s view",
      "Admins see the space wallet so the team can follow the money; only the owner sends. Send is off with the reason in the "
      "caption, never failed after a tap. Members don’t see the space wallet at all, only their own payments to it.",
      space_wallet("Nairobi Sneaker Drop · view only · only Baraka (owner) can send", acts_off=("Send", "Cash Out")), bg="var(--bg-grouped)", tag=P3)

frame(P_SW, "SW1d-Offline", "SW1 · Offline",
      "Balances from the phone, as of the time shown; every money action is off with the reason under the caption. Money never queues.",
      space_wallet("Nairobi Sneaker Drop · space wallet · only you send<br><span style=\"font-size: 12px; line-height: 16px;\">Offline · balances as of 10:42. Sending needs a connection.</span>", acts_off=("Add", "Send", "Cash Out")),
      bg="var(--bg-grouped)", tag=P3)

frame(P_SW, "SW1e-Loading", "SW1 · Opening the space wallet",
      "Switching to a wallet not read yet: the wallet’s own shape in skeletons (caption, card, actions, rows), crossfading "
      "in. The corner already names it.",
      wallet_top("Sneaker Drop", "sneaker") + '<div style="padding: 10px 20px 12px;" aria-busy="true" aria-label="Opening Nairobi Sneaker Drop’s wallet"><span class="sk" style="display: block; width: 70%; height: 14px;"></span></div>'
      + '<div style="padding: 0 16px;"><div class="sk" style="height: 196px; border-radius: 22px;"></div></div>'
      + '<div style="display: flex; justify-content: space-between; padding: 18px 24px 4px;">' + '<span class="sk" style="width: 58px; height: 58px; border-radius: 50%;"></span>' * 4 + "</div>"
      + f'<div style="padding: 24px 16px 0;"><div class="cx-list">{sk_row * 2}</div></div>' + tabbar("Wallet"), bg="var(--bg-grouped)", tag=P3)

frame(P_SW, "SW1f-Empty", "SW1 · A new space wallet",
      "Pasaka’s new space, just set up: zero on the card, Receive first, Send off until there is money (the caption says so). The empty line says how money arrives (members pay with # in their "
      "private chat, joining, renewals).",
      wallet_top("Yoga Collective", "yoga")
      + '<div style="padding: 8px 20px 10px; font: 400 15px/20px var(--font-sans); color: var(--ink-secondary);">Kilimani Yoga Collective · space wallet · only you send<br><span style="font-size: 12px; line-height: 16px;">Nothing to send yet.</span></div>'
      + ncard("Base", "0", "0.00", "Space wallet") + w_acts([("plus", "Add"), ("send", "Send"), ("down", "Receive"), ("bank", "Cash Out")], ("Send",))
      + '<div style="padding: 14px 16px 0; display: flex; flex-direction: column; gap: 8px;"><span style="font: 600 20px/25px var(--font-sans); letter-spacing: -0.45px; padding: 0 4px;">Recent</span>'
      + '<p class="small" style="padding: 0 4px; font-size: 15px; line-height: 20px;">No payments yet. Members pay when they join and renew, or with # in their private chat with you.</p></div>' + tabbar("Wallet"),
      bg="var(--bg-grouped)", tag=P3)

JUMA_ROWS = [("Paying from", wchip3("Nairobi Sneaker Drop", "sneaker", "baraka")), ("Network", "USDC on Base"), ("For", "stock for Friday"),
             ("Fee", 'KES 500 <span style="color: var(--ink-secondary); font-weight: 400;">· 1%</span>'), ("Total", "KES 50,500"), ("Juma receives", "KES 50,000")]
frame(P_SW, "SW2-Send-from-space", "SW2 · Paying a supplier from the space",
      "In Juma’s chat Baraka types “#50000 stock for Friday” and switches Paying from to the space wallet: the card re-quotes "
      "at the space rate (1%) and Pay sends directly (no approvals, no daily cap for spaces). The PIN is the space wallet’s, named.",
      bar("Juma", sub="Online", avatar=av("juma", "sm"))
      + '<div class="cx-thread" style="padding: 8px 16px 0;"><div class="cx-bubble cx-bubble-in">20 pairs ready for Friday. Send when you can</div></div>'
      + mcard("Send to Juma", "KES 50,000", "387.00 USDC", JUMA_ROWS, "Send KES 50,500", av("juma", "md"), foot=f"One Circaso fee, network cost included. {RATE3}", bottom=96, close=False)
      + composer("#50,000", "stock for Friday"), tag=P3)

frame(P_SW, "SW2b-Approve-space", "SW2 · Approve from the space wallet",
      "Circle’s PIN names the space wallet with its tile: “Approve from Nairobi Sneaker Drop”, its PIN, not the personal one. "
      "Fingerprint is offered when it’s on for this wallet (GW4, per wallet).",
      bar("Juma", sub="Online", avatar=av("juma", "sm"))
      + '<div class="cx-thread" style="padding: 8px 16px 0;"><div class="cx-bubble cx-bubble-in">20 pairs ready for Friday. Send when you can</div></div>'
      + mcard("Send to Juma", "KES 50,000", "387.00 USDC", JUMA_ROWS, "Waiting for Nairobi Sneaker Drop’s PIN…", av("juma", "md"), busy=True, bottom=96, close=False)
      + composer("#50,000", "stock for Friday")
      + approve_sheet("Nairobi Sneaker Drop", tile("sneaker", 44), "KES 50,500", "Juma", 4, "Nairobi Sneaker Drop’s PIN, not your personal one."), tag=P3)

# ================================================================ assemble
PAGES = [
    (P_S, "S · Spaces tab", "Phase 1",
     "The new tab between Chats and Wallet: <code>Chats | Spaces | Wallet | You</code>. Moments on top, spaces below. Phase 1 ships the Moments row with an honest card where spaces will go; the phase 3 version is shown for direction."),
    (P_M, "M · Moments composer", "Phase 1",
     "Camera, text, gallery and voice. Camera and composer screens sit on the dark stage in every theme, as the app's photo viewer does; their controls are glass over the picture."),
    (P_V, "V · Moments viewer", "Phase 1",
     "Full-screen, WhatsApp-style. Tap to move, hold to pause, swipe down to close. Replies are private chat messages that quote the moment."),
    (P_P, "P · Moments privacy", "Phase 1",
     "Under You → Privacy. The audience is chosen on the poster's phone; the server never learns anyone's contacts."),
    (P_G, "GW · Groups with wallets, fees and limits", "Phase 2 and 2F",
     "Chamas and groups become one Group. A wallet is optional (a switch at creation, or later from group info) and a target needs one. Every approval names the wallet. The Pay screen gains Paying from and one fee line (gas included); group daily caps show before signing."),
    (P_SD, "SD · Discover and search", "Phase 3",
     "The Spaces tab's spaces sections populated: your spaces (newest activity first, unread counts, Renew when a membership ends soon) and suggestions (public and members-only; contacts first, then popular in Kenya). Search covers spaces by name, topic and <code>@handle</code>, and people whose moments you can see. Invite-only spaces never appear."),
    (P_SS, "SS · The space page", "Phase 3",
     "One page for every join type: <strong>public</strong> (Follow; visitors read the latest posts), <strong>members only</strong> (the price, what members get, the chat behind a lock; Join opens the money card) and <strong>invite only</strong> (from a link: name and size only, Request to Join, pending). Chat, About and Members are a segmented control on the page. Following, notifications and other menus are popovers, not sheets."),
    (P_SN, "SN · Create a space", "Phase 3",
     "Three short full-screen steps (details with a live @handle check, who can join with the price and period for members only, chat and roles), then the space wallet's PIN (every space has a wallet, plan §7), setting up and live. Space Settings holds every later change; a price change applies from each member's next period."),
    (P_SM, "SM · Paid entry and membership", "Phase 3",
     "Joining a members-only space is the chat's money card on the space page (D-M3): everything on one card, Pay goes straight to the Circle PIN, the space fee (seed 1 %, §7b) on top. Renewals are never deducted: a reminder 3 days before (7 for yearly), a 3-day grace period, then the membership ends until paid again. The receipt lands in the private chat with the owner."),
    (P_SI, "SI · Invites", "Phase 3",
     "Admins invite from contacts or by <code>@username</code>; an invite is a card at the top of Chats with Accept and Decline in place, and expires after 14 days. People who open an invite-only link can ask to join; admins answer requests and see invites in one list."),
    (P_SH, "SH · Space chat and the private chat", "Phase 3",
     "<strong>How a space chat differs from a group chat:</strong> it isn't end-to-end encrypted (said on its first line and in About); the header is the space with its member count; every post shows the sender's name and role; reactions are counted; no read receipts or typing names; admins choose who posts (Everyone or Admins only); moderators remove posts (a tombstone stays) and mute people; <code>#</code> is plain text there. <strong>Message privately</strong> is a one-to-one, end-to-end encrypted chat with the owner, marked \"Private · via the space\" on both sides; money to a space and its receipts happen there."),
    (P_SR, "SR · Roles and moderation", "Phase 3",
     "Owner (one; signs for the wallet, price, appoints admins, deletes), admins (members, invites, requests, settings; see the wallet), moderators (remove posts, mute, reports). Member actions are popovers anchored to the row, with destructive confirms inside them. Reports go to the space's moderators and to Circaso; the reporter's name isn't shown to the space."),
    (P_SW, "SW · The space wallet", "Phase 3",
     "The one wallet design (D-M9, project-status \"Wallet tab\"): no title, the corner switcher and its popover, the metal card per network, the same actions and Recent. A space wallet sends directly (no approvals, no daily cap, §7a) and only its owner signs; admins see it, members don't. Paying from a space wallet in a chat is the same money card, with the space wallet's PIN named."),
]

DECISIONS = """
<ul>
<li><strong>Money leaves a group wallet only through an approved request</strong> (option A in the plan). When the owner switches Paying from to a group wallet, Send becomes Request Payout; the PIN signs only from an approved card (Pay Payout, amount locked). Space wallets have no approval rule and keep Send.</li>
<li><strong>Money always starts in the chat</strong>, exactly as today (C3): <code>#amount</code> becomes the chip, <code>@</code> picks the coin, the rest of the line is the note, and the glass preview's arrow opens a <strong>review sheet with nothing to type</strong>: who, amount, Paying from, network, the note as "For", fee, total. In a group with a wallet, <code>#</code> contributes to the group wallet (today <code>#</code> is off in groups). An approved payout's review opens from its D5 card instead.</li>
<li><strong>The review is the C4 sheet</strong> ("Send", "Pay Payout", "Request Payout", "Contribute"), over the chat it was opened from. <em>Superseded 2026-11-04 (D-M3): from a chat, the chat's money card is the review and Pay goes straight to the PIN; the GW sheet frames show its rows, and the phase 3 frames draw the card itself.</em> Once quoted, the fee and the rate are held for the challenge's 15 minutes, so the rate line counts that down instead of C4's 30-second refresh.</li>
<li><strong>The daily cap counts what leaves the wallet, fee included</strong>, over a rolling 24 hours. The server's <code>resetsAt</code> means "when this amount fits", not when the whole cap clears; the copy says "available from 14:30" accordingly.</li>
<li><strong>Creating a group, setting up a group wallet and adding a target are online-only</strong> (the server creates the group and needs the members' keys; Circle needs a live challenge). They are disabled offline with "Needs a connection", never failed after the tap. Recorded in <code>offline-first.md</code>.</li>
<li><strong>A group is kept when its wallet set-up fails or the connection drops mid-way.</strong> The wallet waits for the person (Finish Wallet Setup) and is never made silently.</li>
<li><strong>D1's "Who can spend from the wallet" stays</strong> as "Who approves payouts: 2 admins / Any admin" (2 admins by default; every admin if fewer, as today).</li>
<li><strong>The wallet chip shows only for people who own a group or space wallet.</strong> The personal wallet is named on approval only then; otherwise it keeps "Enter your PIN to send".</li>
<li><strong>The fee row shows the rate, or "minimum"</strong> when the gas floor applies; never a separate gas line. The button carries the total the person signs.</li>
<li><strong>Moment rings are monochrome</strong>: unseen is an ink ring, seen a quiet one; the accessible label says which. Ring 68, item 72: proposed as <code>CircasoSizes.momentRing</code> / <code>momentItem</code>.</li>
<li><strong>Text moment backgrounds reuse existing token values</strong>: Ink (ink), Metal (money-card metal), Paper (fill) and Graphite (ink-secondary) from the light theme, fixed rather than themed so a moment looks the same to everyone. Text is Sans Bold or Regular; the rounded face stays for money.</li>
<li><strong>Camera, composer and viewer are always on the dark stage</strong> in every theme, like the app's photo viewer; controls are glass with fixed white text. In high contrast the glass turns solid black at 0.8 and secondary white text goes to full white.</li>
<li><strong>LIVE is monochrome</strong> (action fill with a soft pulse), not the error red, so red keeps meaning "something is wrong".</li>
<li><strong>Posting a moment is a queueable write</strong> (not money): optimistic, Waiting offline, posts on reconnect; its 24 hours start when it posts.</li>
<li><strong>The Spaces tab icon</strong> is a ring with a centre and two small circles: people around a place, drawn from the Circaso mark.</li>
</ul>
<h3 id="dec-3">Phase 3, Spaces core (phase 0b)</h3>
<ul>
<li><strong>Money to a space happens in the private chat with its owner</strong>, never in the public space chat: <code>#</code> there pays the space wallet and the receipt card stays between the payer and the owner (a payment broadcast to 1,200 members would expose people). The money card is the same one as in chats (D-M3). Joining is the one exception: the card opens on the space page, because a non-member has no chat yet; its receipt still lands in the private chat.</li>
<li><strong>Entry fees and renewals are paid from the personal wallet only</strong> (a membership is the person's own), so the card's Paying from is fixed, with no chip. A group wallet could only pay by payout request; a space wallet paying for a personal membership would mix business and personal money.</li>
<li><strong>The price is set in USDC and shown in the payer's currency first</strong> ("KES 646", with "5.00 USDC a month · priced in USDC" under it), so the owner always receives what they set and the payer sees the KES of today, held for the 15-minute quote.</li>
<li><strong>Membership comes from the confirmed payment, never from the tap</strong>: the card says Joining until the transfer confirms; Not sent says nothing was taken, as G2.</li>
<li><strong>Renewal timing</strong>: reminder 3 days before the end of a monthly period, 7 days before a yearly one (push, a card in the private chat, a banner in the space); 3 days' grace in which everything still works; then the membership ends (chat locked, own posts kept, Rejoin opens the card). A price change applies from each member's next period and is announced the day it's made.</li>
<li><strong>Paid entry is for members-only spaces only</strong> in v1; invite-only spaces are free to join. Removing a paying member doesn't refund automatically; the confirm says so and the owner can pay them back from the private chat.</li>
<li><strong>Join requests</strong>: an invite-only space is hidden from search but its link or QR opens a minimal page (name, photo, size, a known member) with Request to Join; admins answer in Invites and Requests. A declined request can ask again after 30 days.</li>
<li><strong>Invites</strong> arrive as a card at the top of Chats (the inbox) and by push, expire after 14 days, and a decline is never announced to the inviter (it shows as Invited until it expires), so saying no costs nothing.</li>
<li><strong>Visitors read a public space's latest posts</strong> read-only before following; a members-only space shows only the shape of a chat behind a lock, never real posts.</li>
<li><strong>Message privately is open to anyone who can see the space</strong> (people ask before they pay); the owner can limit it to members in Space Settings. An invite-only space's owner is reachable by members only.</li>
<li><strong>Who sees the space wallet</strong>: the owner (signs) and admins (view only, Send off with the reason); members see only their own payments to it. Spaces send directly (no approvals, no daily cap, §7a), so Send stays Send.</li>
<li><strong>Roles</strong>: one owner; admins remove and ban members, invite, accept requests, edit details and who can post; moderators remove posts, mute (1 hour, 24 hours, 7 days, until unmuted) and handle reports. Remove lets the person follow again; Ban stops follow, join, requests and invites.</li>
<li><strong>Reports</strong> go to the space's moderators and admins and to Circaso's review queue; the reporter's name isn't shown to the space's team. Sending a report is online-only (disabled offline with the reason), so a report never silently fails to arrive.</li>
<li><strong>Space chat posts and reactions are queueable writes</strong> (not money, not end-to-end encrypted): they wait offline with a clock, as chat messages do. Follow, Join, Request, Accept, Invite, create and every role change are online-only and disabled offline before the tap.</li>
<li><strong>No bottom sheets for menus in spaces</strong>: Following, a post's long-press, a member's actions and the wallet switcher are popovers anchored to what opened them; destructive confirms happen inside the popover. The only sheet left is Circle's PIN, as GW11.</li>
<li><strong>Creating a space is three full-screen steps</strong> with a progress bar, ending in the space wallet's PIN; the space exists after step 3 and goes live once its wallet is set up. Accounts younger than 7 days can't create one (proposed spam rule, said with the date); at most 3 a day.</li>
<li><strong>A default cover drawn from tokens</strong> (the brand's rings on a quiet fill) until the owner adds a photo, so a new space never looks broken; space photos are rounded squares, people are circles.</li>
<li><strong>Private chats from a space carry a small "Private · {space}" label</strong> in the owner's inbox, and Chats gains a Spaces filter chip for owners and admins, so business chats never mix with family.</li>
<li><strong>One topic list</strong> everywhere (create, Discover chips, search): Business, Shopping, Farming, Health, Faith, Sport, Books, Chamas, Music, Other.</li>
<li><strong>A ban also stops private messages to the owner</strong> (it has to end harassment); a banned person can raise it with Circaso through Asha. A removed person can follow again and message as before.</li>
<li><strong>Join, leave and renewal lines in a space chat are seen only by that person</strong>; other members never learn who paid. The space’s member count changes, nothing else.</li>
<li><strong>Leaving a paid membership confirms in the popover</strong> (access ends now, no refund for the rest of the period); leaving a public space doesn’t confirm (Unfollow with Undo).</li>
<li><strong>A space whose wallet set-up failed is kept but hidden</strong> until its wallet exists, with Finish Wallet Setup (as GW5b for groups).</li>
<li><strong>Non-members can’t pay a space</strong>: the card shows the reason and Follow to Pay (public) or Join to Pay (members only) in place of Pay, with no quote.</li>
<li><strong>Rows show a members-only price as USDC with KES beside it</strong> ("5 USDC (KES 646) a month"), as the plan asks; the join card leads with KES because that is what the person compares with their balance.</li>
<li><strong>Removed posts leave a tombstone</strong> ("Removed by a moderator") so replies still read; the moderator isn't named.</li>
</ul>
"""

def esc_title(t):
    return t.replace("&", "&amp;")

parts = [head_css, EXTRA_CSS, GALLERY_CSS, "</style>\n"]
nav = "".join(f'<a href="#p-{pid}">{title.split(" · ")[0]} · {title.split(" · ")[1]}</a>' for pid, title, _, _ in PAGES)
parts.append(f'<div class="top"><div class="top-in"><div class="brand">Circaso <small>Spaces · phase 0a and 0b design · {len(FRAMES)} frames</small></div>'
             f'<div class="ctrls"><div class="seg" role="group" aria-label="Zoom"><button type="button" data-z="0.44">S</button><button type="button" data-z="0.62" aria-pressed="true">M</button><button type="button" data-z="0.8">L</button></div>'
             f'<div class="seg" role="group" aria-label="Screen appearance"><button type="button" data-a="light" aria-pressed="true">Light</button><button type="button" data-a="dark">Dark</button><button type="button" data-a="light-hc">Light HC</button><button type="button" data-a="dark-hc">Dark HC</button></div></div>'
             f'<nav class="nav">{nav}<a href="#p-dec">Decisions</a></nav></div></div>')
parts.append('<main style="padding-top:40px">\n<div class="lede"><h1>Spaces, Moments and group wallets.</h1>'
             '<p>Phase 0a frames for Spaces phases 1 and 2, and phase 0b frames for phase 3 (Spaces core: discover, the space page, create, paid entry, invites, space chat, roles and moderation, the space wallet), in the Circaso design system (tokens and components copied from the canvas). Every flow has its empty, loading, offline and error states. Plan: <code>references/spaces-plan.md</code>.</p></div>\n')
for pid, title, phase, intro in PAGES:
    fr = [f for f in FRAMES if f[0] == pid]
    parts.append(f'<section class="page" id="p-{pid}"><header class="ph"><h2>{title}</h2><span class="count">{len(fr)} screens · {phase}</span></header><div class="intro"><p>{intro}</p></div><div class="grid">')
    for _, fid, ftitle, desc, html, tag in fr:
        t = f'<span class="tag">{tag}</span>' if tag else ""
        parts.append(f'<figure class="card" id="c-{fid}"><figcaption><span class="ct">{esc_title(ftitle)}{t}</span><span class="cf">{fid}.dc.html</span></figcaption>'
                     f'<div class="frame" style="--w:390px;--h:844px"><div class="scr" id="s-{fid}" style="width:390px;height:844px">\n{html}\n</div></div>'
                     f'<p class="desc">{desc}</p></figure>')
    parts.append("</div></section>\n")
parts.append(f'<section class="page" id="p-dec"><header class="ph"><h2>Design decisions in the gaps</h2></header><div class="decisions">{DECISIONS}</div></section>\n</main>\n')
parts.append("""<script>
(function(){
var P=%s;
document.querySelectorAll('img[data-ph]').forEach(function(i){var s=P[i.getAttribute('data-ph')]; if(s) i.src=s;});
var root=document.documentElement;
function setZ(z){root.style.setProperty('--z',z);document.querySelectorAll('[data-z]').forEach(function(b){b.setAttribute('aria-pressed',String(b.getAttribute('data-z')===z));});try{localStorage.setItem('cx-z',z)}catch(e){}}
function setA(a){document.querySelectorAll('.scr [data-tx]').forEach(function(e){e.setAttribute('data-theme',a);});document.querySelectorAll('[data-a]').forEach(function(b){b.setAttribute('aria-pressed',String(b.getAttribute('data-a')===a));});try{localStorage.setItem('cx-a',a)}catch(e){}}
document.querySelectorAll('[data-z]').forEach(function(b){b.addEventListener('click',function(){setZ(b.getAttribute('data-z'))})});
document.querySelectorAll('[data-a]').forEach(function(b){b.addEventListener('click',function(){setA(b.getAttribute('data-a'))})});
var z=null,a=null;try{z=localStorage.getItem('cx-z');a=localStorage.getItem('cx-a')}catch(e){}
if(z&&window.innerWidth>520)setZ(z); if(a)setA(a);
})();
</script>
""" % photos)

OUT.write_text("".join(parts))
shutil.copyfile(OUT, COPY)
print(f"{len(FRAMES)} frames -> {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB), copy in assets/")
