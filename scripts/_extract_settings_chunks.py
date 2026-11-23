"""Extract Settings probe + widget assembler into settings/ with SettingsPanel field access via public ctl API."""
from __future__ import annotations

import re
from pathlib import Path

PANEL_PATH = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
OUT = Path("src/main/java/com/formacraft/client/ui/panel/settings")
lines = PANEL_PATH.read_text(encoding="utf-8").splitlines(keepends=True)


def sl(a: int, b: int) -> str:
    return "".join(lines[a - 1 : b])


# Re-find line numbers after compact
text = "".join(lines)
for name in [
    "private void initWidgets",
    "private void startValidateSearchKey",
    "private void startDetectModel",
    "private void startValidateKey",
    "public void syncWidgetStateFromDraft",
    "private static String sanitizeEndpoint",
    "private void openModels",
    "private void openProviders",
    "private void openBaseUrlPresets",
    "private void openSearchProviders",
    "private void openResetGroups",
    "private ConnectionIdentity",
    "private void syncConnectionIdentity",
    "private SearchIdentity",
    "private void syncSearchIdentity",
    "private static boolean readSearchValidateOk",
    "private record DetectResponse",
    "private record ConnectionIdentity",
    "private record SearchIdentity",
    "private record BaseUrlPreset",
]:
    idx = text.find(name)
    if idx < 0:
        print("MISSING", name)
        continue
    line = text[:idx].count("\n") + 1
    print(f"{line}: {name}")
