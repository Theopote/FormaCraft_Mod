"""Extract draw/input/keyboard/load/save into SettingsPanelInteraction."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
OUT = Path("src/main/java/com/formacraft/client/ui/panel/settings/SettingsPanelInteraction.java")
lines = PANEL.read_text(encoding="utf-8").splitlines(keepends=True)


def find_line(substr: str) -> int:
    for i, l in enumerate(lines):
        if substr in l:
            return i + 1
    raise KeyError(substr)


def method_end(start_line: int) -> int:
    i = start_line - 1
    while i < len(lines) and "{" not in lines[i]:
        i += 1
    depth = 0
    started = False
    while i < len(lines):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    return i + 1
        i += 1
    raise RuntimeError(start_line)


def grab(substr: str):
    a = find_line(substr)
    # include preceding @Override
    if a > 1 and "@Override" in lines[a - 2]:
        a -= 1
    b = method_end(a if "@Override" not in lines[a - 1] else a + 1)
    if "@Override" in lines[a - 1]:
        # method_end from override line won't work — start from next
        b = method_end(a + 1)
    return a, b, "".join(lines[a - 1 : b])


subs = [
    "protected void drawContents",
    "public boolean mouseClicked",
    "public boolean mouseDragged",
    "public boolean mouseReleased",
    "public void mouseScrolled",
    "protected boolean drawCustomTooltip",
    "public void afterTextEdit",
    "public void loadFromConfig",
    "public boolean saveSettings",
    "public void charTyped",
    "public void keyPressed(int keyCode)",
    "public void keyPressed(int keyCode,int scanCode,int modifiers)",
    "public boolean wantsKeyboardInput",
    "public void blurKeyboardFocus",
    "public static float clamp01",
    "public static int clampReach",
    "public static double reachToValue",
    "public static int valueToReach",
]

remove = set()
bodies = []
for s in subs:
    try:
        a, b, body = grab(s)
    except KeyError as e:
        print("miss", e)
        continue
    remove.update(range(a, b + 1))
    # rewrite to static methods taking SettingsPanel host
    body = re.sub(r"^    @Override\n", "", body)
    body = re.sub(
        r"^    (public |protected )?(static )?(void |boolean |float |int |double )",
        r"    public static \3",
        body,
        count=1,
        flags=re.M,
    )
    # inject SettingsPanel host as first arg for instance methods
    if "static float clamp01" in body or "static int clampReach" in body or "static double reachToValue" in body or "static int valueToReach" in body:
        bodies.append(body)
        print("static", a, b, s)
        continue
    body = re.sub(
        r"public static (void |boolean )(\w+)\(",
        r"public static \1\2(SettingsPanel host, ",
        body,
        count=1,
    )
    body = body.replace("(SettingsPanel host, )", "(SettingsPanel host)")
    # prefix field access — host.
    fields = [
        "details", "choices", "ui", "scrollY", "maxScrollY", "panelX", "panelY", "panelWidth",
        "panelHeight", "toast", "isToastError", "statusButton", "client", "groupButtons",
        "collapsed", "backendSection", "searchRevision", "validatingSearchKey", "connectionRevision",
        "connectionIdentity", "searchIdentity", "detectingModel", "validatingKey", "testKeyButton",
        "availableModels", "availableModelsUpdatedAtMs", "modelNeedsConfirmation", "modelInput",
        "draftModel", "reachInput", "temperatureInput", "interactionReachSlider", "temperatureSlider",
        "probes", "widgets", "apiKeyInput", "orchestratorInput", "llmBaseUrlInput",
        "searchApiKeyInput", "googleCseCxInput",
    ]
    for f in sorted(fields, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", body)
    body = body.replace("host.host.", "host.")
    # BasePanel methods on host
    for m in [
        "drawContentBackground", "getContentY", "drawTooltipCompat", "showToast",
        "super.mouseClicked", "isVisible",
    ]:
        pass
    body = body.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
    # Fix wrong prefixes
    for bad in [
        "SettingsGroup", "SettingsConnectionSection", "SettingsSearchSection",
        "SettingsPreferencesSection", "SettingsActionsSection", "SettingsInputController",
        "SettingsConfigCoordinator", "SettingsPreferenceValues", "SettingsPanel",
        "ButtonWidget", "Text", "List", "DrawContext", "Math", "CONTENT_PADDING",
        "BUTTON_HEIGHT", "BUTTON_ROW_HEIGHT", "GROUP_GAP", "IllegalArgumentException",
        "GLFW", "HudTextInput", "true", "false", "null", "super",
    ]:
        body = body.replace(f"host.{bad}", bad)
    body = body.replace("host.super.mouseClicked", "host.superMouseClicked")
    body = body.replace("super.mouseClicked", "host.superMouseClicked")
    # protected BasePanel fields: panelX etc need getters
    body = body.replace("host.panelX", "host.getPanelX()")
    body = body.replace("host.panelY", "host.getPanelY()")
    body = body.replace("host.panelWidth", "host.getPanelWidth()")
    body = body.replace("host.panelHeight", "host.getPanelHeight()")
    bodies.append(body)
    print("inst", a, b, s)

OUT.write_text(
    """package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Draw / input / config helpers carved from SettingsPanel (move-only). */
