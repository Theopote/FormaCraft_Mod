"""Report interface methods that are declared but never invoked in production.

The zero-consumer scan in ``check_dead_code.py`` misses *mutually referencing*
scaffolding: a registry can be wired up at startup (so the classes look live)
while the interface method that does the actual work is never called.

This gate closes that gap. For every top-level interface it collects the
abstract methods and looks for a call site (``name(`` or ``Type::name``)
somewhere under ``src/main/java`` **outside any declaration of that name**.

A method that only ever appears in test code is reported: that is the
"self-proving" pattern (the implementation tests itself, nothing else uses it)
that this project has been removing.

Framework entry points and intentionally unused SPI are listed in
``config/unused_interface_methods.json``.
"""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"
CONFIG = ROOT / "config/unused_interface_methods.json"

TOP_INTERFACE = re.compile(r"^public\s+(?:abstract\s+)?interface\s+(\w+)", re.M)

# Any identifier immediately followed by '(' — a candidate call or declaration.
# Mixin accessors use ``$`` in their names, so the identifier charset must
# allow it.
IDENT_CALL = re.compile(r"(?P<name>[\w$]+)\s*\(")
# A receiver-qualified call: ``host.method(`` / ``obj.method(``.
QUALIFIED_CALL = re.compile(r"\.\s*(?P<name>[\w$]+)\s*\(")
# A method reference: ``Type::name``.
METHOD_REF = re.compile(r"::\s*(?P<name>[\w$]+)")

MODIFIERS = {
    "public", "protected", "private", "static", "final", "abstract",
    "default", "synchronized", "native", "strictfp", "transient", "volatile",
    "sealed", "non-sealed",
}
# Words that sit where a return type would be but are really statements.
# A call like ``new Foo(`` or ``return foo(`` must not read as a declaration.
STATEMENT_KEYWORDS = {
    "new", "return", "throw", "case", "else", "do", "if", "while", "for",
    "switch", "yield", "await", "assert", "break", "continue", "instanceof",
    "super", "this", "delete", "var",
}

# The token immediately before the candidate identifier, when it looks like a
# return type: ``void ``, ``List<Socket> ``, ``SliderWidget ``.
RETURN_TYPE = re.compile(
    r"(?:^|[\s;{}()\[\]])"
    r"((?:[\w$][\w$]*\s*\.)*[\w$][\w$]*)"
    r"(?:\s*<[^<>]*>)?"
    r"(?:\s*\[\s*\])?"
    r"\s*$"
)


def _load_allowlist() -> set[str]:
    if not CONFIG.is_file():
        return set()
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    return set(data.get("allow") or [])


def _is_declaration(text: str, index: int) -> bool:
    """True if the identifier at ``index`` begins a method *declaration*.

    A declaration is preceded on its line by a return type. Anything else —
    a receiver (``host.``), an operator, an assignment, a statement keyword —
    means the identifier is being *called*.
    """
    line_start = text.rfind("\n", 0, index) + 1
    prefix = text[line_start:index]

    if not prefix.strip():
        # A bare `foo(` — ambiguous. Treat it as a call so the gate stays
        # conservative (a false *pass* is safer than a false *failure*).
        return False

    stripped = prefix.rstrip()
    if stripped.endswith(".") or stripped.endswith(":"):
        return False  # receiver-qualified call

    match = RETURN_TYPE.search(stripped)
    if not match:
        return False
    head = match.group(1).split(".")[-1]
    return head not in STATEMENT_KEYWORDS


IMPLIES = re.compile(
    r"\b(?:implements|extends)\b[^{;]*?\b(?P<iface>\w+)\b"
)


def _implementing_files(iface: str, sources: dict[Path, str]) -> set[Path]:
    """Files that implement or extend ``iface``."""
    found: set[Path] = set()
    for path, text in sources.items():
        for match in IMPLIES.finditer(text):
            if match.group("iface") == iface:
                found.add(path)
                break
    return found


def scan(root: Path | None = None) -> list[tuple[str, str, str]]:
    """Return (interface, method, relative_path) for never-invoked methods."""
    base = (root or ROOT) / "src/main/java"
    files = sorted(base.rglob("*.java"))
    sources = {p: p.read_text(encoding="utf-8", errors="replace") for p in files}

    # name -> list of (path, qualified) for every call site under src/main
    call_sites: dict[str, list[tuple[Path, bool]]] = defaultdict(list)
    for path, text in sources.items():
        for match in QUALIFIED_CALL.finditer(text):
            call_sites[match.group("name")].append((path, True))
        for match in IDENT_CALL.finditer(text):
            if match.start("name") and text[match.start("name") - 1] == ".":
                continue  # already counted as qualified
            if _is_declaration(text, match.start("name")):
                continue
            call_sites[match.group("name")].append((path, False))
        for match in METHOD_REF.finditer(text):
            call_sites[match.group("name")].append((path, True))

    results: list[tuple[str, str, str]] = []
    for path, text in sources.items():
        iface_match = TOP_INTERFACE.search(text)
        if not iface_match or path.stem != iface_match.group(1):
            continue  # only top-level interfaces
        iface = iface_match.group(1)
        body = text[iface_match.end():]
        end = body.rfind("\n}")
        if end > 0:
            body = body[:end]

        # Abstract interface methods end with ';' (no body).
        methods = sorted({
            m.group("name")
            for m in re.finditer(r"(?P<name>[\w$]+)\s*\([^()]*\)\s*;", body)
            if _is_declaration(body, m.start("name"))
        })
        if not methods:
            continue

        # An unqualified call only counts when it can reach this interface:
        # either inside the interface itself (default methods calling siblings)
        # or inside a class that implements/extends it.
        unqualified_ok = {path} | _implementing_files(iface, sources)

        for name in methods:
            invoked = False
            for call_path, qualified in call_sites.get(name, []):
                if qualified or call_path in unqualified_ok:
                    invoked = True
                    break
            if not invoked:
                rel = path.relative_to(root or ROOT).as_posix()
                results.append((iface, name, rel))
    results.sort()
    return results


def main() -> int:
    allow = _load_allowlist()
    found = scan()
    unexpected = [r for r in found if f"{r[0]}.{r[1]}" not in allow]
    if unexpected:
        print("Interface methods that are never invoked in production:", file=sys.stderr)
        for iface, method, rel in unexpected:
            print(f"  {rel}: {iface}.{method}", file=sys.stderr)
        print(
            "  Wire the method up, delete it, or allowlist it in "
            "config/unused_interface_methods.json.",
            file=sys.stderr,
        )
        return 1
    print(
        f"Unused-interface scan OK ({len(found)} never-invoked methods, all allowlisted)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
