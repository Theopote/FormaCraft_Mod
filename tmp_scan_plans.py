# -*- coding: utf-8 -*-
import sys, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
path = r"F:\development\formacraft-1.21.10\run\logs\latest.log"
with open(path, "r", encoding="utf-8", errors="replace") as f:
    lines = f.readlines()

def dump_plan(start, end, label):
    for i, l in enumerate(lines):
        if "Received response from orchestrator" not in l:
            continue
        if not (start <= i <= end):
            continue
        js = l[l.find("{") :]
        print("===", label, "line", i + 1, "len", len(js))
        pat = re.compile(
            r'\{"component_type":"([^"]+)".*?"relative_position":\{"x":(-?\d+),"y":(-?\d+),"z":(-?\d+)\}'
            r'.*?"dimensions":\{"width":(\d+),"depth":(\d+),"height":(\d+)\}'
        )
        for m in pat.finditer(js):
            ctype, x, y, z, w, d, h = m.groups()
            chunk = js[m.start() : m.start() + 450]
            cid = re.search(r'"component_id":"([^"]+)"', chunk)
            extras = []
            for k in ("open", "wall_type", "foundation_type", "stilt", "pillar_block", "host_id", "crown_template"):
                mm = re.search(rf'"{k}":("[^"]+"|true|false|[0-9.]+)', chunk)
                if mm:
                    extras.append(f"{k}={mm.group(1)}")
            print(
                f"  {ctype:16} id={(cid.group(1) if cid else '?'):28} "
                f"pos=({x},{y},{z}) dim={w}x{d}x{h} {' '.join(extras)}"
            )
        return

dump_plan(760, 900, "water_pavilion")
dump_plan(1530, 1680, "hall_colonnade")
dump_plan(1665, 1790, "greenhouse")
dump_plan(1780, 1950, "palace")
