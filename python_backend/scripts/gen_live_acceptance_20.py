"""Generate frozen plan fixtures for the 20 live-acceptance prompts."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.models.llm_plan import validate_llm_plan_dict
from app.services.generation_capability import apply_stage_b_capability_repair
from app.services.plan_semantic_gate import assert_plan_semantics
from app.services.request_intent import (
    attach_request_intent,
    extract_request_intent,
    scaffold_plan_for_intent,
)

OUT = ROOT / "src" / "test" / "resources" / "regressions" / "live_acceptance_20"

# (id, prompt, expected_building_class, expected_tower_subtype|None)
CASES = [
    ("01_tibetan_stupa_9", "生成一座白色藏式佛塔，主体尺寸9×9", "tower_like", "tibetan_stupa"),
    ("02_pagoda_3_chongyan", "生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like", "dense_eaves_pagoda"),
    ("03_pagoda_5", "生成一座五层中式宝塔，顶部有塔刹", "tower_like", "dense_eaves_pagoda"),
    ("04_temple_gate", "生成一座中式寺庙山门", "gate", None),
    ("05_paifang", "生成一座中式牌坊", "gate", None),
    ("06_daoguan_single", "生成一座传统道观", "temple", None),
    ("07_temple_compound", "生成一座寺庙建筑群，两进院落与厢房", "temple", None),
    ("08_jiangnan_garden", "生成一座江南园林", "garden", None),
    ("09_japanese_garden", "生成一座日式庭院", "garden", None),
    ("10_huizhou_house", "生成一栋徽派民居，白墙黑瓦", "generic", None),
    ("11_xieshan_stone_shrine", "生成一座歇山顶道观，石砖墙", "temple", None),
    ("12_wudian_hall", "生成一座庑殿顶道观大殿", "temple", None),
    ("13_wood_house_no_windows", "生成一座11×9的两层木屋，不要窗", "generic", None),
    ("14_arched_entrance_house", "生成一座带拱形大门的石砖民居，尺寸13×11", "generic", None),
    ("15_siheyuan_courtyard", "生成一座四合院庭院", "garden", None),
    ("16_pagoda_7_white", "生成一座7×7三层密檐古塔，白墙", "tower_like", "dense_eaves_pagoda"),
    ("17_daoguan_xieshan", "生成一座道观主殿，歇山顶", "temple", None),
    ("18_japanese_tea_garden", "生成一座日式茶室庭院", "garden", None),
    ("19_modern_flat_house", "生成一座简约现代平顶住宅，尺寸12×10", "generic", None),
    ("20_pavilion_garden", "生成一座带亭子的江南园林", "garden", None),
]


def _architecture_intent(prompt: str) -> dict:
    return {
        "schema": "formacraft.architecture_intent.v1",
        "stage": "request_observation",
        "precedence": ["explicit_request", "purpose_rules", "style_defaults", "material_defaults"],
        "style_identities": [],
        "scopes": [{
            "scope": "global",
            "text": prompt,
            "purposes": [],
            "styles": [],
            "structures": [],
        }],
        "original_text": prompt,
        "scope_binding": "lexical_not_component_binding",
        "unrecognized_text_preserved": True,
        "limits": [
            "Alias coverage is finite.",
            "Observations do not certify generated style or functional rooms.",
        ],
    }


def _generic_house_plan(prompt: str, intent: dict) -> dict:
    fp = intent.get("footprint") or {}
    width = int(fp.get("width") or 11)
    depth = int(fp.get("depth") or 9)
    materials = intent.get("materials") or {}
    wall = materials.get("wall") or "minecraft:oak_planks"
    roof_mat = materials.get("roof") or "minecraft:dark_oak_stairs"
    forbids = list(intent.get("forbids") or [])
    roof_type = intent.get("roof_type") or ("flat" if "平顶" in prompt or "flat" in prompt.lower() else "gable")
    if "徽派" in prompt or "黑瓦" in prompt:
        wall = materials.get("wall") or "minecraft:white_concrete"
        roof_mat = materials.get("roof") or "minecraft:black_terracotta"
        roof_type = "xieshan"
    if "石砖" in prompt:
        wall = materials.get("wall") or "minecraft:stone_bricks"
    height = 8 if "两层" in prompt else 6
    if "平顶" in prompt:
        height = 5
    features = []
    if "拱" in prompt:
        features.append("arched")
    door_w = 3 if width >= 7 else 2
    entrance = {
        "component_type": "ENTRANCE",
        "slot_id": "main",
        "relative_position": {"x": width // 2 - door_w // 2, "y": 0, "z": 0},
        "dimensions": {"width": door_w, "depth": 1, "height": 3},
        "features": features,
        "params": {
            "component_id": "main_door",
            "host_id": "main",
            "door_width": door_w,
            "door_height": 3,
        },
    }
    mass_params = {
        "component_id": "main",
        "wall_block": wall,
        "roof_block": roof_mat,
        "roof_type": roof_type,
        "door_width": door_w,
        "anchor_mode": "min_corner",
    }
    if "windows" in forbids:
        mass_params["window_style"] = "none"
        mass_params["window_ratio"] = 0
    comps = [
        {
            "component_type": "MASS_MAIN",
            "slot_id": "main",
            "relative_position": {"x": 0, "y": 0, "z": 0},
            "dimensions": {"width": width, "depth": depth, "height": height},
            "features": features,
            "params": mass_params,
        },
        {
            "component_type": "ROOF",
            "slot_id": "main",
            "relative_position": {"x": -1, "y": height, "z": -1},
            "dimensions": {
                "width": width + 2,
                "depth": depth + 2,
                "height": 1 if roof_type == "flat" else 3,
            },
            "features": [],
            "params": {
                "component_id": "main_roof",
                "host_id": "main",
                "roof_type": roof_type,
                "roof_block": roof_mat if roof_type != "flat" else "minecraft:stone",
            },
        },
    ]
    if "entrance" not in forbids:
        comps.append(entrance)
    return {
        "mode": "build",
        "style_profile": "DEFAULT",
        "anchor": {"x": 0, "y": 64, "z": 0},
        "distinguishing_features": list(intent.get("markers") or ["house"]),
        "style_attributes": {
            "wall_material": wall,
            "roof_material": roof_mat,
            "palette": {"wall": wall, "roof": roof_mat},
        },
        "components": comps,
        "global_constraints": {"facing": "SOUTH", "symmetry": "NONE", "terrain_strategy": "PRESERVE"},
        "layout": {
            "slots": [{"slot_id": "main", "anchor": {"x": 0, "y": 0, "z": 0}, "facing": "SOUTH"}]
        },
        "plan_meta": {
            "building_class": "generic",
            "scaffolded_from_request_intent": True,
            "allow_compiler_satellites": False,
        },
        "proportion_hints": {"architecture_intent": _architecture_intent(prompt)},
    }


def build_plan(fixture_id: str, prompt: str) -> dict:
    intent = extract_request_intent(prompt)
    # Offline fixtures use a fixed relative origin (not silent player-spawn drift).
    plan = scaffold_plan_for_intent(intent, anchor={"x": 8, "y": 64, "z": 8})
    if plan is None:
        plan = _generic_house_plan(prompt, intent)
    # Japanese gardens: keep an irimoya token for catalog alias coverage, then Stage-B maps it.
    if "日式" in prompt and plan.get("components"):
        for c in plan["components"]:
            if c.get("component_type") in ("MASS_MAIN", "ROOF") and isinstance(c.get("params"), dict):
                if c["component_type"] == "ROOF" or c.get("params", {}).get("roof_type"):
                    c.setdefault("params", {})["roof_type"] = "irimoya"
                    break
    attach_request_intent(plan, prompt)
    plan.setdefault("plan_meta", {})
    # Compiler requires explicit OMITTED for satellites we intentionally skip.
    reqs = dict(plan["plan_meta"].get("design_requirements") or {})
    reqs.setdefault("facade", "OMITTED")
    reqs.setdefault("entrance", "OMITTED")
    if any(c.get("component_type") == "STRUCTURE" for c in plan.get("components") or []):
        reqs.setdefault("roof", "OMITTED")
    if not any(c.get("component_type") == "ROOF" for c in plan.get("components") or []):
        reqs.setdefault("roof", "OMITTED")
    plan["plan_meta"]["design_requirements"] = reqs
    plan["plan_meta"]["acceptance_prompt"] = prompt
    plan["plan_meta"]["fixture_id"] = fixture_id
    plan["plan_meta"]["request_intent_text"] = prompt
    plan["plan_meta"]["allow_compiler_satellites"] = False
    plan.setdefault("proportion_hints", {})
    plan["proportion_hints"].setdefault("architecture_intent", _architecture_intent(prompt))
    plan, _errors = apply_stage_b_capability_repair(plan, prompt)
    attach_request_intent(plan, prompt)
    plan["plan_meta"]["acceptance_prompt"] = prompt
    plan["plan_meta"]["fixture_id"] = fixture_id
    plan["plan_meta"]["design_requirements"] = reqs
    return plan


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    catalog = []
    failures = []
    for fixture_id, prompt, building_class, typology in CASES:
        intent = extract_request_intent(prompt)
        if intent["building_class"] != building_class:
            failures.append(f"{fixture_id}: class {intent['building_class']} != {building_class}")
        if typology and intent.get("tower_subtype") != typology:
            failures.append(
                f"{fixture_id}: subtype {intent.get('tower_subtype')} != {typology}"
            )
        plan = build_plan(fixture_id, prompt)
        try:
            validate_llm_plan_dict(plan)
            assert_plan_semantics(plan, prompt)
        except Exception as e:
            failures.append(f"{fixture_id}: validate/semantics failed: {e}")
            continue
        path = OUT / f"{fixture_id}.json"
        path.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        catalog.append({
            "fixture_id": fixture_id,
            "prompt": prompt,
            "building_class": building_class,
            "tower_subtype": typology,
        })
        print(f"OK {fixture_id}")
    (OUT / "catalog.json").write_text(
        json.dumps({"cases": catalog}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if failures:
        print("FAILURES:")
        for f in failures:
            print(" -", f)
        return 1
    print(f"Wrote {len(catalog)} fixtures to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
