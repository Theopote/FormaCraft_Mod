"""PR-C: routing telemetry helpers + baseline report."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from eval.routing_baseline import (
    PATH_PRIORITY,
    classify_component,
    classify_plan,
    collect_baseline,
    render_markdown,
)


class RoutingClassificationTest(unittest.TestCase):
    def test_typology_structure_component(self):
        comp = {
            "component_type": "STRUCTURE",
            "features": ["typology:radial_terrace_hall"],
            "params": {"typology_id": "radial_terrace_hall"},
        }
        self.assertEqual(classify_component(comp), "typology_builder")

    def test_migrated_module_classifies_as_typology(self):
        comp = {
            "component_type": "MODULE",
            "features": ["landmark:birds_nest_stadium"],
        }
        self.assertEqual(classify_component(comp), "typology_builder")

    def test_migrated_module_classifies_as_typology(self):
        comp = {
            "component_type": "MODULE",
            "features": ["landmark:temple_of_heaven"],
        }
        self.assertEqual(classify_component(comp), "typology_builder")

    def test_compositional_mass(self):
        comp = {"component_type": "MASS_MAIN", "features": ["elliptical_footprint"]}
        self.assertEqual(classify_component(comp), "compositional")

    def test_temple_golden_fixture_typology_first(self):
        repo = Path(__file__).resolve().parents[2]
        plan_path = repo / "python_backend" / "eval" / "fixtures" / "plans" / "temple_of_heaven_golden.json"
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        cls = classify_plan(plan)
        self.assertEqual(cls["primary_path"], "typology_builder")

    def test_stadium_captured_compositional(self):
        repo = Path(__file__).resolve().parents[2]
        plan_path = repo / "python_backend" / "eval" / "fixtures" / "plans" / "modern_elliptical_stadium_captured.json"
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        cls = classify_plan(plan)
        self.assertEqual(cls["primary_path"], "compositional")


class RoutingBaselineReportTest(unittest.TestCase):
    def test_collect_baseline_has_samples(self):
        stats = collect_baseline()
        self.assertGreater(stats.total, 0)
        self.assertGreater(stats.by_path.get("compositional", 0), 0)
        self.assertGreater(stats.by_path.get("typology_builder", 0), 0)

    def test_render_markdown_contains_summary(self):
        stats = collect_baseline()
        md = render_markdown(stats)
        self.assertIn("Typology-first rate", md)
        self.assertIn("typology_builder", md)
        for path in PATH_PRIORITY:
            self.assertIn(path, md)

    def test_typology_first_rate_meets_target(self):
        stats = collect_baseline()
        # After stadium diversity fixtures migrated off MODULE, golden set should hit 80% goal.
        self.assertGreaterEqual(stats.typology_first_rate(), 80.0)
        self.assertEqual(stats.by_path.get("module", 0), 0)


if __name__ == "__main__":
    unittest.main()
