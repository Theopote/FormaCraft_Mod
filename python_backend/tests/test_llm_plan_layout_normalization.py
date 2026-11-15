from copy import deepcopy

import pytest

from app.services import llm_plan_layout_normalization as layout


@pytest.mark.parametrize("value,expected", [("front-back", "MIRROR_Z"), ("both", "MIRROR_X"),
                                         ("off", "NONE"), ("circular", "RADIAL")])
def test_explicit_symmetry_precedes_genome(value, expected):
    assert layout._normalize_global_symmetry_value(value, genome={"symmetry": {"type": "radial"}}) == expected


def test_unknown_symmetry_falls_back_to_genome():
    assert layout._normalize_global_symmetry_value("unknown", genome={"symmetry": {"type": "bilateral"}}) == "MIRROR_X"
    assert layout._normalize_global_symmetry_value(None, genome={}) is None


@pytest.mark.parametrize("value,expected", [(None, None), ("tower shaft", "VERTICAL_STACK"),
                                         ("RADIAL_RING", "RADIAL_RING"), ("unknown", "COMPOUND")])
def test_existing_skeleton_aliases(value, expected):
    assert layout._coerce_skeleton_type(value) == expected


def test_layout_cleanup_preserves_source_and_valid_slot_metadata():
    source = {"skeleton_type": "tower", "slots": [None,
              {"id": "entry", "facing": "东", "anchor": {"x": "3", "y": "bad"}, "component_preset": "hall"},
              {"facing": "ANY", "anchor": [], "component_preset": 3}]}
    before = deepcopy(source)
    result = layout._sanitize_layout_slots(source)
    assert result["skeleton_type"] == "VERTICAL_STACK"
    first, second = result["slots"]
    assert first["slot_id"] == "entry"
    assert first["facing"] == "EAST"
    assert first["anchor"] == {"x": 3, "y": 0, "z": 0}
    assert first["component_preset"] == "hall"
    assert second["slot_id"] == "slot_2"
    assert "facing" not in second and "component_preset" not in second
    assert source == before


def test_genome_symmetry_coercion_preserves_source():
    source = {"symmetry": {"mirror": "yes", "order": "4"}, "custom": 3}
    before = deepcopy(source)
    result = layout._sanitize_genome_dict(source)
    assert result == {"symmetry": {"mirror": True, "order": 4}, "custom": 3}
    assert source == before
    assert layout._coerce_optional_bool("true|false") is None


def test_planner_import_aliases():
    from app.services import ai_planner

    for name in ("_symmetry_from_genome_type", "_normalize_global_symmetry_value", "_coerce_optional_bool",
                 "_sanitize_genome_dict", "_coerce_skeleton_type", "_sanitize_layout_slots"):
        assert getattr(ai_planner, name) is getattr(layout, name)
