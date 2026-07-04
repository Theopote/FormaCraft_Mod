"""
ReferenceBlueprint — Vision / 用户上传 JSON 的结构化参考蓝图。

对齐 Gemini 多模态归纳格式：metadata、block_palette、architectural_layers、generation_rules。
用于 Stage R → BuildingProfile.reference_blueprint → LlmPlan 生成。
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field


REFERENCE_BLUEPRINT_VISION_PROMPT = """Analyze the building(s) in the reference image for Minecraft reconstruction.
Return ONE JSON object with this structure (use null/omit optional sections if unknown):

{
  "metadata": {
    "project_name": "snake_case id",
    "source_image": "optional filename hint",
    "dimensions": { "width_x": int, "height_y": int, "depth_z": int },
    "style": "style tag e.g. cyberpunk_slum_steampunk | oriental_fantasy_steampunk"
  },
  "block_palette": {
    "<semantic_group>": {
      "<role>": ["minecraft:block_id", "..."]
    }
  },
  "structural_backbone": {
    "description": "string",
    "center_axis": { "x": int, "z": int },
    "main_tower_radius": int
  },
  "architectural_layers": [
    {
      "layer_id": "string",
      "y_range": [min_y, max_y],
      "description": "string",
      "components": [
        {
          "name": "string",
          "bounding_box": { "min": [x,y,z], "max": [x,y,z] },
          "primary_materials": ["palette role keys"],
          "features": ["detailed feature strings"]
        }
      ]
    }
  ],
  "generation_rules": {
    "<rule_name>": "string or object with method/notes"
  }
}

Rules:
- Use ONLY valid vanilla minecraft:block_id values in block_palette.
- dimensions are block counts (reasonable for Minecraft builds, often 8–64).
- architectural_layers bottom-to-top; bounding_box coords relative to build origin (0,0,0).
- primary_materials refer to keys inside block_palette groups (not raw block ids).
- generation_rules capture texture mixing ratios, asymmetry, micro-detail techniques.
- Output ONLY valid JSON, no markdown."""


class ReferenceBlueprint(BaseModel):
    model_config = ConfigDict(extra="allow")

    metadata: Optional[Dict[str, Any]] = None
    block_palette: Optional[Dict[str, Any]] = None
    structural_backbone: Optional[Dict[str, Any]] = None
    architectural_layers: Optional[List[Dict[str, Any]]] = None
    generation_rules: Optional[Dict[str, Any]] = None
    detailing_rules: Optional[Dict[str, Any]] = None

    def to_prompt_dict(self) -> Dict[str, Any]:
        return self.model_dump(exclude_none=True)

    def style_tag(self) -> Optional[str]:
        meta = self.metadata or {}
        style = meta.get("style")
        return str(style).strip() if style else None

    def dimensions(self) -> Dict[str, Optional[int]]:
        meta = self.metadata or {}
        dims = meta.get("dimensions") if isinstance(meta.get("dimensions"), dict) else {}
        return {
            "width_x": _coerce_int(dims.get("width_x")),
            "height_y": _coerce_int(dims.get("height_y")),
            "depth_z": _coerce_int(dims.get("depth_z")),
        }

    def distinctive_features(self) -> List[str]:
        out: List[str] = []
        for layer in self.architectural_layers or []:
            if not isinstance(layer, dict):
                continue
            desc = str(layer.get("description") or "").strip()
            if desc:
                out.append(desc)
            for comp in layer.get("components") or []:
                if not isinstance(comp, dict):
                    continue
                for feat in comp.get("features") or []:
                    if feat and str(feat) not in out:
                        out.append(str(feat))
        rules = self.generation_rules or self.detailing_rules or {}
        if isinstance(rules, dict):
            for k, v in rules.items():
                if isinstance(v, str) and v.strip():
                    out.append(f"{k}: {v.strip()}")
                elif isinstance(v, list):
                    out.extend(str(x) for x in v if x)
        return out[:20]

    def summary_for_notes(self, max_chars: int = 1200) -> str:
        parts: List[str] = []
        meta = self.metadata or {}
        if meta.get("project_name"):
            parts.append(f"project={meta.get('project_name')}")
        if meta.get("style"):
            parts.append(f"style={meta.get('style')}")
        dims = self.dimensions()
        if any(dims.values()):
            parts.append(
                f"dims={dims.get('width_x')}x{dims.get('height_y')}x{dims.get('depth_z')}"
            )
        parts.extend(self.distinctive_features()[:6])
        text = " | ".join(parts)
        return text[:max_chars]


def _coerce_int(v: Any) -> Optional[int]:
    try:
        if v is None:
            return None
        return int(v)
    except (TypeError, ValueError):
        return None


def parse_reference_blueprint(data: Any) -> Optional[ReferenceBlueprint]:
    if data is None:
        return None
    if isinstance(data, ReferenceBlueprint):
        return data
    if isinstance(data, str):
        text = data.strip()
        if not text:
            return None
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return None
    if not isinstance(data, dict):
        return None
    try:
        return ReferenceBlueprint.model_validate(data)
    except Exception:
        # tolerate partial Gemini output
        return ReferenceBlueprint.model_validate({k: v for k, v in data.items() if k in (
            "metadata", "block_palette", "structural_backbone",
            "architectural_layers", "generation_rules", "detailing_rules",
        )})
