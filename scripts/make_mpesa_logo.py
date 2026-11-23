#!/usr/bin/env python3
"""
Makes the app's M-Pesa logo files from Safaricom's own artwork, without redrawing it.

Source: Safaricom's Brand Toolkit, Logos page (https://www.safaricom.co.ke/about/brand-toolkit/logos), the file
"M-PESA Logo" at https://www.safaricom.co.ke/images/lifeimages/M-PESA-logo-pdf.pdf (Adobe Illustrator, PDF/X-4,
created 2020-09-23, modified 2021-07-29), kept unchanged in assets/brand/mpesa/. Its page is pure vector: five filled
shapes (the flag's two pieces, the phone's two pieces, the letters). This script reads those paths exactly as drawn
(every coordinate and curve as in the file; PDF's `v`/`y` curves written out as full cubics, which is the same curve),
moves them so the logo's own outline starts at 0,0 (nothing scaled or stretched), and writes:

- assets/brand/mpesa/mpesa-logo.svg and the Compose resource mpesa_logo.xml: the logo as published (green letters,
  grey phone, red flag), for white or light ground. Its CMYK colours are turned into sRGB through the file's own
  output intent (U.S. Web Coated (SWOP) v2, relative colorimetric), which is how Illustrator shows them on screen.
- assets/brand/mpesa/mpesa-logo-reversed.svg and mpesa_logo_reversed.xml: Safaricom's reversed version, for green
  ground, as their toolkit publishes it in https://www.safaricom.co.ke/images/lifeimages/M-PESA-logo-2.png (white
  letters and phone, the flag in its red and shade): the same shapes, those colours.

M-PESA is a registered trademark of Safaricom PLC; the logo is used only to say that the money moves through M-Pesa.

Needs pypdf and Pillow (in a virtual environment: `python3 -m venv /tmp/v && /tmp/v/bin/pip install pypdf pillow`),
then `/tmp/v/bin/python scripts/make_mpesa_logo.py`. Rerun if Safaricom's file changes.
"""
import io
import os
import re
import sys

from PIL import Image, ImageCms
from pypdf import PdfReader

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "assets/brand/mpesa/M-PESA-logo-safaricom.pdf")
OUT_SVG = os.path.join(ROOT, "assets/brand/mpesa")
OUT_XML = os.path.join(ROOT, "shared/src/commonMain/composeResources/drawable")

# Safaricom's reversed logo (M-PESA-logo-2.png): what each part is coloured on green ground.
REVERSED = {"letters": "#FFFFFF", "phone": "#FFFFFF", "flag": "#CE383B", "flag-shade": "#834E44"}


def shapes(pdf):
    """The page's filled shapes in order: (cmyk, [subpaths of absolute points and curves]), PDF coordinates."""
    reader = PdfReader(pdf)
    page = reader.pages[0]
    height = float(page.mediabox.height)
    ops = page.get_contents().get_data().decode("latin1")
    tokens = re.findall(r"-?\d*\.?\d+|[A-Za-z*]+|/\w+", ops)
    stack, cmyk, origin, out, path = [], None, (0.0, 0.0), [], []
    cur = start = (0.0, 0.0)
    saved = []

    def pt(i):
        return (origin[0] + stack[i], origin[1] + stack[i + 1])

    for tok in tokens:
        if re.fullmatch(r"-?\d*\.?\d+", tok):
            stack.append(float(tok))
            continue
        if tok == "k":
            cmyk = tuple(stack[-4:])
        elif tok == "q":
            saved.append(origin)
        elif tok == "Q":
            origin = saved.pop()
        elif tok == "cm":
            a, b, c, d, e, f = stack[-6:]
            assert (a, b, c, d) == (1, 0, 0, 1), "only translations are expected"
            origin = (origin[0] + e, origin[1] + f)
        elif tok == "m":
            cur = start = pt(-2)
            path.append(["M", cur])
        elif tok == "l":
            cur = pt(-2)
            path.append(["L", cur])
        elif tok == "c":
            c1, c2, cur = pt(-6), pt(-4), pt(-2)
            path.append(["C", c1, c2, cur])
        elif tok == "v":  # first control point is the current point
            c1, c2, cur2 = cur, pt(-4), pt(-2)
            cur = cur2
            path.append(["C", c1, c2, cur])
        elif tok == "y":  # second control point is the end point
            c1, cur = pt(-4), pt(-2)
            path.append(["C", c1, cur, cur])
        elif tok == "h":
            path.append(["Z"])
            cur = start
        elif tok == "re":
            path = []  # the white page and the clip: not part of the logo
        elif tok in ("f", "f*"):
            if path:
                out.append((cmyk, tok == "f*", path))
            path = []
        elif tok == "n" or tok == "W" or tok == "gs" or tok.startswith("/"):
            path = [] if tok == "n" else path
        stack = [] if not tok.startswith("/") else stack
    return out, height, reader


