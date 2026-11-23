"""Safe Capture split: same-package peers, curated field list only."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java")
PKG = Path("src/main/java/com/formacraft/client/ui/panel")
CAP = Path("src/main/java/com/formacraft/client/ui/panel/capture")
lines = PANEL.read_text(encoding="utf-8").splitlines(keepends=True)

FIELDS = [
    "nameInput", "tagsInput", "socketIdInput", "typeSection", "captureButtons",
    "categoryButton", "pickAnchorButton", "clearAnchorButton", "hostFaceButton",
    "anchorOutsideButton", "autoAnchorButton", "facingButton", "mirrorButton",
    "semanticSkinButton", "semanticTagOnSaveButton", "semanticStyleButton", "semanticPartButton",
    "socketContextButton", "socketPickOriginButton", "socketFacingButton", "socketAddButton",
    "socketPreviewButton", "socketClearButton", "autoFixButton", "undoAutoFixButton",
    "cancelButton", "saveButton", "boxSelectButton", "pointSelectButton", "clearSelectionButton",
    "advancedOptionsButton", "attachmentModeButton", "directionalityButton", "culturalStyleButton",
    "geometryArchetypeButton", "setInsideButton", "setOutsideButton", "setBottomButton", "setTopButton",
    "nameInputX", "nameInputY", "nameInputW", "nameInputH",
    "tagsInputX", "tagsInputY", "tagsInputW", "tagsInputH",
    "socketIdInputX", "socketIdInputY", "socketIdInputW", "socketIdInputH",
    "nameInputBoundsValid", "tagsInputBoundsValid", "socketIdInputBoundsValid",
    "selectionController", "adaptationSection", "thumbnailService", "orientationController",
    "healthCoordinator", "semanticPreview", "aiExplanation", "worldOverlay",
    "scrollY", "maxScrollY", "attachmentMode", "currentPhase", "phaseCollapsed", "phaseHeaders",
    "lastAutoCollapsePhase", "advancedOptionsExpanded", "client",
]


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
    if a > 1 and lines[a - 2].strip() == "@Override":
        a2 = a - 1
        b = method_end(a)
        return a2, b, "".join(lines[a2 - 1 : b])
    b = method_end(a)
    return a, b, "".join(lines[a - 1 : b])


def rewrite_body(body: str, static: bool = False) -> str:
    body = re.sub(r"^    @Override\n", "", body)
    body = re.sub(r"^    (private |public |protected )", "    ", body, count=1, flags=re.M)
    if static:
        body = re.sub(r"^    static ", "    static ", body, count=1, flags=re.M)
        # no host for pure static helpers like clickButton
        return "    " + body.lstrip() if not body.startswith("    ") else body
    # instance -> static with host
    body = re.sub(
        r"^    (void |boolean |int |CapturePhase |String )(\w+)\(",
        r"    static \1\2(ComponentCapturePanel p, ",
        body,
        count=1,
        flags=re.M,
    )
    body = body.replace("(ComponentCapturePanel p, )", "(ComponentCapturePanel p)")
    body = body.replace("this.", "p.")
    for f in sorted(FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(f)}(?!\w)", f"p.{f}", body)
    body = body.replace("p.p.", "p.")
    # geometry
    body = body.replace("p.panelX", "p.getPanelX()")
    body = body.replace("p.panelY", "p.getPanelY()")
    body = body.replace("p.panelWidth", "p.getPanelWidth()")
    body = body.replace("p.panelHeight", "p.getPanelHeight()")
    body = re.sub(r"(?<![\w.])getContentY\(", "p.getContentY(", body)
    body = re.sub(r"(?<![\w.])drawContentBackground\(", "p.drawContentBackgroundPublic(", body)
    body = re.sub(r"(?<![\w.])contentScissorLeft\(", "p.contentScissorLeftPublic(", body)
    body = re.sub(r"(?<![\w.])contentScissorRight\(", "p.contentScissorRightPublic(", body)
    body = re.sub(r"(?<![\w.])drawTooltipCompat\(", "p.drawTooltipCompatPublic(", body)
    body = re.sub(r"(?<![\w.])super\.mouseClicked\(", "p.superMouseClicked(", body)
    # Don't prefix constants / types
    for bad in [
        "CONTENT_PADDING", "LABEL_OFFSET", "BUTTON_HEIGHT", "THUMBNAIL_SIZE", "FIELD_SPACING",
        "STICKY_FOOTER_HEIGHT", "DEBUG_CAPTURE", "CapturePhase", "ButtonWidget", "Text", "List",
        "Math", "ComponentTool", "HudClickSupport", "Click", "MouseInput", "DrawContext",
        "ComponentCaptureDrawSupport", "ComponentSelectionMode", "BlockPos", "BlockHitResult",
        "AttachmentType", "true", "false", "null", "Override", "Integer", "String", "Boolean",
        "ArrayList", "Objects", "System", "Locale", "Collections",
    ]:
        body = body.replace(f"p.{bad}", bad)
    return body


GROUPS = {
    "ComponentCaptureWidgetBuilder.java": (
        ["private void ensureWidgets()"],
        '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.ui.panel.ComponentCapturePanel;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

public final class ComponentCaptureWidgetBuilder {
    private ComponentCaptureWidgetBuilder() {}
''',
    ),
    "ComponentCaptureLayoutRenderer.java": (
        [
            "protected void drawContents",
            "private void finishCaptureLayout",
            "private void drawStickyFooter",
            "private void autoCollapseNonActivePhases",
            "private CapturePhase computeCurrentPhase",
            "private boolean isPhaseComplete",
            "private int drawWrappedText",
        ],
        '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.panel.CapturePhase;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

public final class ComponentCaptureLayoutRenderer {
    private ComponentCaptureLayoutRenderer() {}
''',
    ),
    "ComponentCaptureActionRouter.java": (
        [
            "private static boolean clickButton",
            "public boolean mouseClicked",
            "public boolean handleWorldClick",
            "public void handleWorldRelease",
            "public void mouseScrolled",
            "public void keyPressed",
            "public void charTyped",
        ],
        '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

public final class ComponentCaptureActionRouter {
    private ComponentCaptureActionRouter() {}
''',
    ),
    "ComponentCaptureSubmitter.java": (
        [
            "private boolean canSaveWithoutHealthCheck",
            "private boolean canSave",
            "private void saveComponent",
        ],
        '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;

public final class ComponentCaptureSubmitter {
    private ComponentCaptureSubmitter() {}
''',
    ),
}

remove = set()
for fname, (subs, header) in GROUPS.items():
    bodies = []
    for s in subs:
        a, b, body = grab(s)
        remove.update(range(a, b + 1))
        is_static = "static " in s
        bodies.append(rewrite_body(body, static=is_static))
        print(fname, a, b, s)
    # Fix cross-calls among extractors
    joined = "\n".join(bodies)
    joined = joined.replace("ensureWidgets(p)", "ComponentCaptureWidgetBuilder.ensureWidgets(p)")
    joined = joined.replace("ensureWidgets()", "ComponentCaptureWidgetBuilder.ensureWidgets(p)")
    joined = joined.replace("saveComponent(p)", "ComponentCaptureSubmitter.saveComponent(p)")
    joined = joined.replace("canSave(p)", "ComponentCaptureSubmitter.canSave(p)")
    joined = joined.replace("canSaveWithoutHealthCheck(p)", "ComponentCaptureSubmitter.canSaveWithoutHealthCheck(p)")
    joined = joined.replace("computeCurrentPhase(p)", "ComponentCaptureLayoutRenderer.computeCurrentPhase(p)")
    joined = joined.replace("clickButton(", "ComponentCaptureActionRouter.clickButton(")
    # make methods public
    joined = re.sub(r"\n    static ", "\n    public static ", joined)
    (CAP / fname).write_text(header + joined + "}\n", encoding="utf-8")

# Strip private from fields in panel
kept = [l for i, l in enumerate(lines, 1) if i not in remove]
text = "".join(kept)
text = re.sub(r"(?m)^    private final ", "    public final ", text)
text = re.sub(r"(?m)^    private (?!static |void |boolean \w+\(|int \w+\(|String |CapturePhase )", "    public ", text)
text = text.replace("    private static final boolean DEBUG_CAPTURE", "    public static final boolean DEBUG_CAPTURE")
# constants used by extractors
for c in ["CONTENT_PADDING", "LABEL_OFFSET", "BUTTON_HEIGHT", "THUMBNAIL_SIZE", "FIELD_SPACING", "STICKY_FOOTER_HEIGHT"]:
    text = text.replace(f"    private static final int {c}", f"    public static final int {c}")

delegates = '''
    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }
    public int contentScissorLeftPublic() { return contentScissorLeft(); }
    public int contentScissorRightPublic() { return contentScissorRight(); }
    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }

    private void ensureWidgets() { ComponentCaptureWidgetBuilder.ensureWidgets(this); }
    @Override protected void drawContents(net.minecraft.client.gui.DrawContext ctx) { ComponentCaptureLayoutRenderer.drawContents(this, ctx); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return ComponentCaptureActionRouter.mouseClicked(this, mouseX, mouseY, button); }
    @Override public void mouseScrolled(double mouseX, double mouseY, double amount) { ComponentCaptureActionRouter.mouseScrolled(this, mouseX, mouseY, amount); }
    @Override public void keyPressed(int keyCode, int scanCode, int modifiers) { ComponentCaptureActionRouter.keyPressed(this, keyCode, scanCode, modifiers); }
    @Override public void charTyped(char chr) { ComponentCaptureActionRouter.charTyped(this, chr); }
    public boolean handleWorldClick(net.minecraft.util.hit.BlockHitResult hit, int button) { return ComponentCaptureActionRouter.handleWorldClick(this, hit, button); }
    public void handleWorldRelease(int button) { ComponentCaptureActionRouter.handleWorldRelease(this, button); }
    private void saveComponent() { ComponentCaptureSubmitter.saveComponent(this); }

'''

text = text.replace(
    "package com.formacraft.client.ui.panel;",
    "package com.formacraft.client.ui.panel;\n\n"
    "import com.formacraft.client.ui.panel.capture.ComponentCaptureActionRouter;\n"
    "import com.formacraft.client.ui.panel.capture.ComponentCaptureLayoutRenderer;\n"
    "import com.formacraft.client.ui.panel.capture.ComponentCaptureSubmitter;\n"
    "import com.formacraft.client.ui.panel.capture.ComponentCaptureWidgetBuilder;",
)
text = text.replace("    public ComponentCapturePanel() {", delegates + "\n    public ComponentCapturePanel() {")
# Remove leftover empty javadoc / blanks
text = re.sub(r"\n{3,}", "\n\n", text)
PANEL.write_text(text, encoding="utf-8")
print("panel", text.count("\n") + 1)
