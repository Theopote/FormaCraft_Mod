# -*- coding: utf-8 -*-
from __future__ import annotations
from pathlib import Path
import re

# ToolPanel
p = Path("src/main/java/com/formacraft/client/ui/panel/ToolPanel.java")
t = p.read_text(encoding="utf-8")
t = t.replace(
    "public boolean clickButton(ButtonWidget button, Click click)",
    "public static boolean clickButton(ButtonWidget button, Click click)",
)
if "LABEL_RANGE_TOOLTIP" not in t:
    t = t.replace(
        "public static final int LABEL_RANGE_MAX = 40;",
        "public static final int LABEL_RANGE_MAX = 40;\n"
        "    public static final Text LABEL_RANGE_TOOLTIP = Text.literal("
        '"设置标签作用范围（方块），用于提示 AI 该标签影响的周边区域");',
    )
p.write_text(t, encoding="utf-8")
print("ToolPanel", t.count("\n") + 1)

# ToolWidgetBuilder
p = Path("src/main/java/com/formacraft/client/ui/panel/tool/ToolWidgetBuilder.java")
t = p.read_text(encoding="utf-8").replace("this::applyPlaneHeight", "host::applyPlaneHeight")
p.write_text(t, encoding="utf-8")

# ToolInteraction
p = Path("src/main/java/com/formacraft/client/ui/panel/tool/ToolInteraction.java")
t = p.read_text(encoding="utf-8")
t = t.replace("LABEL_RANGE_TOOLTIP", "ToolPanel.LABEL_RANGE_TOOLTIP")
t = t.replace("ToolPanel.ToolPanel.", "ToolPanel.")
p.write_text(t, encoding="utf-8")

# BasePanel publicize
p = Path("src/main/java/com/formacraft/client/ui/panel/BasePanel.java")
t = p.read_text(encoding="utf-8")
reps = [
    ("protected int contentScissorLeft()", "public int contentScissorLeft()"),
    ("protected int contentScissorRight()", "public int contentScissorRight()"),
    ("protected void drawContentBackground(", "public void drawContentBackground("),
    ("protected final void drawTooltipCompat(", "public final void drawTooltipCompat("),
    ("protected boolean drawWidgetTooltip(", "public boolean drawWidgetTooltip("),
    ("protected boolean drawWidgetMessageTooltip(", "public boolean drawWidgetMessageTooltip("),
    ("private record ToolbarLayout", "public record ToolbarLayout"),
    ("private void ensureToolbarWidgets()", "public void ensureToolbarWidgets()"),
    ("private ToolbarLayout computeToolbarLayout()", "public ToolbarLayout computeToolbarLayout()"),
    ("private double getScaledMouseX()", "public double getScaledMouseX()"),
    ("private double getScaledMouseY()", "public double getScaledMouseY()"),
    ("private void drawExpandedPanelBorder(", "public void drawExpandedPanelBorder("),
    ("private void drawCollapsedHandle(", "public void drawCollapsedHandle("),
    ("private void drawNewChatButton(", "public void drawNewChatButton("),
    ("private void drawCloseButton(", "public void drawCloseButton("),
    ("private void drawCollapseButton(", "public void drawCollapseButton("),
    ("private void drawTooltip(", "public void drawTooltip("),
    ("private void drawBackground(", "public void drawBackground("),
    ("protected void drawToolbar(", "public void drawToolbar("),
    ("protected boolean visible", "public boolean visible"),
    ("protected int panelX", "public int panelX"),
    ("protected int panelY", "public int panelY"),
    ("protected int panelWidth", "public int panelWidth"),
    ("protected int panelHeight", "public int panelHeight"),
    ("protected final MinecraftClient client", "public final MinecraftClient client"),
    ("protected final TabBar tabBar", "public final TabBar tabBar"),
    ("private ButtonWidget closeButton", "public ButtonWidget closeButton"),
    ("private ButtonWidget collapseButton", "public ButtonWidget collapseButton"),
    ("private ButtonWidget newChatButton", "public ButtonWidget newChatButton"),
    ("private static boolean sidebarCollapsed", "public static boolean sidebarCollapsed"),
    ("private static final int SIDEBAR_EXPANDED_WIDTH", "public static final int SIDEBAR_EXPANDED_WIDTH"),
    ("private static final int SIDEBAR_COLLAPSED_WIDTH", "public static final int SIDEBAR_COLLAPSED_WIDTH"),
    ("private static float sidebarAnimWidth", "public static float sidebarAnimWidth"),
    ("private static final float SIDEBAR_ANIM_SPEED", "public static final float SIDEBAR_ANIM_SPEED"),
    ("private static float contentAlpha", "public static float contentAlpha"),
    ("private static final int TOOLBAR_HEIGHT", "public static final int TOOLBAR_HEIGHT"),
    ("private static final int TOOLBAR_PADDING", "public static final int TOOLBAR_PADDING"),
    ("private static final int CLOSE_BUTTON_SIZE", "public static final int CLOSE_BUTTON_SIZE"),
    ("private static final int CLOSE_BUTTON_PADDING", "public static final int CLOSE_BUTTON_PADDING"),
    ("private static final int COLLAPSE_BUTTON_SIZE", "public static final int COLLAPSE_BUTTON_SIZE"),
    ("private static final int COLLAPSE_BUTTON_PADDING", "public static final int COLLAPSE_BUTTON_PADDING"),
    ("private static float clamp01", "public static float clamp01"),
    ("private static float smoothstep", "public static float smoothstep"),
]
for a, b in reps:
    t = t.replace(a, b)
if "drawTooltipCompatPublic" not in t:
    t = t.replace(
        "public abstract class BasePanel {",
        "public abstract class BasePanel {\n"
        "    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n"
        "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<? extends net.minecraft.text.Text> lines, int mx, int my) {\n"
        "        @SuppressWarnings(\"unchecked\") java.util.List<net.minecraft.text.Text> cast = (java.util.List<net.minecraft.text.Text>) (java.util.List<?>) lines;\n"
        "        drawTooltipCompat(ctx, cast, mx, my);\n"
        "    }\n"
        "    public net.minecraft.client.MinecraftClient client() { return client; }\n",
        1,
    )
p.write_text(t, encoding="utf-8")
print("BasePanel", t.count("\n") + 1, "ToolbarLayout public", "public record ToolbarLayout" in t)
