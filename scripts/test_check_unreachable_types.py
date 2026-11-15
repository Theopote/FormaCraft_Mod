"""Unit tests for check_unreachable_types (stdlib only)."""
import json
import tempfile
import unittest
from pathlib import Path

import check_unreachable_types as C


class UnreachableTypeTests(unittest.TestCase):
    def test_duplicate_unreachable_names_report_every_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold(root)
            self._write(root, "src/main/java/Entry.java", "public class Entry {}")
            for package in ("a", "b"):
                self._write(root, f"src/main/java/{package}/Duplicate.java",
                            f"package {package};\npublic class Duplicate {{}}")
            duplicates = [path for name, path in C.scan(root) if name == "Duplicate"]
            self.assertEqual(len(duplicates), 2)

    def test_duplicate_reachable_names_traverse_both_dependencies(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold(root)
            self._write(root, "src/main/java/Entry.java", "public class Entry { Duplicate value; }")
            for package, dependency in (("a", "Left"), ("b", "Right")):
                self._write(root, f"src/main/java/{package}/Duplicate.java",
                            f"package {package};\npublic class Duplicate {{ {dependency} value; }}")
                self._write(root, f"src/main/java/{package}/{dependency}.java",
                            f"package {package};\npublic class {dependency} {{}}")
            self.assertEqual(C.scan(root), [])

    def _write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def _scaffold(self, root: Path) -> None:
        """Minimal framework config so _load_roots() finds a known root."""
        self._write(
            root,
            "src/main/resources/fabric.mod.json",
            json.dumps({"entrypoints": {"main": ["com.example.Entry"]}}),
        )
        self._write(root, "src/main/resources/formacraft.mixins.json", json.dumps({"client": []}))
        self._write(root, "build.gradle", "tasks.register('x') { }\n")
        self._write(
            root,
            "config/dead_code_allowlist.json",
            json.dumps({"allow": []}),
        )

    def _names(self, root: Path) -> set[str]:
        return {name for name, _ in C.scan(root)}

    def test_reports_type_not_reachable_from_roots(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold(root)
            self._write(
                root,
                "src/main/java/com/example/Entry.java",
                "package com.example;\npublic final class Entry {}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Orphan.java",
                "package com.example;\npublic final class Orphan {}\n",
            )
            self.assertIn("Orphan", self._names(root))

    def test_reports_mutually_referencing_cluster(self):
        """The blind spot of the per-class reference count: A references B and
        B references A, so neither has zero external references — but the
        cluster is unreachable from the roots."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold(root)
            self._write(
                root,
                "src/main/java/com/example/Entry.java",
                "package com.example;\npublic final class Entry {}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/A.java",
                "package com.example;\npublic final class A { B b; }\n",
            )
            self._write(
                root,
                "src/main/java/com/example/B.java",
                "package com.example;\npublic final class B { A a; }\n",
            )
            names = self._names(root)
            self.assertIn("A", names)
            self.assertIn("B", names)

    def test_keeps_type_reachable_through_a_chain(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold(root)
            self._write(
                root,
                "src/main/java/com/example/Entry.java",
                "package com.example;\npublic final class Entry { Mid mid; }\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Mid.java",
                "package com.example;\npublic final class Mid { Leaf leaf; }\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Leaf.java",
                "package com.example;\npublic final class Leaf {}\n",
            )
            self.assertEqual(self._names(root), set())

    def test_mixin_class_is_a_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/resources/fabric.mod.json",
                json.dumps({"entrypoints": {"main": ["com.example.Entry"]}}),
            )
            self._write(
                root,
                "src/main/resources/formacraft.mixins.json",
                json.dumps({"client": ["SomeMixin"]}),
            )
            self._write(root, "build.gradle", "\n")
            self._write(root, "config/dead_code_allowlist.json", json.dumps({"allow": []}))
            self._write(
                root,
                "src/main/java/com/example/Entry.java",
                "package com.example;\npublic final class Entry {}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/SomeMixin.java",
                "package com.example;\npublic final class SomeMixin {}\n",
            )
            self.assertEqual(self._names(root), set())

    def test_roots_are_read_from_config_not_hand_listed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/resources/fabric.mod.json",
                json.dumps({"entrypoints": {"main": ["com.example.Entry"]}}),
            )
            self._write(
                root,
                "src/main/resources/formacraft.mixins.json",
                json.dumps({"client": []}),
            )
            self._write(root, "build.gradle", "\n")
            self._write(root, "config/dead_code_allowlist.json", json.dumps({"allow": []}))
            self._write(
                root,
                "src/main/java/com/example/Entry.java",
                "package com.example;\npublic final class Entry {}\n",
            )
            roots, sources = C._load_roots(root)
            self.assertIn("Entry", roots)
            self.assertTrue(any("fabric.mod.json" in s for s in sources))


if __name__ == "__main__":
    unittest.main()
