# -*- coding: utf-8 -*-
"""Post-fix HUD panel helpers after _panel_chunk extraction."""
from __future__ import annotations
import re
from pathlib import Path

BASE = Path("src/main/java/com/formacraft/client/ui/panel/BasePanel.java")
base = BASE.read_text(encoding="utf-8")

# Publicize BasePanel members helpers need
reps = [
    ("private double getScaledMouseX()", "public double getScaledMouseX()"),
    ("private double getScaledMouseY()", "public double getScaledMouseY()"),
    ("private void ensureToolbarWidgets()", "public void ensureToolbarWidgets()"),
    ("private BasePanel.ToolbarLayout computeToolbarLayout()", "public BasePanel.ToolbarLayout computeToolbarLayout()"),
    ("private ToolbarLayout computeToolbarLayout()", "public ToolbarLayout computeToolbarLayout()"),
    ("private void drawExpandedPanelBorder(", "public void drawExpandedPanelBorder("),
    ("private void drawCollapsedHandle(", "public void drawCollapsedHandle("),
    ("private void drawNewChatButton(", "public void drawNewChatButton("),
    ("private void drawCloseButton(", "public void drawCloseButton("),
    ("private void drawCollapseButton(", "public void drawCollapseButton("),
    ("private void drawTooltip(", "public void drawTooltip("),
    ("private void drawBackground(", "public void drawBackground("),
    ("protected void drawContentBackground(", "public void drawContentBackground("),
    ("protected int contentScissorLeft()", "public int contentScissorLeft()"),
    ("protected int contentScissorRight()", "public int contentScissorRight()"),
    ("protected final void drawTooltipCompat(", "public final void drawTooltipCompat("),
    ("protected boolean drawWidgetTooltip(", "public boolean drawWidgetTooltip("),
    ("protected boolean drawWidgetMessageTooltip(", "public boolean drawWidgetMessageTooltip("),
    ("protected boolean drawCustomTooltip(", "public boolean drawCustomTooltip("),
    ("protected void drawToolbar(", "public void drawToolbar("),
    ("protected boolean visible", "public boolean visible"),
]
for a, b in reps:
    base = base.replace(a, b)

# Ensure public API bridges used by older rewrites
if "drawTooltipCompatPublic" not in base:
    insert = """
    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }
    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }
"""
    base = base.replace(
        "public class BasePanel {",
        "public class BasePanel {" + insert,
        1,
    )
    if "public abstract class BasePanel" in base and "drawTooltipCompatPublic" not in base:
        base = base.replace(
            "public abstract class BasePanel {",
            "public abstract class BasePanel {" + insert,
            1,
        )

BASE.write_text(base, encoding="utf-8")

HOST_METHODS = sorted(
    {
        "ensureToolbarWidgets",
        "computeToolbarLayout",
        "drawBackground",
        "drawExpandedPanelBorder",
        "drawCollapsedHandle",
        "drawToolbar",
        "drawNewChatButton",
        "drawCloseButton",
        "drawCollapseButton",
        "drawContents",
        "drawTooltip",
        "drawTooltipCompat",
        "drawTooltipCompatPublic",
        "drawContentBackground",
        "drawContentBackgroundPublic",
        "drawCustomTooltip",
        "drawWidgetTooltip",
        "drawWidgetMessageTooltip",
        "getScaledMouseX",
        "getScaledMouseY",
        "contentScissorLeft",
        "contentScissorRight",
        "isMouseOver",
        "blurKeyboardFocus",
        "getContentY",
        "getContentHeight",
        "getPanelX",
        "getPanelY",
        "getPanelWidth",
        "getPanelHeight",
        "ensureWidgets",
        "ensureLayout",
        "clickButton",
        "superMouseClicked",
        "superMouseReleased",
        "applyPlaneHeight",
        "rangeToValue",
        "valueToRange",
        "drawWrappedText",
        "formatAge",
        "buildComponentTooltip",
        "selectedEntry",
        "deleteSelectedComponent",
        "cycleSort",
        "cycleCategory",
        "requestSource",
        "stepSource",
        "renderSourceButton",
        "getScaledMouseXForSources",
        "drawLibraryFixedHeader",
        "drawLibraryScrollable",
        "drawRecipeEditor",
        "drawImportStatus",
        "drawSourceDetails",
        "typeSearchMatches",
        "matchScore",
        "displayName",
        "sortLabel",
        "getEntryComparator",
        "optionButton",
        "drawOptionButton",
        "drawWorkPlaneOptions",
        "drawSelectionToolOptions",
        "drawProtectedZoneToolOptions",
        "drawOutlineToolOptions",
        "drawPathToolOptions",
        "drawBrushToolOptions",
        "drawSymmetryToolOptions",
        "drawSemanticLabelToolOptions",
        "wantsKeyboardInput",
    },
    key=len,
    reverse=True,
)


