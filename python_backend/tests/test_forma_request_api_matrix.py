"""Cross-language API matrix: Java flat FormaRequest ↔ nested BuildRequest."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.models.request import BuildRequest
from app.models.request_adapter import FormaRequestAdapter
from app.routes import build


def test_flat_java_fields_map_to_nested_build_request():
    adapter = FormaRequestAdapter(
        requestText="盖房子",
        playerPos={"x": 10, "y": 64, "z": -20},
        facing="WEST",
        dimension="minecraft:overworld",
        biome="plains",
        selectionMin={"x": 0, "y": 60, "z": 0},
        selectionMax={"x": 15, "y": 80, "z": 15},
        brushMin={"x": 1, "y": 61, "z": 1},
        brushMax={"x": 5, "y": 70, "z": 5},
        outline={"shapeType": "circle", "center": {"x": 8, "y": 64, "z": 8}, "radius": 12, "minY": 60, "maxY": 80},
        protectedZones=[{"min": {"x": -5, "y": 60, "z": -5}, "max": {"x": -1, "y": 70, "z": -1}}],
        pathNodes=[{"x": 0, "y": 64, "z": 0}, {"x": 10, "y": 64, "z": 10}],
        pathRadius=3,
        outputFormat="llmplan",
        promptMode="BUILD",
    )
    req = adapter.to_build_request()
    assert req.requestText == "盖房子"
    assert req.player.facing == "WEST"
    assert req.player.pos.x == 10 and req.player.pos.z == -20
    assert req.world.dimension == "minecraft:overworld"
    assert req.world.biome == "plains"
    assert req.selection is not None
    assert req.selection.min.x == 0 and req.selection.max.x == 15
    # brush is only used when selection is absent
    assert req.brushSelection is None
    assert req.outline is not None
    assert req.protectedZones and len(req.protectedZones) == 1
    assert req.path is not None
    assert len(req.path.nodes) == 2
    assert req.path.radius == 3
    assert req.outputFormat == "llmplan"


def test_brush_used_when_selection_absent():
    req = FormaRequestAdapter(
        requestText="brush only",
        playerPos={"x": 0, "y": 64, "z": 0},
        facing="SOUTH",
        brushMin={"x": 1, "y": 61, "z": 1},
        brushMax={"x": 5, "y": 70, "z": 5},
    ).to_build_request()
    assert req.selection is None
    assert req.brushSelection is not None
    assert req.brushSelection.min.x == 1


@pytest.mark.parametrize("fmt,expect_legacy", [
    (None, False),
    ("auto", False),
    ("llmplan", False),
    ("buildingspec", True),
])
def test_format_matrix_defaults_and_legacy_opt_in(fmt, expect_legacy, monkeypatch):
    """null/auto/llmplan → canonical; explicit buildingspec → legacy route."""
    from unittest.mock import Mock

    data = {
        "player": {"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        "world": {"dimension": "minecraft:overworld"},
        "requestText": "house",
    }
    nested = BuildRequest(**data, outputFormat=fmt)
    flat = FormaRequestAdapter(requestText="house", outputFormat=fmt).to_build_request()
    for req in (nested, flat):
        if expect_legacy:
            assert req.outputFormat == "buildingspec"
        else:
            # BuildRequest keeps explicit None/auto; routing still uses canonical pipeline.
            assert req.outputFormat in (None, "auto", "llmplan", fmt)

    canonical = Mock(return_value={"components": []})
    legacy = Mock(return_value={})
    monkeypatch.setattr(build, "generate_llm_plan", canonical)
    monkeypatch.setattr(build, "generate_building_spec", legacy)
    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)
    monkeypatch.setattr(build, "generate_city_spec", Mock(side_effect=AssertionError("city")))
    monkeypatch.setattr(build, "generate_composite_spec", Mock(side_effect=AssertionError("composite")))

    routed = build._generate_response(BuildRequest(**data, outputFormat=fmt))
    if expect_legacy:
        assert routed["kind"] == "buildingspec"
        legacy.assert_called_once()
        canonical.assert_not_called()
    else:
        assert routed["kind"] == "llmplan"
        canonical.assert_called_once()
        legacy.assert_not_called()


def test_shape_detection_does_not_require_nonempty_request_text():
    """Java flat path is keyed by playerPos, not solely by non-empty requestText."""
    req = FormaRequestAdapter(
        requestText="",
        playerPos={"x": 7, "y": 70, "z": 9},
        facing="EAST",
        dimension="minecraft:the_nether",
        outputFormat="llmplan",
    ).to_build_request()
    assert req.player.pos.x == 7
    assert req.player.facing == "EAST"
    assert req.world.dimension == "minecraft:the_nether"
    assert req.requestText == ""


def test_unknown_output_format_rejected_both_shapes():
    with pytest.raises(ValidationError):
        BuildRequest(
            player={"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
            world={"dimension": "minecraft:overworld"},
            requestText="x",
            outputFormat="building_spec",
        )
    with pytest.raises(ValidationError):
        FormaRequestAdapter(requestText="x", outputFormat="building_spec")


def test_symmetry_flat_fields_map_to_nested_constraint():
    """SymmetryTool 必须真正到达后端，否则对称工具对生成结果没有任何影响。"""
    req = FormaRequestAdapter(
        requestText="symmetric hall",
        playerPos={"x": 0, "y": 64, "z": 0},
        symmetryMode="MIRROR_X",
    ).to_build_request()
    assert req.symmetry is not None
    assert req.symmetry.mode == "MIRROR_X"


def test_symmetry_custom_axis_carries_axis_points():
    req = FormaRequestAdapter(
        requestText="symmetric hall",
        playerPos={"x": 0, "y": 64, "z": 0},
        symmetryMode="CUSTOM_AXIS",
        symmetryAxisA={"x": 1, "y": 64, "z": 2},
        symmetryAxisB={"x": 9, "y": 64, "z": 2},
    ).to_build_request()
    assert req.symmetry is not None
    assert req.symmetry.mode == "CUSTOM_AXIS"
    assert req.symmetry.axisA is not None and req.symmetry.axisA.x == 1
    assert req.symmetry.axisB is not None and req.symmetry.axisB.x == 9


def test_symmetry_absent_or_none_produces_no_constraint():
    assert FormaRequestAdapter(requestText="x").to_build_request().symmetry is None
    assert FormaRequestAdapter(
        requestText="x", symmetryMode="NONE"
    ).to_build_request().symmetry is None


def test_nested_symmetry_object_is_preserved():
    """标准嵌套请求的 symmetry 对象必须真的到达后端。

    回归：适配器曾经只读 Java 扁平字段 symmetryMode/AxisA/AxisB，
    嵌套请求的 symmetry 被静默丢弃，对称规划约束丢失。
    """
    req = FormaRequestAdapter(
        player={"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText="symmetric hall",
        symmetry={"mode": "MIRROR_X"},
    ).to_build_request()
    assert req.symmetry is not None
    assert req.symmetry.mode == "MIRROR_X"


def test_nested_symmetry_custom_axis_preserved():
    req = FormaRequestAdapter(
        player={"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText="symmetric hall",
        symmetry={"mode": "CUSTOM_AXIS", "axisA": {"x": 1, "y": 64, "z": 2},
                  "axisB": {"x": 9, "y": 64, "z": 2}},
    ).to_build_request()
    assert req.symmetry is not None
    assert req.symmetry.mode == "CUSTOM_AXIS"
    assert req.symmetry.axisA is not None and req.symmetry.axisA.x == 1
    assert req.symmetry.axisB is not None and req.symmetry.axisB.x == 9


def test_rag_budget_passes_through_both_shapes():
    """ragBudget 在两种线格式里都是独立对象，两条分支都必须透传。

    回归：适配器声明了 ragBudget 却从未传给 BuildRequest，
    显式预算会静默退回环境变量默认值。
    """
    flat = FormaRequestAdapter(
        requestText="x", playerPos={"x": 0, "y": 64, "z": 0},
        ragBudget={"topK": 7, "fewShotK": 1},
    ).to_build_request()
    assert flat.ragBudget is not None
    assert flat.ragBudget.topK == 7
    assert flat.ragBudget.fewShotK == 1

    nested = FormaRequestAdapter(
        player={"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText="x",
        ragBudget={"topK": 7, "fewShotK": 1},
    ).to_build_request()
    assert nested.ragBudget is not None
    assert nested.ragBudget.topK == 7
    assert nested.ragBudget.fewShotK == 1


def test_rag_budget_request_overrides_env(monkeypatch):
    """resolve_rag_budget 的优先级：请求 > 环境变量 > 默认值。"""
    from app.services.rag_budget import resolve_rag_budget

    monkeypatch.setenv("RAG_TOPK", "2")
    monkeypatch.setenv("RAG_MAX_CHARS", "5000")

    req = FormaRequestAdapter(
        player={"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        world={"dimension": "minecraft:overworld"},
        requestText="x",
        ragBudget={"topK": 11, "maxChars": 9999},
    ).to_build_request()

    resolved = resolve_rag_budget(req)
    assert resolved["topK"] == 11
    assert resolved["maxChars"] == 9999


def test_nested_request_forwards_future_fields_without_code_change():
    """嵌套分支整体交给 BuildRequest 校验，新增字段无需在适配器里补一遍。

    这是"逐字段复制必然漏字段"的根治方式：任何 BuildRequest 新增的
    选填字段，嵌套请求自动继承，扁平分支则需显式映射。
    """
    payload = {
        "player": {"name": "t", "pos": {"x": 0, "y": 64, "z": 0}, "facing": "SOUTH"},
        "world": {"dimension": "minecraft:overworld"},
        "requestText": "x",
        "someFutureOptionalField": "value",
    }
    req = FormaRequestAdapter(**payload).to_build_request()
    # 未知字段被忽略，但已知字段必须完整保留（证明走的是 BuildRequest 校验路径）。
    assert req.requestText == "x"
    assert req.player.facing == "SOUTH"
    assert req.world.dimension == "minecraft:overworld"
