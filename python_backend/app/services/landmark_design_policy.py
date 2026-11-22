"""Keep researched landmark design under model authority, not preset repair."""
from copy import deepcopy


def reject_unrequested_fixed_routes(plan, user_text):
    from .building_research_agent import resolve_explicit_structural_typology, resolve_landmark_module_for_intent
    from .typology_plan_repair import _extract_landmark_id
    from .typology_registry import get_migration, get_typology, is_compositional_typology

    if plan.get("plan_status") == "capability_gap":
        return None
    preset = resolve_landmark_module_for_intent(user_text)
    typology = resolve_explicit_structural_typology(user_text)
    migration = get_migration(preset) if preset else None
    allowed_typology = typology or (migration.typology_id if migration else None)
    components = plan.get("components")
    if not isinstance(components, list):
        return None
    for index, component in enumerate(components):
        if not isinstance(component, dict):
            continue
        kind = str(component.get("component_type") or "").upper()
        params = component.get("params") or {}
        if not isinstance(params, dict):
            params = {}
        raw_features = component.get("features")
        features = [f.strip().lower() for f in raw_features if isinstance(f, str)] if isinstance(raw_features, list) else []
        landmark = _extract_landmark_id(component)
        fixed_types = {f.split(":", 1)[1].strip() for f in features if f.startswith("typology:")}
        fixed_types.update(str(params[key]).strip().lower() for key in (
            "typology_id", "structural_typology", "typologyId", "structuralTypologyId",
        ) if params.get(key))
        fixed_types = {t for t in fixed_types if get_typology(t) and not is_compositional_typology(t)}
        module = kind in {"MODULE", "LANDMARK"} or landmark is not None
        reference = str(params.get("reference_landmark") or "").strip().lower()
        if (module and (not preset or landmark != preset)
                or kind == "STRUCTURE" and fixed_types
                and (fixed_types != {allowed_typology} or reference and reference != preset)):
            result = deepcopy(plan)
            result["components"].pop(index)
            result["plan_status"] = "capability_gap"
            result["capability_gap"] = {
                "code": "FIXED_LANDMARK_PRESET_DISALLOWED",
                "path": f"components[{index}]",
                "message": "A whole-building executor requires the matching explicit user request.",
                "suggestions": ["Replan the requested geometry using supported components or FBD."],
            }
            return result
    return None


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
