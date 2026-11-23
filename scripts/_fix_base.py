# -*- coding: utf-8 -*-
from pathlib import Path
import re

panel = Path("src/main/java/com/formacraft/client/ui/panel/BasePanel.java")
pt = panel.read_text(encoding="utf-8")
# publicize private methods still needed
pt = re.sub(
    r"(?m)^(\s*)private ((?:static\s+)?(?:@\w+(?:\([^)]*\))?\s+)*[\w.<>,?\[\]]+\s+\w+\s*\()",
    r"\1public \2",
    pt,
)
# publicize remaining private fields
pt = re.sub(
    r"(?m)^(\s*)private ((?!class |interface |enum |record )(?:static\s+)?(?:final\s+)?[\w.<>,?\[\]]+\s+\w+\s*[=;])",
    r"\1public \2",
    pt,
)
# nested types public
pt = pt.replace("private record ToolbarLayout", "public record ToolbarLayout")
pt = pt.replace("private static final class", "public static final class")
if "client() { return client; }" not in pt:
    pt = re.sub(
        r"(public abstract class BasePanel[^{]*\{)",
        r"\1\n    public net.minecraft.client.MinecraftClient client() { return client; }\n",
        pt,
        count=1,
    )
panel.write_text(pt, encoding="utf-8")

fields = [
    "closeButton", "collapseButton", "newChatButton", "sidebarCollapsed",
    "panelX", "panelY", "panelWidth", "panelHeight", "tabBar", "client",
]
methods = [
    "getScaledMouseX", "getScaledMouseY", "isMouseOver", "ensureToolbarWidgets",
    "computeToolbarLayout", "drawTooltip", "drawToolbar", "drawNewChatButton",
    "drawCollapseButton", "drawCloseButton", "drawCollapsedHandle",
    "drawExpandedPanelBorder", "ensureLayout",
]

imports = """
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.PanelType;
import com.formacraft.client.ui.tab.TabBar;
"""

for name in ["BasePanelChrome", "BasePanelInteraction", "BasePanelRender"]:
    hp = Path(f"src/main/java/com/formacraft/client/ui/panel/{name}.java")
    if not hp.exists():
        continue
    t = hp.read_text(encoding="utf-8")
    if "FormaCraftHudOverlay" not in t:
        t = t.replace(
            "package com.formacraft.client.ui.panel;",
            "package com.formacraft.client.ui.panel;\n" + imports,
        )
    for f in sorted(fields, key=len, reverse=True):
        t = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", t)
    for m in sorted(methods, key=len, reverse=True):
        t = re.sub(rf"(?<![\w.]){m}\(", f"host.{m}(", t)
    t = t.replace("host.host.", "host.")
    t = re.sub(r"\(host,\s*\)", "(host)", t)
    t = t.replace("new host.ToolbarLayout", "new BasePanel.ToolbarLayout")
    t = t.replace("host.ToolbarLayout", "BasePanel.ToolbarLayout")
    t = re.sub(r"(public static [^{\n]+ )host\.(\w+\()", r"\1\2", t)
    # TabBar package may differ
    if "TabBar" in t and "import com.formacraft.client.ui.tab.TabBar" not in t and "import com.formacraft.client.ui.TabBar" not in t:
        # find actual TabBar
        pass
    t = t.replace("host.client.", "host.client().")
    t = t.replace("host.client().()", "host.client()")
    # constants on BasePanel
    for c in [
        "CLOSE_BUTTON_SIZE", "CLOSE_BUTTON_PADDING", "COLLAPSE_BUTTON_SIZE",
        "COLLAPSE_BUTTON_PADDING", "SIDEBAR_EXPANDED_WIDTH", "SIDEBAR_COLLAPSED_WIDTH",
    ]:
        t = t.replace(f"host.{c}", f"BasePanel.{c}")
        t = re.sub(rf"(?<![\w.]){c}(?!\w)", f"BasePanel.{c}", t)
    hp.write_text(t, encoding="utf-8")
    print("fixed", name)

# Locate TabBar package
for p in Path("src").rglob("TabBar.java"):
    print("TabBar at", p)
