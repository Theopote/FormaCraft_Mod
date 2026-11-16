"""Unit tests for check_design_authority_invariants (stdlib only)."""

from __future__ import annotations

import tempfile
import textwrap
import unittest
from pathlib import Path

from check_design_authority_invariants import violations


class CheckDesignAuthorityInvariantsTest(unittest.TestCase):
    def test_repo_root_passes(self):
        self.assertEqual([], violations())

    def test_detects_keyword_preset_return(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel in (
                "AGENTS.md",
                ".cursor/rules/formacraft-ai-design-authority.mdc",
                ".cursor/skills/formacraft-design-authority-audit/SKILL.md",
                "docs/architecture/AI_DESIGN_AUTHORITY_GUARDRAILS.md",
            ):
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("x", encoding="utf-8")
            target = root / "python_backend/app/services/assembly_plan_validator.py"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                textwrap.dedent(
                    '''
                    def resolve_preset_for_intent(text):
                        if "spiral" in (text or ""):
                            return "spiral_watchtower"
                        return None
                    '''
                ),
                encoding="utf-8",
            )
            errs = violations(root)
            self.assertTrue(any("banned keyword→preset" in e for e in errs))
            self.assertTrue(any("preset:<id>" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
