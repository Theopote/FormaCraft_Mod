import json
d = json.load(open("src/test/resources/regressions/live-2250-231046.json", encoding="utf-8"))
for c in d.get("components") or []:
    t = c.get("component_type")
    p = c.get("params") or {}
    if t in ("MASS_MAIN", "TOWER") or p.get("from"):
        print(t, "id=", p.get("component_id"), "dims=", c.get("dimensions"), "pos=", c.get("relative_position"),
              "wall=", p.get("wall_thickness"), "from=", p.get("from"), "to=", p.get("to"),
              "slot=", c.get("slot_id"), "width_param=", p.get("width"))
print("slots:", [(s.get("slot_id"), s.get("anchor")) for s in (d.get("layout") or {}).get("slots") or []])
