"""Fail if any client Panel source exceeds the 400-line budget."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL_DIR = ROOT / "src/main/java/com/formacraft/client/ui/panel"
BUDGET = 400


def main() -> int:
    if not PANEL_DIR.is_dir():
        print(f"Panel source directory not found: {PANEL_DIR}")
        return 1

    over = []
    for path in sorted(PANEL_DIR.rglob("*Panel.java")):
        lines = len(path.read_text(encoding="utf-8-sig").splitlines())
        if lines > BUDGET:
            over.append((lines, path.relative_to(ROOT).as_posix()))
    over.sort(reverse=True)
    print(f"Panels over {BUDGET} lines: {len(over)}")
    for lines, rel in over[:20]:
        print(f"  {lines:5d}  {rel}")
    return 1 if over else 0


if __name__ == "__main__":
    raise SystemExit(main())
