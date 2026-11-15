"""Unit tests for check_doc_dead_symbols (stdlib only)."""
import json
import tempfile
import unittest
from pathlib import Path

from check_doc_dead_symbols import violations


CONFIG = {
    "tracked": ["GhostType", "RemovedHelper"],
    "allow_line_substrings": ["已删除", "核对", "不得"],
}


class DocDeadSymbolsTests(unittest.TestCase):
    def _write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_flags_live_mention(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "docs/LIVE.md", "Use GhostType for routing.\n")
            errs = violations(root, CONFIG)
            self.assertEqual(len(errs), 1)
            self.assertIn("LIVE.md:1", errs[0])

    def test_allows_historical_annotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "docs/LIVE.md", "GhostType 已删除；勿恢复。\n")
            self.assertEqual(violations(root, CONFIG), [])

    def test_marker_on_adjacent_line_covers_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "docs/LIVE.md",
                "以下类已删除：\n\n- GhostType\n- RemovedHelper\n",
            )
            self.assertEqual(violations(root, CONFIG), [])

    def test_archived_docs_are_exempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(root, "docs/archive/OLD.md", "Use GhostType for routing.\n")
            self.assertEqual(violations(root, CONFIG), [])

    def test_missing_docs_dir_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            errs = violations(Path(tmp), CONFIG)
            self.assertTrue(any("docs" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
