"""API-level contract: the same semantic request must reach the planner identically
whether it is submitted in the Java flat shape or in the canonical nested shape.

Covers the real ``POST /build`` HTTP entrypoint (not just the adapter in isolation).
"""
from __future__ import annotations

import asyncio

import httpx

from app.main import app
from app.routes import build


def _planner_capture(monkeypatch):
    """Capture the BuildRequest the planner actually receives."""
    captured = []

    def generate(req):
        captured.append(req)
        return {"components": []}

    monkeypatch.setattr(build, "generate_llm_plan", generate)
    monkeypatch.setattr(build, "_maybe_clarify", lambda req: None)
    return captured


FLAT_PAYLOAD = {
    "requestText": "盖一座哥特式教堂",
    "playerPos": {"x": 120, "y": 64, "z": -40},
    "facing": "WEST",
    "dimension": "minecraft:the_nether",
    "biome": "soul_sand_valley",
    "selectionMin": {"x": 100, "y": 60, "z": -60},
    "selectionMax": {"x": 140, "y": 90, "z": -20},
    "brushMin": {"x": 110, "y": 62, "z": -50},
    "brushMax": {"x": 130, "y": 80, "z": -30},
    "outline": {
        "shapeType": "circle",
        "center": {"x": 120, "y": 64, "z": -40},
        "radius": 12,
        "minY": 60,
        "maxY": 90,
    },
    "protectedZones": [
        {"min": {"x": 0, "y": 60, "z": 0}, "max": {"x": 5, "y": 70, "z": 5}}
    ],
    "pathNodes": [
        {"x": 100, "y": 64, "z": -60},
        {"x": 130, "y": 64, "z": -20},
    ],
    "pathRadius": 3,
    "promptMode": "BUILD",
    "outputFormat": "llmplan",
}

NESTED_PAYLOAD = {
    "requestText": "盖一座哥特式教堂",
    "player": {
        "name": "Player",
        "pos": {"x": 120, "y": 64, "z": -40},
        "facing": "WEST",
    },
    "world": {
        "dimension": "minecraft:the_nether",
        "biome": "soul_sand_valley",
    },
    "selection": {
        "min": {"x": 100, "y": 60, "z": -60},
        "max": {"x": 140, "y": 90, "z": -20},
    },
    "brushSelection": {
        "min": {"x": 110, "y": 62, "z": -50},
        "max": {"x": 130, "y": 80, "z": -30},
    },
    "outline": {
        "shapeType": "circle",
        "center": {"x": 120, "y": 64, "z": -40},
        "radius": 12,
        "minY": 60,
        "maxY": 90,
    },
    "protectedZones": [
        {"min": {"x": 0, "y": 60, "z": 0}, "max": {"x": 5, "y": 70, "z": 5}}
    ],
    "path": {
        "nodes": [
            {"x": 100, "y": 64, "z": -60},
            {"x": 130, "y": 64, "z": -20},
        ],
        "radius": 3,
    },
    "promptMode": "BUILD",
    "outputFormat": "llmplan",
}


def _post(payload):
    async def scenario():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            return await client.post("/build", json=payload)

    return asyncio.run(scenario())


def _spatial(req):
    return {
        "pos": (req.player.pos.x, req.player.pos.y, req.player.pos.z),
        "facing": req.player.facing,
        "dimension": req.world.dimension,
        "biome": req.world.biome,
        "selection": (
            (req.selection.min.x, req.selection.min.y, req.selection.min.z),
            (req.selection.max.x, req.selection.max.y, req.selection.max.z),
        ) if req.selection else None,
        "brush": (
            (req.brushSelection.min.x, req.brushSelection.min.y, req.brushSelection.min.z),
            (req.brushSelection.max.x, req.brushSelection.max.y, req.brushSelection.max.z),
        ) if req.brushSelection else None,
        "outline": req.outline.model_dump() if req.outline else None,
        "protectedZones": [z.model_dump() for z in (req.protectedZones or [])],
        "path": (
            [(n.x, n.y, n.z) for n in (req.path.nodes or [])],
            req.path.radius,
        ) if req.path else None,
        "requestText": req.requestText,
        "promptMode": req.promptMode,
        "outputFormat": req.outputFormat,
    }


def test_flat_and_nested_requests_reach_planner_identically(monkeypatch):
    """Same semantic request, two wire shapes → identical planner-visible context."""
    captured = _planner_capture(monkeypatch)

    flat_response = _post(FLAT_PAYLOAD)
    nested_response = _post(NESTED_PAYLOAD)

    assert flat_response.status_code == 200, flat_response.text
    assert nested_response.status_code == 200, nested_response.text
    assert len(captured) == 2, "both requests must reach the planner exactly once"

    flat_req, nested_req = captured
    assert _spatial(flat_req) == _spatial(nested_req), (
        "flat and nested wire shapes must produce identical planner context; "
        f"flat={_spatial(flat_req)} nested={_spatial(nested_req)}"
    )


def test_nested_request_keeps_facing_dimension_selection_and_path(monkeypatch):
    """Regression: a nested request must not be downgraded to the flat shape."""
    captured = _planner_capture(monkeypatch)

    response = _post(NESTED_PAYLOAD)
    assert response.status_code == 200, response.text

    req = captured[0]
    assert req.player.facing == "WEST"
    assert req.player.pos.x == 120 and req.player.pos.z == -40
    assert req.world.dimension == "minecraft:the_nether"
    assert req.world.biome == "soul_sand_valley"
    assert req.selection is not None
    assert (req.selection.min.x, req.selection.max.x) == (100, 140)
    # Selection wins over brush, matching the flat-shape semantics.
    assert req.brushSelection is None
    assert req.protectedZones and len(req.protectedZones) == 1
    assert req.outline is not None and req.outline.radius == 12
    assert req.path is not None
    assert [(n.x, n.y, n.z) for n in req.path.nodes] == [
        (100, 64, -60),
        (130, 64, -20),
    ]
    assert req.path.radius == 3


def test_nested_request_with_request_field_is_accepted(monkeypatch):
    """The legacy nested key ``request`` is still accepted as request text."""
    captured = _planner_capture(monkeypatch)

    payload = dict(NESTED_PAYLOAD)
    payload.pop("requestText")
    payload["request"] = "盖一座哥特式教堂"

    response = _post(payload)
    assert response.status_code == 200, response.text
    assert captured[0].requestText == "盖一座哥特式教堂"


def test_flat_request_keeps_flat_semantics(monkeypatch):
    """The Java flat shape keeps its existing mapping (brush only when no selection)."""
    captured = _planner_capture(monkeypatch)

    payload = dict(FLAT_PAYLOAD)
    payload.pop("selectionMin")
    payload.pop("selectionMax")

    response = _post(payload)
    assert response.status_code == 200, response.text

    req = captured[0]
    assert req.selection is None
    assert req.brushSelection is not None
    assert (req.brushSelection.min.x, req.brushSelection.max.x) == (110, 130)
