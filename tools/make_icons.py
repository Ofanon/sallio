"""Builds Sallio's subject icons as Android vector drawables.

- Tabler icons (MIT): SVG -> vector, stroked like the existing ones.
- Language badges: two letters of Fredoka (OFL, already in the app) as filled paths.
Usage: python make_icons.py <tabler_svg_dir> <font.ttf> <res/drawable dir>
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

tabler_dir, font_path, out_dir = map(Path, sys.argv[1:4])

STROKE = ('android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" '
          'android:strokeLineJoin="round" android:fillColor="#00000000"')
HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" '
        'android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">')


def num(v):
    return float(v)


def shape_to_path(el):
    tag = el.tag.split('}')[-1]
    a = el.attrib
    if tag == 'path':
        if a.get('stroke') == 'none':
            return None
        return a['d']
    if tag == 'circle':
        cx, cy, r = num(a['cx']), num(a['cy']), num(a['r'])
        return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0"
    if tag == 'line':
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ('polyline', 'polygon'):
        pts = re.split(r'[\s,]+', a['points'].strip())
        pairs = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        return 'M' + 'L'.join(pairs) + ('z' if tag == 'polygon' else '')
    if tag == 'rect':
        x, y, w, h = (num(a.get(k, 0)) for k in ('x', 'y', 'width', 'height'))
        rx = num(a.get('rx', 0))
        if rx:
            return (f"M{x + rx} {y}h{w - 2 * rx}a{rx} {rx} 0 0 1 {rx} {rx}v{h - 2 * rx}a{rx} {rx} 0 0 1 {-rx} {rx}"
                    f"h{-(w - 2 * rx)}a{rx} {rx} 0 0 1 {-rx} {-rx}v{-(h - 2 * rx)}a{rx} {rx} 0 0 1 {rx} {-rx}z")
        return f"M{x} {y}h{w}v{h}h{-w}z"
    return None


def tabler(name, res):
    root = ET.parse(tabler_dir / f"{name}.svg").getroot()
    paths = [p for p in (shape_to_path(el) for el in root.iter()) if p]
    body = '\n'.join(f'    <path android:pathData="{p}" {STROKE} />' for p in paths)
    (out_dir / f"{res}.xml").write_text(
        f"<!-- Tabler icon \"{name}\" (MIT), converted to a vector drawable. Tinted from code. -->\n{HEAD}\n{body}\n</vector>\n",
        encoding='utf-8')


font = TTFont(font_path)
if 'fvar' in font:
    font = instantiateVariableFont(font, {'wght': 650})
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()


def badge(letters, res, height=11.0):
    """Letters as one filled path, [height] units tall, centred in the 24x24 box."""
    names = [cmap[ord(c)] for c in letters]
    advance = 0
    placed = []
    for n in names:
        placed.append((n, advance))
        advance += glyphs[n].width - 30  # a little tighter than the font's spacing
    bounds = BoundsPen(glyphs)
    for n, x in placed:
        glyphs[n].draw(TransformPen(bounds, (1, 0, 0, 1, x, 0)))
    x0, y0, x1, y1 = bounds.bounds
    scale = height / (y1 - y0)
    width = (x1 - x0) * scale
    if width > 21:  # wide pairs (WM) shrink to fit
        scale *= 21 / width
        width = 21
    dx = (24 - width) / 2
    top = (24 - (y1 - y0) * scale) / 2
    svg = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip('0').rstrip('.'))
    for n, x in placed:
        # Font units, y up -> viewport, y down.
        t = (scale, 0, 0, -scale, dx + (x - x0) * scale, top + y1 * scale)
        glyphs[n].draw(TransformPen(svg, t))
    (out_dir / f"{res}.xml").write_text(
        f"<!-- \"{letters}\" in Fredoka (OFL 1.1), as a filled vector. Tinted from code. -->\n{HEAD}\n"
        f'    <path android:pathData="{svg.getCommands()}" android:fillColor="#FFFFFFFF" />\n</vector>\n',
        encoding='utf-8')


for name, res in [
    ('feather', 'ic_feather'), ('building-bank', 'ic_columns'), ('atom', 'ic_atom'), ('scale', 'ic_scale'),
    ('tool', 'ic_tool'), ('movie', 'ic_movie'), ('masks-theater', 'ic_theater'), ('gavel', 'ic_gavel'),
    ('briefcase', 'ic_briefcase'), ('dna-2', 'ic_dna'), ('users', 'ic_users'),
]:
    tabler(name, res)

for letters in ['DE', 'EN', 'ES', 'IT', 'PT', 'RU', 'ZH', 'JA', 'AR', 'NL', 'KO', 'HE']:
    badge(letters, f"ic_lang_{letters.lower()}")
print('ok')
