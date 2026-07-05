"""Schema checks for ASSEMBLY preset golden plans."""
import json
import unittest
from pathlib import Path

FIXTURE = Path(__file__).resolve().parents[1] / "eval" / "fixtures" / "plans" / "assembly_spiral_preset_golden.json"


class AssemblyPresetGoldenPlanTest(unittest.TestCase):
    def test_preset_golden_has_spiral_watchtower(self):
        plan = json.loads(FIXTURE.read_text(encoding="utf-8"))
        comps = plan.get("components") or []
        self.assertEqual(1, len(comps))
        self.assertEqual("ASSEMBLY", comps[0].get("component_type"))
        assembly = (comps[0].get("params") or {}).get("assembly") or {}
        self.assertEqual("spiral_watchtower", assembly.get("preset"))
        preset_params = assembly.get("presetParams") or {}
        self.assertIn("twistTurns", preset_params)
        self.assertIn("height", preset_params)


if __name__ == "__main__":
    unittest.main()
