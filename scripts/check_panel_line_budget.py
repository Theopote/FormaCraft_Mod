"""Soft gate: client Panel classes over ~400 lines (ARCHITECTURE 2.2). Exit 0 always; prints debt."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL_DIR = ROOT / "src/main/java/com/formacraft/client/ui/panel"
BUDGET = 400


def main() -> int:
    over = []
    for path in sorted(PANEL_DIR.rglob("*Panel.java")):
        lines = path.read_text(encoding="utf-8-sig").count("\n") + 1
        if lines > BUDGET:
            over.append((lines, path.relative_to(ROOT).as_posix()))
    over.sort(reverse=True)
    print(f"Panels over {BUDGET} lines: {len(over)}")
    for lines, rel in over[:20]:
        print(f"  {lines:5d}  {rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
