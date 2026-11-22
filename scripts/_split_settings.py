# Mechanical SettingsPanel split: ProbeController + WidgetAssembler + Dialogs helper.
# Leaves SettingsPanel as thin orchestrator (<400 lines).
from __future__ import annotations

from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
SETTINGS = Path("src/main/java/com/formacraft/client/ui/panel/settings")
text = PANEL.read_text(encoding="utf-8")
lines = text.splitlines(keepends=True)


def slice_lines(a: int, b: int) -> str:
    return "".join(lines[a - 1 : b])


# We'll take a different approach: write SettingsPanelCore in settings that holds
# almost all logic as a package-private class referencing an expanded host.
# Actually simplest for line budget: rename current SettingsPanel content into
# SettingsPanelController.java in settings package as `final class SettingsPanelController`
# that takes SettingsPanel shell — too coupled.

# PRACTICAL: move probe+widget+dialog method BODIES into static helpers that take
# SettingsPanel — make SettingsPanel fields package-visible by moving helpers into
# the SAME package `com.formacraft.client.ui.panel` then re-export from settings?
# Plan wants settings/ — use public nested state object.

print("lines", len(lines))
# Identify how many lines are host one-liners (1054-end)
print("host section", len(lines) - 1053)
