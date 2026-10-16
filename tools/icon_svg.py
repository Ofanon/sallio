"""Turns the launcher icon's vector layers into one SVG, for the website.

Usage: python icon_svg.py <res/drawable dir> <out.svg>
The adaptive icon is a 108x108 canvas whose middle 72 is what launchers show:
the SVG keeps that part, on the yellow background, rounded like an app icon.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

A = '{http://schemas.android.com/apk/res/android}'


def color(c):
    # #AARRGGBB -> #RRGGBB + opacity
    if c and len(c) == 9:
        return '#' + c[3:], int(c[1:3], 16) / 255
    return c, 1.0


def paths(xml):
    out = []
    for p in ET.parse(xml).getroot().iter('path'):
        d = p.get(A + 'pathData')
        fill, fo = color(p.get(A + 'fillColor', '#00000000'))
        stroke, so = color(p.get(A + 'strokeColor', '#00000000'))
        attrs = [f'd="{d}"']
        attrs.append(f'fill="{fill}" fill-opacity="{fo:.3f}"' if fo > 0 else 'fill="none"')
        if so > 0:
            attrs.append(f'stroke="{stroke}" stroke-opacity="{so:.3f}" stroke-width="{p.get(A + "strokeWidth", "1")}"')
        out.append(f'<path {" ".join(attrs)}/>')
    return out


res, target = Path(sys.argv[1]), Path(sys.argv[2])
# The background is a plain <shape> (the logo's yellow), not a vector.
bg = ET.parse(res / 'ic_launcher_bg.xml').getroot().find('solid')
body = [f'<rect x="0" y="0" width="108" height="108" fill="{bg.get(A + "color")}"/>'] + paths(res / 'ic_launcher_fg.xml')
svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">'
    '<clipPath id="c"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath>'
    f'<g clip-path="url(#c)">{"".join(body)}</g></svg>'
)
target.write_text(svg, encoding='utf-8')
print(target, len(svg))
