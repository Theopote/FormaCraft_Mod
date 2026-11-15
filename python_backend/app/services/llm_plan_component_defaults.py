"""Existing component parameter completion for normalized LlmPlan output.

Authored style defaults and feature/genome hints fill missing keys only.
"""

from typing import Any, Dict


def _fill_component_params(params: Dict[str, Any], comp: Dict[str, Any], genome: Dict[str, Any]) -> None:
    ctype = str(comp.get("component_type", "")).upper()
    from .style_feature_compiler import component_style_defaults
    for key, value in component_style_defaults(comp.get("_style_hint"), ctype).items():
        params.setdefault(key, value)
    features = comp.get("features") or []
    dims = comp.get("dimensions") or {}

    def feat_contains(token: str) -> bool:
        return any(token in str(f).lower() for f in features if f)

    def genome_contains(token: str) -> bool:
        cultural = genome.get("culturalStyle") or {}
        region = str(cultural.get("region") or "").lower()
        keywords = cultural.get("keywords") or []
        if token and token in region:
            return True
        return any(token in str(k).lower() for k in keywords if k)

    if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING"):
        if "shape" not in params:
            layout = ((genome.get("topology") or {}).get("layout") or "").lower()
            if "circular" in layout or feat_contains("round") or feat_contains("circle"):
                params["shape"] = "circle"
            elif feat_contains("rounded"):
                params["shape"] = "rounded_rect"
            else:
                params["shape"] = "rectangle"
        if "roof_type" not in params:
            if feat_contains("xieshan") or feat_contains("hip_and_gable") or feat_contains("歇山"):
                params["roof_type"] = "xieshan"
            elif feat_contains("xuanshan") or feat_contains("悬山"):
                params["roof_type"] = "xuanshan"
            elif feat_contains("double_gable") or feat_contains("double gable") or feat_contains("shuangpo") or feat_contains("双坡"):
                params["roof_type"] = "double_gable"
            elif feat_contains("gable"):
                params["roof_type"] = "gable"
            elif feat_contains("hip"):
                params["roof_type"] = "hip"
            elif feat_contains("pyramid"):
                params["roof_type"] = "pyramid"
            elif feat_contains("cone"):
                params["roof_type"] = "cone"
            elif feat_contains("dome") or feat_contains("curved"):
                params["roof_type"] = "dome"
        if "roof_type" not in params:
            if feat_contains("chinese") or feat_contains("hui") or genome_contains("chinese") or genome_contains("hui"):
                params["roof_type"] = "xuanshan"
            elif feat_contains("gothic") or feat_contains("medieval") or genome_contains("gothic") or genome_contains("medieval"):
                params["roof_type"] = "gable"
            elif feat_contains("modern") or genome_contains("modern"):
                params["roof_type"] = "flat"
        if "window_ratio" not in params:
            params["window_ratio"] = 0.6 if feat_contains("glass") or feat_contains("curtain") else 0.35
        if "void_ratio" not in params:
            vr = ((genome.get("structure") or {}).get("voidRatio"))
            params["void_ratio"] = vr if vr is not None else (0.35 if feat_contains("courtyard") else 0.15)
        if "plan_type" not in params:
            plan_alias = params.get("footprint_pattern") or params.get("plan_pattern") or params.get("planPattern")
            if plan_alias:
                params["plan_type"] = plan_alias
            else:
                plan_type = None
                if feat_contains("courtyard") or feat_contains("siheyuan"):
                    plan_type = "courtyard"
                elif feat_contains("gothic") or feat_contains("cathedral") or feat_contains("cruciform"):
                    plan_type = "cross"
                elif feat_contains("l-shape") or feat_contains("l_shape") or feat_contains("corner_wing"):
                    plan_type = "l_shape"
                elif feat_contains("chinese") or feat_contains("hui") or genome_contains("chinese") or genome_contains("hui"):
                    plan_type = "cut_corners"
                if plan_type:
                    params["plan_type"] = plan_type
        if params.get("plan_type") == "courtyard" and "courtyard_ratio" not in params:
            ratio = params.get("void_ratio")
            if isinstance(ratio, (int, float)):
                params["courtyard_ratio"] = float(max(0.2, min(0.6, ratio)))
        if "setback_ratio" not in params:
            prog = ((genome.get("form") or {}).get("progression") or "").lower()
            if prog in ("stepping", "tapering"):
                params["setback_ratio"] = 0.06
        if "floor_height" not in params and dims.get("height"):
            params["floor_height"] = max(3, int(dims.get("height", 6) / max(1, dims.get("height", 6) // 3)))
        if "wall_pattern" not in params and params.get("facade_profile") == "vertical_pilasters":
            params["wall_pattern"] = "gradient"

    if ctype == "ROOF":
        parent_h = dims.get("height")
        if "roof_height" not in params and isinstance(parent_h, int) and parent_h > 0:
            params["roof_height"] = max(2, min(12, parent_h))
        if "overhang" not in params:
            params["overhang"] = 2

    if ctype == "FACADE_WINDOWS":
        if "window_ratio" not in params:
            params["window_ratio"] = 0.5 if feat_contains("large") else 0.35
        if "window_style" not in params:
            if feat_contains("lattice"):
                params["window_style"] = "lattice"
            elif feat_contains("stained"):
                params["window_style"] = "stained"
        if "rhythm" not in params:
            rhythm = ((genome.get("form") or {}).get("rhythm"))
            if rhythm:
                params["rhythm"] = rhythm

    if ctype == "ENTRANCE":
        if "door_width" not in params and dims.get("width"):
            params["door_width"] = max(2, int(dims.get("width", 3)) - 2)
        if "door_height" not in params and dims.get("height"):
            params["door_height"] = min(4, int(dims.get("height", 4)))
