from types import SimpleNamespace

from app.models.building_spec import BuildingSpec
from app.services import legacy_spec_genome as genome


def spec(extra=None):
    return BuildingSpec(type="HOUSE", footprint={"shape": "rectangle", "width": 8, "depth": 6},
                        height=20, floors=2, materials={"wall": "minecraft:oak_planks"},
                        features={"hasBalcony": True}, extra=extra)


def test_completion_preserves_user_overrides_and_populates_defaults():
    building = spec({"genome": {"materials": {"primary": "metal"}}, "planType": "courtyard"})
    assert genome._ensure_genome_for_spec(building, None) is building
    result = building.extra["genome"]
    assert result["materials"]["primary"] == "metal"
    assert result["topology"]["layout"] == "rectangular"
    assert "balcony" in result["modules"]
    assert building.extra["component_params"]["MASS_MAIN"]["floor_height"] == 10
    assert building.extra["component_params"]["MASS_MAIN"]["plan_type"] == "courtyard"


def test_existing_component_params_remain_untouched_and_completion_is_repeatable():
    params = {"custom": [1, 2]}
    building = spec({"component_params": params})
    original = building.extra["component_params"]
    genome._ensure_genome_for_spec(building, None)
    first = building.model_dump()
    genome._ensure_genome_for_spec(building, None)
    assert building.extra["component_params"] is original
    assert building.model_dump() == first


def test_circular_geometry_and_request_constraints():
    building = spec()
    building.footprint.shape = "circle"
    req = SimpleNamespace(selection=object(), outline=None)
    result = genome._build_genome_from_spec(req, building)
    assert result.topology.layout == "circular"
    assert result.symmetry.type == "radial"
    assert result.materials.primary == "wood"
    assert result.constraints.insideSelectionOnly is True
    assert result.constraints.respectTerrain is True


def test_nested_city_and_composite_completion_skips_missing_entries():
    for complete in (genome._ensure_genome_for_city_spec, genome._ensure_genome_for_composite_spec):
        building = spec()
        container = SimpleNamespace(structures=[None, SimpleNamespace(spec=None), SimpleNamespace(spec=building)])
        assert complete(container, None) is container
        assert "genome" in building.extra
        assert complete(None, None) is None


def test_merge_does_not_mutate_default_nested_values():
    default = {"nested": {"a": 1, "b": 2}, "list": [1]}
    result = genome._deep_merge(default, {"nested": {"a": None}, "list": [2]})
    assert result == {"nested": {"a": None, "b": 2}, "list": [2]}
    assert default == {"nested": {"a": 1, "b": 2}, "list": [1]}


def test_existing_planner_imports_remain_available():
    from app.services import ai_planner

    for name in ("_deep_merge", "_semantic_material_from_block", "_build_genome_from_spec",
                 "_default_component_params_for_spec", "_ensure_genome_for_spec",
                 "_ensure_genome_for_composite_spec", "_ensure_genome_for_city_spec"):
        assert getattr(ai_planner, name) is getattr(genome, name)