def qualify_bare_calls(text: str) -> str:
    # Do not touch method definitions
    out = text
    for meth in HOST_METHODS:
        # bare meth( -> host.meth( when not already host./Class./static helper
        out = re.sub(
            rf"(?<![\w.]){re.escape(meth)}\(",
            f"host.{meth}(",
            out,
        )
    # undo definition headers accidentally rewritten
    out = re.sub(
        rf"(public static [^{{]+ )host\.(\w+)\(BasePanel host",
        r"\1\2(BasePanel host",
        out,
    )
    out = re.sub(
        rf"(public static [^{{]+ )host\.(\w+)\(ToolPanel host",
        r"\1\2(ToolPanel host",
        out,
    )
    out = re.sub(
        rf"(public static [^{{]+ )host\.(\w+)\(ComponentLibraryPanel host",
        r"\1\2(ComponentLibraryPanel host",
        out,
    )
    out = out.replace("host.host.", "host.")
    # sibling helpers in same class: for BasePanelChrome methods called from BasePanelChrome
    # leave host.ensureToolbarWidgets which hits the host delegate — OK
    return out


def ensure_imports(path: Path, text: str) -> str:
    extras = []
    checks = [
        ("FormaCraftHudOverlay", "import com.formacraft.client.ui.FormaCraftHudOverlay;"),
        ("FormacraftUIState", "import com.formacraft.client.ui.FormacraftUIState;"),
        ("TabBar", "import com.formacraft.client.ui.widget.TabBar;"),
        ("PanelType", "import com.formacraft.client.ui.panel.PanelType;"),
        ("ToolManager", "import com.formacraft.client.tool.ToolManager;"),
        ("ClientComponentCatalogState", "import com.formacraft.client.component.ClientComponentCatalogState;"),
        ("ComponentLibraryUsage", "import com.formacraft.client.component.ComponentLibraryUsage;"),
        ("FormaCraftClientNetworking", "import com.formacraft.client.network.FormaCraftClientNetworking;"),
        ("HudToast", "import com.formacraft.client.ui.toast.HudToast;"),
        ("AnchorState", "import com.formacraft.client.interaction.AnchorState;"),
        ("FormacraftTool", "import com.formacraft.client.tool.FormacraftTool;"),
        ("SliderWidget", "import net.minecraft.client.gui.widget.SliderWidget;"),
        ("ClickableWidget", "import net.minecraft.client.gui.widget.ClickableWidget;"),
        ("WorkPlaneTool", "import com.formacraft.client.tool.WorkPlaneTool;"),
        ("SelectionTool", "import com.formacraft.client.tool.SelectionTool;"),
        ("ProtectedZoneTool", "import com.formacraft.client.tool.ProtectedZoneTool;"),
        ("OutlineTool", "import com.formacraft.client.tool.OutlineTool;"),
        ("PathTool", "import com.formacraft.client.tool.PathTool;"),
        ("BrushTool", "import com.formacraft.client.tool.BrushTool;"),
        ("SymmetryTool", "import com.formacraft.client.tool.SymmetryTool;"),
        ("SemanticLabelTool", "import com.formacraft.client.tool.SemanticLabelTool;"),
        ("CommunityLibraryPane", "import com.formacraft.client.ui.panel.CommunityLibraryPane;"),
    ]
    for needle, imp in checks:
        if needle in text and imp not in text:
            extras.append(imp)
    if extras:
        text = text.replace(
            "package ",
            "package ",
            1,
        )
        # insert after package line
        lines = text.splitlines(True)
        out = []
        inserted = False
        for i, line in enumerate(lines):
            out.append(line)
            if not inserted and line.startswith("package "):
                out.append("\n")
                for imp in extras:
                    out.append(imp + "\n")
                inserted = True
        text = "".join(out)
    # drop bad LabelRangeSlider top-level import
    text = re.sub(r"import com\.formacraft\.client\.ui\.panel\.LabelRangeSlider;\s*", "", text)
    text = text.replace("new host.ToolbarLayout(", "new BasePanel.ToolbarLayout(")
    return text


