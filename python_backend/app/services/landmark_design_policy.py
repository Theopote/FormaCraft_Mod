"""Keep researched landmark design under model authority, not preset repair."""
from copy import deepcopy


def reject_fixed_landmark_plan(plan, profile):
    if not getattr(getattr(profile, "minecraft_strategy", None), "model_designed_landmark", False):
        return None
    for component in plan.get("components") or []:
        if not isinstance(component, dict):
            continue
        params = component.get("params") or {}
        if not isinstance(params, dict):
            params = {}
        from .archetype_registry import get_archetype_def
        named_generator = get_archetype_def(str(component.get("component_type", "")).lower())
        features = component.get("features") or []
        fixed = str(component.get("component_type", "")).upper() in {"MODULE", "LANDMARK", "HOUSE", "CASTLE", "BUILDING", "COMPOUND"}
        fixed = fixed or bool(named_generator and named_generator.generator_id and not named_generator.research_only)
        fixed = fixed or isinstance(params.get("assembly"), str)
        fixed = fixed or any(params.get(key) for key in (
            "module_id", "landmark", "template", "typology_id", "structural_typology", "reference_landmark", "structure_generator", "blueprint"))
        fixed = fixed or any(isinstance(f, str) and f.strip().lower().startswith(
            ("landmark:", "module:", "typology:", "structure_generator:")) for f in features)
        if fixed:
            result = deepcopy(plan)
            result["components"] = []
            result["plan_status"] = "capability_gap"
            result["capability_gap"] = {
                "code": "FIXED_LANDMARK_PRESET_DISALLOWED",
                "message": "Model-designed landmark requires compositional geometry; a fixed preset cannot substitute for the design.",
                "path": "components",
                "suggestions": ["Describe massing and structure using supported components or FBD."],
            }
            return result
    return None
