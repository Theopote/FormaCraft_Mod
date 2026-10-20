"""CI quality gate smoke tests."""

from __future__ import annotations

import unittest
from unittest.mock import patch


class CiGateTest(unittest.TestCase):
    def test_ci_gate_quick_passes(self):
        from eval.ci_gate import run_ci_gate

        # Java behavior is exercised by constraintParityTest; this smoke test isolates orchestration.
        with patch('eval.ci_gate.run_constraint_parity', return_value=0) as parity:
            code = run_ci_gate(gate=True, quick=True)
            parity.assert_called_once()
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
