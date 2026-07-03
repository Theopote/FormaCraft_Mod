"""Phase 5d UI extraction."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAPTURE = ROOT / "src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java"
SETTINGS = ROOT / "src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java"
CAP_OUT = ROOT / "src/main/java/com/formacraft/client/ui/panel/capture"
SET_OUT = ROOT / "src/main/java/com/formacraft/client/ui/panel/settings"


def find(lines: list[str], s: str, start: int = 0) -> int:
    for i in range(start, len(lines)):
        if s in lines[i]:
            return i
    raise ValueError(s)


def xform_capture(lines: list[str]) -> list[str]:
    out = []
    for line in lines:
        line = line.replace("drawWrappedText(ctx,", "textDrawer.draw(ctx,")
        line = line.replace("getScaledMouseX()", "mouseX")
        line = line.replace("getScaledMouseY()", "mouseY")
        line = line.replace(
            "drawStatusIndicator(ctx, x, y, w)",
            "ComponentCaptureDrawSupport.drawStatusIndicator(ctx, client, healthCoordinator, x, y, w)",
        )
        out.append(line)
    return out


def section(name: str, doc: str, extra_imports: str, extra_params: str, body: list[str]) -> str:
    return f"""package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.component.ComponentCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
{extra_imports}

/** {doc} */
public final class {name} {{
    private {name}() {{}}

    public static int drawSection(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureSelectionController selectionController,
            ComponentCaptureThumbnailService thumbnailService,
            ComponentCaptureHealthCoordinator healthCoordinator,
            ComponentCaptureHealthDrawer.WrappedTextDrawer textDrawer,
            int mouseX,
            int mouseY,
            boolean phaseCollapsed,
            boolean isPhaseActive,
            boolean isPhaseComplete,
            {extra_params}
            int x,
            int y,
            int w
    ) {{
{chr(10).join(xform_capture(body))}
    }}
}}
"""


cl = CAPTURE.read_text(encoding="utf-8").splitlines()
CAP_OUT.mkdir(parents=True, exist_ok=True)
SET_OUT.mkdir(parents=True, exist_ok=True)

# --- ComponentCaptureDrawSupport ---
draw_support = CAPTURE.read_text(encoding="utf-8")
# extract drawStatusIndicator method body from panel
ds_start = find(cl, "private int drawStatusIndicator")
ds_end = find(cl, "    // ============ 世界交互方法 ============")
status_body = cl[ds_start + 1 : ds_end]
status_lines = []
for line in status_body:
    if line.strip() == "}" and not status_lines:
        continue
    if line.startswith("    private int drawStatusIndicator"):
        continue
    status_lines.append(line.replace("    private int drawStatusIndicator(DrawContext ctx, int x, int y, int w) {", "").rstrip())
# clean: remove trailing closing brace of method
while status_lines and status_lines[-1].strip() == "}":
    status_lines.pop()

draw_support_java = '''package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Shared draw helpers for component capture phase sections. */
public final class ComponentCaptureDrawSupport {
    private ComponentCaptureDrawSupport() {}

    public static int drawStatusIndicator(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureHealthCoordinator healthCoordinator,
            int x,
            int y,
            int w
    ) {
'''
for line in status_lines:
    if not line.strip():
        draw_support_java += "\n"
        continue
    draw_support_java += line + "\n"
draw_support_java += "    }\n}\n"
(CAP_OUT / "ComponentCaptureDrawSupport.java").write_text(draw_support_java, encoding="utf-8")

# Phase 1
p1s = find(cl, "// ============ 阶段 1：选区定义") + 1
p1e = find(cl, "// ============ 阶段 2：锚点与朝向")
p1 = cl[p1s:p1e]
p1 = [ln.replace("boolean phase1Collapsed = phaseCollapsed[0];", "")
      .replace("phase1Collapsed", "phaseCollapsed")
      .replace("isPhase1Active", "isPhaseActive")
      .replace("isPhase1Complete", "isPhaseComplete")
      .replace("THUMBNAIL_SIZE", "thumbnailSize") for ln in p1]
(CAP_OUT / "ComponentCaptureSelectionSection.java").write_text(
    section(
        "ComponentCaptureSelectionSection",
        "阶段 1：选区定义",
        "",
        "ButtonWidget boxSelectButton,\n            ButtonWidget pointSelectButton,\n            ButtonWidget clearSelectionButton,\n            int thumbnailSize,\n",
        ["        " + ln.strip() if ln.strip() else "" for ln in p1],
    ),
    encoding="utf-8",
)

# Phase 2
p2s = find(cl, "// ============ 阶段 2：锚点与朝向") + 1
p2e = find(cl, "// ============ 阶段 3：构件语义确认")
p2 = cl[p2s:p2e]
p2 = [ln.replace("boolean phase2Collapsed = phaseCollapsed[1];", "")
      .replace("if (!isPhase1Complete) {", "if (forceCollapsed) {")
      .replace("phase2Collapsed = true;", "phaseCollapsed = true;")
      .replace("phase2Collapsed", "phaseCollapsed")
      .replace("isPhase2Active", "isPhaseActive")
      .replace("isPhase2Complete", "isPhaseComplete")
      .replace("selectionController.hasValidSelection()", "selectionController.hasValidSelection()") for ln in p2]
# remove st declaration line - pass via ComponentTool
p2 = [ln for ln in p2 if "var st = ComponentTool" not in ln and "syncPlacementHintsToState" not in ln]
(CAP_OUT / "ComponentCaptureAnchorSection.java").write_text(
    section(
        "ComponentCaptureAnchorSection",
        "阶段 2：锚点与朝向",
        "",
        "boolean forceCollapsed,\n            ButtonWidget pickAnchorButton,\n            ButtonWidget clearAnchorButton,\n            ButtonWidget hostFaceButton,\n            ButtonWidget anchorOutsideButton,\n            ButtonWidget autoAnchorButton,\n            ButtonWidget facingButton,\n            ButtonWidget mirrorButton,\n",
        ["        var st = ComponentTool.INSTANCE.getState();"] + ["        " + ln.strip() if ln.strip() else "" for ln in p2],
    ),
    encoding="utf-8",
)

print("capture sections 1-2 done")
