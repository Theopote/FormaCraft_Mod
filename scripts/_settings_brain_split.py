"""
Move SettingsPanel implementation into settings/SettingsPanelBrain.java,
leave a thin SettingsPanel facade under 400 lines that delegates everything.
Zero behavior change.
"""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
BRAIN = Path("src/main/java/com/formacraft/client/ui/panel/settings/SettingsPanelBrain.java")
PROBE = Path("src/main/java/com/formacraft/client/ui/panel/settings/SettingsProbeController.java")
WIDGET = Path("src/main/java/com/formacraft/client/ui/panel/settings/SettingsWidgetAssembler.java")

src = PANEL.read_text(encoding="utf-8")

# Brain holds all former SettingsPanel state+logic but is NOT a BasePanel.
# Instead SettingsPanel keeps being BasePanel and brain methods take (SettingsPanel self).
# Simpler approach: brain IS the logic with a back-ref to SettingsPanel for geometry.

# Convert class body: extract fields+methods into SettingsPanelBrain,
# SettingsPanel keeps extends BasePanel implements host and holds brain.

# Find class opening
m = re.search(r"public class SettingsPanel extends BasePanel implements SettingsPanelRenderHost \{", src)
if not m:
    raise SystemExit("class decl not found")

header = src[: m.end()]
body = src[m.end() : src.rfind("}")]  # inside class without final brace

# Brain class: package settings, holds SettingsPanel panel ref for BasePanel hooks
brain = '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import com.formacraft.config.SettingsConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/**
 * Move-only extraction of SettingsPanel logic (zero behavior change).
 * Panel geometry/BasePanel hooks remain on {@link SettingsPanel}.
 */
public final class SettingsPanelBrain implements SettingsPanelRenderHost {
    private final SettingsPanel panel;
'''

# Rewrite body: replace panelX with panel.panelX etc for protected BasePanel fields
# BasePanel fields used: panelX, panelY, panelWidth, panelHeight, visible, client (own), getContentY, drawContentBackground, drawTooltipCompat, super.mouseClicked, super.isInteractiveArea, super.isMouseOver

replacements = [
    (r"\bpanelX\b", "panel.getPanelX()"),
    (r"\bpanelY\b", "panel.getPanelY()"),
    (r"\bpanelWidth\b", "panel.getPanelWidth()"),
    (r"\bpanelHeight\b", "panel.getPanelHeight()"),
    (r"\bgetContentY\(\)", "panel.getContentY()"),
    (r"\bdrawContentBackground\(", "panel.drawContentBackgroundPublic("),
    (r"\bdrawTooltipCompat\(", "panel.drawTooltipCompatPublic("),
    (r"\bsuper\.mouseClicked\(", "panel.superMouseClicked("),
    (r"\bsuper\.isInteractiveArea\(", "panel.superIsInteractiveArea("),
    (r"\bsuper\.isMouseOver\(", "panel.superIsMouseOver("),
    (r"\bvisible\b", "panel.isVisiblePublic()"),
]

# Actually SettingsPanel has `private final MinecraftClient client` - brain can keep its own.
# visible is protected on BasePanel - need accessor.

# This approach is getting messy with protected access.
print("body lines", body.count("\n"))
print("Abort brain approach complexity - use package-private same-package extract instead")