def srgb(reader, cmyk):
    oi = reader.trailer["/Root"]["/OutputIntents"][0].get_object()
    profile = ImageCms.ImageCmsProfile(io.BytesIO(oi["/DestOutputProfile"].get_object().get_data()))
    px = Image.new("CMYK", (1, 1), tuple(round(v * 255) for v in cmyk))
    rgb = ImageCms.profileToProfile(px, profile, ImageCms.createProfile("sRGB"), renderingIntent=ImageCms.Intent.RELATIVE_COLORIMETRIC, outputMode="RGB")
    return "#%02X%02X%02X" % rgb.getpixel((0, 0))


def main():
    found, page_h, reader = shapes(SRC)
    # The order Illustrator wrote them in: flag shade, flag, phone (two pieces), the letters (the rest).
    parts = ["flag-shade", "flag", "phone", "phone"] + ["letters"] * (len(found) - 4)
    xs, ys = [], []
    for _, _, path in found:
        for seg in path:
            for p in seg[1:]:
                xs.append(p[0]); ys.append(page_h - p[1])
    left, top = min(xs), min(ys)
    width, height = max(xs) - left, max(ys) - top

    def d(path):
        s = []
        for seg in path:
            if seg[0] == "Z":
                s.append("Z")
                continue
            s.append(seg[0] + " ".join("%.3f,%.3f" % (p[0] - left, page_h - p[1] - top) for p in seg[1:]))
        return "".join(s)

    note = ("M-PESA logo, Safaricom PLC's artwork from its Brand Toolkit "
            "(https://www.safaricom.co.ke/images/lifeimages/M-PESA-logo-pdf.pdf), paths unchanged; made by "
            "scripts/make_mpesa_logo.py. M-PESA is a registered trademark of Safaricom PLC. Do not edit by hand.")
    for name, colours in (("mpesa-logo", None), ("mpesa-logo-reversed", REVERSED)):
        fills = [colours[part] if colours else srgb(reader, cmyk) for (cmyk, _, _), part in zip(found, parts)]
        svg = ['<?xml version="1.0" encoding="UTF-8"?>', f"<!-- {note} -->",
               f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width:.3f} {height:.3f}" width="{width:.3f}" height="{height:.3f}">']
        xml = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {note} -->",
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               f'    android:width="{width / height * 24:.2f}dp" android:height="24dp" android:viewportWidth="{width:.3f}" android:viewportHeight="{height:.3f}">']
        for (cmyk, evenodd, path), fill in zip(found, fills):
            svg_rule = ' fill-rule="evenodd"' if evenodd else ""
            xml_rule = ' android:fillType="evenOdd"' if evenodd else ""
            svg.append(f'  <path d="{d(path)}" fill="{fill}"{svg_rule}/>')
            xml.append(f'    <path android:pathData="{d(path)}" android:fillColor="{fill}"{xml_rule} />')
        svg.append("</svg>")
        xml.append("</vector>")
        with open(os.path.join(OUT_SVG, name + ".svg"), "w") as f:
            f.write("\n".join(svg) + "\n")
        with open(os.path.join(OUT_XML, name.replace("-", "_") + ".xml"), "w") as f:
            f.write("\n".join(xml) + "\n")
        print(name, f"{width:.2f} x {height:.2f}", fills)


if __name__ == "__main__":
    sys.exit(main())
