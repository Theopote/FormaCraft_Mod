# -*- coding: utf-8 -*-
from pathlib import Path

hosts = ["ComponentLibraryPanel", "ToolPanel", "BasePanel"]
for n in hosts:
    p = Path(f"src/main/java/com/formacraft/client/ui/panel/{n}.java")
    print(f"{n}: {p.read_text(encoding='utf-8').count(chr(10)) + 1}")

print("--- helpers ---")
roots = [
    Path("src/main/java/com/formacraft/client/ui/panel"),
    Path("src/main/java/com/formacraft/client/ui/panel/library"),
    Path("src/main/java/com/formacraft/client/ui/panel/tool"),
]
for root in roots:
    for p in sorted(root.glob("*.java")):
        keep = False
        if root.name in ("library", "tool"):
            keep = True
        if p.name in ("BasePanelUi.java", "BuildConfirmUi.java"):
            keep = True
        if keep:
            print(f"{p.as_posix()}: {p.read_text(encoding='utf-8').count(chr(10)) + 1}")
