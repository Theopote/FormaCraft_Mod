# -*- coding: utf-8 -*-
from pathlib import Path
import re

panel_path = Path("src/main/java/com/formacraft/client/ui/panel/BuildConfirmPanel.java")
src = panel_path.read_text(encoding="utf-8")

# Drop private from instance fields / nested Mode so same-package helpers can access.
# Keep private methods that stay on panel, and keep LOG/INSTANCE as-is where appropriate.
replacements = [
    (r"^(\s*)private final MinecraftClient client", r"\1final MinecraftClient client"),
    (r"^(\s*)private boolean visible", r"\1boolean visible"),
    (r"^(\s*)private BuildingSpec spec", r"\1BuildingSpec spec"),
    (r"^(\s*)private BlockPos patchOrigin", r"\1BlockPos patchOrigin"),
    (r"^(\s*)private java\.util\.List<BlockPatch> patchList", r"\1java.util.List<BlockPatch> patchList"),
    (r"^(\s*)private java\.util\.List<BlockPatch> rejectedPatchList", r"\1java.util.List<BlockPatch> rejectedPatchList"),
    (r"^(\s*)private java\.util\.List<String> patchWarnings", r"\1java.util.List<String> patchWarnings"),
    (r"^(\s*)private boolean awaitingPatchApplyResult", r"\1boolean awaitingPatchApplyResult"),
    (r"^(\s*)private boolean patchCanUndo", r"\1boolean patchCanUndo"),
    (r"^(\s*)private boolean patchCanRedo", r"\1boolean patchCanRedo"),
    (r"^(\s*)private UUID previewTicketId", r"\1UUID previewTicketId"),
    (r"^(\s*)private UUID buildId", r"\1UUID buildId"),
    (r"^(\s*)private final PatchPanelPlacement patchPlacement", r"\1final PatchPanelPlacement patchPlacement"),
    (r"^(\s*)private boolean draggingPatch", r"\1boolean draggingPatch"),
    (r"^(\s*)private double patchDragX,patchDragY", r"\1double patchDragX,patchDragY"),
    (r"^(\s*)private ButtonWidget confirmButton", r"\1ButtonWidget confirmButton"),
    (r"^(\s*)private ButtonWidget cancelButton", r"\1ButtonWidget cancelButton"),
    (r"^(\s*)private ButtonWidget applyPatchButton", r"\1ButtonWidget applyPatchButton"),
    (r"^(\s*)private ButtonWidget undoPatchButton", r"\1ButtonWidget undoPatchButton"),
    (r"^(\s*)private ButtonWidget redoPatchButton", r"\1ButtonWidget redoPatchButton"),
    (r"^(\s*)private enum Mode", r"\1enum Mode"),
    (r"^(\s*)private Mode mode", r"\1Mode mode"),
    (r"^(\s*)private boolean previewQualityErrorHint", r"\1boolean previewQualityErrorHint"),
    (r"^(\s*)private boolean previewQualityError", r"\1boolean previewQualityError"),
    (r"^(\s*)private boolean previewForceArmed", r"\1boolean previewForceArmed"),
]
for a, b in replacements:
    src = re.sub(a, b, src, flags=re.M)

# Extract method bodies by name
def extract_method(text: str, name: str):
    # find "void name(" or "Type name("
    m = re.search(rf"(?:public |private |protected )?[^\n]*\b{name}\s*\([^;]*\)\s*\{{", text)
    if not m:
        raise SystemExit(f"method not found: {name}")
    start = m.start()
    brace = text.find("{", m.end() - 1)
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1, text[start : i + 1]
        i += 1
    raise SystemExit("unbalanced " + name)


# Methods to move to BuildConfirmUi
move_names = [
    "layoutBuildButtons",
    "ensureWidgets",
    "render",
    "patchBounds",
    "layoutPatchButtons",
    "mouseClicked",
    "mouseReleased",
    "keyPressed",
    "runPreviewCommand",
    "clickButton",
]

bodies = {}
spans = []
for name in move_names:
    s, e, body = extract_method(src, name)
    bodies[name] = body
    spans.append((s, e, name))

