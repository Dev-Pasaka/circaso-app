#!/usr/bin/env python3
"""
Circaso coin icons: vendors the Web3 Icons mono set (npm `@web3icons/core`, MIT) into the app and the backend.

Decided 2026-11-06: coin icons follow Circaso's theme, so only the single-colour *mono* set is used, and every icon is
repainted at run time in a theme colour. This script is the one way the icons enter either repo:

  1. The app's bundled list (BUNDLED_COINS, BUNDLED_NETWORKS) becomes Compose path data in common code
     (`shared/.../designsystem/coins/BundledCoinIcons.kt`): instant and offline.
  2. Every mono token whose symbol passes the backend's allow-list (`[A-Z0-9.]{1,15}`) becomes a cleaned SVG in the
     backend's wallet resources (`coin-icons/{SYMBOL}.svg`), served by `GET /v1/assets/icons/{symbol}`, with an
     index (`symbols.txt`) and the licence beside them.

The package is downloaded data, not code: nothing in it is executed or imported. Each icon's SVG is read out of its
`*.svg.js` wrapper as a string literal, parsed, and rebuilt from an allow-list (svg, g, path; geometry, fill rule and
opacity only). An icon with anything else (filters, masks, clip paths, strokes, transforms, styles, scripts, entities,
another view box) is left out and listed at the end.

Reproduce (run Python isolated, from outside the package directory):

    mkdir -p "$TMP/w3i" && cd "$TMP/w3i" && npm pack @web3icons/core@4.0.58 && mkdir x && tar -xzf *.tgz -C x
    python3 -I scripts/coin-icons/generate.py --package "$TMP/w3i/x/package" \
        --app . --backend ../circaso   # either target may be left out
"""

import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

VERSION = "4.0.58"

# The app's bundled coins (upper-case symbols as the set names them). Aliases (WETH → ETH, MATIC → POL, cbBTC → BTC)
# are resolved in the app's `CoinSymbols`, so they need no icon of their own.
BUNDLED_COINS = ["USDC", "USDT", "EURC", "ETH", "POL", "SOL", "BTC", "WBTC", "AVAX"]

# Our chains' network marks: the app key → the set's file name.
BUNDLED_NETWORKS = {
    "base": "base",
    "ethereum": "ethereum",
    "polygon": "polygon",
    "solana": "solana",
    "avalanche": "avalanche",
    "arbitrum": "arbitrum-one",
    "arc": "arc",
}

SYMBOL = re.compile(r"^[A-Z0-9.]{1,15}$")
PATH_DATA = re.compile(r"^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]+$")
NUMBER = re.compile(r"^(0|1|0?\.\d+|1\.0+)$")
SVG_NS = "{http://www.w3.org/2000/svg}"
LITERAL = re.compile(r"=\s*'((?:[^'\\]|\\.)*)'", re.S)

LICENCE = """MIT License

Copyright (c) 2024 0xa3k5

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""


class Rejected(Exception):
    pass


def unescape_js(body: str) -> str:
    """The body of a single-quoted JS literal; only the escapes the build emits are accepted."""
    out, i = [], 0
    simple = {"n": "\n", "t": "\t", "r": "\r", "'": "'", '"': '"', "\\": "\\"}
    while i < len(body):
        ch = body[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        nxt = body[i + 1] if i + 1 < len(body) else ""
        if nxt not in simple:
            raise Rejected(f"escape \\{nxt}")
        out.append(simple[nxt])
        i += 2
    return "".join(out)


def read_svg(js_file: pathlib.Path) -> str:
    text = js_file.read_text(encoding="utf-8")
    if len(text) > 200_000:
        raise Rejected("too large")
    m = LITERAL.search(text)
    if not m:
        raise Rejected("no string literal")
    svg = unescape_js(m.group(1)).strip()
    if "<!" in svg or "<?" in svg:
        raise Rejected("doctype, entity or processing instruction")
    return svg


def opacity(value, where):
    if value is None:
        return 1.0
    if not NUMBER.match(value.strip()):
        raise Rejected(f"{where} opacity {value!r}")
    return float(value)


def clean(svg_text: str):
    """Returns the icon's paths as (d, even_odd, alpha), painted ones only."""
    try:
        root = ET.fromstring(svg_text)
    except ET.ParseError as e:
        raise Rejected(f"parse: {e}")
    if root.tag != SVG_NS + "svg":
        raise Rejected("root is not svg")
    allowed_root = {"width", "height", "viewBox", "fill", "class"}
    extra = set(root.attrib) - allowed_root
    if extra:
        raise Rejected(f"svg attributes {sorted(extra)}")
    if root.get("viewBox", "0 0 24 24") != "0 0 24 24":
        raise Rejected("view box")
    paths = []

    def walk(node, fill, alpha, rule):
        for child in node:
            tag = child.tag.replace(SVG_NS, "")
            if tag == "g":
                extra = set(child.attrib) - {"fill", "opacity", "fill-opacity", "fill-rule"}
                if extra:
                    raise Rejected(f"g attributes {sorted(extra)}")
                walk(
                    child,
                    child.get("fill", fill),
                    alpha * opacity(child.get("opacity"), "g") * opacity(child.get("fill-opacity"), "g"),
                    child.get("fill-rule", rule),
                )
            elif tag == "path":
                extra = set(child.attrib) - {"d", "fill", "fill-rule", "clip-rule", "opacity", "fill-opacity"}
                if extra:
                    raise Rejected(f"path attributes {sorted(extra)}")
                if len(child):
                    raise Rejected("path with children")
                d = " ".join((child.get("d") or "").split())
                if not d or not PATH_DATA.match(d) or len(d) > 60_000:
                    raise Rejected("path data")
                f = child.get("fill", fill)
                a = alpha * opacity(child.get("opacity"), "path") * opacity(child.get("fill-opacity"), "path")
                r = child.get("fill-rule", rule)
                if r not in ("nonzero", "evenodd"):
                    raise Rejected(f"fill rule {r!r}")
                if f == "none" or a <= 0:
                    continue
                if not re.match(r"^(#[0-9A-Fa-f]{3,8}|currentColor|black|white)$", f or ""):
                    raise Rejected(f"fill {f!r}")
                paths.append((d, r == "evenodd", round(a, 3)))
            else:
                raise Rejected(f"element <{tag}>")

    walk(root, root.get("fill", "#000"), 1.0, "nonzero")
    if not paths:
        raise Rejected("nothing painted")
    return paths


