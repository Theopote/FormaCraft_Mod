"""Sync typology golden fixtures _meta and scenarios.json entries."""

from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).resolve().parent / "typology_golden_manifest.json"
PLANS_DIR = Path(__file__).resolve().parent / "fixtures" / "plans"
SCENARIOS = Path(__file__).resolve().parent / "fixtures" / "scenarios.json"


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for entry in manifest:
        path = PLANS_DIR / Path(entry["plan_fixture"]).name
        plan = json.loads(path.read_text(encoding="utf-8"))
        meta = plan.get("_meta") if isinstance(plan.get("_meta"), dict) else {}
        meta.update(
            {
                "prompt": entry["prompt"],
                "reference_landmark": entry["reference_landmark"],
                "structural_typology": entry["typology_id"],
                "skeleton_type": entry["skeleton_type"],
            }
        )
        plan["_meta"] = meta
        path.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    scenarios = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    by_id = {s.get("id"): s for s in scenarios if isinstance(s, dict)}
    for entry in manifest:
        notes = [
            f"STRUCTURE + typology:{entry['typology_id']} (reference_landmark={entry['reference_landmark']})",
            f"layout.skeleton_type={entry['skeleton_type']}; FORBID MODULE + landmark",
            f"proportion_card {entry['proportion_card']}",
        ]
        if entry["id"] in by_id:
            row = by_id[entry["id"]]
            row["ideal_notes"] = notes
            row["proportion_card"] = entry["proportion_card"]
            row["tags"] = list(dict.fromkeys((row.get("tags") or []) + entry["tags"]))
            row["ci"] = True
            continue
        scenarios.append(
            {
                "id": entry["id"],
                "prompt": entry["prompt"],
                "plan_fixture": entry["plan_fixture"],
                "tags": entry["tags"] + ["ci"],
                "proportion_card": entry["proportion_card"],
                "ci": True,
                "ideal_notes": notes,
            }
        )
    SCENARIOS.write_text(json.dumps(scenarios, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"updated {len(manifest)} typology goldens; scenarios={len(scenarios)}")


if __name__ == "__main__":
    main()