spans.sort()
# rebuild panel without those methods, insert thin wrappers for public API
new_src = src
# remove from end to start
for s, e, name in reversed(spans):
    new_src = new_src[:s] + new_src[e:]

# Add thin public delegates before closing brace of class
delegates = """
    void layoutBuildButtons(int screenW, int screenH) { BuildConfirmUi.layoutBuildButtons(this, screenW, screenH); }
    void ensureWidgets() { BuildConfirmUi.ensureWidgets(this); }
    public void render(net.minecraft.client.gui.DrawContext context) { BuildConfirmUi.render(this, context); }
    PatchPanelPlacement.Bounds patchBounds() { return BuildConfirmUi.patchBounds(this); }
    void layoutPatchButtons(PatchPanelPlacement.Bounds box) { BuildConfirmUi.layoutPatchButtons(this, box); }
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return BuildConfirmUi.mouseClicked(this, mouseX, mouseY, button); }
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return BuildConfirmUi.mouseReleased(this, mouseX, mouseY, button); }
    public boolean keyPressed(int keyCode) { return BuildConfirmUi.keyPressed(this, keyCode); }
    void runPreviewCommand(String commandNoSlash) { BuildConfirmUi.runPreviewCommand(this, commandNoSlash); }
"""

# Insert before final closing }
idx = new_src.rfind("}")
new_src = new_src[:idx] + delegates + "\n" + new_src[idx:]
new_src = re.sub(r"\n{3,}", "\n\n", new_src)

panel_path.write_text(new_src, encoding="utf-8")
print("panel lines", new_src.count("\n") + 1)

# Build helper file from extracted bodies - rewrite as static methods taking panel
helper_imports = """package com.formacraft.client.ui.panel;

import com.formacraft.client.preview.*;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;

/** UI helpers extracted from BuildConfirmPanel (behavior-preserving). */
final class BuildConfirmUi {
    private BuildConfirmUi() {}
    private static final FcaLog LOG = FcaLog.of("BuildConfirmPanel");
"""


def convert_body(name: str, body: str) -> str:
    # strip original modifiers/signature opening through first {
    m = re.match(r"(?:public |private |protected )?[^{]+\{", body, re.S)
    if not m:
        raise SystemExit("bad body " + name)
    rest = body[m.end() :]  # includes trailing }
    # qualify field access - naive: prefix known fields with p.
    fields = [
        "client",
        "visible",
        "spec",
        "patchOrigin",
        "patchList",
        "rejectedPatchList",
        "patchWarnings",
        "awaitingPatchApplyResult",
        "patchCanUndo",
        "patchCanRedo",
        "previewTicketId",
        "buildId",
        "patchPlacement",
        "draggingPatch",
        "patchDragX",
        "patchDragY",
        "confirmButton",
        "cancelButton",
        "applyPatchButton",
        "undoPatchButton",
        "redoPatchButton",
        "mode",
        "previewQualityErrorHint",
        "previewQualityError",
        "previewForceArmed",
    ]
    # Also method calls on panel
    methods = [
        "ensureWidgets",
        "layoutBuildButtons",
        "layoutPatchButtons",
        "patchBounds",
        "clickButton",
        "runPreviewCommand",
        "confirm",
        "cancel",
        "applyPatch",
        "hide",
        "show",
        "showPreviewActions",
        "releaseChatKeyboardFocus",
    ]
    # Don't rewrite inside strings - keep simple
    out = rest
    # Remove trailing closing brace of original method - we'll add our own
    out = out.rstrip()
    if out.endswith("}"):
        out = out[:-1]
    # Prefix fields
    for f in sorted(fields, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"p.{f}", out)
    for mname in sorted(methods, key=len, reverse=True):
        if mname == name:
            # recursive call should stay as BuildConfirmUi.name(p, ...)
            continue
        out = re.sub(rf"(?<![\w.]){mname}\(", f"p.{mname}(", out)
    # Fix Mode -> BuildConfirmPanel.Mode? Mode is package enum inside panel - actually nested enum Mode is BuildConfirmPanel.Mode but package-private nested - accessible as Mode from same package only if static nested... Nested enum Mode without private is package-private nested type BuildConfirmPanel.Mode
    out = out.replace("p.Mode", "BuildConfirmPanel.Mode")  # if any
    # Fix LOG if referenced
    out = out.replace("p.LOG", "LOG")
    # Fix self calls to moved methods
    for mname in move_names:
        out = re.sub(rf"p\.{mname}\(", f"{mname}(p, " if False else f"TODO", out)
    return out


