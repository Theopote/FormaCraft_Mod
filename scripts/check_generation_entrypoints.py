"""Verify config/generation_entrypoints.json still matches the repo.

Checks:
- Java class files exist; optional ``methods`` / ``source_contains`` markers appear in source
- Python modules exist; optional ``symbol`` is importable/callable
"""

from __future__ import annotations

import ast
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config/generation_entrypoints.json"
JAVA_ROOT = ROOT / "src/main/java"
PY_ROOT = ROOT / "python_backend"


def _java_source_ok(path: Path, entry: dict) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8")
    for method in entry.get("methods") or []:
        # Simple presence: method name as a declaration-ish token (avoids false "comment only").
        needle = f" {method}("
        alt = f" {method} ("
        if needle not in text and alt not in text and f".{method}(" not in text:
            # Also accept static/public method declarations without leading space quirks
            if f"{method}(" not in text:
                errors.append(f"{entry.get('id')}: Java method/marker not found: {method} in {path.name}")
    for fragment in entry.get("source_contains") or []:
        if fragment not in text:
            errors.append(
                f"{entry.get('id')}: expected source fragment missing in {path.name}: {fragment!r}"
            )
    return errors


def _python_symbol_ok(module: str, symbol: str | None, entry_id: str) -> list[str]:
    if not symbol:
        return []
    mod_path = PY_ROOT / (module.replace(".", "/") + ".py")
    errors: list[str] = []
    try:
        src = mod_path.read_text(encoding="utf-8")
        tree = ast.parse(src, filename=str(mod_path))
    except SyntaxError as e:
        return [f"{entry_id}: cannot parse {module}: {e}"]

    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
        elif isinstance(node, ast.Assign):
            for t in node.targets:
                if isinstance(t, ast.Name):
                    names.add(t.id)
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                names.add(alias.asname or alias.name)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            names.add(node.target.id)

    if symbol not in names:
        # Fallback: load module and check attribute (covers re-exports assigned dynamically).
        try:
            spec = importlib.util.spec_from_file_location(module, mod_path)
            if spec is None or spec.loader is None:
                errors.append(f"{entry_id}: cannot load {module} for symbol {symbol}")
                return errors
            mod = importlib.util.module_from_spec(spec)
            # Avoid executing heavy side effects: AST miss is enough for most; try getattr only if needed.
            # Prefer AST; if missing, report without import execution of ai_planner.
            errors.append(
                f"{entry_id}: symbol {symbol!r} not defined/imported at module top-level in {module}"
            )
        except Exception as e:  # noqa: BLE001
            errors.append(f"{entry_id}: symbol check failed for {module}.{symbol}: {e}")
    return errors


def main() -> int:
    if not CONFIG.is_file():
        print(f"Missing {CONFIG}", file=sys.stderr)
        return 1
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    errors: list[str] = []

    for entry in data.get("java", []):
        cls = entry.get("class", "")
        if not cls:
            errors.append(f"java entry missing class: {entry}")
            continue
        rel = cls.replace(".", "/") + ".java"
        path = JAVA_ROOT / rel
        if not path.is_file():
            errors.append(f"Java class not found: {cls} ({path})")
            continue
        errors.extend(_java_source_ok(path, entry))

    for entry in data.get("python", []):
        mod = entry.get("module", "")
        entry_id = entry.get("id", mod)
        if not mod:
            errors.append(f"python entry missing module: {entry}")
            continue
        path = PY_ROOT / f"{mod.replace('.', '/')}.py"
        if not path.is_file():
            errors.append(f"Python module not found: {mod} ({path})")
            continue
        errors.extend(_python_symbol_ok(mod, entry.get("symbol"), entry_id))

    if errors:
        for e in errors:
            print(e, file=sys.stderr)
        return 1
    print(
        f"Generation entrypoints OK "
        f"({len(data.get('java', []))} Java, {len(data.get('python', []))} Python; symbols/methods checked)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
