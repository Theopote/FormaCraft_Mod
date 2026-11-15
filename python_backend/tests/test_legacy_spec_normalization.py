import pytest

from app.services import legacy_spec_normalization as normalization


@pytest.mark.parametrize("value", [None, [], "invalid", 42])
def test_non_objects_pass_through(value):
    assert normalization._normalize_building_spec_dict(value) is value
    assert normalization._normalize_composite_spec_dict(value) is value


def test_building_aliases_defaults_and_in_place_contract():
    data = {"buildingType": "TOWER", "buildingStyle": "MODERN", "height": -1}
    assert normalization._normalize_building_spec_dict(data) is data
    assert data["type"] == "TOWER"
    assert data["style"] == "MODERN"
    assert data["height"] == 10
    assert data["materials"] == data["features"] == {}
    assert data["footprint"] == {"shape": "rectangle", "width": 8, "depth": 6}


def test_explicit_building_values_win_over_aliases():
    footprint = {"shape": "circle", "radius": 7}
    data = {"type": "HOUSE", "buildingType": "TOWER", "style": "RUSTIC",
            "buildingStyle": "MODERN", "height": "12", "footprint": footprint}
    result = normalization._normalize_building_spec_dict(data)
    assert result["type"] == "HOUSE"
    assert result["style"] == "RUSTIC"
    assert result["height"] == "12"
    assert result["footprint"] is footprint


def test_catalog_fallback_and_layout_normalization(monkeypatch):
    from app.services import style_profile_registry as registry

    monkeypatch.setattr(registry, "has_style_profile", lambda value: value == "known")
    monkeypatch.setattr(registry, "has_palette", lambda value: value == "default")
    monkeypatch.setattr(registry, "default_palette_for_style", lambda value: "default")
    data = {"style_profile_id": "known", "palette_id": "missing",
            "layout": {"entrance_facing": " east ", "symmetry": "both",
                       "floor_plan": "front/back", "courtyard": "yes", "courtyard_ratio": 2}}
    extra = normalization._normalize_building_spec_dict(data)["extra"]
    assert extra["styleProfileId"] == "known"
    assert extra["paletteId"] == "default"
    assert extra["paletteIdAutoFromStyle"] is True
    assert extra["debugWarnings"] == ["Invalid extra.paletteId='missing', dropped."]
    assert extra["layout"] == {"entranceFacing": "EAST", "symmetry": "BOTH",
                               "plan": "front_back", "courtyard": True, "courtyardRatio": 0.8}


def test_composite_plan_aliases_and_nested_defaults():
    data = {"buildings": [{"name": "watch tower", "position": {"x": "4", "y": 0, "z": -3},
                           "dimensions": {"width": 5, "depth": 7, "height": 15},
                           "style": "Chinese palace", "features": ["door", "decorative_eaves"]}, None],
            "roads": [{"width": 3}]}
    assert normalization._normalize_composite_spec_dict(data) is data
    tower, fallback = data["structures"]
    assert tower["type"] == tower["spec"]["type"] == "TOWER"
    assert tower["offset"] == {"x": 4, "y": 0, "z": -3}
    assert tower["spec"]["style"] == "ASIAN"
    assert tower["spec"]["height"] == 15
    assert tower["spec"]["features"]["hasRoofDecoration"] is True
    assert tower["spec"]["features"]["hasWindows"] is False
    assert fallback["offset"] == {"x": 16, "y": 0, "z": 0}
    assert fallback["type"] == "HOUSE"
    assert data["paths"] is data["roads"]


def test_composite_existing_spec_and_invalid_paths():
    data = {"structures": [{"type": "CUSTOM", "spec": {"type": "CUSTOM", "style": "unknown"}}],
            "paths": "invalid"}
    result = normalization._normalize_composite_spec_dict(data)
    assert result["paths"] == []
    entry = result["structures"][0]
    assert entry["type"] == entry["spec"]["type"] == "HOUSE"
    assert entry["spec"]["style"] == "DEFAULT"


def test_planner_retains_compatibility_imports():
    from app.services import ai_planner

    for name in ("_normalize_building_spec_dict", "_normalize_building_style_value",
                 "_normalize_features_value", "_guess_building_type_from_name",
                 "_normalize_substructure_item", "_normalize_composite_spec_dict"):
        assert getattr(ai_planner, name) is getattr(normalization, name)
