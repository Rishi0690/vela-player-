#!/usr/bin/env python3
"""v2.14: widget-picker preview images (static, Midnight Glass palette) mirroring the real widget layouts.

The launcher's widget picker can only show a static picture before a widget exists, so these are drawn here with the
same structure/colours as WidgetRenderer (glass layers, accent orb, artwork from tools/artgen). In the app itself the
gallery shows LIVE previews rendered from the real layouts and data instead.
Usage: python3 tools/widgetgen/gen_previews.py . [showcase.png]
"""
import os, sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

root = sys.argv[1] if len(sys.argv) > 1 else "."
res = os.path.join(root, "app/src/main/res")
out_dir = os.path.join(res, "drawable-nodpi")
S = 3  # px per dp while drawing
TOP, BOT = (6, 9, 21), (25, 33, 76)
GLOWA, GLOWB = (91, 71, 201), (47, 100, 200)
ACC, ONACC = (181, 196, 255), (3, 41, 122)
TXT, DIM = (235, 237, 246), (154, 161, 188)
# VELA_THEME=ember|light: showcase only (shows theme sync); the picker images always use Midnight Glass.
THEME = os.environ.get("VELA_THEME", "")
if THEME == "ember":
    TOP, BOT = (12, 6, 6), (58, 22, 18); GLOWA, GLOWB = (220, 90, 50), (200, 60, 90)
    ACC, ONACC = (255, 181, 150), (92, 26, 6); TXT, DIM = (246, 236, 233), (190, 160, 152)
elif THEME == "light":
    TOP, BOT = (246, 248, 252), (214, 222, 240); GLOWA, GLOWB = (170, 182, 240), (180, 194, 238)
    ACC, ONACC = (52, 84, 200), (255, 255, 255); TXT, DIM = (20, 24, 38), (93, 99, 120)
STROKE = (27, 33, 51, 34) if THEME == "light" else (255, 255, 255, 52)
TILE = (255, 255, 255, 175) if THEME == "light" else (255, 255, 255, 22)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONTB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
def font(sz, bold=False):
    try: return ImageFont.truetype(FONTB if bold else FONT, int(sz * S))
    except Exception: return ImageFont.load_default()

def mix(a, b, t): return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))

def rr_mask(w, h, r):
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255); return m

CONTENT = False  # True: draw only the widget's content on a transparent canvas (for the Android 12+ previewLayout)
OPACITY = 0.88

def glass(w, h, r=24, opacity=None):
    W, H, R = w * S, h * S, r * S
    if CONTENT: return Image.new("RGBA", (W, H), (0, 0, 0, 0))
    if opacity is None: opacity = OPACITY
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    t = (yy / H)[..., None]
    bottom = np.array(mix(BOT, GLOWA, 0.1), np.float32)
    col = np.array(TOP, np.float32) * (1 - t) + bottom * t
    def pool(cx, cy, rad, c, a):
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / rad
        return np.clip(1 - d, 0, 1)[..., None] ** 1.6 * a, np.array(c, np.float32)
    for (cx, cy, rad, c, a) in [(0.14 * W, 0, 0.95 * min(W, H) * 1.3, GLOWA, 0.5), (0.95 * W, H, 0.8 * min(W, H) * 1.3, GLOWB, 0.35)]:
        k, cc = pool(cx, cy, rad, c, a)
        col = col * (1 - k) + cc * k
    sheen = np.clip(1 - yy / (0.32 * H), 0, 1)[..., None] * 0.12
    col = col * (1 - sheen) + 255 * sheen
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    a = rr_mask(W, H, R).point(lambda v: int(v * opacity))
    img.putalpha(a)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], R, outline=STROKE, width=max(1, S))
    return img

