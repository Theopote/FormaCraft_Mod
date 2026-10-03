"""Regression tests for the source boundary guard (standard library only)."""
import tempfile
from pathlib import Path
import unittest

from check_architecture import violations


class BoundaryTests(unittest.TestCase):
    def test_imports_and_qualified_references_ignore_comments_and_literals(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "common").mkdir()
            (root / "common/Example.java").write_text('''
                // com.formacraft.server.Ignore
                /* com.formacraft.client.Ignore */
                class Example {
                    String text = "com.formacraft.server.Ignore";
                    String block = """com.formacraft.client.Ignore""";
                    com.formacraft.server.Real value;
                }
                import com.formacraft.client.Real;
            ''', encoding="utf-8")
            self.assertEqual(len(violations(root)), 2)

    def test_server_can_use_common_but_cannot_use_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "server").mkdir()
            (root / "server/Example.java").write_text('''
                import com.formacraft.common.model.Data;
                import com.formacraft.server.build.Service;
                import static com.formacraft.client.State.value;
            ''', encoding="utf-8")
            self.assertEqual(len(violations(root)), 1)


if __name__ == "__main__":
    unittest.main()
