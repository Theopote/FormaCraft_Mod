import json
for name in ["oct17_evening_7", "oct17_late_3", "oct17_late_4.normalized", "oct20_retry_6.normalized"]:
    d = json.load(open(f"python_backend/tests/fixtures/complex_build_retest/{name}.json", encoding="utf-8"))
    print("===", name, "layout=", (d.get("layout") or {}).get("skeleton_type"), "===")
    for c in d.get("components") or []:
        t = c.get("component_type") or c.get("type")
        p = c.get("params") or {}
        text = json.dumps(c, ensure_ascii=False)
        if any(k in text.lower() for k in ["stair", "from", "to", "circulation", "structure", "connector", "bridge"]):
            if t in ("ROOF", "FACADE_WINDOWS", "ENTRANCE", "DECOR_DETAIL") and "stair" not in text.lower() and "from" not in p:
                continue
            print(f"  {t} id={p.get('component_id')} exterior={p.get('exterior')} host={p.get('host_id')} target={p.get('target_host_id')} from={p.get('from')} to={p.get('to')} dims={c.get('dimensions')} pos={c.get('relative_position')}")
