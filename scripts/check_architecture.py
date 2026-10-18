"""Check Java source package boundaries without Minecraft or third-party dependencies.

Run from any directory: python scripts/check_architecture.py
This source check covers imports and fully qualified references, not runtime loading.
"""

from pathlib import Path
from collections import Counter
import json
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/formacraft"
REFERENCE = re.compile(r"\bcom\.formacraft\.(client|server)\b")
# Preserve line numbers while excluding comments and Java string/character literals.
NON_CODE = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def violations(root: Path) -> list[str]:
    errors = []
    for layer, forbidden in (("common", {"client", "server"}), ("server", {"client"})):
        for path in sorted((root / layer).rglob("*.java")):
            source = path.read_text(encoding="utf-8-sig")
            code = NON_CODE.sub(lambda match: "\n" * match.group().count("\n"), source)
            for match in REFERENCE.finditer(code):
                if match.group(1) in forbidden:
                    errors.append(f"{path.relative_to(root).as_posix()}: {layer} references {match.group(1)}")
    for path in sorted(root.rglob("*.java")):
        if path.relative_to(root).as_posix() == "common/world/BlockMutationAccess.java":
            continue
        code = NON_CODE.sub(lambda m: "\n" * m.group().count("\n"), path.read_text(encoding="utf-8-sig"))
        for match in re.finditer(r"\.\s*setBlockState\s*\(([^;]*)", code):
            # World writes take position + state; Voxel setters take one argument.
            depth = 0
            has_separator = False
            for char in match.group(1):
                if char == "(": depth += 1
                elif char == ")":
                    if depth == 0: break
                    depth -= 1
                elif char == "," and depth == 0: has_separator = True
            if has_separator:
                errors.append(f"{path.relative_to(root).as_posix()}: world write bypasses BlockMutationAccess")
    return errors


def main() -> int:
    if not JAVA.is_dir():
        print(f"Missing Java source root: {JAVA}", file=sys.stderr)
        return 1
    actual = Counter(violations(JAVA))
    baseline = Counter(json.loads((ROOT / "config/architecture-debt.json").read_text(encoding="utf-8")))
    errors = actual - baseline
    stale = baseline - actual
    for error, count in errors.items():
        print(f"{error} ({count} new reference(s))", file=sys.stderr)
    for error, count in stale.items():
        print(f"Remove resolved baseline debt: {error} ({count} reference(s))", file=sys.stderr)
    print(f"Java package boundaries: {sum(actual.values())} known debt reference(s), {sum(errors.values())} new")
    return bool(errors or stale)


if __name__ == "__main__":
    raise SystemExit(main())
