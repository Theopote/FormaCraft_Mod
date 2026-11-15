"""Unit tests for check_dead_code (stdlib only)."""
import json
import tempfile
import unittest
from pathlib import Path

from check_dead_code import scan


class DeadCodeTests(unittest.TestCase):
    def _write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_reports_unreferenced_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Dead.java",
                "package com.example;\npublic final class Dead {\n}\n",
            )
            names = {name for _, name, _ in scan(root)}
            self.assertIn("Dead", names)

    def test_ignores_type_referenced_elsewhere(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Live.java",
                "package com.example;\npublic final class Live {\n}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/User.java",
                "package com.example;\npublic final class User {\n"
                "    Live x;\n}\n",
            )
            names = {name for _, name, _ in scan(root)}
            self.assertNotIn("Live", names)

    def test_ignores_type_declared_in_multiple_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Same type name declared in two files: not a zero-consumer candidate.
            self._write(
                root,
                "src/main/java/com/example/A.java",
                "package com.example;\npublic final class A {\n}\n",
            )
            self._write(
                root,
                "src/main/java/com/other/A.java",
                "package com.other;\npublic final class A {\n}\n",
            )
            names = {name for _, name, _ in scan(root)}
            self.assertNotIn("A", names)


if __name__ == "__main__":
    unittest.main()
