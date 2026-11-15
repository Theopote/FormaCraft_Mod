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


class CommentAndStringStrippingTests(unittest.TestCase):
    """A type mentioned only in a comment/string is NOT a live dependency."""

    def _write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def _scaffold_with_dead_type(self, root: Path, entry_body: str) -> None:
        self._write(
            root,
            "src/main/resources/fabric.mod.json",
            json.dumps({"entrypoints": {"main": ["com.example.Entry"]}}),
        )
        self._write(root, "src/main/resources/formacraft.mixins.json", json.dumps({"client": []}))
        self._write(root, "build.gradle", "\n")
        self._write(root, "config/dead_code_allowlist.json", json.dumps({"allow": []}))
        self._write(
            root,
            "src/main/java/com/example/Entry.java",
            f"package com.example;\npublic final class Entry {{\n{entry_body}\n}}\n",
        )
        self._write(
            root,
            "src/main/java/com/example/DeadWidget.java",
            "package com.example;\npublic final class DeadWidget {}\n",
        )

    def _names(self, root: Path) -> set[str]:
        return {name for name, _ in C.scan(root)}

    def test_type_mentioned_only_in_line_comment_is_unreachable(self):
        """Negative test: a comment must not keep a dead class alive."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(root, "    // 旧的 DeadWidget 已经不用了")
            self.assertIn("DeadWidget", self._names(root))

    def test_type_mentioned_only_in_block_comment_is_unreachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(root, "    /* DeadWidget was removed long ago */")
            self.assertIn("DeadWidget", self._names(root))

    def test_type_mentioned_only_in_javadoc_is_unreachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(
                root,
                "    /**\n     * @see DeadWidget\n     */",
            )
            self.assertIn("DeadWidget", self._names(root))

    def test_type_mentioned_only_in_string_literal_is_unreachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(
                root,
                '    private static final String OLD = "DeadWidget";',
            )
            self.assertIn("DeadWidget", self._names(root))

    def test_type_mentioned_only_in_text_block_is_unreachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(
                root,
                '    private static final String OLD = """\n        DeadWidget\n        """;',
            )
            self.assertIn("DeadWidget", self._names(root))

    def test_real_field_declaration_still_counts_as_dependency(self):
        """Stripping must not remove genuine code references."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(root, "    private DeadWidget widget;")
            self.assertNotIn("DeadWidget", self._names(root))

    def test_real_constructor_call_still_counts_as_dependency(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(root, "    private final Object w = new DeadWidget();")
            self.assertNotIn("DeadWidget", self._names(root))

    def test_comment_mention_in_unreachable_file_keeps_both_unreachable(self):
        """A dead cluster that only mentions each other in comments is still dead."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._scaffold_with_dead_type(root, "    // nothing here")
            self._write(
                root,
                "src/main/java/com/example/Other.java",
                "package com.example;\npublic final class Other {\n"
                "    // DeadWidget is referenced here only in prose.\n}\n",
            )
            names = self._names(root)
            self.assertIn("DeadWidget", names)
            self.assertIn("Other", names)


class StripHelperTests(unittest.TestCase):
    def test_strip_removes_comments_and_literals_but_keeps_code(self):
        text = (
            "class A {\n"
            "  // DeadWidget\n"
            "  /* DeadWidget */\n"
            '  String s = "DeadWidget";\n'
            "  char c = 'x';\n"
            "  DeadWidget real;\n"
            "}\n"
        )
        stripped = C.strip_comments_and_strings(text)
        # The only surviving occurrence must be the genuine field declaration.
        self.assertEqual(stripped.count("DeadWidget"), 1)
        self.assertIn("DeadWidget real;", stripped)
        self.assertIn("class A", stripped)

    def test_strip_handles_escaped_quotes(self):
        stripped = C.strip_comments_and_strings('String s = "a\\"DeadWidget";')
        self.assertNotIn("DeadWidget", stripped)

    def test_strip_preserves_declaration_lines(self):
        text = "package com.example;\npublic final class Entry {}\n"
        self.assertIn("class Entry", C.strip_comments_and_strings(text))


if __name__ == "__main__":
    unittest.main()
