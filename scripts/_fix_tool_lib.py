# -*- coding: utf-8 -*-
"""Qualify bare host field/method refs in Tool and Library helpers."""
from __future__ import annotations
import re
from pathlib import Path

TOOL_FIELDS = [
    "noneToolButton", "toolButtons", "toolBaseLabels", "lastToolCount",
    "clearAnchorButton", "clearSelectionButton", "clearProtectedZonesButton",
    "outlineModeButton", "clearOutlineButton", "clearPathsButton",
    "pathDrawingModeButton", "pathRadiusMinusButton", "pathRadiusPlusButton",
    "pathSelectButton", "pathRemoveButton", "pathIntentButton",
    "brushUndoButton", "brushRedoButton", "brushModeButton",
    "brushRadiusMinusButton", "brushRadiusPlusButton", "clearBrushSelectionButton",
    "symmetryModeButton", "clearSymmetryButton", "clearLabelsButton",
    "labelNameInput", "planeHeightInput",
    "planeLockButton", "planeModeButton", "planeDownButton", "planeUpButton",
    "planeApplyButton", "planeClearButton",
    "planeInputX", "planeInputY", "planeInputW", "planeInputVisible",
    "labelRangeSlider", "activeSlider",
    "labelNameInputX", "labelNameInputY", "labelNameInputW", "labelNameInputH",
    "labelNameInputBoundsValid", "scrollY", "maxScrollY", "separatorY",
]
TOOL_METHODS = [
    "ensureWidgets", "drawContentBackgroundPublic", "drawWrappedText",
    "drawBrushToolOptions", "drawSemanticLabelToolOptions", "drawPathToolOptions",
    "drawOutlineToolOptions", "drawWorkPlaneOptions", "drawSymmetryToolOptions",
    "drawProtectedZoneToolOptions", "drawSelectionToolOptions", "applyPlaneHeight",
    "isMouseOver", "clickButton", "superMouseClicked", "drawTooltipCompatPublic",
]
TOOL_CONSTS = [
    "CONTENT_PADDING", "BUTTON_HEIGHT", "LABEL_OFFSET", "FIELD_SPACING",
    "LABEL_RANGE_MIN", "LABEL_RANGE_MAX",
]

LIB_FIELDS = [
    "confirmImportButton", "restoreRecipeButton", "cancelExportButton",
    "replayRecipeButton", "cancelImportButton", "searchBoundsValid",
    "recipeSizeButtons", "nextSourceButton", "saveRecipeButton",
    "recipeViewButton", "prevSourceButton", "communityToggle",
    "lastImportPhase", "nextPageButton", "categoryButton", "validateButton",
    "prevPageButton", "refreshButton", "communityMode", "communityPane",
    "scrollAreaTop", "autoFixButton", "exportButton", "deleteButton",
    "importButton", "sourceButton", "lastClickId", "searchInput",
    "lastClickMs", "recipeDraft", "sortButton", "maxScrollY", "gridValid",
    "searchW", "scrollY", "searchH", "searchX", "searchY", "grid",
]
LIB_METHODS = [
    "ensureWidgets", "isMouseOver", "clickButton", "blurKeyboardFocus",
    "cycleCategory", "buildComponentTooltip", "formatAge", "drawTooltipCompatPublic",
    "selectedEntry", "requestLoadSelectedComponent", "drawContentBackgroundPublic",
    "drawLibraryFixedHeader", "drawLibraryScrollable", "getContentY",
    "contentScissorLeft", "contentScissorRight", "drawWrappedText",
    "renderSourceButton", "sortLabel", "cycleSort", "requestSource", "stepSource",
    "deleteSelectedComponent",
]
LIB_CONSTS = ["CONTENT_PADDING", "BUTTON_HEIGHT", "LABEL_OFFSET", "DOUBLE_CLICK_MS"]