ART = None
def art_thumb(w, h, r, seed=0, progress=None, dur=None):
    """Abstract cinematic frame (the Vela artwork, tinted) standing in for a video thumbnail."""
    global ART
    if ART is None:
        ART = Image.open(os.path.join(out_dir, "art_luminous_forms.webp")).convert("RGBA")
    W, H = int(w * S), int(h * S)
    hues = [((32, 24, 70), (180, 120, 255)), ((10, 40, 60), (90, 200, 230)), ((60, 22, 30), (255, 140, 110)), ((14, 40, 30), (120, 230, 170)), ((40, 30, 12), (255, 200, 110)), ((20, 22, 50), (150, 170, 255))]
    base, light = hues[seed % len(hues)]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    t = ((xx / W) * 0.6 + (yy / H) * 0.4)[..., None]
    col = np.array(base, np.float32) * (1 - t) + np.array(mix(base, light, 0.45), np.float32) * t
    d = np.sqrt((xx - W * (0.3 + 0.4 * (seed % 3) / 2)) ** 2 + (yy - H * 0.35) ** 2) / (0.7 * W)
    k = np.clip(1 - d, 0, 1)[..., None] ** 2 * 0.7
    col = col * (1 - k) + np.array(light, np.float32) * k
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    a = ART.resize((W, int(W * ART.height / ART.width)))
    tint = Image.new("RGBA", a.size, light + (0,)); tint.putalpha(a.getchannel("A").point(lambda v: int(v * 0.8)))
    img.alpha_composite(tint, (0, (H - a.height) // 2))
    dr = ImageDraw.Draw(img)
    if progress:
        th = max(3, int(H * 0.05))
        dr.rectangle([0, H - th, W, H], fill=(255, 255, 255, 90)); dr.rectangle([0, H - th, int(W * progress), H], fill=ACC + (255,))
    if dur:
        f = font(8.5); tw = dr.textlength(dur, font=f); ph = 8.5 * S * 1.5
        x1 = W - 4 * S; x0 = x1 - tw - 8 * S; y1 = H - 4 * S; y0 = y1 - ph
        dr.rounded_rectangle([x0, y0, x1, y1], ph / 2, fill=(0, 0, 0, 170)); dr.text((x0 + 4 * S, y0 + ph * 0.12), dur, font=f, fill=(255, 255, 255, 255))
    m = rr_mask(W, H, int(r * S))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0)); out.paste(img, (0, 0), m)
    return out

def circle(img, cx, cy, r, color, alpha=255):
    d = ImageDraw.Draw(img); d.ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], fill=color + (alpha,))

def glowc(img, cx, cy, r, color, a=0.55):
    g = Image.new("RGBA", img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(g)
    d.ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], fill=color + (int(255 * a),))
    img.alpha_composite(g.filter(ImageFilter.GaussianBlur(r * S * 0.45)))

def icon(img, kind, cx, cy, size, color):
    d = ImageDraw.Draw(img); s = size * S / 24.0; ox, oy = cx * S - 12 * s, cy * S - 12 * s
    P = lambda pts: [(ox + x * s, oy + y * s) for x, y in pts]
    c = color + (255,)
    if kind == "play": d.polygon(P([(8.6, 4.6), (19.3, 12), (8.6, 19.4)]), fill=c)
    elif kind == "pause": d.rounded_rectangle([ox + 6.5 * s, oy + 5 * s, ox + 10.9 * s, oy + 19 * s], 1 * s, fill=c); d.rounded_rectangle([ox + 13.1 * s, oy + 5 * s, ox + 17.5 * s, oy + 19 * s], 1 * s, fill=c)
    elif kind == "next": d.polygon(P([(5.5, 5.8), (15, 12), (5.5, 18.2)]), fill=c); d.rounded_rectangle([ox + 16.2 * s, oy + 5.5 * s, ox + 19.2 * s, oy + 18.5 * s], 0.7 * s, fill=c)
    elif kind == "prev": d.polygon(P([(18.5, 5.8), (9, 12), (18.5, 18.2)]), fill=c); d.rounded_rectangle([ox + 4.8 * s, oy + 5.5 * s, ox + 7.8 * s, oy + 18.5 * s], 0.7 * s, fill=c)
    elif kind == "folder": d.polygon(P([(2, 6), (10, 6), (12, 8), (22, 8), (22, 19), (2, 19)]), fill=c)
    elif kind == "library": d.rounded_rectangle([ox + 6 * s, oy + 2 * s, ox + 22 * s, oy + 18 * s], 2 * s, fill=c); d.polygon(P([(12, 5.5), (18, 10), (12, 14.5)]), fill=color + (0,)); d.polygon(P([(12, 5.5), (18, 10), (12, 14.5)]), fill=(20, 24, 50, 255)); d.line(P([(3, 6), (3, 21), (18, 21)]), fill=c, width=int(2 * s))
    elif kind == "playlist":
        for y in (5, 9): d.rectangle([ox + 2 * s, oy + y * s, ox + 19 * s, oy + (y + 2) * s], fill=c)
        d.rectangle([ox + 2 * s, oy + 13 * s, ox + 15 * s, oy + 15 * s], fill=c); d.polygon(P([(17, 13), (22, 16), (17, 19)]), fill=c)
    elif kind == "star":
        pts = [(12 + 10 * math.cos(math.radians(-90 + i * 36)) * (1 if i % 2 == 0 else 0.45), 12.5 + 10 * math.sin(math.radians(-90 + i * 36)) * (1 if i % 2 == 0 else 0.45)) for i in range(10)]
        d.polygon(P(pts), fill=c)
    elif kind == "continue": d.ellipse([ox + 2.5 * s, oy + 2.5 * s, ox + 21.5 * s, oy + 21.5 * s], outline=c, width=int(2 * s)); d.polygon(P([(10, 7.8), (17, 12), (10, 16.2)]), fill=c)
    elif kind == "open": d.line(P([(9, 15), (19, 5)]), fill=c, width=int(2 * s)); d.line(P([(13, 4), (20, 4), (20, 11)]), fill=c, width=int(2 * s)); d.line(P([(11, 4), (4, 4), (4, 20), (20, 20), (20, 13)]), fill=c, width=int(2 * s))
    elif kind == "shuffle":
        d.line(P([(4, 6), (9.5, 11.5)]), fill=c, width=int(2 * s)); d.line(P([(13, 13), (18, 18)]), fill=c, width=int(2 * s))
        d.line(P([(4, 19), (18.5, 5.5)]), fill=c, width=int(2 * s))
        d.polygon(P([(14, 4), (20, 4), (20, 10)]), fill=c); d.polygon(P([(14, 20), (20, 20), (20, 14)]), fill=c)

