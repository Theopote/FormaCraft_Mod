"""Regression tests for the source boundary guard (standard library only)."""
import tempfile
from pathlib import Path
import unittest

from check_architecture import client_mixin_sources, violations


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

    def test_root_legacy_and_config_packages_cannot_hide_client_dependencies(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("FormacraftMod.java", "ai/Context.java", "config/Settings.java"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("import com.formacraft.client.tool.SelectionTool;", encoding="utf-8")
            self.assertEqual(len(violations(root)), 3)

    def test_client_api_references_are_checked_outside_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Bad.java").write_text("""
                import net.minecraft.client.MinecraftClient;
                import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
            """, encoding="utf-8")
            (root / "client").mkdir()
            (root / "client/Good.java").write_text(
                "import net.minecraft.client.MinecraftClient;", encoding="utf-8")
            self.assertEqual(len(violations(root)), 2)

    def test_only_explicit_client_mixins_are_exempt_from_client_boundary(self):
        config = {"package": "com.formacraft.mixin", "client": ["ClientMixin"], "mixins": ["SharedMixin"]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "mixin").mkdir()
            for name in ("ClientMixin", "SharedMixin", "UndeclaredMixin"):
                (root / f"mixin/{name}.java").write_text(
                    "import net.minecraft.client.MinecraftClient;", encoding="utf-8")
            errors = violations(root, client_only_sources=client_mixin_sources(config))
            self.assertEqual(len(errors), 2)
            self.assertTrue(any("SharedMixin" in error for error in errors))
            self.assertTrue(any("UndeclaredMixin" in error for error in errors))

    def test_world_writes_are_checked_even_in_client_mixins(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Mixin.java").write_text("world.setBlockState(pos, state);", encoding="utf-8")
            errors = violations(root, client_only_sources={"Mixin.java"})
            self.assertEqual(len(errors), 1)
            self.assertIn("world write bypasses", errors[0])


if __name__ == "__main__":
    unittest.main()
