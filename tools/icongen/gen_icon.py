#!/usr/bin/env python3
"""v2.13 launcher icon generator (Midnight Glass).

One design, two outputs:
  * Android VectorDrawables (adaptive icon background / foreground / monochrome)
  * SVG -> PNG (cairosvg) for the legacy mipmaps (API 24-25) and a 512 px preview.

Design (108 x 108 adaptive canvas, foreground kept inside the 66 dp safe circle):
  background: midnight-blue -> near-black gradient + violet/blue radial glows + accent bloom behind the screen
  foreground: a stacked back glass card (library depth), a frosted glass "screen" with hairline + top sheen,
              a glowing glass play triangle, and a seek bar with an accent progress and a glowing white thumb.
No letters, no text (the app name may change).

Run: python3 tools/icongen/gen_icon.py <repo root> [preview.png]
"""
import os
import sys

import cairosvg

ACCENT = "8FA8FF"   # Midnight Glass primary
SECOND = "B79CFF"   # secondary (violet)
TERT = "7DD3FC"     # tertiary (sky)


def c(hex6, a=1.0):
    return (hex6, a)


# ---------------------------------------------------------------- elements
# fill / stroke specs:
#   ("solid", (hex, alpha))
#   ("linear", x1, y1, x2, y2, [(offset, (hex, alpha)), ...])
#   ("radial", cx, cy, r, [(offset, (hex, alpha)), ...])

