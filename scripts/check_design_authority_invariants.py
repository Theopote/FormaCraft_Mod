"""Static Design Authority invariants for planner/repair hot paths.

Hard-fails keyword→preset resurrection and missing governance files.
"""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = (
    "AGENTS.md",
    ".cursor/rules/formacraft-ai-design-authority.mdc",
    ".cursor/skills/formacraft-design-authority-audit/SKILL.md",
    "docs/architecture/AI_DESIGN_AUTHORITY_GUARDRAILS.md",
)

ASSEMBLY_VALIDATOR = ROOT / "python_backend/app/services/assembly_plan_validator.py"

# Hard-coded aesthetic keyword → preset returns that must not reappear in resolve_preset_for_intent.
_BANNED_KEYWORD_PRESET_RETURNS = (
    'return "spiral_watchtower"',
    "return 'spiral_watchtower'",
    'return "gothic_shell_box"',
    "return 'gothic_shell_box'",
    'return "suspension_bridge_simple"',
    "return 'suspension_bridge_simple'",
)


def _resolve_preset_function_source(path: Path) -> str:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == "resolve_preset_for_intent":
            return ast.get_source_segment(path.read_text(encoding="utf-8"), node) or ""
    return ""


def violations(root: Path | None = None) -> list[str]:
    root = root or ROOT
    errors: list[str] = []

    for rel in REQUIRED_FILES:
        if not (root / rel).is_file():
            errors.append(f"missing governance file: {rel}")

    validator = root / "python_backend/app/services/assembly_plan_validator.py"
    if not validator.is_file():
        errors.append("missing assembly_plan_validator.py")
        return errors

    body = _resolve_preset_function_source(validator)
    if not body:
        errors.append("resolve_preset_for_intent not found in assembly_plan_validator.py")
        return errors

    if "preset:" not in body and "preset\\:" not in body:
        # Source uses a regex with preset: — accept either literal form.
        if not re.search(r"preset\s*:", body):
            errors.append(
                "resolve_preset_for_intent must require explicit preset:<id> opt-in"
            )

    for needle in _BANNED_KEYWORD_PRESET_RETURNS:
        if needle in body:
            errors.append(
                "resolve_preset_for_intent contains banned keyword→preset return: "
                + needle
            )

    # Must not call detects_assembly_intent as a fallback selector.
    if re.search(r"detects_assembly_intent\s*\(", body):
        errors.append(
            "resolve_preset_for_intent must not fall back via detects_assembly_intent()"
        )

    return errors


def main() -> int:
    errs = violations()
    if errs:
        print("Design authority invariant failures:")
        for err in errs:
            print(f"  - {err}")
        return 1
    print("Design authority invariants OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
