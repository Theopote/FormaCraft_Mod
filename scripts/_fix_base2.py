# -*- coding: utf-8 -*-
from pathlib import Path
import re

bp = Path("src/main/java/com/formacraft/client/ui/panel/BasePanel.java")
t = bp.read_text(encoding="utf-8")

# drawCustomTooltip must stay protected
t = re.sub(
    r"(?m)^(\s*)public boolean drawCustomTooltip\(",
    r"\1protected boolean drawCustomTooltip(",
    t,
)

# Normalize / ensure a single compatible tooltip bridge
t = re.sub(
    r"(?s)\s*public void drawTooltipCompatPublic\([^;]+;\s*",
    "\n",
    t,
)
# Insert after client() if present, else after class open
bridge = (
    "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, "
    "java.util.List<net.minecraft.text.Text> lines, int mx, int my) {\n"
    "        drawTooltipCompat(ctx, lines, mx, my);\n"
    "    }\n"
)
if "drawTooltipCompatPublic" not in t:
    if "client() { return client; }" in t:
        t = t.replace(
            "public net.minecraft.client.MinecraftClient client() { return client; }",
            "public net.minecraft.client.MinecraftClient client() { return client; }\n" + bridge,
        )
    else:
        t = re.sub(
            r"(public abstract class BasePanel[^{]*\{)",
            r"\1\n" + bridge,
            t,
            count=1,
        )

bp.write_text(t, encoding="utf-8")

# Remove duplicate bridges from subclasses (use BasePanel's)
for panel in [
    "ChatPanel.java",
    "ToolPanel.java",
    "ComponentLibraryPanel.java",
    "ComponentCapturePanel.java",
    "SettingsPanel.java",
]:
    p = Path("src/main/java/com/formacraft/client/ui/panel") / panel
    if not p.exists():
        continue
    s = p.read_text(encoding="utf-8")
    s2 = re.sub(
        r"(?m)^\s*public void drawTooltipCompatPublic\([^\{]+\{[^}]*\}\s*\n?",
        "",
        s,
    )
    if s2 != s:
        p.write_text(s2, encoding="utf-8")
        print("removed bridge", panel)

# Fix Chrome/Interaction/Render static calls
for name in ["BasePanelChrome", "BasePanelInteraction", "BasePanelRender"]:
    p = Path(f"src/main/java/com/formacraft/client/ui/panel/{name}.java")
    s = p.read_text(encoding="utf-8")
    # Explicit replacements for remaining bad calls
    for m in [
        "ensureToolbarWidgets",
        "computeToolbarLayout",
        "drawBackground",
        "drawExpandedPanelBorder",
        "drawCollapsedHandle",
        "drawToolbar",
        "drawNewChatButton",
        "drawCloseButton",
        "drawCollapseButton",
    ]:
        s = s.replace(f"host.{m}(host,", f"{m}(host,")
        s = s.replace(f"host.{m}(host)", f"{m}(host)")
    # Cross-helper calls from Render -> Chrome
    if name == "BasePanelRender":
        for m in [
            "ensureToolbarWidgets",
            "computeToolbarLayout",
            "drawBackground",
            "drawExpandedPanelBorder",
            "drawCollapsedHandle",
            "drawToolbar",
            "drawNewChatButton",
            "drawCloseButton",
            "drawCollapseButton",
        ]:
            # if still host.m( without host arg, leave; if host.m(host already fixed
            s = re.sub(rf"(?<![\w.]){m}\(host,", f"BasePanelChrome.{m}(host,", s)
            # but methods defined in Render itself
        for m in ["render", "drawTooltip"]:
            s = s.replace(f"BasePanelChrome.{m}(", f"{m}(")
    if name == "BasePanelChrome":
        for m in [
            "ensureToolbarWidgets",
            "computeToolbarLayout",
            "drawBackground",
            "drawExpandedPanelBorder",
            "drawCollapsedHandle",
            "drawToolbar",
            "drawNewChatButton",
            "drawCloseButton",
            "drawCollapseButton",
        ]:
            s = s.replace(f"BasePanelChrome.{m}(", f"{m}(")
    if "import com.formacraft.client.ui.widget.TabBar;" not in s and "TabBar" in s:
        s = s.replace(
            "package com.formacraft.client.ui.panel;",
            "package com.formacraft.client.ui.panel;\n\nimport com.formacraft.client.ui.widget.TabBar;",
        )
    if "FormaCraftHudOverlay" in s and "import com.formacraft.client.ui.FormaCraftHudOverlay;" not in s:
        s = s.replace(
            "package com.formacraft.client.ui.panel;",
            "package com.formacraft.client.ui.panel;\n\nimport com.formacraft.client.ui.FormaCraftHudOverlay;\nimport com.formacraft.client.ui.FormacraftUIState;",
        )
    p.write_text(s, encoding="utf-8")
    print("fixed", name)

# Verify Chrome lines
chrome = Path("src/main/java/com/formacraft/client/ui/panel/BasePanelChrome.java").read_text(encoding="utf-8")
print("bad compute?", "host.computeToolbarLayout(host)" in chrome)
print("bad ensure?", "host.ensureToolbarWidgets(host)" in chrome)
print("bad drawNew?", "host.drawNewChatButton(host" in chrome)
