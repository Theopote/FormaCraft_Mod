"""CI quality gate smoke tests."""

from __future__ import annotations

import unittest


class CiGateTest(unittest.TestCase):
    def test_ci_gate_quick_passes(self):
        from eval.ci_gate import run_ci_gate

        code = run_ci_gate(gate=True, quick=True)
        self.assertEqual(code, 0, "ci_gate --quick should pass offline")

    def test_golden_scenarios_ci_only_gate(self):
        from eval.golden_eval import run_scenarios

        code = run_scenarios(gate=False, ci_only=True)
        self.assertEqual(code, 0)

    def test_diversity_ci_only(self):
        from eval.diversity_eval import run_diversity_scenarios

        code = run_diversity_scenarios(gate=True, ci_only=True)
        self.assertEqual(code, 0)

    def test_diversity_negative(self):
        from eval.ci_gate import run_diversity_negative

        self.assertEqual(run_diversity_negative(), 0)


if __name__ == "__main__":
    unittest.main()
