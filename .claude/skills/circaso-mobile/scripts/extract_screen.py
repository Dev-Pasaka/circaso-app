#!/usr/bin/env python3
"""Print one screen from the design canvas with embedded images stripped.

Usage: extract_screen.py <card-id-or-substring>   e.g. ID06-Enter-code, C4, A2-Welcome
       extract_screen.py --list
"""
import html, pathlib, re, sys

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "assets"
# every design canvas in assets/ (Circaso-Screens.html, Circaso-Offline-States.html, ...)
s = "".join(
    re.sub(r"data:[a-z/+\-]+;base64,[A-Za-z0-9+/=]+", "DATA", f.read_text(encoding="utf-8"))
    for f in sorted(ASSETS.glob("Circaso-*.html"))
)
def _cards():
    out = []
    for m in re.finditer(r'<figure class="card[^"]*"(?: id="c-([^"]+)")?>(.*?)</figure>', s, re.S):
        cid, body = m.group(1), m.group(2)
        if cid is None:  # the offline file has no card ids; use the screen's own id
            sid = re.search(r'class="scr" id="s-([^"]+)"', body)
            cid = sid.group(1) if sid else None
        if cid:
            out.append((cid, body))
    return out


cards = _cards()

if len(sys.argv) != 2:
    sys.exit(__doc__)
if sys.argv[1] == "--list":
    for cid, _ in cards:
        print(cid)
    sys.exit()
hits = [(c, b) for c, b in cards if sys.argv[1] == c] or [(c, b) for c, b in cards if sys.argv[1].lower() in c.lower()]
if not hits:
    sys.exit("no card matches; try --list")
for cid, body in hits:
    # drop the logo SVG defs; they repeat on every screen
    body = re.sub(r"<svg[^>]*aria-label=\"(?:Circaso mark|CoinX)\".*?</svg>", "<svg logo/>", body, flags=re.S)
    print(f"<!-- {cid} -->\n{body.strip()}\n")
