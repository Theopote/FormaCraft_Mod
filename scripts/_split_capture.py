"""Split ComponentCapturePanel into capture/* collaborators (move-only)."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java")
CAP = Path("src/main/java/com/formacraft/client/ui/panel/capture")
raw = PANEL.read_text(encoding="utf-8")
lines = raw.splitlines(keepends=True)


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
        a -= 1
    start = a
    if lines[a - 1].strip() == "@Override":
        b = method_end(a + 1)
    else:
        b = method_end(a)
    return a, b, "".join(lines[a - 1 : b])


# Collect field names for host. prefix (simple: all private fields)
field_names = set()
for l in lines:
    m = re.match(r"\s+private (?:static )?(?:final )?[\w.<>,\s\[\]]+? (\w+)\s*[=;]", l)
    if m and "(" not in l.split("=")[0]:
        field_names.add(m.group(1))
# also package fields without private from earlier patterns
for l in lines:
    m = re.match(r"\s+(?:public )?(?:final )?[\w.<>,\s\[\]]+? (\w+)\s*=", l)
    if m and "class " not in l and "record " not in l:
        field_names.add(m.group(1))

# Known important fields
field_names.update({
    "nameInput", "tagsInput", "socketIdInput", "captureButtons", "selectionController",
    "thumbnailService", "orientationController", "semanticPreview", "phaseCollapsed",
    "phaseHeaders", "client", "scrollY", "maxScrollY", "panelX", "panelY", "panelWidth",
    "panelHeight", "advancedOptionsExpanded",
})

SKIP = {
    "ButtonWidget", "Text", "DrawContext", "Click", "HudTextInput", "List", "Math", "System",
    "ComponentTool", "ComponentCaptureDraft", "CapturePhase", "Override", "true", "false", "null",
    "super", "this", "var", "new", "return", "if", "else", "for", "while", "try", "catch",
    "ComponentCaptureDrawSupport", "ComponentCapturePhaseHeaders", "Integer", "String", "Boolean",
}


def rewrite(body: str, locals_: set[str]) -> str:
    body = re.sub(r"^    @Override\n", "", body)
    body = re.sub(r"^    (private |public |protected )", "    public ", body, count=1, flags=re.M)
    # inject host param for non-static
    if re.search(r"public static ", body):
        return body
    body = re.sub(
        r"public (void |boolean |int |String |CapturePhase |[\w.]+ )(\w+)\(",
        r"public \1\2(ComponentCapturePanel host, ",
        body,
        count=1,
    )
    body = body.replace("(ComponentCapturePanel host, )", "(ComponentCapturePanel host)")
    for f in sorted(field_names, key=len, reverse=True):
        if f in SKIP:
            continue
        body = re.sub(rf"(?<![\w.]){re.escape(f)}(?!\w)", f"host.{f}", body)
    body = body.replace("host.host.", "host.")
    for bad in SKIP:
        body = body.replace(f"host.{bad}", bad)
    # BasePanel
    body = body.replace("host.panelX", "host.getPanelX()")
    body = body.replace("host.panelY", "host.getPanelY()")
    body = body.replace("host.panelWidth", "host.getPanelWidth()")
    body = body.replace("host.panelHeight", "host.getPanelHeight()")
    body = body.replace("host.getContentY()", "host.getContentY()")  # noop
    body = re.sub(r"(?<![\w.])getContentY\(", "host.getContentY(", body)
    body = re.sub(r"(?<![\w.])drawContentBackground\(", "host.drawContentBackgroundPublic(", body)
    body = re.sub(r"(?<![\w.])contentScissorLeft\(", "host.contentScissorLeftPublic(", body)
    body = re.sub(r"(?<![\w.])contentScissorRight\(", "host.contentScissorRightPublic(", body)
    body = re.sub(r"(?<![\w.])drawTooltipCompat\(", "host.drawTooltipCompatPublic(", body)
    body = re.sub(r"(?<![\w.])super\.mouseClicked\(", "host.superMouseClicked(", body)
    # local methods stay on same class
    for m in locals_:
        body = body.replace(f"host.{m}(", f"{m}(")
        body = re.sub(rf"(?<![\w.]){m}\(ComponentCapturePanel host,", f"{m}(host,", body)
    return body


GROUPS = {
    "ComponentCaptureWidgetBuilder": {
        "subs": ["private void ensureWidgets()"],
        "locals": {"ensureWidgets"},
        "header": '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/** ensureWidgets carved from ComponentCapturePanel. */
public final class ComponentCaptureWidgetBuilder {
    private ComponentCaptureWidgetBuilder() {}
''',
    },
    "ComponentCaptureLayoutRenderer": {
        "subs": [
            "protected void drawContents",
            "private void finishCaptureLayout",
            "private void drawStickyFooter",
            "private void autoCollapseNonActivePhases",
            "private CapturePhase computeCurrentPhase",
            "private boolean isPhaseComplete",
            "private int drawWrappedText",
        ],
        "locals": {
            "drawContents", "finishCaptureLayout", "drawStickyFooter", "autoCollapseNonActivePhases",
            "computeCurrentPhase", "isPhaseComplete", "drawWrappedText", "ensureWidgets",
        },
        "header": '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.ui.panel.ComponentCapturePanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

/** Layout/draw helpers carved from ComponentCapturePanel. */
public final class ComponentCaptureLayoutRenderer {
    private ComponentCaptureLayoutRenderer() {}
''',
    },
    "ComponentCaptureActionRouter": {
        "subs": [
            "private static boolean clickButton",
            "public boolean mouseClicked",
            "public boolean handleWorldClick",
            "public void handleWorldRelease",
            "public void mouseScrolled",
            "public void keyPressed",
            "public void charTyped",
        ],
        "locals": {"clickButton", "mouseClicked", "handleWorldClick", "handleWorldRelease"},
        "header": '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.ui.panel.ComponentCapturePanel;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.hit.BlockHitResult;

/** Input/action routing carved from ComponentCapturePanel. */
public final class ComponentCaptureActionRouter {
    private ComponentCaptureActionRouter() {}
''',
    },
    "ComponentCaptureSubmitter": {
        "subs": [
            "private void saveComponent",
            "private boolean canSave",
            "private boolean canSaveWithoutHealthCheck",
        ],
        "locals": {"saveComponent", "canSave", "canSaveWithoutHealthCheck"},
        "header": '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.ui.panel.ComponentCapturePanel;

/** saveComponent helpers carved from ComponentCapturePanel. */
public final class ComponentCaptureSubmitter {
    private ComponentCaptureSubmitter() {}
''',
    },
}

# Cross-group calls: layout ensureWidgets -> WidgetBuilder; action save -> Submitter etc.
CROSS = {
    "ensureWidgets": "ComponentCaptureWidgetBuilder.ensureWidgets",
    "saveComponent": "ComponentCaptureSubmitter.saveComponent",
    "canSave": "ComponentCaptureSubmitter.canSave",
    "canSaveWithoutHealthCheck": "ComponentCaptureSubmitter.canSaveWithoutHealthCheck",
    "computeCurrentPhase": "ComponentCaptureLayoutRenderer.computeCurrentPhase",
    "drawStickyFooter": "ComponentCaptureLayoutRenderer.drawStickyFooter",
    "clickButton": "ComponentCaptureActionRouter.clickButton",
}

remove = set()
written = {}
for cls, meta in GROUPS.items():
    bodies = []
    for s in meta["subs"]:
        try:
            a, b, body = grab(s)
        except KeyError:
            print("MISS", cls, s)
            continue
        remove.update(range(a, b + 1))
        body = rewrite(body, meta["locals"])
        # qualify cross calls to other classes
        for m, qual in CROSS.items():
            if m not in meta["locals"]:
                body = body.replace(f"{m}(host,", f"{qual}(host,")
                body = re.sub(rf"(?<![\w.]){m}\(", f"{qual}(", body)
        bodies.append(body)
        print(cls, a, b, s)
    (CAP / f"{cls}.java").write_text(meta["header"] + "\n".join(bodies) + "}\n", encoding="utf-8")
    written[cls] = True

# Make fields public on panel
text = "".join(l for i, l in enumerate(lines, 1) if i not in remove)
text = text.replace("    private record ", "    public record ")
text = text.replace("    private static final class GridItem", "    public static final class GridItem")
# publicize fields
text = re.sub(r"(?m)^    private final ", "    public final ", text)
text = re.sub(r"(?m)^    private (?!static |void |boolean |int |String |CapturePhase |List)", "    public ", text)
text = re.sub(r"(?m)^    private static boolean clickButton", "    public static boolean clickButton", text)

delegates = '''
    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }
    public int contentScissorLeftPublic() { return contentScissorLeft(); }
    public int contentScissorRightPublic() { return contentScissorRight(); }
    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }

    private void ensureWidgets() { ComponentCaptureWidgetBuilder.ensureWidgets(this); }
    @Override protected void drawContents(net.minecraft.client.gui.DrawContext ctx) { ComponentCaptureLayoutRenderer.drawContents(this, ctx); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return ComponentCaptureActionRouter.mouseClicked(this, mouseX, mouseY, button); }
    public boolean handleWorldClick(net.minecraft.util.hit.BlockHitResult hit, int button) { return ComponentCaptureActionRouter.handleWorldClick(this, hit, button); }
    public void handleWorldRelease(int button) { ComponentCaptureActionRouter.handleWorldRelease(this, button); }
    private void saveComponent() { ComponentCaptureSubmitter.saveComponent(this); }

'''

# Avoid duplicate if methods still present as stubs - we removed bodies so add after class fields
if "ComponentCaptureWidgetBuilder" not in text:
    text = text.replace(
        "    public ComponentCapturePanel() {",
        delegates + "\n    public ComponentCapturePanel() {",
    )

# Fix import for capture classes
if "import com.formacraft.client.ui.panel.capture." not in text:
    text = text.replace(
        "package com.formacraft.client.ui.panel;",
        "package com.formacraft.client.ui.panel;\n\nimport com.formacraft.client.ui.panel.capture.ComponentCaptureActionRouter;\n"
        "import com.formacraft.client.ui.panel.capture.ComponentCaptureLayoutRenderer;\n"
        "import com.formacraft.client.ui.panel.capture.ComponentCaptureSubmitter;\n"
        "import com.formacraft.client.ui.panel.capture.ComponentCaptureWidgetBuilder;",
    )

PANEL.write_text(text, encoding="utf-8")
print("panel lines", text.count("\n") + 1)