paths = []
paths += list(Path("src/main/java/com/formacraft/client/ui/panel").glob("BasePanel*.java"))
paths += list(Path("src/main/java/com/formacraft/client/ui/panel/tool").glob("*.java"))
paths += list(Path("src/main/java/com/formacraft/client/ui/panel/library").glob("*.java"))

for p in paths:
    if p.name == "BasePanel.java":
        continue
    t = p.read_text(encoding="utf-8")
    t = qualify_bare_calls(t)
    t = ensure_imports(p, t)
    # static BasePanel fields
    for f in (
        "sidebarCollapsed",
        "sidebarAnimWidth",
        "contentAlpha",
        "SIDEBAR_ANIM_SPEED",
        "SIDEBAR_EXPANDED_WIDTH",
        "SIDEBAR_COLLAPSED_WIDTH",
    ):
        t = re.sub(rf"host\.{f}\b", f"BasePanel.{f}", t)
    # nested types
    t = re.sub(r"(?<![\w.])LabelRangeSlider(?!\w)", "ToolPanel.LabelRangeSlider", t)
    t = t.replace("ToolPanel.ToolPanel.", "ToolPanel.")
    t = re.sub(r"(?<![\w.])GridItem(?!\w)", "ComponentLibraryPanel.GridItem", t)
    t = t.replace("ComponentLibraryPanel.ComponentLibraryPanel.", "ComponentLibraryPanel.")
    # drawContentBackgroundPublic preferred when rewritten that way
    t = t.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
    t = t.replace("host.drawTooltipCompat(", "host.drawTooltipCompatPublic(")
    # but BasePanel itself has drawTooltipCompatPublic now; ComponentLibraryPanel inherits
    p.write_text(t, encoding="utf-8")
    print("fixed", p)

# Re-publicize Base after helper edits didn't touch it beyond earlier
base = BASE.read_text(encoding="utf-8")
if "drawTooltipCompatPublic" not in base:
    # find class opening
    m = re.search(r"public abstract class BasePanel\s*\{", base)
    if m:
        base = base[: m.end()] + (
            "\n    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n"
            "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<? extends net.minecraft.text.Text> lines, int mx, int my) {\n"
            "        drawTooltipCompat(ctx, (java.util.List<net.minecraft.text.Text>) (java.util.List<?>) lines, mx, my);\n"
            "    }\n"
        ) + base[m.end() :]
        BASE.write_text(base, encoding="utf-8")
        print("added bridges")
else:
    # widen signature if needed
    base2 = base.replace(
        "public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my)",
        "public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<? extends net.minecraft.text.Text> lines, int mx, int my)",
    )
    if "List<? extends" in base2 and "drawTooltipCompat(ctx, lines" in base2:
        base2 = re.sub(
            r"public void drawTooltipCompatPublic\(net\.minecraft\.client\.gui\.DrawContext ctx, java\.util\.List<\? extends net\.minecraft\.text\.Text> lines, int mx, int my\) \{ drawTooltipCompat\(ctx, lines, mx, my\); \}",
            "public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<? extends net.minecraft.text.Text> lines, int mx, int my) {\n"
            "        @SuppressWarnings(\"unchecked\") java.util.List<net.minecraft.text.Text> cast = (java.util.List<net.minecraft.text.Text>) (java.util.List<?>) lines;\n"
            "        drawTooltipCompat(ctx, cast, mx, my);\n"
            "    }",
            base2,
        )
    if base2 != base:
        BASE.write_text(base2, encoding="utf-8")
        print("widened bridge")

print("done")
