"""Unit tests for extended generation entrypoint checks."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import check_generation_entrypoints as cg


class EntrypointChecks(unittest.TestCase):
    def test_python_symbol_detected_via_ast(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            mod = root / "pkg"
            mod.mkdir()
            (mod / "m.py").write_text("def generate_llm_plan():\n    return {}\n", encoding="utf-8")
            with mock.patch.object(cg, "PY_ROOT", root):
                errs = cg._python_symbol_ok("pkg.m", "generate_llm_plan", "t")
                self.assertEqual(errs, [])
                errs = cg._python_symbol_ok("pkg.m", "missing", "t")
                self.assertEqual(len(errs), 1)

    def test_java_method_and_fragment(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "X.java"
            path.write_text("class X { public void requestAiPlan() {} }\n", encoding="utf-8")
            errs = cg._java_source_ok(path, {"id": "t", "methods": ["requestAiPlan"], "source_contains": ["requestAiPlan"]})
            self.assertEqual(errs, [])
            errs = cg._java_source_ok(path, {"id": "t", "methods": ["absent"]})
            self.assertEqual(len(errs), 1)


if __name__ == "__main__":
    unittest.main()
