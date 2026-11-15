from app.models.request import BuildRequest
from app.models.semantic_spatial_plan import SemanticSpatialPlan
from app.services import semantic_plan_adapters as adapters


def request(text=""):
    return BuildRequest(player={"name": "test", "pos": {"x": 100, "y": 64, "z": 200},
                                "facing": "WEST"},
                        world={"dimension": "minecraft:overworld"}, requestText=text)


def zone(id, type, priority=5, policy="BALANCED"):
    return {"id": id, "type": type, "priority": priority, "terrain_policy": policy}


def test_default_and_explicit_terrain_budgets():
    plan = SemanticSpatialPlan()
    extra = adapters._compile_semantic_plan_extra(request("small"), plan)
    assert extra["clusterTerrainPolicy"] == "balanced"
    # Preserve the existing float multiplication followed by integer truncation.
    assert extra["clusterTerrainBudgetBlocks"] == 1819
    assert extra["allowWaterEdit"] is False
    assert extra["allowLavaEdit"] is False
    plan.constraints.terrain_budget_blocks = 123
    assert adapters._compile_semantic_plan_extra(request(), plan)["clusterTerrainBudgetBlocks"] == 123


def test_zone_priority_policy_promotion_and_axial_hints():
    plan = SemanticSpatialPlan(zones=[zone("low", "PUBLIC", 3, "PRESERVE_DOMINANT"),
                                     zone("high", "PUBLIC", 8, "ENGINEERED"),
                                     zone("private", "PRIVATE", 5, "PRESERVE_DOMINANT")],
                               relations=[{"from": "high", "to": "private", "relation": "AXIAL"}])
    extra = adapters._compile_semantic_plan_extra(request(), plan)
    assert extra["clusterTerrainPolicy"] == "engineered"
    assert extra["allowWaterEdit"] is True
    assert extra["axisMode"] == "z"
    assert extra["axisWeight"] == 0.18
    assert extra["zoneTerrainRules"]["PLAZA"] == {
        "policy": "engineered", "budget": 650, "allowWaterEdit": True, "allowLavaEdit": False}
    assert extra["zoneTerrainRules"]["RESIDENTIAL"] == {"policy": "preserve_dominant", "budget": 650}


def test_circular_core_axial_facing_and_relative_coordinates():
    plan = SemanticSpatialPlan(zones=[zone("public", "PUBLIC"), zone("core", "CORE", 10)],
                               relations=[{"from": "core", "to": "public", "relation": "AXIAL"}])
    result = adapters._semantic_to_skeleton_layout(request("small tulou"), plan)
    core, public = result.skeletons
    # The existing BFS reserves the axial north slot, then places its neighbor east.
    assert (core.zone, core.shape, core.radius, core.facing) == ("core", "CIRCLE", 8, "EAST")
    assert core.anchor.model_dump() == {"x": 0, "y": 0, "z": 0}
    assert public.anchor.model_dump() == {"x": 14, "y": 0, "z": 0}
    assert (public.shape, public.width, public.depth, public.facing) == ("RECTANGLE", 16, 14, "WEST")
    assert result == adapters._semantic_to_skeleton_layout(request("small tulou"), plan)


def test_disconnected_circulation_and_empty_layout():
    plan = SemanticSpatialPlan(zones=[zone("core", "CORE", 10), zone("walk", "CIRCULATION")])
    core, walk = adapters._semantic_to_skeleton_layout(request(), plan).skeletons
    assert core.facing == "WEST"
    assert walk.shape == "LINEAR"
    assert [point.model_dump() for point in walk.points] == [
        {"x": 0, "y": 0, "z": 0}, {"x": 6, "y": 0, "z": -22}]
    assert adapters._semantic_to_skeleton_layout(request(), SemanticSpatialPlan()).skeletons == []
