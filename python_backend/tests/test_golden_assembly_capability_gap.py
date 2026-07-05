"""Golden eval checks for ASSEMBLY + capability_gap fixtures."""
import json
import unittest
from pathlib import Path

_REPO = Path(__file__).resolve().parents[2]
_FIXTURES = _REPO / "python_backend" / "eval" / "fixtures" / "plans"


class GoldenAssemblyCapabilityGapTest(unittest.TestCase):
    def test_explicit_capability_gap_fixture_passes_golden_eval(self):
        from eval.golden_eval import evaluate_plan

        path = _FIXTURES / "assembly_capability_gap_explicit_golden.json"
        plan = json.loads(path.read_text(encoding="utf-8"))
        result = evaluate_plan(plan, label=path.name, prompt=plan.get("prompt"))
        self.assertTrue(result.ok, msg=result.hard_failures)

    def test_spiral_preset_fixture_passes_schema_and_assembly_checks(self):
        from eval.golden_eval import evaluate_plan

        path = _FIXTURES / "assembly_spiral_preset_golden.json"
        plan = json.loads(path.read_text(encoding="utf-8"))
        result = evaluate_plan(plan, label=path.name, prompt=plan.get("prompt"))
        self.assertTrue(result.ok, msg=result.hard_failures)


if __name__ == "__main__":
    unittest.main()
