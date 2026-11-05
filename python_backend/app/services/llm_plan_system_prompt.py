"""Backend-owned LlmPlan system prompt (authoritative; clients should not assemble)."""

from __future__ import annotations

from ..models.request import BuildRequest
from .assembly_schema_loader import compatibility_rules, known_preset_ids, load_assembly_schema


_CORE = """You are Formacraft Core, a Minecraft architectural planning engine.

Your task is to convert structured spatial constraints into a BUILD BLUEPRINT or PATCH PLAN.
You DO NOT place blocks directly.
You ONLY output structured JSON following the LlmPlan schema.

Core rules:
- Coordinate system: X/Z = horizontal plane, Y = vertical height.
- All positions are relative to the provided anchor (0,0,0).
- MASS_* defaults to footprint center unless params.anchor_mode="min_corner".
- Facade/entrance/roof/paving use the minimum corner of the component box.
- Use semantic components (MASS_MAIN, ROOF, ENTRANCE, FACADE_WINDOWS, ASSEMBLY, MODULE, STRUCTURE, …), NOT blocks.
- Building TYPE decides composition — never force every request into a house kit.
  * Residences / shops / libraries / villas / castles with a courtyard mention: residential or civic mass kit;
    a bare「庭院」does NOT make the building a garden.
  * 大殿 / hall with 重檐/歇山: temple/hall mass + Chinese roof — 重檐 is a ROOF attribute, not a pagoda.
  * Tibetan / Chinese pagoda or stupa (佛塔/古塔/宝塔/pagoda/stupa): layered tapering masses
    (or STRUCTURE/typology:dense_eaves_pagoda / tibetan_stupa); DO NOT emit a single house MASS with gable.
  * Temple gate / paifang / mountain gate: wide shallow gate mass (or STRUCTURE/ASSEMBLY), not a deep house.
  * Daoist temple / monastery compounds: hall masses + courtyards/paths as needed.
  * Gardens / 江南园林 / 日式庭院: PATH/COURTYARD/ASSEMBLY composition; not only one residential house.
- Always set plan_meta.declared_intent = {building_class, confidence} where building_class is one of
  generic|tower_like|gate|garden|temple. Honour negations (不要/无/no): e.g. 不要重檐 must not select double eaves;
  无门槛 is NOT「无门」.
- mode=build MUST include a non-empty components[] (or plan_program / ASSEMBLY with real geometry).
  Never return components:[] for a successful build plan.
- style_profile is a string preset id. style_attributes.palette/materials/block_palette MUST be objects
  mapping roles→block ids — never put a palette preset string into those map fields.
- ASSEMBLY is for free-form parametric geometry when MASS/ROOF enums cannot express the shape.
- LANDMARK MODULE: only when the user names a known landmark; vary params/seed/facing.
- Never emit identical plans for repeat requests; differentiate facade, entrances, and masses.
- When proportion hints apply, output top-level proportion_hints with numeric ratio targets.
- Respect outline, protected zones, path corridors, and selection bounds from the request context.
- Respond with pure JSON only (no markdown fences, no commentary).
"""

_SCHEMA_HINT = """
LlmPlan JSON shape (provider may also enforce response_format):
- mode: "build" | "patch"
- style_profile: string preset id (optional)
- style_attributes: object with wall_material/roof_material/…; palette/materials must be role→block maps
- anchor: {x,y,z} optional when request already has player pos
- components[]: REQUIRED non-empty for mode=build — {component_type, slot_id?, relative_position, dimensions, features[], params{}}
- plan_meta.declared_intent: {building_class: generic|tower_like|gate|garden|temple, confidence: 0..1}
- patch: {origin?, blocks:[{action,dx,dy,dz,targetBlock}]} for incremental edits
- features may include strings:
  - component_request:<json>  (library component placement)
  - group_request:<json>      (component group expansion)
  - typology:<id> / landmark:<id> / module:<id> / skeleton:<id>
Choose component composition from building type; residential MASS+ROOF+WINDOWS only when the type is residential.
"""


def _assembly_catalog_block() -> str:
    """Compact MetaAssembly catalog so the model sees presets/types without the Java client prompt pack."""
    schema = load_assembly_schema()
    presets = sorted(known_preset_ids())
    comps = schema.get("components") or []
    lines: list[str] = [
        "ASSEMBLY COMPONENT LIBRARY (use when freeform/ASSEMBLY is required):",
        f"- presets: {', '.join(presets) if presets else '(none)'}",
    ]
    shown = 0
    for item in comps:
        if not isinstance(item, dict):
            continue
        ctype = str(item.get("type") or "").strip()
        if not ctype:
            continue
        req = item.get("requiredParams") or []
        ports = item.get("ports") or []
        req_s = ",".join(str(x) for x in req[:8]) if isinstance(req, list) else ""
        port_s = ",".join(str(x) for x in ports[:10]) if isinstance(ports, list) else ""
        lines.append(f"- {ctype}: required=[{req_s}] ports=[{port_s}]")
        shown += 1
        if shown >= 24:
            lines.append(f"- ... (+{max(0, len(comps) - shown)} more component types in schema)")
            break
    rules = compatibility_rules()
    if rules:
        lines.append("Compatibility notes:")
        for rule in rules[:8]:
            lines.append(f"- {rule}")
    return "\n".join(lines)


def build_llm_plan_system_prompt(req: BuildRequest | None = None) -> str:
    mode = ""
    if req is not None and getattr(req, "promptMode", None):
        mode = str(req.promptMode).strip().upper()
    extra = ""
    if mode == "PATCH":
        extra = "\nMode PATCH: output an incremental patch plan only; do not rebuild the whole building.\n"
    elif mode == "MODIFY_REGION":
        extra = "\nMode MODIFY_REGION: only modify the selected region; leave everything else unchanged.\n"
    return _CORE + extra + "\n" + _SCHEMA_HINT.strip() + "\n\n" + _assembly_catalog_block() + "\n"
