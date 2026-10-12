"""Builds Sallio's launcher icon: a classroom door plate, stuck on askew, with a green tick.

Writes the adaptive icon layers (108dp canvas, safe zone 66dp) and the launcher
shortcut icons, from Fredoka (OFL, in the app's assets) for the "B204" letters.
Usage: python make_logo.py <Fredoka.ttf> <res/drawable dir>
"""
import math
import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

font_path, out = Path(sys.argv[1]), Path(sys.argv[2])

YELLOW = '#FFD66B'
SHADOW = '#D49A1C'
PLATE = '#FFFFFF'
INK = '#2E2A3A'
GREEN = '#2F9E5B'

ANGLE = -8.0      # degrees, the plate stuck on askew
CX, CY = 52.0, 57.0
W, H, RADIUS = 56.0, 31.0, 8.0

font = TTFont(font_path)
if 'fvar' in font:
    font = instantiateVariableFont(font, {'wght': 600})
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()


def rotate(x, y):
    a = math.radians(ANGLE)
    dx, dy = x - CX, y - CY
    return CX + dx * math.cos(a) - dy * math.sin(a), CY + dx * math.sin(a) + dy * math.cos(a)


def fmt(v):
    return f"{v:.2f}".rstrip('0').rstrip('.')


def rounded_rect(x, y, w, h, r):
    """Rounded rectangle as absolute points, rotated about the plate's centre (corners as quadratic curves)."""
    pts = [
        ('M', [(x + r, y)]), ('L', [(x + w - r, y)]), ('Q', [(x + w, y), (x + w, y + r)]),
        ('L', [(x + w, y + h - r)]), ('Q', [(x + w, y + h), (x + w - r, y + h)]),
        ('L', [(x + r, y + h)]), ('Q', [(x, y + h), (x, y + h - r)]),
        ('L', [(x, y + r)]), ('Q', [(x, y), (x + r, y)]),
    ]
    d = ''
    for cmd, ps in pts:
        d += cmd + ' '.join(f"{fmt(px)},{fmt(py)}" for px, py in (rotate(*p) for p in ps)) + ' '
    return d + 'Z'


def text_path(text, height):
    """[text] in Fredoka, [height] units tall, centred on the plate and rotated with it."""
    placed, advance = [], 0
    for c in text:
        n = cmap[ord(c)]
        placed.append((n, advance))
        advance += glyphs[n].width
    bounds = BoundsPen(glyphs)
    for n, x in placed:
        glyphs[n].draw(TransformPen(bounds, (1, 0, 0, 1, x, 0)))
    x0, y0, x1, y1 = bounds.bounds
    s = height / (y1 - y0)
    left = CX - (x1 - x0) * s / 2
    top = CY - height / 2
    a = math.radians(ANGLE)
    cos, sin = math.cos(a), math.sin(a)
    pen = SVGPathPen(glyphs, ntos=fmt)
    for n, x in placed:
        # Font units (y up) -> canvas (y down), then the plate's rotation about its centre.
        tx = left + (x - x0) * s
        ty = top + y1 * s
        # Point p -> R * ((s*px + tx, -s*py + ty) - C) + C
        m = (s * cos, s * sin, s * sin, -s * cos,
             cos * (tx - CX) - sin * (ty - CY) + CX, sin * (tx - CX) + cos * (ty - CY) + CY)
        glyphs[n].draw(TransformPen(pen, m))
    return pen.getCommands()


plate = rounded_rect(CX - W / 2, CY - H / 2, W, H, RADIUS)
shadow = rounded_rect(CX - W / 2 + 3, CY - H / 2 + 3.5, W, H, RADIUS)
letters = text_path('B204', 13.5)
tick_c = (73.0, 38.5)
tick_r = 8.0
badge = f"M{fmt(tick_c[0] - tick_r)},{fmt(tick_c[1])}a{fmt(tick_r)},{fmt(tick_r)} 0 1,0 {fmt(2 * tick_r)},0a{fmt(tick_r)},{fmt(tick_r)} 0 1,0 {fmt(-2 * tick_r)},0"
check = f"M{fmt(tick_c[0] - 3.6)},{fmt(tick_c[1] + 0.3)}l2.7,2.7l5,-5.4"

HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

(out / 'ic_launcher_bg.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<!-- Launcher background: the logo\'s yellow. Made by tools/make_logo.py. -->\n'
    f'<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
    f'    <solid android:color="{YELLOW}" />\n</shape>\n', encoding='utf-8')

(out / 'ic_launcher_fg.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- A classroom door plate, "B204", stuck on askew, with a green tick: the room, sorted.\n'
    '     108dp adaptive canvas, safe zone 66dp. Made by tools/make_logo.py (letters: Fredoka, OFL). -->\n'
    + HEAD +
    f'    <path android:fillColor="{SHADOW}" android:pathData="{shadow}" />\n'
    f'    <path android:fillColor="{PLATE}" android:pathData="{plate}" />\n'
    f'    <path android:fillColor="{INK}" android:pathData="{letters}" />\n'
    f'    <path android:fillColor="{GREEN}" android:pathData="{badge}" />\n'
    f'    <path android:fillColor="#00000000" android:strokeColor="#FFFFFF" android:strokeWidth="2.6" '
    f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{check}" />\n'
    '</vector>\n', encoding='utf-8')

# Themed icons (Android 13+): one colour, the letters cut out of the plate.
(out / 'ic_launcher_mono.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<!-- Themed launcher icon: the plate with its letters cut out, and the tick. Made by tools/make_logo.py. -->\n'
    + HEAD +
    f'    <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{plate} {letters}" />\n'
    f'    <path android:fillColor="#FFFFFF" android:pathData="{badge}" />\n'
    '</vector>\n', encoding='utf-8')
print('ok')
