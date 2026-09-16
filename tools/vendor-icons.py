#!/usr/bin/env python3
"""Vendors Lucide icons as Android VectorDrawables. No dependencies.

Lucide (https://lucide.dev) is ISC licensed; the notice is in docs/PROVENANCE.md. Each icon
is a 24x24 stroke drawing. SVG shapes that VectorDrawable lacks (line, circle, rect,
polyline, polygon, ellipse) are rewritten as path data. Stroke colour is white so the app
tints at runtime with Drawable.setTint.

Usage: tools/vendor-icons.py name [name ...]   (writes res/drawable/ic_<name_with_underscores>.xml)
"""
import re, sys, urllib.request, xml.etree.ElementTree as ET, pathlib

RAW = "https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/%s.svg"
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/res/drawable"

def f(v):
    s = ("%.3f" % float(v)).rstrip("0").rstrip(".")
    return s if s else "0"

def shape_to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "line":
        return "M%s,%sL%s,%s" % (f(a["x1"]), f(a["y1"]), f(a["x2"]), f(a["y2"]))
    if tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        return "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0" % (
            f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))
    if tag == "ellipse":
        cx, cy, rx, ry = (float(a[k]) for k in ("cx", "cy", "rx", "ry"))
        return "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0" % (
            f(cx - rx), f(cy), f(rx), f(ry), f(2 * rx), f(rx), f(ry), f(-2 * rx))
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        rx = float(a.get("rx", a.get("ry", 0)))
        if rx == 0:
            return "M%s,%sh%sv%sh%sz" % (f(x), f(y), f(w), f(h), f(-w))
        return ("M%s,%sh%sa%s,%s 0 0,1 %s,%sv%sa%s,%s 0 0,1 %s,%sh%sa%s,%s 0 0,1 %s,%sv%sa%s,%s 0 0,1 %s,%sz" % (
            f(x + rx), f(y), f(w - 2 * rx), f(rx), f(rx), f(rx), f(rx), f(h - 2 * rx),
            f(rx), f(rx), f(-rx), f(rx), f(-(w - 2 * rx)), f(rx), f(rx), f(-rx), f(-rx),
            f(-(h - 2 * rx)), f(rx), f(rx), f(rx), f(-rx)))
    if tag in ("polyline", "polygon"):
        pts = re.findall(r"[-\d.]+", a["points"])
        pairs = ["%s,%s" % (f(pts[i]), f(pts[i + 1])) for i in range(0, len(pts), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise SystemExit("unsupported element <%s>" % tag)

def vendor(name):
    svg = urllib.request.urlopen(RAW % name, timeout=30).read()
    root = ET.fromstring(svg)
    paths = [shape_to_path(el) for el in root]
    res = "ic_" + name.replace("-", "_")
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Lucide "%s" (ISC), via tools/vendor-icons.py. Tint at runtime. -->' % name,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp"',
           '    android:viewportWidth="24" android:viewportHeight="24">']
    for d in paths:
        out.append('    <path android:pathData="%s"' % d)
        out.append('        android:strokeColor="#FFFFFFFF" android:strokeWidth="2"')
        out.append('        android:strokeLineCap="round" android:strokeLineJoin="round"')
        out.append('        android:fillColor="#00000000" />')
    out.append('</vector>')
    (OUT / (res + ".xml")).write_text("\n".join(out) + "\n")
    print(res)

if __name__ == "__main__":
    for n in sys.argv[1:]:
        vendor(n)
