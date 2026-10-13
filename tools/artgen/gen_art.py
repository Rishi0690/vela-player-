#!/usr/bin/env python3
"""v2.14 Vela artwork: "luminous glass forms" (original, procedural).

Renders three WHITE alpha layers so the app can tint them with the live theme at runtime (Artwork.kt):
  art_forms_a  - main forms: a lit glass ring, two stacked glass "screens" (echo of the launcher icon), a light ribbon
  art_forms_b  - atmosphere: a second ribbon, light pools and soft bokeh (tinted with the theme's second glow)
  art_forms_hi - specular highlights, drawn untinted on top
plus art_luminous_forms = all three merged (single-tint use: widget empty states).
Usage: python3 tools/artgen/gen_art.py .   (needs numpy + Pillow with WebP)
"""
import os, sys, math
import numpy as np
from PIL import Image

root = sys.argv[1] if len(sys.argv) > 1 else "."
out = os.path.join(root, "app/src/main/res/drawable-nodpi")
os.makedirs(out, exist_ok=True)
W, H = 1440, 800
S = 2  # supersample handled by final downscale (render at W,H then /2)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def clamp(a): return np.clip(a, 0.0, 1.0)

def ring(cx, cy, R, w, light_deg=225.0, base=0.25):
    dx, dy = xx - cx, yy - cy
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.degrees(np.arctan2(dy, dx)) % 360
    lit = base + (1 - base) * clamp(np.cos(np.radians(ang - light_deg))) ** 1.6
    return np.exp(-((r - R) / w) ** 2) * lit

def disc_fill(cx, cy, R, a):
    r = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inside = clamp((R - r) / 2.0)
    # glass body: brighter towards the top-left rim
    grad = clamp(1 - ((xx - (cx - R)) + (yy - (cy - R))) / (4 * R))
    return inside * a * (0.35 + 0.65 * grad)

def rrect_sdf(cx, cy, hw, hh, rad, rot_deg):
    t = math.radians(rot_deg)
    x = (xx - cx) * math.cos(t) + (yy - cy) * math.sin(t)
    y = -(xx - cx) * math.sin(t) + (yy - cy) * math.cos(t)
    qx = np.abs(x) - (hw - rad); qy = np.abs(y) - (hh - rad)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - rad, x, y

def panel(cx, cy, hw, hh, rad, rot, edge=2.2, fill=0.07):
    d, x, y = rrect_sdf(cx, cy, hw, hh, rad, rot)
    edge_a = np.exp(-(d / edge) ** 2)
    # light from the top-left: edges facing it glow, the far edges fade
    lit = clamp(0.25 + 0.75 * clamp((-x / hw - y / hh) * 0.6 + 0.5))
    body = clamp(-d / 2.0) * fill * (0.5 + 0.5 * clamp(-y / hh * 0.5 + 0.5))
    sheen = clamp(-d / 2.0) * clamp((-y / hh) - 0.25) * 0.10
    return edge_a * lit + body + sheen

def ribbon(y0, amp, freq, phase, width, x_fade=(0.0, 1.0), tilt=0.0):
    u = xx / W
    yc = y0 + amp * np.sin(2 * math.pi * freq * u + phase) + tilt * (u - 0.5) * H
    dy = yy - yc
    a = np.exp(-(dy / width) ** 2)
    core = np.exp(-(dy / (width * 0.18)) ** 2) * 0.6
    f0, f1 = x_fade
    fade = clamp((u - f0) / 0.18) * clamp((f1 - u) / 0.22)
    return (a * 0.55 + core) * fade

def blob(cx, cy, R, a=1.0):
    r2 = ((xx - cx) ** 2 + (yy - cy) ** 2) / (R * R)
    return np.exp(-r2 * 2.2) * a

def bokeh(cx, cy, R, a):
    r = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    return clamp((R - r) / 3.0) * a * (0.6 + 0.4 * clamp(1 - r / R)) + np.exp(-((r - R) / 2.0) ** 2) * a * 0.6

# --- layer A: main forms ---
A = np.zeros((H, W), np.float32)
A += ring(930, 380, 250, 5.0) * 0.95
A += ring(930, 380, 268, 14.0, base=0.1) * 0.25
A += disc_fill(930, 380, 248, 0.10)
A += panel(560, 470, 250, 150, 34, -8, edge=2.4, fill=0.10)
A += panel(640, 405, 250, 150, 34, -8, edge=2.0, fill=0.06) * 0.7
# a play mark as negative-space glint on the front screen
d, x, y = rrect_sdf(560, 470, 250, 150, 34, -8)
tri = clamp(1 - (np.abs(y) * 1.6 + x * 0.9 - 30) / 2.0) * clamp((x + 30) / 2.0)
A += tri * clamp(-d / 2) * 0.22
A += ribbon(560, 90, 0.75, 0.6, 26, (0.02, 0.98), tilt=-0.25) * 0.75
A = clamp(A)

# --- layer B: atmosphere ---
B = np.zeros((H, W), np.float32)
B += blob(1080, 250, 420, 0.42)
B += blob(330, 640, 380, 0.30)
B += ribbon(300, 70, 0.55, 2.2, 40, (0.0, 0.9), tilt=0.18) * 0.55
for (cx, cy, R, a) in [(1220, 150, 26, 0.35), (1290, 560, 16, 0.28), (210, 230, 20, 0.22), (420, 150, 10, 0.3),
                       (1130, 690, 12, 0.25), (760, 120, 8, 0.35), (1350, 360, 9, 0.3), (120, 520, 14, 0.18)]:
    B += bokeh(cx, cy, R, a)
B = clamp(B)

# --- highlights ---
Hh = np.zeros((H, W), np.float32)
Hh += ring(930, 380, 250, 2.2, light_deg=225, base=0.0) ** 3 * 1.0
Hh += blob(760, 205, 26, 0.9)
Hh += blob(345, 340, 14, 0.8)
Hh += np.exp(-(rrect_sdf(640, 405, 250, 150, 34, -8)[0] / 1.2) ** 2) * clamp((-(xx - 640) - (yy - 405)) / 300 - 0.35) * 0.9
Hh += ribbon(560, 90, 0.75, 0.6, 5, (0.25, 0.7), tilt=-0.25) * 0.5
Hh = clamp(Hh)

def save(a, name, size=(720, 400)):
    img = Image.new("LA", (W, H))
    alpha = Image.fromarray((a * 255).astype(np.uint8), "L")
    white = Image.new("L", (W, H), 255)
    img = Image.merge("LA", (white, alpha)).convert("RGBA").resize(size, Image.LANCZOS)
    p = os.path.join(out, name + ".webp")
    img.save(p, "WEBP", quality=88, method=6)
    print(name, os.path.getsize(p))

save(A, "art_forms_a")
save(B, "art_forms_b")
save(Hh, "art_forms_hi")
save(clamp(A * 0.95 + B * 0.55 + Hh), "art_luminous_forms", (480, 268))

# preview (midnight glass tint) for checking by eye
if len(sys.argv) > 2:
    def tint(a, rgb):
        return np.dstack([a * c for c in rgb])
    base = np.zeros((H, W, 3), np.float32)
    top = np.array([0.012, 0.016, 0.04]); bot = np.array([0.05, 0.06, 0.14])
    base += top + (bot - top) * (yy / H)[..., None]
    col = base + tint(B, (0.36, 0.30, 0.85)) * 0.7 + tint(A, (0.56, 0.66, 1.0)) * 0.9 + tint(Hh, (1, 1, 1))
    Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8)).resize((720, 400), Image.LANCZOS).save(sys.argv[2])
