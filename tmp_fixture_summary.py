import json
for name in ["oct17_evening_7", "oct17_late_3", "oct17_late_4.normalized", "oct20_retry_6.normalized"]:
    path = f"python_backend/tests/fixtures/complex_build_retest/{name}.json"
    d = json.load(open(path, encoding="utf-8"))
    comps = d.get("components") or []
    print("===", name, "n=", len(comps), "===")
    for c in comps:
        t = c.get("component_type") or c.get("type")
        p = c.get("params") or {}
        interesting = t in {
            "CIRCULATION_CONNECTOR", "BRIDGE", "STAIR_SYSTEM", "MASS_MAIN",
            "MASS_SECONDARY", "DECOR_DETAIL", "TERRACE"
        } or p.get("target_host_id") or p.get("exterior") or p.get("role") in {
            "column", "pillar", "post", "bridge"
        } or p.get("wall_style") or p.get("open") or p.get("open_pavilion") or p.get("open_sides")
        if not interesting:
            continue
        print(
            f"  {t} id={p.get('component_id')} host={p.get('host_id')} "
            f"target={p.get('target_host_id')} role={p.get('role')} "
            f"exterior={p.get('exterior')} wall_style={p.get('wall_style')} "
            f"open={p.get('open')} open_pavilion={p.get('open_pavilion')} "
            f"open_sides={p.get('open_sides')} from={p.get('from')} to={p.get('to')}"
        )
