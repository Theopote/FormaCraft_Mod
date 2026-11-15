"""Unit tests for check_unused_interface_methods (stdlib only)."""
import tempfile
import unittest
from pathlib import Path

from check_unused_interface_methods import scan


class UnusedInterfaceMethodTests(unittest.TestCase):
    def _write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def _names(self, root: Path) -> set[str]:
        return {f"{iface}.{method}" for iface, method, _ in scan(root)}

    def test_reports_never_invoked_method(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    void doWork();\n"
                "}\n",
            )
            self.assertIn("Worker.doWork", self._names(root))

    def test_ignores_method_invoked_on_a_receiver(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    void doWork();\n"
                "}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Runner.java",
                "package com.example;\n"
                "public final class Runner {\n"
                "    void go(Worker w) { w.doWork(); }\n"
                "}\n",
            )
            self.assertNotIn("Worker.doWork", self._names(root))

    def test_ignores_method_invoked_unqualified_inside_an_implementor(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    void doWork();\n"
                "}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Impl.java",
                "package com.example;\n"
                "public final class Impl implements Worker {\n"
                "    public void doWork() { helper(); }\n"
                "    private void helper() {}\n"
                "}\n",
            )
            # doWork() is invoked only as a declaration in Impl; the interface
            # method itself is never called from anywhere.
            self.assertIn("Worker.doWork", self._names(root))

    def test_unrelated_same_named_method_does_not_count_as_a_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    void doWork();\n"
                "}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Unrelated.java",
                "package com.example;\n"
                "public final class Unrelated {\n"
                "    void doWork() {}\n"
                "}\n",
            )
            self.assertIn("Worker.doWork", self._names(root))

    def test_method_reference_counts_as_a_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    void doWork();\n"
                "}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Runner.java",
                "package com.example;\n"
                "public final class Runner {\n"
                "    Runnable r = Worker::doWork;\n"
                "}\n",
            )
            self.assertNotIn("Worker.doWork", self._names(root))

    def test_mixin_dollar_names_are_recognised(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Accessor.java",
                "package com.example;\n"
                "public interface Accessor {\n"
                "    float formacraft$getFov();\n"
                "}\n",
            )
            self._write(
                root,
                "src/main/java/com/example/Use.java",
                "package com.example;\n"
                "public final class Use {\n"
                "    float go(Accessor a) { return a.formacraft$getFov(); }\n"
                "}\n",
            )
            self.assertNotIn("Accessor.formacraft$getFov", self._names(root))

    def test_ignores_nested_interfaces(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Outer.java",
                "package com.example;\n"
                "public final class Outer {\n"
                "    public interface Inner {\n"
                "        void never();\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual(self._names(root), set())

    def test_default_methods_are_not_abstract_contracts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write(
                root,
                "src/main/java/com/example/Worker.java",
                "package com.example;\n"
                "public interface Worker {\n"
                "    default void helper() {}\n"
                "}\n",
            )
            self.assertEqual(self._names(root), set())


if __name__ == "__main__":
    unittest.main()
