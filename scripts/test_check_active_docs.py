"""Unit tests for check_active_docs (stdlib only)."""
import json
import tempfile
import unittest
from pathlib import Path

from check_active_docs import violations


class ActiveDocsTests(unittest.TestCase):
    def test_flags_live_guidance_but_allows_historical_annotation(self):
        cfg = {
            "tier0_globs": ["ARCHITECTURE.md"],
            "banned": [{"id": "BuildingSpecRoutingPolicy", "patterns": ["BuildingSpecRoutingPolicy"]}],
            "allow_line_substrings": ["已删除", "历史", "REMOVED"],
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "ARCHITECTURE.md").write_text(
                "Use BuildingSpecRoutingPolicy for routing.\n"
                "BuildingSpecRoutingPolicy 已删除；勿恢复。\n"
                "REMOVED: BuildingSpecRoutingPolicy\n",
                encoding="utf-8",
            )
            errs = violations(root, cfg)
            self.assertEqual(len(errs), 1)
            self.assertIn("ARCHITECTURE.md:1", errs[0])

    def test_missing_tier0_file_is_error(self):
        cfg = {"tier0_globs": ["missing.md"], "banned": [], "allow_line_substrings": []}
        with tempfile.TemporaryDirectory() as tmp:
            errs = violations(Path(tmp), cfg)
            self.assertTrue(any("missing" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
