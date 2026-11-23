# -*- coding: utf-8 -*-
"""Extract BuildConfirmPanel render/interaction into same-package helpers."""
from pathlib import Path
import re

panel_path = Path("src/main/java/com/formacraft/client/ui/panel/BuildConfirmPanel.java")
src = panel_path.read_text(encoding="utf-8")

# Make fields package-visible (drop private) for helpers in same package
src2 = src
# only field declarations at class level - careful
src2 = re.sub(
    r"^(\s*)private (static final BuildConfirmPanel INSTANCE)",
    r"\1\2",
    src2,
    flags=re.M,
)
# Keep INSTANCE private; publicize instance fields used by helpers
for field in [
    "MinecraftClient client",
    "boolean visible",
    "Mode mode",
    "BuildingSpec pendingSpec",
    "ButtonWidget confirmButton",
    "ButtonWidget cancelButton",
    "ButtonWidget applyPatchButton",
    "ButtonWidget undoPatchButton",
    "ButtonWidget redoPatchButton",
    "boolean draggingPatch",
    "double patchDragX",
    "double patchDragY",
    "boolean previewQualityError",
    "boolean previewForceArmed",
]:
    src2 = re.sub(rf"^(\s*)private {re.escape(field)}", rf"\1{field}", src2, flags=re.M)

# Also Mode enum and other privates - read actual fields
fields = re.findall(r"^\s*private (?!static final BuildConfirmPanel|class |enum |record |void |boolean mouse|static void)(.+;)\s*$", src, re.M)
print("private fields:", fields[:30])