def rrect(x, y, w, h, r):
    return (f"M{x + r},{y}H{x + w - r}A{r},{r} 0 0 1 {x + w},{y + r}V{y + h - r}"
            f"A{r},{r} 0 0 1 {x + w - r},{y + h}H{x + r}A{r},{r} 0 0 1 {x},{y + h - r}"
            f"V{y + r}A{r},{r} 0 0 1 {x + r},{y}Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy}A{r},{r} 0 1 1 {cx + r},{cy}A{r},{r} 0 1 1 {cx - r},{cy}Z"


def rounded_triangle(pts, r):
    """Triangle with rounded corners (quadratic corners), as a path."""
    import math
    n = len(pts)
    segs = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        def toward(a, b, d):
            dx, dy = b[0] - a[0], b[1] - a[1]
            L = math.hypot(dx, dy)
            return (a[0] + dx / L * d, a[1] + dy / L * d)
        a = toward(p1, p0, r)
        b = toward(p1, p2, r)
        segs.append((a, p1, b))
    d = f"M{segs[0][2][0]:.2f},{segs[0][2][1]:.2f}"
    for i in range(1, n + 1):
        a, p, b = segs[i % n]
        d += f"L{a[0]:.2f},{a[1]:.2f}Q{p[0]:.2f},{p[1]:.2f} {b[0]:.2f},{b[1]:.2f}"
    return d + "Z"


# Geometry
SX, SY, SW, SH, SR = 29, 32, 50, 34, 8.5          # glass screen
BX, BY, BW, BH, BR = 36, 26.5, 36, 12, 6.5        # stacked back card (peeks above the screen)
PC = (54.8, 49)                                    # play centre
TRI = [(49.5, 41.0), (49.5, 57.0), (63.0, 49.0)]
BAR_Y, BAR_X0, BAR_X1, BAR_H = 74.5, 33, 75, 3.0
THUMB_X = 58.5

BACKGROUND = [
    (rrect(0, 0, 108, 108, 0), ("linear", 0, 0, 0, 108, [(0, c("101A44")), (0.55, c("0A0E26")), (1, c("05060F"))]), None),
    (rrect(0, 0, 108, 108, 0), ("radial", 22, 18, 70, [(0, c("5B47C9", 0.55)), (1, c("5B47C9", 0))]), None),
    (rrect(0, 0, 108, 108, 0), ("radial", 92, 96, 66, [(0, c("2F64C8", 0.50)), (1, c("2F64C8", 0))]), None),
    (rrect(0, 0, 108, 108, 0), ("radial", 54, 50, 36, [(0, c(ACCENT, 0.30)), (1, c(ACCENT, 0))]), None),
]

FOREGROUND = [
    # back card (depth: a second "screen" behind, violet-tinted glass)
    (rrect(BX, BY, BW, BH, BR), ("linear", BX, BY, BX + BW, BY + BH, [(0, c(SECOND, 0.34)), (1, c(ACCENT, 0.20))]),
     ("linear", 0, BY, 0, BY + 6, [(0, c("FFFFFF", 0.45)), (1, c("FFFFFF", 0.10))]), 0.8),
    # soft shadow under the screen
    (rrect(SX + 1, SY + 2.5, SW - 2, SH, SR), ("solid", c("02030A", 0.50)), None),
    # deep midnight base so the glass reads as one luminous pane (nothing shows through muddily)
    (rrect(SX, SY, SW, SH, SR), ("linear", 0, SY, 0, SY + SH, [(0, c("2A3270", 0.92)), (1, c("141A44", 0.92))]), None),
    # screen: frosted glass fill (accent-tinted top-left -> clear bottom-right)
    (rrect(SX, SY, SW, SH, SR), ("linear", SX, SY, SX + SW, SY + SH,
                                 [(0, c("C9D4FF", 0.34)), (0.5, c(ACCENT, 0.18)), (1, c(SECOND, 0.14))]), None),
    # inner glow behind the play triangle
    (rrect(SX, SY, SW, SH, SR), ("radial", PC[0] - 1, PC[1], 22, [(0, c(ACCENT, 0.55)), (1, c(ACCENT, 0))]), None),
    # top sheen (upper half, fades out)
    (rrect(SX + 1, SY + 1, SW - 2, SH * 0.48, SR - 1),
     ("linear", 0, SY, 0, SY + SH * 0.48, [(0, c("FFFFFF", 0.20)), (1, c("FFFFFF", 0))]), None),
    # hairline edge (bright top, dim bottom)
    (rrect(SX + 0.5, SY + 0.5, SW - 1, SH - 1, SR - 0.5), None,
     ("linear", SX, SY, SX + SW * 0.4, SY + SH, [(0, c("FFFFFF", 0.75)), (0.45, c("FFFFFF", 0.22)), (1, c("FFFFFF", 0.10))]), 1.0),
    # play triangle: glow halo + white glass triangle
    (circle(PC[0] - 1, PC[1], 11), ("radial", PC[0] - 1, PC[1], 11, [(0, c("DDE4FF", 0.35)), (1, c("DDE4FF", 0))]), None),
    (rounded_triangle(TRI, 2.6), ("linear", 49.5, 41, 60, 57, [(0, c("FFFFFF")), (1, c("D6DEFF"))]), None),
    # seek bar: track, accent progress, thumb glow, thumb
    (rrect(BAR_X0, BAR_Y - BAR_H / 2, BAR_X1 - BAR_X0, BAR_H, BAR_H / 2), ("solid", c("FFFFFF", 0.22)), None),
    (rrect(BAR_X0, BAR_Y - BAR_H / 2, THUMB_X - BAR_X0, BAR_H, BAR_H / 2),
     ("linear", BAR_X0, 0, THUMB_X, 0, [(0, c(SECOND)), (1, c(TERT))]), None),
    (circle(THUMB_X, BAR_Y, 7.0), ("radial", THUMB_X, BAR_Y, 7.0, [(0, c(TERT, 0.65)), (1, c(TERT, 0))]), None),
    (circle(THUMB_X, BAR_Y, 3.3), ("solid", c("FFFFFF")), None),
]

# Monochrome (themed icons, Android 13): the system tints it; alpha builds the depth.
MONO = [
    (rrect(BX, BY, BW, BH, BR), ("solid", c("FFFFFF", 0.40)), None),
    (rrect(SX, SY, SW, SH, SR), ("solid", c("FFFFFF", 1.0)), None),  # screen body (triangle is cut out below)
    (rrect(BAR_X0, BAR_Y - BAR_H / 2, BAR_X1 - BAR_X0, BAR_H, BAR_H / 2), ("solid", c("FFFFFF", 0.40)), None),
    (rrect(BAR_X0, BAR_Y - BAR_H / 2, THUMB_X - BAR_X0, BAR_H, BAR_H / 2), ("solid", c("FFFFFF")), None),
    (circle(THUMB_X, BAR_Y, 3.3), ("solid", c("FFFFFF")), None),
]
# Screen with the play triangle punched out (evenOdd), so it reads at tiny sizes.
MONO[1] = (rrect(SX, SY, SW, SH, SR) + rounded_triangle(TRI, 2.6), ("solid", c("FFFFFF")), None, None, "evenOdd")


# ---------------------------------------------------------------- writers
def _hexa(col):
    h, a = col
    return f"#{int(round(a * 255)):02X}{h}"


def _el(e):
    path, fill, stroke = e[0], e[1], e[2]
    width = e[3] if len(e) > 3 and e[3] is not None else 1.0
    rule = e[4] if len(e) > 4 else None
    return path, fill, stroke, width, rule


def to_vector(elements, size_dp=108):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- GENERATED by tools/icongen/gen_icon.py (v2.13 launcher icon). Do not hand-edit. -->',
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{size_dp}dp" android:height="{size_dp}dp" android:viewportWidth="108" android:viewportHeight="108">']
    for e in elements:
        path, fill, stroke, width, rule = _el(e)
        attrs = [f'android:pathData="{path}"']
        if rule:
            attrs.append(f'android:fillType="{rule}"')
        inner = []
        if fill and fill[0] == "solid":
            attrs.append(f'android:fillColor="{_hexa(fill[1])}"')
        elif fill:
            inner.append(_grad_vec("android:fillColor", fill))
        if stroke:
            attrs.append(f'android:strokeWidth="{width}"')
            if stroke[0] == "solid":
                attrs.append(f'android:strokeColor="{_hexa(stroke[1])}"')
            else:
                inner.append(_grad_vec("android:strokeColor", stroke))
        if inner:
            out.append("    <path " + " ".join(attrs) + ">")
            out.extend(inner)
            out.append("    </path>")
        else:
            out.append("    <path " + " ".join(attrs) + " />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def _grad_vec(name, g):
    if g[0] == "linear":
        _, x1, y1, x2, y2, stops = g
        head = (f'<gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                f'android:endX="{x2}" android:endY="{y2}">')
    else:
        _, cx, cy, r, stops = g
        head = f'<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}">'
    items = "".join(f'<item android:offset="{o}" android:color="{_hexa(col)}" />' for o, col in stops)
    return f'        <aapt:attr name="{name}">{head}{items}</gradient></aapt:attr>'


def to_svg(layers, clip=None, view=(0, 0, 108, 108)):
    defs, body, n = [], [], [0]

    def grad(g):
        n[0] += 1
        gid = f"g{n[0]}"
        if g[0] == "linear":
            _, x1, y1, x2, y2, stops = g
            head = f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
            tail = "</linearGradient>"
        else:
            _, cx, cy, r, stops = g
            head = f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">'
            tail = "</radialGradient>"
        s = "".join(f'<stop offset="{o}" stop-color="#{col[0]}" stop-opacity="{col[1]}"/>' for o, col in stops)
        defs.append(head + s + tail)
        return f"url(#{gid})"

    def paint(p):
        if p is None:
            return "none"
        if p[0] == "solid":
            return f'#{p[1][0]}" {"fill" if False else ""}'
        return grad(p)

    for elements in layers:
        for e in elements:
            path, fill, stroke, width, rule = _el(e)
            a = [f'd="{path}"']
            if fill is None:
                a.append('fill="none"')
            elif fill[0] == "solid":
                a.append(f'fill="#{fill[1][0]}" fill-opacity="{fill[1][1]}"')
            else:
                a.append(f'fill="{grad(fill)}"')
            if rule == "evenOdd":
                a.append('fill-rule="evenodd"')
            if stroke is not None:
                if stroke[0] == "solid":
                    a.append(f'stroke="#{stroke[1][0]}" stroke-opacity="{stroke[1][1]}"')
                else:
                    a.append(f'stroke="{grad(stroke)}"')
                a.append(f'stroke-width="{width}"')
            body.append("<path " + " ".join(a) + "/>")
    clip_open = clip_close = ""
    if clip:
        defs.append(f'<clipPath id="clip"><path d="{clip}"/></clipPath>')
        clip_open, clip_close = '<g clip-path="url(#clip)">', "</g>"
    vb = " ".join(str(v) for v in view)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="512" height="512">'
            f'<defs>{"".join(defs)}</defs>{clip_open}{"".join(body)}{clip_close}</svg>')


def render(svg, path, px):
    cairosvg.svg2png(bytestring=svg.encode(), write_to=path, output_width=px, output_height=px)


def main():
    root = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    res = os.path.join(root, "app/src/main/res")
    os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
    with open(os.path.join(res, "drawable/ic_launcher_background.xml"), "w") as f:
        f.write(to_vector(BACKGROUND))
    with open(os.path.join(res, "drawable/ic_launcher_fg.xml"), "w") as f:
        f.write(to_vector(FOREGROUND))
    with open(os.path.join(res, "drawable/ic_launcher_monochrome.xml"), "w") as f:
        f.write(to_vector(MONO))

    # Legacy mipmaps: the visible 72x72 centre of the adaptive canvas, masked.
    view = (18, 18, 72, 72)
    square = rrect(18, 18, 72, 72, 16)     # rounded square like most launchers
    roundc = circle(54, 54, 36)
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dens, px in sizes.items():
        d = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        render(to_svg([BACKGROUND, FOREGROUND], clip=square, view=view), os.path.join(d, "ic_launcher.png"), px)
        render(to_svg([BACKGROUND, FOREGROUND], clip=roundc, view=view), os.path.join(d, "ic_launcher_round.png"), px)
    if preview:
        os.makedirs(os.path.dirname(preview), exist_ok=True)
        render(to_svg([BACKGROUND, FOREGROUND], clip=square, view=view), preview, 512)
        base = preview[:-4]
        render(to_svg([BACKGROUND, FOREGROUND], clip=roundc, view=view), base + "-round.png", 512)
        render(to_svg([BACKGROUND, FOREGROUND]), base + "-full108.png", 512)
        render(to_svg([[(rrect(0, 0, 108, 108, 0), ("solid", c("1C1F2A")), None)], MONO], clip=roundc, view=view), base + "-mono.png", 256)
        render(to_svg([BACKGROUND, FOREGROUND], clip=square, view=view), base + "-48.png", 48)


if __name__ == "__main__":
    main()
