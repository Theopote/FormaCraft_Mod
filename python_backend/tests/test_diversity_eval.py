"""Diversity eval unit tests (offline, no LLM)."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

_FIXTURES = Path(__file__).resolve().parent.parent / "eval" / "fixtures" / "diversity" / "stadium"


def _load(name: str) -> dict:
    return json.loads((_FIXTURES / name).read_text(encoding="utf-8"))


class DiversityEvalTest(unittest.TestCase):
    def test_routing_path(self):
        from eval.diversity_eval import routing_path, unwrap_plan

        seed100 = unwrap_plan(_load("compositional_seed_100.json"))
        seed8842 = unwrap_plan(_load("compositional_seed_8842.json"))
        comp = unwrap_plan(_load("compositional_v1.json"))
        self.assertEqual(routing_path(seed100), "compositional")
        self.assertEqual(routing_path(seed8842), "compositional")
        self.assertEqual(routing_path(comp), "compositional")

    def test_identical_plans_zero_distance(self):
        from eval.diversity_eval import evaluate_diversity, jaccard_distance, plan_signature_tokens, unwrap_plan

        a = unwrap_plan(_load("compositional_seed_100.json"))
        b = unwrap_plan(_load("compositional_seed_100_dup.json"))
        sig_a = plan_signature_tokens(a)
        sig_b = plan_signature_tokens(b)
        self.assertEqual(jaccard_distance(sig_a, sig_b), 0.0)

        m = evaluate_diversity([a, b], label="dup")
        self.assertEqual(m.unique_signatures, 1)
        self.assertEqual(m.unique_ratio, 0.5)

    def test_variants_are_diverse(self):
        from eval.diversity_eval import (
            DiversityThresholds,
            evaluate_diversity,
            unwrap_plan,
        )

        plans = [
            unwrap_plan(_load("compositional_seed_100.json")),
            unwrap_plan(_load("compositional_seed_8842.json")),
            unwrap_plan(_load("compositional_v1.json")),
        ]
        th = DiversityThresholds(
            min_samples=3,
            min_unique_ratio=1.0,
            min_mean_distance=0.25,
            min_routing_unique=1,
        )
        m = evaluate_diversity(plans, label="variants", thresholds=th)
        self.assertEqual(m.unique_signatures, 3)
        self.assertGreaterEqual(m.mean_pairwise_distance, 0.25)
        self.assertGreaterEqual(m.routing_unique, 1)
        self.assertTrue(m.ok)

    def test_design_seed_affects_signature(self):
        from eval.diversity_eval import plan_signature_tokens, unwrap_plan

        a = unwrap_plan(_load("compositional_seed_100.json"))
        b = unwrap_plan(_load("compositional_seed_8842.json"))
        self.assertNotEqual(plan_signature_tokens(a), plan_signature_tokens(b))

    def test_diversity_scenarios_runner(self):
        from eval.diversity_eval import run_diversity_scenarios

        # stadium_diversity_variants 应 PASS；duplicate 场景仅 WARN（无 --gate）
        code = run_diversity_scenarios(gate=False)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