def text(img, x, y, s, size, color, bold=False, maxw=None):
    d = ImageDraw.Draw(img); f = font(size, bold)
    if maxw:
        while d.textlength(s, font=f) > maxw * S and len(s) > 2: s = s[:-2].rstrip() + "…"
    d.text((x * S, y * S), s, font=f, fill=color + (255,))

def progress(img, x, y, w, frac):
    d = ImageDraw.Draw(img); h = 2.6 * S
    d.rounded_rectangle([x * S, y * S, (x + w) * S, y * S + h], h / 2, fill=TXT + (60,))
    glowc(img, x + w * frac / 2, y + 1.3, 3, ACC, 0.25)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([x * S, y * S, (x + w * frac) * S, y * S + h], h / 2, fill=ACC + (255,))
    circle(img, x + w * frac, y + 1.3, 2.6, mix(ACC, (255, 255, 255), 0.55))

def play_orb(img, cx, cy, r, playing=True, glow=False):
    if glow: glowc(img, cx, cy, r * 1.25, ACC, 0.5)
    circle(img, cx, cy, r, ACC); icon(img, "pause" if playing else "play", cx, cy, r * 1.05, ONACC)

def now_medium(w=300, h=150):
    img = glass(w, h)
    img.alpha_composite(art_thumb(96, 96, 16, seed=0), (12 * S, (h - 96) // 2 * S))
    x = 12 + 96 + 12
    text(img, x, 18, "NOW PLAYING", 7.2, ACC, True)
    text(img, x, 31, "Weekend in the mountains", 10.5, TXT, True, maxw=w - x - 10)
    text(img, x, 52, "12:48 / 31:05  ·  3 of 12", 8, DIM)
    progress(img, x, 69, w - x - 14, 0.41)
    cy = 100; icon(img, "prev", x + 14, cy, 20, TXT); play_orb(img, x + 14 + 18 + 21, cy, 19); icon(img, "next", x + 14 + 18 + 42 + 18, cy, 20, TXT)
    return img

def now_bar(w=300, h=72):
    img = glass(w, h)
    img.alpha_composite(art_thumb(44, 44, 12, seed=0), (10 * S, (h - 44) // 2 * S))
    text(img, 64, h / 2 - 15, "Weekend in the mountains", 10.5, TXT, True, maxw=w - 64 - 120)
    text(img, 64, h / 2 + 2, "Now playing  ·  3 of 12", 8, DIM)
    icon(img, "prev", w - 104, h / 2, 18, TXT); play_orb(img, w - 66, h / 2, 19); icon(img, "next", w - 28, h / 2, 18, TXT)
    return img

def now_square(w=160, h=160):
    img = glass(w, h)
    t = art_thumb(w, h, 24, seed=0)
    sc = Image.new("RGBA", t.size, (0, 0, 0, 0)); arr = np.zeros((t.size[1], t.size[0], 4), np.uint8)
    yy = np.linspace(0, 1, t.size[1])[:, None]; arr[..., 3] = (np.clip((yy - 0.35) / 0.65, 0, 1) * 217).astype(np.uint8).repeat(t.size[0], 1)
    t.alpha_composite(Image.fromarray(arr, "RGBA")); t.putalpha(Image.fromarray(np.minimum(np.array(t.getchannel("A")), np.array(rr_mask(*t.size, 24 * S)))))
    img.alpha_composite(t)
    text(img, 12, h - 46, "NOW PLAYING", 7.2, (220, 224, 240), True)
    text(img, 12, h - 33, "Weekend in the", 10.5, (255, 255, 255), True); text(img, 12, h - 19, "mountains", 10.5, (255, 255, 255), True)
    play_orb(img, w - 32, h - 32, 20)
    ImageDraw.Draw(img).rounded_rectangle([0, 0, w * S - 1, h * S - 1], 24 * S, outline=STROKE, width=S)
    return img

def header(img, w, title, sub, ic, action="open", shuffle=False):
    circle(img, 12 + 15, 10 + 15, 15, ACC, 70); icon(img, ic, 27, 25, 16, ACC)
    text(img, 50, 11, title, 10.5, TXT, True); text(img, 50, 27, sub, 8, DIM)
    play_orb_or = w - 8 - 17
    circle(img, play_orb_or, 25, 17, ACC); icon(img, action, play_orb_or, 25, 18, ONACC)
    if shuffle: icon(img, "shuffle", w - 8 - 34 - 4 - 17, 25, 18, TXT)

def list_widget(w, h, title, sub, ic, rows, action="open", shuffle=False, cont=True):
    img = glass(w, h)
    header(img, w, title, sub, ic, action, shuffle)
    y = 52
    for i, (t, meta, prog) in enumerate(rows):
        if y + 60 > h: break
        img.alpha_composite(art_thumb(88, 50, 9, seed=i + 1, progress=prog), (12 * S, int((y + 7) * S)))
        text(img, 110, y + 14, t, 9.8, TXT, True, maxw=w - 110 - 40); text(img, 110, y + 32, meta, 8, DIM)
        icon(img, "play", w - 26, y + 32, 16, mix(ACC, TOP, 0.25))
        y += 64
    return img

def grid_widget(w, h):
    img = glass(w, h)
    header(img, w, "Recently added", "Newest on this phone", "library")
    cols = max(1, (w - 20 + 6) // (128 + 6)); cw = (w - 20 - (cols - 1) * 6) / cols
    titles = ["Beach sunset", "Lecture 04", "Concert clip", "Family dinner", "Drone flight", "Recipe test"]
    durs = ["4:12", "48:30", "2:57", "12:05", "6:40", "9:18"]
    y = 54; i = 0
    while y + cw * 9 / 16 + 18 <= h - 6 and i < len(titles):
        for c in range(cols):
            if i >= len(titles): break
            x = 10 + c * (cw + 6)
            img.alpha_composite(art_thumb(cw - 6, (cw - 6) * 9 / 16, 10, seed=i + 2, dur=durs[i]), (int((x + 3) * S), int((y + 3) * S)))
            text(img, x + 5, y + 6 + (cw - 6) * 9 / 16, titles[i], 8.5, TXT, maxw=cw - 8)
            i += 1
        y += (cw - 6) * 9 / 16 + 24
    return img

def quick(w, h, grid):
    img = glass(w, h)
    items = [("continue", "Continue"), ("library", "Videos"), ("folder", "Folders"), ("playlist", "Playlists")]
    pad = 8; gap = 6
    cols = 2 if grid else 4; rws = 2 if grid else 1
    tw = (w - 2 * pad - (cols - 1) * gap) / cols; th = (h - 2 * pad - (rws - 1) * gap) / rws
    for i, (ic, lab) in enumerate(items):
        cx0 = pad + (i % cols) * (tw + gap); cy0 = pad + (i // cols) * (th + gap)
        tile = Image.new("RGBA", img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(tile)
        fill = ACC + (56,) if i == 0 else TILE
        d.rounded_rectangle([cx0 * S, cy0 * S, (cx0 + tw) * S, (cy0 + th) * S], 18 * S if grid else 16 * S, fill=fill, outline=(ACC + (60,)) if i == 0 else STROKE, width=S)
        img.alpha_composite(tile)
        isz = 24 if grid else 20
        icon(img, ic, cx0 + tw / 2, cy0 + th / 2 - (8 if grid else 7), isz, ACC)
        f = font(9 if grid else 8.2, True); lw = ImageDraw.Draw(img).textlength(lab, font=f) / S
        text(img, cx0 + tw / 2 - lw / 2, cy0 + th / 2 + (8 if grid else 6), lab, 9 if grid else 8.2, TXT, True)
    return img

def wallpaper(w, h):
    W, H = w * S, h * S
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    col = np.array((14, 18, 40), np.float32) * (1 - yy / H)[..., None] + np.array((70, 40, 90), np.float32) * (yy / H)[..., None]
    for (cx, cy, r, c) in [(0.2, 0.25, 0.6, (60, 90, 200)), (0.85, 0.7, 0.55, (180, 80, 140)), (0.5, 1.0, 0.5, (220, 140, 90))]:
        d = np.sqrt((xx - cx * W) ** 2 + (yy - cy * H) ** 2) / (r * max(W, H)); k = np.clip(1 - d, 0, 1)[..., None] ** 2 * 0.8
        col = col * (1 - k) + np.array(c, np.float32) * k
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

def on_wall(img, pad=10):
    w, h = img.size[0] // S + 2 * pad, img.size[1] // S + 2 * pad
    bg = wallpaper(w, h); bg.alpha_composite(img, (pad * S, pad * S)); return bg

def save_preview(img, name):
    final = img.resize((img.size[0] * 2 // S, img.size[1] * 2 // S), Image.LANCZOS)
    p = os.path.join(out_dir, f"wg_preview_{name}.webp"); final.save(p, "WEBP", quality=90, method=6); print(name, os.path.getsize(p))

def build_all():
    g = {}
    g["np"] = now_medium(); g["bar"] = now_bar(); g["sq"] = now_square()
    g["cont"] = list_widget(300, 180, "Continue watching", "3 unfinished videos", "continue",
        [("Weekend in the mountains", "18 min left · 31:05", 0.41), ("Lecture 04 — Signals", "33 min left · 48:30", 0.32), ("Concert clip", "Almost done · 2:57", 0.9)])
    g["col"] = list_widget(300, 180, "Favorites", "12 videos · 2:41:08", "star",
        [("Beach sunset", "4:12", None), ("Family dinner", "12:05 · resume 3:10", 0.26), ("Drone flight over the bay", "6:40", None)], action="play", shuffle=True)
    g["lib"] = grid_widget(300, 260); g["q"] = quick(300, 76, False); g["qg"] = quick(160, 160, True)
    return g

# Picker previews (v2.16.2). The launcher fits previewImage into a box sized from targetCellWidth/Height; the old
# images were opaque rectangles (widget on a wallpaper) whose aspect didn't match that box, so the picker showed
# blank/white strips beside them. Now:
#  * previewImage  = ONLY the widget card (transparent corners, no wallpaper), on a canvas at the target-cell aspect.
#  * previewLayout (Android 12+, wg_pvl_*) = a glass background drawable that fills ANY box the launcher gives,
#    with the widget's content image (wg_pv_*, transparent) fitted on top, so there is never an empty strip.
# name: (key in build_all, card w, card h in dp; content is centred inside the card)
PICKER = {"now_playing": ("np", 300, 176), "continue": ("cont", 300, 180), "collection": ("col", 300, 180),
          "library": ("lib", 300, 280), "quick": ("q", 300, 84)}

def save_img(img, fname):
    final = img.resize((img.size[0] * 2 // S, img.size[1] * 2 // S), Image.LANCZOS)
    p = os.path.join(out_dir, fname); final.save(p, "WEBP", quality=90, method=6); print(fname, final.size, os.path.getsize(p))

g = build_all()
if not THEME:
    OPACITY = 1.0
    full = build_all()
    CONTENT = True
    content = build_all()
    CONTENT = False; OPACITY = 0.88
    for name, (key, cw, ch) in PICKER.items():
        c_img = content[key]; w0, h0 = c_img.size[0] // S, c_img.size[1] // S
        card_img = glass(cw, ch, opacity=1.0)
        card_img.alpha_composite(c_img, ((cw - w0) // 2 * S, (ch - h0) // 2 * S))
        save_img(card_img, f"wg_preview_{name}.webp")
        save_img(c_img, f"wg_pv_{name}.webp")
w_np, w_bar, w_sq, w_cont, w_col, w_lib, w_q, w_qg = g["np"], g["bar"], g["sq"], g["cont"], g["col"], g["lib"], g["q"], g["qg"]

if len(sys.argv) > 2:
    W, H = 680, 764
    sheet = wallpaper(W, H)
    def put(img, x, y): sheet.alpha_composite(img, (x * S, y * S))
    put(w_np, 20, 20); put(w_bar, 20, 186); put(w_cont, 20, 274); put(w_lib, 20, 470)
    put(w_sq, 340, 20); put(w_qg, 510, 20); put(w_col, 340, 196); put(w_q, 340, 392); put(quick(300, 150, True), 340, 484)
    sheet.resize((W * 2, H * 2), Image.LANCZOS).save(sys.argv[2])
    print("showcase", sys.argv[2])
