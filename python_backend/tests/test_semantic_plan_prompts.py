from types import SimpleNamespace
from unittest.mock import Mock

from app.models.request import BuildRequest, Selection
from app.services import ai_planner
from app.services.semantic_plan_prompts import build_semantic_plan_prompts


def request():
    return BuildRequest(player={"name": "test", "pos": {"x": 10, "y": 64, "z": -20}, "facing": "WEST"},
                        world={"dimension": "minecraft:overworld"}, requestText="small hilly village")


def test_context_and_selection_are_preserved():
    req = request()
    system, user = build_semantic_plan_prompts(req)
    assert "CORE|PUBLIC|SEMI_PUBLIC|PRIVATE|SERVICE|LANDSCAPE|TRANSITION|CIRCULATION" in system
    assert "- Scale: SMALL" in user
    assert "- Terrain: HILLY" in user
    assert "anchor=(10,64,-20) facing=WEST" in user
    assert "Selection Area: none" in user
    req.selection = Selection(min={"x": 0, "y": 1, "z": 2}, max={"x": 3, "y": 4, "z": 5})
    assert "box(min=(0,1,2), max=(3,4,5))" in build_semantic_plan_prompts(req)[1]


def test_semantic_stage_sends_builder_output_and_validates_response(monkeypatch):
    req = request()
    create = Mock(return_value=SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"zones":[]}'))]))
    monkeypatch.setattr(ai_planner, "get_client", lambda req: SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create))))
    monkeypatch.setattr(ai_planner, "_call_with_timeout", lambda fn, timeout: fn())
    monkeypatch.setattr(ai_planner, "_resolve_model", lambda *args: "test")
    result = ai_planner.generate_semantic_spatial_plan(req)
    system, user = build_semantic_plan_prompts(req)
    assert result.zones == []
    assert create.call_args.kwargs["messages"] == [{"role": "system", "content": system}, {"role": "user", "content": user}]