# Better conversion: rewrite signatures properly manually using original bodies with p. prefix script
def to_static(name: str, body: str, new_sig: str) -> str:
    m = re.match(r"[^{]+\{", body, re.S)
    rest = body[m.end() : -1]  # inner without final }
    fields = [
        "confirmButton",
        "cancelButton",
        "applyPatchButton",
        "undoPatchButton",
        "redoPatchButton",
        "previewQualityErrorHint",
        "previewQualityError",
        "previewForceArmed",
        "awaitingPatchApplyResult",
        "patchCanUndo",
        "patchCanRedo",
        "previewTicketId",
        "patchPlacement",
        "draggingPatch",
        "patchDragX",
        "patchDragY",
        "patchWarnings",
        "rejectedPatchList",
        "patchList",
        "patchOrigin",
        "buildId",
        "visible",
        "client",
        "spec",
        "mode",
    ]
    methods_local = set(move_names) | {
        "confirm",
        "cancel",
        "applyPatch",
        "hide",
        "releaseChatKeyboardFocus",
    }
    out = rest
    for f in sorted(fields, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"p.{f}", out)
    # method calls: if local moved method, call static with p; else p.method
    for mname in sorted(methods_local, key=len, reverse=True):
        if mname in move_names:
            # replace bare mname( with mname(p,  except when already mname(p
            def repl(match, _n=mname):
                return f"{_n}(p, "

            out = re.sub(rf"(?<![\w.]){mname}\(", repl, out)
        else:
            out = re.sub(rf"(?<![\w.]){mname}\(", f"p.{mname}(", out)
    out = out.replace("p.Mode.", "BuildConfirmPanel.Mode.")
    # Mode.BUILD etc without BuildConfirmPanel
    out = re.sub(r"(?<![\w.])Mode\.", "BuildConfirmPanel.Mode.", out)
    out = out.replace("p.LOG", "LOG")
    out = out.replace("p.p.", "p.")
    # Fix double p for static: layoutBuildButtons(p, p, -> layoutBuildButtons(p,
    out = re.sub(r"(layoutBuildButtons|ensureWidgets|render|patchBounds|layoutPatchButtons|mouseClicked|mouseReleased|keyPressed|runPreviewCommand|clickButton)\(p, p,", r"\1(p,", out)
    # Fix clickButton(p, widget -> ok
    return f"    static {new_sig} {{\n{out}\n    }}\n"


sigs = {
    "clickButton": "boolean clickButton(BuildConfirmPanel p, ButtonWidget button, Click click)",
    "layoutBuildButtons": "void layoutBuildButtons(BuildConfirmPanel p, int screenW, int screenH)",
    "ensureWidgets": "void ensureWidgets(BuildConfirmPanel p)",
    "render": "void render(BuildConfirmPanel p, DrawContext context)",
    "patchBounds": "PatchPanelPlacement.Bounds patchBounds(BuildConfirmPanel p)",
    "layoutPatchButtons": "void layoutPatchButtons(BuildConfirmPanel p, PatchPanelPlacement.Bounds box)",
    "mouseClicked": "boolean mouseClicked(BuildConfirmPanel p, double mouseX, double mouseY, int button)",
    "mouseReleased": "boolean mouseReleased(BuildConfirmPanel p, double mouseX, double mouseY, int button)",
    "keyPressed": "boolean keyPressed(BuildConfirmPanel p, int keyCode)",
    "runPreviewCommand": "void runPreviewCommand(BuildConfirmPanel p, String commandNoSlash)",
}

parts = [helper_imports]
for name in move_names:
    parts.append(to_static(name, bodies[name], sigs[name]))
parts.append("}\n")
helper = "\n".join(parts)
Path("src/main/java/com/formacraft/client/ui/panel/BuildConfirmUi.java").write_text(helper, encoding="utf-8")
print("helper lines", helper.count("\n") + 1)
