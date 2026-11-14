"""Fixture contract for complex_build_retest historical replays.

Raw ``*.json`` files are immutable LLM capture inputs. They must stay free of
normalize-only artifacts. ``*.normalized.json`` files are expected outputs after
``apply_building_contract`` / ``normalize_circulation_plan`` and may gain fields
such as ``door_width_clamped_from`` or ``plan_meta.request_intent``.

Updating a golden must never mean rewriting the raw capture to already look
normalized — otherwise regressions in clamping / intent injection are masked.
"""

from __future__ import annotations

import copy
import hashlib
import json
import unittest
from pathlib import Path

from app.services.building_contract import apply_building_contract
from app.services.circulation_plan_normalizer import normalize_circulation_plan

FIXTURES = Path(__file__).parent / "fixtures" / "complex_build_retest"
RAW_SHA256_MANIFEST = FIXTURES / "raw_fixture_sha256.json"

# Markers that only the normalize / contract pipeline should introduce.
NORMALIZE_ONLY_MARKERS = (
    "door_width_clamped_from",
)


def _paired_fixtures():
    for raw_path in sorted(FIXTURES.glob("*.json")):
        if raw_path.name.endswith(".normalized.json"):
            continue
        norm_path = FIXTURES / f"{raw_path.stem}.normalized.json"
        if norm_path.is_file():
            yield raw_path, norm_path


def _intent_text(plan: dict) -> str:
    hints = plan.get("proportion_hints") or {}
    intent = hints.get("architecture_intent") or {}
    text = intent.get("original_text")
    if isinstance(text, str) and text.strip():
        return text
    meta = plan.get("plan_meta") or {}
    req = meta.get("request_intent") or {}
    if isinstance(req, dict) and isinstance(req.get("original_text"), str):
        return req["original_text"]
    if isinstance(meta.get("request_intent_text"), str):
        return meta["request_intent_text"]
    return ""


class ComplexBuildFixtureContract(unittest.TestCase):
    def test_raw_llm_captures_exclude_normalize_only_markers(self):
        polluted = []
        for raw_path, _ in _paired_fixtures():
            blob = raw_path.read_text(encoding="utf-8")
            hits = [m for m in NORMALIZE_ONLY_MARKERS if m in blob]
            if hits:
                polluted.append((raw_path.name, hits))
        self.assertEqual(
            [],
            polluted,
            "raw replay inputs must not contain normalize-only fields; "
            "keep those on *.normalized.json only",
        )

    def test_normalize_pipeline_does_not_rewrite_on_disk_raw_bytes(self):
        raw_path = FIXTURES / "oct18_2.json"
        before = raw_path.read_bytes()
        plan = json.loads(before.decode("utf-8"))
        text = _intent_text(plan)
        normalize_circulation_plan(
            apply_building_contract(copy.deepcopy(plan), text, finalize=True)
        )
        self.assertEqual(before, raw_path.read_bytes())

    def test_oct18_2_raw_door_width_is_clamped_not_pre_normalized(self):
        """Acceptance sample: raw keeps door_width=2; normalize clamps to 1."""
        raw_path = FIXTURES / "oct18_2.json"
        norm_path = FIXTURES / "oct18_2.normalized.json"
        raw = json.loads(raw_path.read_text(encoding="utf-8"))
        golden = json.loads(norm_path.read_text(encoding="utf-8"))

        raw_doors = {
            c["params"]["component_id"]: c["params"].get("door_width")
            for c in raw["components"]
            if c.get("params", {}).get("component_id") in (
                "north_guest_entrance",
                "south_guest_entrance",
            )
        }
        self.assertEqual(
            {"north_guest_entrance": 2, "south_guest_entrance": 2},
            raw_doors,
            "raw capture must preserve pre-clamp LLM door widths",
        )
        for cid in raw_doors:
            self.assertNotIn(
                "door_width_clamped_from",
                next(c for c in raw["components"] if c["params"]["component_id"] == cid)["params"],
            )

        result = normalize_circulation_plan(
            apply_building_contract(copy.deepcopy(raw), _intent_text(raw), finalize=True)
        )
        self.assertEqual(result, golden)
        for cid in ("north_guest_entrance", "south_guest_entrance"):
            params = next(c for c in result["components"] if c["params"]["component_id"] == cid)["params"]
            self.assertEqual(1, params.get("door_width"))
            self.assertEqual(2, params.get("door_width_clamped_from"))

        # Golden must differ from raw where clamping happened — never store
        # already-normalized JSON as the only historical baseline.
        self.assertNotEqual(raw["components"], golden["components"])

    def test_paired_fixtures_with_clamp_markers_keep_distinct_raw_inputs(self):
        checked = 0
        for raw_path, norm_path in _paired_fixtures():
            golden_blob = norm_path.read_text(encoding="utf-8")
            if "door_width_clamped_from" not in golden_blob:
                continue
            raw = json.loads(raw_path.read_text(encoding="utf-8"))
            golden = json.loads(golden_blob)
            self.assertNotEqual(
                raw.get("components"),
                golden.get("components"),
                f"{raw_path.name}: raw components must not already equal clamped golden",
            )
            checked += 1
        self.assertGreaterEqual(checked, 1, "expected at least one clamped golden pair")

    def test_raw_fixture_sha256_manifest_pins_historical_llm_bytes(self):
        """Raw captures are immutable; golden updates must not rewrite them quietly."""
        self.assertTrue(
            RAW_SHA256_MANIFEST.is_file(),
            "raw_fixture_sha256.json must pin historical LLM capture bytes",
        )
        expected = json.loads(RAW_SHA256_MANIFEST.read_text(encoding="utf-8"))
        actual = {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(FIXTURES.glob("*.json"))
            if not path.name.endswith(".normalized.json")
            and path.name != RAW_SHA256_MANIFEST.name
        }
        self.assertEqual(
            expected,
            actual,
            "raw complex_build fixtures changed; update raw_fixture_sha256.json "
            "only in an explicitly reviewed commit that intends to replace LLM captures",
        )


if __name__ == "__main__":
    unittest.main()
