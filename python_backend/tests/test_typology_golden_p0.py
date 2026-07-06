"""P0: typology golden fixtures — proportion, golden_eval, routing classify."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_MANIFEST_PATH = (
    Path(__file__).resolve().parent.parent / "eval" / "typology_golden_manifest.json"
)
_FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures"


def _load_manifest() -> list[dict]:
    data = json.loads(_MANIFEST_PATH.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("typology_golden_manifest.json must be a list")
    return [entry for entry in data if isinstance(entry, dict)]


class TypologyGoldenManifestTest(unittest.TestCase):
    def test_manifest_has_ten_entries(self):
        entries = _load_manifest()
        self.assertEqual(len(entries), 10)
        ids = {e["id"] for e in entries}
        self.assertEqual(
            ids,
            {
                "famen_pagoda",
                "giant_wild_goose_pagoda",
                "foguang_temple_hall",
                "temple_of_heaven",
                "birds_nest_stadium",
                "golden_gate_bridge",
                "gothic_cathedral",
                "mingqing_courtyard",
                "castle_compound",
                "modern_skyscraper",
            },
        )


class TypologyGoldenFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.entries = _load_manifest()

    def _load_plan(self, entry: dict) -> dict:
        fixture = entry.get("plan_fixture")
        path = _FIXTURES / str(fixture)
        self.assertTrue(path.is_file(), f"missing fixture: {path}")
        return json.loads(path.read_text(encoding="utf-8"))

    def test_each_fixture_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        for entry in self.entries:
            with self.subTest(entry_id=entry["id"]):
                plan = self._load_plan(entry)
                prompt = str(entry.get("prompt") or "")
                result = evaluate_plan(plan, label=entry["id"], prompt=prompt)
                self.assertTrue(
                    result.ok,
                    f"{entry['id']} hard failures: {result.hard_failures}",
                )
                names = {c.name for c in result.checks if c.passed}
                self.assertIn("typology_structure_route", names)
                self.assertIn("typology_proportion_hints", names)
                self.assertIn("no_migrated_module", names)

    def test_each_fixture_passes_proportion_eval(self):
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions

        for entry in self.entries:
            with self.subTest(entry_id=entry["id"]):
                plan = self._load_plan(entry)
                prompt = str(entry.get("prompt") or "")
                typology_id = str(entry.get("typology_id") or "")

                for name, ok, _ in evaluate_proportions(plan, prompt):
                    if name == "has_proportion_hints":
                        self.assertTrue(ok, f"{entry['id']}: missing proportion_hints")
                for name, ok, _ in evaluate_enclosure(plan, prompt):
                    if name == "typology_fixture_structure_route":
                        self.assertTrue(
                            ok,
                            f"{entry['id']}: expected STRUCTURE typology:{typology_id}",
                        )

    def test_each_fixture_classifies_typology_builder(self):
        from eval.routing_baseline import classify_plan

        for entry in self.entries:
            with self.subTest(entry_id=entry["id"]):
                plan = self._load_plan(entry)
                cls = classify_plan(plan)
                self.assertEqual(cls["primary_path"], "typology_builder")


if __name__ == "__main__":
    unittest.main()
