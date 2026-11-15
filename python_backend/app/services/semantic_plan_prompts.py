"""Prompt construction for the existing semantic spatial planning stage."""

from ..models.request import BuildRequest
from .planning_request_hints import _infer_scale, _infer_terrain


def build_semantic_plan_prompts(req: BuildRequest) -> tuple[str, str]:
    system_prompt = (
        "You are FormaCraft Spatial Planner.\n"
        "Your task is NOT to generate blocks or buildings directly.\n"
        "Your task is to analyze user intent and produce a structured Semantic Spatial Plan for a Minecraft build cluster.\n"
        "You must think like an architect + urban planner. Terrain, circulation, hierarchy, and spatial meaning matter.\n\n"
        "STRICT OUTPUT RULES:\n"
        "- Output MUST be valid JSON only. No markdown. No explanations.\n"
        "- Output MUST match the schema exactly. Do NOT add extra keys.\n"
        "- All enum values MUST be one of the allowed strings listed in the schema.\n\n"
        "SCHEMA:\n"
        "{\n"
        '  "zones": [\n'
        "    {\n"
        '      "id": "string",\n'
        '      "type": "CORE|PUBLIC|SEMI_PUBLIC|PRIVATE|SERVICE|LANDSCAPE|TRANSITION|CIRCULATION",\n'
        '      "priority": 1,\n'
        '      "terrain_policy": "PRESERVE_DOMINANT|BALANCED|ENGINEERED",\n'
        '      "notes": "string"\n'
        "    }\n"
        "  ],\n"
        '  "relations": [\n'
        "    {\n"
        '      "from": "zone_id",\n'
        '      "to": "zone_id",\n'
        '      "relation": "DIRECT|BUFFERED|FORBIDDEN|AXIAL|VISUAL_ONLY",\n'
        '      "notes": "string"\n'
        "    }\n"
        "  ],\n"
        '  "circulation": {\n'
        '    "primary_flow": ["zone_id"],\n'
        '    "secondary_flow": ["zone_id"]\n'
        "  },\n"
        '  "constraints": {\n'
        '    "prefer_axis_alignment": true,\n'
        '    "avoid_direct_private_access": true,\n'
        '    "max_terrain_disturbance": "LOW|MEDIUM|HIGH",\n'
        '    "terrain_budget_blocks": 0\n'
        "  }\n"
        "}\n"
    )

    scale = _infer_scale(req)
    terrain = _infer_terrain(req)
    anchor = req.player.pos

    selection_str = "none"
    if req.selection is not None:
        smin = req.selection.min
        smax = req.selection.max
        selection_str = f"box(min=({smin.x},{smin.y},{smin.z}), max=({smax.x},{smax.y},{smax.z}))"

    user_prompt = (
        "User Intent:\n"
        f"\"{req.requestText}\"\n\n"
        "Context:\n"
        "- Minecraft world\n"
        "- Building cluster (multiple structures possible)\n"
        f"- Scale: {scale}\n"
        f"- Terrain: {terrain}\n"
        f"- Anchor: anchor=({anchor.x},{anchor.y},{anchor.z}) facing={req.player.facing}\n"
        f"- Selection Area: {selection_str}\n\n"
        "Your task:\n"
        "1) Identify the major semantic zones required.\n"
        "2) Assign each zone a semantic type and priority.\n"
        "3) Describe spatial relationships between zones.\n"
        "4) Indicate terrain sensitivity per zone.\n"
        "5) Indicate circulation and access logic.\n\n"
        "Output JSON ONLY matching the schema.\n"
    )
    return system_prompt, user_prompt
