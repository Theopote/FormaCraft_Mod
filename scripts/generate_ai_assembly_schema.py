"""Generate ai-assembly-schema.json snapshot (mirrors Java AssemblySchemaExporter.exportRuntime)."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "formacraft" / "ai-assembly-schema.json"
PRESET_DIR = ROOT / "src" / "main" / "resources" / "assets" / "formacraft" / "assembly_presets"

OPS = [
    "PUSH_ORIGIN", "POP_ORIGIN", "CLEAR_BOX",
    "ANCHOR_FOOTPRINT", "ANCHORAGE",
    "SHELL_BOX", "CYLINDER", "CONNECTOR_LINE",
    "TRUSS_2D", "ARCH_RIB", "BUTTRESS", "TENSION_CABLE",
    "FRAME_GRID_3D", "STAIR_SYSTEM",
    "BEZIER_SURFACE", "BEZIER_SURFACE_SET", "SURFACE_OFFSET",
    "IMPLICIT_FIELD", "MARCHING_CUBES", "REVOLVE_SURFACE", "LOFT_SURFACE",
    "SPLINE_SWEEP", "SPLINE_TUBE",
    "PATH_ROUTE", "WALL_ROUTE", "BRIDGE_ROUTE",
    "EXTRUDE_POLYGON", "ROOF_COVER", "BSP_FLOOR_PLAN",
    "SURFACE_PATTERN", "FACADE_GRID", "SURFACE_BANDS", "OPENINGS",
]

BUILTIN_PORTS = [
    "center", "bottom_center", "top_center", "bottom", "top", "mid",
    "north", "south", "east", "west", "nw", "ne", "sw", "se",
    "entrance", "exit", "in", "out",
    "front_left", "front_right", "back_left", "back_right",
    "corner_front_left", "corner_front_right", "corner_back_left", "corner_back_right",
]

COMPONENTS = [
    {"type": "SHELL_BOX", "category": "shell", "aliases": ["BOX_SHELL"],
     "requiredParams": ["w", "d", "h"],
     "optionalParams": ["twistTurns", "twistPhase", "wall", "window", "floor", "roof", "facade", "floorStep"],
     "ports": BUILTIN_PORTS},
    {"type": "CYLINDER", "category": "solid", "aliases": [],
     "requiredParams": ["r", "h"],
     "optionalParams": ["radius", "height", "thickness", "hollow", "wall", "material"],
     "ports": BUILTIN_PORTS + ["ne", "nw", "se", "sw"]},
    {"type": "FRAME_GRID_3D", "category": "structure", "aliases": ["FRAMEGRID_3D", "SPACE_FRAME", "EXOSKELETON"],
     "requiredParams": ["w", "d", "h"],
     "optionalParams": ["x0", "x1", "y0", "y1", "z0", "z1", "stepX", "stepY", "stepZ", "step", "thickness", "mode", "diagonal", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "SPLINE_SWEEP", "category": "curve", "aliases": ["SWEEP_SPLINE", "SPLINE_TUBE", "SPLINE"],
     "requiredParams": ["points"],
     "optionalParams": ["profileW", "profileH", "profile", "twistTurns", "twistPhase", "thickness", "hollow", "material", "wall"],
     "ports": BUILTIN_PORTS + ["start", "end", "start_n", "start_s", "start_e", "start_w", "end_n", "end_s", "end_e", "end_w"]},
    {"type": "BEZIER_SURFACE", "category": "surface", "aliases": ["BEZIER_PATCH", "BEZIER"],
     "requiredParams": ["points"],
     "optionalParams": ["p00", "p10", "p01", "p11", "uSamples", "vSamples", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "BEZIER_SURFACE_SET", "category": "surface", "aliases": ["BEZIER_PATCH_SET", "BEZIER_SET"],
     "requiredParams": ["patches"],
     "optionalParams": ["topology", "uSamples", "vSamples", "thickness", "stitch", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "LOFT_SURFACE", "category": "surface", "aliases": ["LOFT", "SKIN_SURFACE"],
     "requiredParams": ["sections"],
     "optionalParams": ["uSamples", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "REVOLVE_SURFACE", "category": "surface", "aliases": ["REVOLVE", "SURFACE_OF_REVOLUTION"],
     "requiredParams": ["profilePoints"],
     "optionalParams": ["segments", "angleDeg", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "IMPLICIT_FIELD", "category": "field", "aliases": ["IMPLICIT"],
     "requiredParams": ["kind"],
     "optionalParams": ["radius", "expression", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "MARCHING_CUBES", "category": "field", "aliases": ["MARCHING"],
     "requiredParams": ["field"],
     "optionalParams": ["resolution", "isoLevel", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "EXTRUDE_POLYGON", "category": "solid", "aliases": ["EXTRUDE"],
     "requiredParams": ["h"],
     "optionalParams": ["points", "shape", "w", "d", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "ROOF_COVER", "category": "roof", "aliases": ["ROOF"],
     "requiredParams": ["w", "d"],
     "optionalParams": ["roofType", "h", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "TENSION_CABLE", "category": "bridge", "aliases": ["CABLE", "SAG_CABLE"],
     "requiredParams": ["from", "to"],
     "optionalParams": ["sag", "samples", "thickness", "material", "cableCount", "cableSpacing"],
     "ports": BUILTIN_PORTS},
    {"type": "ARCH_RIB", "category": "structure", "aliases": ["ARCH", "RIB_ARCH"],
     "requiredParams": ["span", "rise"],
     "optionalParams": ["from", "to", "samples", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "TRUSS_2D", "category": "structure", "aliases": ["TRUSS", "TRUSS2D"],
     "requiredParams": ["from", "to"],
     "optionalParams": ["height", "module", "pattern", "thickness", "chord", "web", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "STAIR_SYSTEM", "category": "circulation", "aliases": ["STAIRS_SYSTEM", "STAIRCASE"],
     "requiredParams": ["from", "to"],
     "optionalParams": ["width", "clearHeight", "carve", "support", "stairs", "floor", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "BUTTRESS", "category": "structure", "aliases": ["FLYING_BUTTRESS"],
     "requiredParams": ["from", "to"],
     "optionalParams": ["width", "thickness", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "CONNECTOR_LINE", "category": "route", "aliases": ["CONNECTOR"],
     "requiredParams": ["from", "to"],
     "optionalParams": ["width", "thickness", "material", "routing"],
     "ports": BUILTIN_PORTS},
    {"type": "ANCHOR_FOOTPRINT", "category": "foundation", "aliases": ["FOOTPRINT_ANCHOR"],
     "requiredParams": ["x0", "x1", "z0", "z1"],
     "optionalParams": ["yBase", "maxDepth", "material"],
     "ports": BUILTIN_PORTS},
    {"type": "ANCHORAGE", "category": "foundation", "aliases": ["ANCHORAGE_BLOCK"],
     "requiredParams": ["w", "d", "h"],
     "optionalParams": ["yBase", "maxDepth", "solid", "material", "holes"],
     "ports": BUILTIN_PORTS},
    {"type": "BSP_FLOOR_PLAN", "category": "interior", "aliases": ["BSP_INTERIOR"],
     "requiredParams": ["w", "d", "h"],
     "optionalParams": ["config", "coreWall", "roomWall", "stairs"],
     "ports": BUILTIN_PORTS},
    {"type": "CLEAR_BOX", "category": "utility", "aliases": ["CARVE_BOX"],
     "requiredParams": ["x0", "x1", "y0", "y1", "z0", "z1"],
     "optionalParams": [],
     "ports": BUILTIN_PORTS},
]

RULES = [
    "Use component_type=ASSEMBLY at top level; never nest params.assembly inside MASS_*.",
    "Prefer preset shorthand when intent matches: spiral_watchtower, suspension_bridge_simple, gothic_shell_box.",
    'graph.connections endpoints must be "ComponentId.port" using exported port ids only.',
    "For conventional buildings use MASS_* + ROOF; use ASSEMBLY preset/graph/ops for freeform geometry.",
    "If geometry is unsupported, return plan_status=capability_gap with capability_gap.code instead of empty components.",
    "Do NOT invent component types or port names outside this schema export.",
]


def load_presets() -> list[dict]:
    presets = []
    if not PRESET_DIR.is_dir():
        return presets
    for path in sorted(PRESET_DIR.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        params = list((data.get("parameters") or {}).keys())
        presets.append({
            "id": data.get("id", path.stem),
            "displayName": data.get("displayName", ""),
            "description": data.get("description", ""),
            "matchKeywords": data.get("matchKeywords") or [],
            "parameters": params,
        })
    return presets


def main() -> None:
    doc = {
        "schemaVersion": 3,
        "generatedAt": "2026-07-05T00:00:00Z",
        "ops": OPS,
        "components": COMPONENTS,
        "presets": load_presets(),
        "compatibilityRules": RULES,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUT} ({len(COMPONENTS)} components, {len(doc['presets'])} presets)")


if __name__ == "__main__":
    main()