def qualify(text: str, fields, methods, consts, host_type: str) -> str:
    out = text
    for f in sorted(fields, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", out)
    for m in sorted(methods, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){m}\(", f"host.{m}(", out)
    for c in consts:
        out = out.replace(f"host.{c}", f"{host_type}.{c}")
        out = re.sub(rf"(?<![\w.]){c}(?!\w)", f"{host_type}.{c}", out)
    out = out.replace("host.host.", "host.")
    out = re.sub(r"\(host,\s*\)", "(host)", out)
    out = out.replace("host.client.", "host.client().")
    out = out.replace("host.client().()", "host.client()")
    out = out.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
    out = re.sub(r"(public static [^{\n]+ )host\.(\w+\()", r"\1\2", out)
    out = out.replace("var host.entry", "var entry")
    return out


# Tool helpers
tool_dir = Path("src/main/java/com/formacraft/client/ui/panel/tool")
for hp in tool_dir.glob("*.java"):
    t = hp.read_text(encoding="utf-8")
    t = qualify(t, TOOL_FIELDS, TOOL_METHODS, TOOL_CONSTS, "ToolPanel")
    # common tool imports
    for imp in [
        "import com.formacraft.client.tool.OutlineTool;",
        "import com.formacraft.client.tool.PathTool;",
        "import com.formacraft.client.tool.BrushTool;",
        "import com.formacraft.client.tool.ProtectedZoneTool;",
        "import com.formacraft.client.tool.SemanticLabelTool;",
        "import com.formacraft.client.tool.SelectionTool;",
        "import com.formacraft.client.tool.SymmetryTool;",
        "import com.formacraft.client.tool.WorkPlaneTool;",
        "import com.formacraft.client.tool.FormacraftTool;",
        "import com.formacraft.client.interaction.AnchorState;",
        "import net.minecraft.client.gui.widget.SliderWidget;",
        "import com.formacraft.client.ui.toast.HudToast;",
    ]:
        if imp not in t and any(x in t for x in imp.split(".")[-1].replace(";", "").split()):
            # only add if symbol likely used — simpler: always add missing common ones if referenced
            pass
    # Always ensure key imports if symbols present
    needed = {
        "OutlineTool": "import com.formacraft.client.tool.OutlineTool;",
        "PathTool": "import com.formacraft.client.tool.PathTool;",
        "BrushTool": "import com.formacraft.client.tool.BrushTool;",
        "ProtectedZoneTool": "import com.formacraft.client.tool.ProtectedZoneTool;",
        "SemanticLabelTool": "import com.formacraft.client.tool.SemanticLabelTool;",
        "SelectionTool": "import com.formacraft.client.tool.SelectionTool;",
        "SymmetryTool": "import com.formacraft.client.tool.SymmetryTool;",
        "WorkPlaneTool": "import com.formacraft.client.tool.WorkPlaneTool;",
        "FormacraftTool": "import com.formacraft.client.tool.FormacraftTool;",
        "AnchorState": "import com.formacraft.client.interaction.AnchorState;",
        "SliderWidget": "import net.minecraft.client.gui.widget.SliderWidget;",
        "HudToast": "import com.formacraft.client.ui.toast.HudToast;",
        "LabelRangeSlider": "import com.formacraft.client.ui.panel.LabelRangeSlider;",
    }
    for sym, imp in needed.items():
        if sym in t and imp not in t:
            t = t.replace("package com.formacraft.client.ui.panel.tool;", "package com.formacraft.client.ui.panel.tool;\n\n" + imp)
    hp.write_text(t, encoding="utf-8")
    print("tool", hp.name)

# Library helpers
lib_dir = Path("src/main/java/com/formacraft/client/ui/panel/library")
for hp in lib_dir.glob("*.java"):
    t = hp.read_text(encoding="utf-8")
    t = qualify(t, LIB_FIELDS, LIB_METHODS, LIB_CONSTS, "ComponentLibraryPanel")
    t = t.replace("host.getEntryComparator(", "ComponentLibraryPanel.getEntryComparator(")
    t = t.replace("host.typeSearchMatches(", "ComponentLibraryPanel.typeSearchMatches(")
    t = t.replace("host.displayName(", "ComponentLibraryPanel.displayName(")
    t = t.replace("host.clickButton(", "ComponentLibraryPanel.clickButton(")
    t = t.replace("host.sortLabel(", "ComponentLibraryPanel.sortLabel(")
    t = re.sub(r"(?<![\w.])GridItem(?!\w)", "ComponentLibraryPanel.GridItem", t)
    t = t.replace("ComponentLibraryPanel.ComponentLibraryPanel.", "ComponentLibraryPanel.")
    for imp in [
        "import com.formacraft.client.component.ClientComponentCatalogState;",
        "import com.formacraft.client.component.ComponentThumbnailCache;",
        "import com.formacraft.client.component.ComponentLibraryUsage;",
        "import com.formacraft.common.component.ComponentCategory;",
        "import com.formacraft.client.network.FormaCraftClientNetworking;",
        "import com.formacraft.client.ui.render.ImageRenderer;",
        "import java.util.Locale;",
        "import java.util.Comparator;",
        "import java.util.ArrayList;",
    ]:
        if imp.split()[-1].replace(";", "") in t or imp.split(".")[-1].replace(";", "") in t:
            if imp not in t:
                t = t.replace("package com.formacraft.client.ui.panel.library;", "package com.formacraft.client.ui.panel.library;\n\n" + imp)
    # Force critical imports if symbols present
    for sym, imp in {
        "ClientComponentCatalogState": "import com.formacraft.client.component.ClientComponentCatalogState;",
        "ComponentThumbnailCache": "import com.formacraft.client.component.ComponentThumbnailCache;",
        "ComponentLibraryUsage": "import com.formacraft.client.component.ComponentLibraryUsage;",
        "ComponentCategory": "import com.formacraft.common.component.ComponentCategory;",
        "FormaCraftClientNetworking": "import com.formacraft.client.network.FormaCraftClientNetworking;",
        "ImageRenderer": "import com.formacraft.client.ui.render.ImageRenderer;",
        "Locale": "import java.util.Locale;",
    }.items():
        if sym in t and imp not in t:
            t = t.replace("package com.formacraft.client.ui.panel.library;", "package com.formacraft.client.ui.panel.library;\n\n" + imp)
    hp.write_text(t, encoding="utf-8")
    print("lib", hp.name)

print("done")