public final class SettingsPanelInteraction {
    private SettingsPanelInteraction() {}

"""
    + "\n".join(bodies)
    + "}\n",
    encoding="utf-8",
)

kept = [l for i, l in enumerate(lines, 1) if i not in remove]
text = "".join(kept)

# Add thin delegates + BasePanel bridges
bridges = '''
    void drawContentBackgroundPublic(DrawContext ctx) { drawContentBackground(ctx); }
    boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }

    @Override protected void drawContents(DrawContext ctx) { SettingsPanelInteraction.drawContents(this, ctx); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return SettingsPanelInteraction.mouseClicked(this, mouseX, mouseY, button); }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return SettingsPanelInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { return SettingsPanelInteraction.mouseReleased(this, mouseX, mouseY, button); }
    @Override public void mouseScrolled(double mouseX, double mouseY, double amount) { SettingsPanelInteraction.mouseScrolled(this, mouseX, mouseY, amount); }
    @Override protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return SettingsPanelInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }
    public void afterTextEdit(HudTextInput input, String before) { SettingsPanelInteraction.afterTextEdit(this, input, before); }
    public void loadFromConfig() { SettingsPanelInteraction.loadFromConfig(this); }
    public boolean saveSettings() { return SettingsPanelInteraction.saveSettings(this); }
    @Override public void charTyped(char chr) { SettingsPanelInteraction.charTyped(this, chr); }
    @Override public void keyPressed(int keyCode) { SettingsPanelInteraction.keyPressed(this, keyCode); }
    @Override public void keyPressed(int keyCode, int scanCode, int modifiers) { SettingsPanelInteraction.keyPressed(this, keyCode, scanCode, modifiers); }
    @Override public boolean wantsKeyboardInput() { return SettingsPanelInteraction.wantsKeyboardInput(this); }
    @Override public void blurKeyboardFocus() { SettingsPanelInteraction.blurKeyboardFocus(this); }
    public static float clamp01(float v) { return SettingsPanelInteraction.clamp01(v); }
    public static int clampReach(int v) { return SettingsPanelInteraction.clampReach(v); }
    public static double reachToValue(int reach) { return SettingsPanelInteraction.reachToValue(reach); }
    public static int valueToReach(double value) { return SettingsPanelInteraction.valueToReach(value); }

'''

text = text.replace(
    "    public SettingsPanel() {",
    bridges + "\n    public SettingsPanel() {",
)
# ctor should call SettingsPanelInteraction / widgets
text = text.replace(
    "        loadFromConfig();\n        widgets.initWidgets();",
    "        SettingsPanelInteraction.loadFromConfig(this);\n        widgets.initWidgets();",
)
text = text.replace(
    "        loadFromConfig();\n        initWidgets();",
    "        SettingsPanelInteraction.loadFromConfig(this);\n        widgets.initWidgets();",
)
text = re.sub(r"\n{3,}", "\n\n", text)
PANEL.write_text(text, encoding="utf-8")
print("panel", text.count("\n") + 1)
print("interaction", OUT.read_text(encoding="utf-8").count("\n") + 1)
