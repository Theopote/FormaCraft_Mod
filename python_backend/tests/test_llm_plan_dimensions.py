import pytest

from app.services import llm_plan_dimensions as dimensions


@pytest.mark.parametrize("type,expected", [
    ("MASS_MAIN", (8, 6, 6)), ("TOWER_ROUND", (6, 6, 12)),
    ("ROOF", (8, 6, 1)), ("FACADE", (4, 1, 2)),
    ("ENTRANCE", (4, 1, 3)), ("SIGNAGE", (4, 1, 1)),
    ("PATH", (6, 6, 1)), ("UNKNOWN", (4, 4, 3)),
])
def test_existing_type_defaults(type, expected):
    assert dimensions._normalize_dimensions(None, type, {}, None) == dict(zip(("width", "depth", "height"), expected))


def test_explicit_values_and_invalid_values_do_not_mutate_input():
    source = {"width": "12", "depth": -2, "height": "bad"}
    assert dimensions._normalize_dimensions(source, "MASS_MAIN", {"floorHeight": 4, "floorCount": 3}, None) == {
        "width": 12, "depth": 6, "height": 12}
    assert source == {"width": "12", "depth": -2, "height": "bad"}


def test_first_valid_mass_supplies_inherited_dimensions():
    components = [None, {"component_type": "MASS_MAIN", "dimensions": {"width": 0}},
                  {"component_type": "main_mass", "dimensions": {"width": "20", "depth": 16, "height": 10}},
                  {"component_type": "MASS_MAIN", "dimensions": {"width": 30, "depth": 30, "height": 30}}]
    mass = dimensions._extract_mass_dims(components)
    assert mass == {"width": 20, "depth": 16, "height": 10}
    assert dimensions._normalize_dimensions({}, "ROOF", {"roofHeightBlocks": 5}, mass) == {
        "width": 20, "depth": 16, "height": 5}
    assert dimensions._normalize_dimensions({}, "ENTRANCE", {"door_height": 4}, mass) == {
        "width": 4, "depth": 1, "height": 4}


def test_numeric_and_parameter_alias_contract():
    assert dimensions._normalize_vec3(["3", "bad", -4]) == {"x": 3, "y": 0, "z": -4}
    assert dimensions._normalize_vec3({"y": "5"}) == {"x": 0, "y": 5, "z": 0}
    assert dimensions._param_int({"primary": "bad", "alias": "4"}, "primary", "alias") == 4
    assert dimensions._param_string({"primary": " ", "alias": " roof "}, "primary", "alias") == "roof"
    assert dimensions._extract_mass_dims([]) is None


def test_planner_import_aliases():
    from app.services import ai_planner

    for name in ("_coerce_int", "_normalize_vec3", "_extract_mass_dims", "_param_int", "_param_string",
                 "_infer_mass_height", "_default_dimensions_for_component", "_normalize_dimensions"):
        assert getattr(ai_planner, name) is getattr(dimensions, name)
