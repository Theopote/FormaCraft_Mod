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
REFERENCE = re.compile(
    r"\bcom\.formacraft\.(client|server)\b"
    r"|\bnet\.minecraft\.client\b|\bnet\.fabricmc\.fabric\.api\.client\b"
)
# common must not grow new dependencies on Minecraft server runtime types.
SERVER_TYPE = re.compile(
    r"\b(?:ServerWorld|ServerPlayerEntity|MinecraftServer|net\.minecraft\.server)\b"
)
# Preserve line numbers while excluding comments and Java string/character literals.
NON_CODE = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def client_mixin_sources(config: dict) -> set[str]:
    """Only classes explicitly declared in the mixin config's client list are client-only."""
    package = config["package"].removeprefix("com.formacraft.").replace(".", "/")
    return {f"{package}/{name.replace('.', '/')}.java" for name in config.get("client", [])}


def violations(root: Path, *, client_only_sources: set[str] = frozenset()) -> list[str]:
    errors = []
    for path in sorted(root.rglob("*.java")):
        relative = path.relative_to(root).as_posix()
        code = NON_CODE.sub(lambda m: "\n" * m.group().count("\n"), path.read_text(encoding="utf-8-sig"))
        layer = relative.split("/")[0] if "/" in relative else "root"
        if layer != "client" and relative not in client_only_sources:
            forbidden = {"client", "server"} if layer == "common" else {"client"}
            for match in REFERENCE.finditer(code):
                target = match.group(1) or "client"
                if target in forbidden:
                    errors.append(f"{relative}: {layer} references {target}")
        if layer == "common":
            for _ in SERVER_TYPE.finditer(code):
                errors.append(f"{relative}: common uses minecraft server type")
        if relative == "common/world/BlockMutationAccess.java":
            continue
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
    mixins = json.loads((ROOT / "src/main/resources/formacraft.mixins.json").read_text(encoding="utf-8"))
    actual = Counter(violations(JAVA, client_only_sources=client_mixin_sources(mixins)))
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
