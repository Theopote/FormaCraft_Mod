"""Schema checks for ASSEMBLY component golden plans."""
import json
import unittest
from pathlib import Path

FIXTURE = Path(__file__).resolve().parents[1] / "eval" / "fixtures" / "plans" / "assembly_gothic_shell_golden.json"


class AssemblyGoldenPlanTest(unittest.TestCase):
    def test_assembly_golden_has_payload(self):
        plan = json.loads(FIXTURE.read_text(encoding="utf-8"))
        comps = plan.get("components") or []
        self.assertEqual(1, len(comps))
        self.assertEqual("ASSEMBLY", comps[0].get("component_type"))
        params = comps[0].get("params") or {}
        assembly = params.get("assembly")
        self.assertIsInstance(assembly, dict)
        self.assertIn("graph", assembly)
        self.assertIn("macro", assembly)
        graph = assembly.get("graph") or {}
        components = graph.get("components") or []
        self.assertTrue(components)
        self.assertIn("twistTurns", components[0])


if __name__ == "__main__":
    unittest.main()