def to_svg(paths) -> str:
    """The cleaned icon, one colour (black, repainted by the app), the set's 24 × 24 box."""
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">']
    for d, even_odd, alpha in paths:
        attrs = f'd="{d}"'
        if even_odd:
            attrs += ' fill-rule="evenodd"'
        if alpha != 1.0:
            attrs += f' fill-opacity="{alpha:g}"'
        parts.append(f"<path {attrs}/>")
    parts.append("</svg>\n")
    return "".join(parts)


def load(directory: pathlib.Path):
    icons, skipped = {}, {}
    for js in sorted(directory.glob("*.svg.js")):
        name = js.name[: -len(".svg.js")]
        try:
            icons[name] = clean(read_svg(js))
        except Rejected as e:
            skipped[name] = str(e)
    return icons, skipped


def kotlin_string(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def write_app(app: pathlib.Path, tokens, networks):
    out = app / "shared/src/commonMain/kotlin/com/circaso/designsystem/coins/BundledCoinIcons.kt"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "// Generated by scripts/coin-icons/generate.py from @web3icons/core " + VERSION + " (mono set, MIT). Do not edit.",
        "package com.circaso.designsystem.coins",
        "",
        "/** The bundled coins' marks by upper-case symbol, as path data in the set's 24 × 24 box. */",
        "internal val BundledCoinPaths: Map<String, List<IconPath>> = mapOf(",
    ]
    for sym in BUNDLED_COINS:
        if sym not in tokens:
            sys.exit(f"bundled coin {sym} is missing or was rejected")
        lines.append(f"    {kotlin_string(sym)} to listOf(")
        for d, eo, a in tokens[sym]:
            lines.append(f"        IconPath({kotlin_string(d)}, evenOdd = {str(eo).lower()}, alpha = {a}f),")
        lines.append("    ),")
    lines += [")", "", "/** Our chains' network marks by key (`base`, `ethereum`, …). */", "internal val BundledNetworkPaths: Map<String, List<IconPath>> = mapOf("]
    for key, name in BUNDLED_NETWORKS.items():
        if name not in networks:
            sys.exit(f"bundled network {name} is missing or was rejected")
        lines.append(f"    {kotlin_string(key)} to listOf(")
        for d, eo, a in networks[name]:
            lines.append(f"        IconPath({kotlin_string(d)}, evenOdd = {str(eo).lower()}, alpha = {a}f),")
        lines.append("    ),")
    lines += [")", ""]
    out.write_text("\n".join(lines), encoding="utf-8")
    return out


def write_backend(backend: pathlib.Path, tokens):
    root = backend / "modules/wallet/impl/src/main/resources/coin-icons"
    root.mkdir(parents=True, exist_ok=True)
    for old in root.glob("*.svg"):
        old.unlink()
    served = sorted(s for s in tokens if SYMBOL.match(s))
    total = 0
    for sym in served:
        body = to_svg(tokens[sym])
        (root / f"{sym}.svg").write_text(body, encoding="utf-8")
        total += len(body.encode())
    (root / "symbols.txt").write_text("\n".join(served) + "\n", encoding="utf-8")
    (root / "LICENCE").write_text(
        f"Coin icons: Web3 Icons (https://github.com/0xa3k5/web3icons), @web3icons/core {VERSION}, mono set,\n"
        "cleaned and repainted in one colour by CircasoApp/scripts/coin-icons/generate.py.\n\n" + LICENCE,
        encoding="utf-8",
    )
    return root, served, total


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--package", required=True, type=pathlib.Path, help="the unpacked npm package (its package.json)")
    p.add_argument("--app", type=pathlib.Path)
    p.add_argument("--backend", type=pathlib.Path)
    args = p.parse_args()

    meta = json.loads((args.package / "package.json").read_text(encoding="utf-8"))
    if meta.get("name") != "@web3icons/core" or meta.get("version") != VERSION or meta.get("license") != "MIT":
        sys.exit(f"expected @web3icons/core {VERSION} (MIT), found {meta.get('name')} {meta.get('version')}")

    tokens, skipped_tokens = load(args.package / "dist/svgs/tokens/mono")
    networks, skipped_networks = load(args.package / "dist/svgs/networks/mono")
    print(f"tokens: {len(tokens)} clean, {len(skipped_tokens)} left out; networks: {len(networks)} clean, {len(skipped_networks)} left out")
    for name, why in sorted({**skipped_tokens, **{"network/" + k: v for k, v in skipped_networks.items()}}.items()):
        print(f"  left out {name}: {why}")

    if args.app:
        print("app:", write_app(args.app, tokens, networks))
    if args.backend:
        root, served, total = write_backend(args.backend, tokens)
        print(f"backend: {root} ({len(served)} icons, {total} bytes); not served (symbol outside the allow-list): "
              f"{len(tokens) - len(served)}")
        digest = hashlib.sha256("".join(sorted(served)).encode()).hexdigest()[:12]
        print("symbols digest:", digest)


if __name__ == "__main__":
    main()
