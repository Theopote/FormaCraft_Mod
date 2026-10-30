# -*- coding: utf-8 -*-
from pathlib import Path
import re
from collections import Counter

text = Path(r"F:/development/formacraft-1.21.10/run/logs/latest.log").read_text(
    encoding="utf-8", errors="replace"
)
out = []

# All patch conflicts with prev/writer
conflicts = re.findall(
    r"prev=([A-Z_]+)/[^\s]+ writer=([A-Z_]+)/[^\s]+ id=([^\s]+)", text
)
out.append("=== conflict pairs ===")
for (a, b), n in Counter((p, w) for p, w, _ in conflicts).most_common(20):
    out.append(f"  {n}x {a} <- {b}")

# Find component plans near pavilion prompts
for label in ["八角", "凉亭", "水上亭", "水榭"]:
    for m in list(re.finditer(label, text))[-2:]:
        chunk = text[max(0, m.start() - 2000) : m.start() + 8000]
        out.append(f"\n=== around {label}@{m.start()} ===")
        for key in [
            "shape",
            "sides",
            "foundation_type",
            "component_id",
            "open",
            "wall_type",
            "wall_style",
            "height",
            "width",
            "depth",
            "anchor_mode",
            "roof_type",
        ]:
            hits = re.findall(
                rf'"{key}"\s*:\s*("?[^,"\n]{{1,40}}"?|\d+(?:\.\d+)?)', chunk
            )
            if hits:
                out.append(f"  {key}: {hits[:8]}")

# Tower / pagoda / spire components
for key in ["pagoda", "spire", "尖顶", "塔", "tower_nw", "tower_ne"]:
    n = len(re.findall(key, text, re.I))
    out.append(f"hit {key}={n}")

# Soft cliff
out.append("\nAPPROACH_CLIFF samples:")
for m in re.finditer(r"APPROACH_CLIFF:[^\n]{0,80}", text):
    out.append("  " + m.group(0))

Path(r"F:/development/formacraft-1.21.10/build/tmp/log_scan3.txt").write_text(
    "\n".join(out), encoding="utf-8"
)
print("wrote", len(out), "lines")
