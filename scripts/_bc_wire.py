# -*- coding: utf-8 -*-
from pathlib import Path
import re

p = Path("src/main/java/com/formacraft/client/ui/panel/BuildConfirmPanel.java")
t = p.read_text(encoding="utf-8")

# Package-visible fields / nested Mode / LOG / applyPatch
subs = [
    ("private static final FcaLog LOG", "static final FcaLog LOG"),
    ("private final MinecraftClient client", "final MinecraftClient client"),
    ("private boolean visible", "boolean visible"),
    ("private BuildingSpec spec", "BuildingSpec spec"),
    ("private BlockPos patchOrigin", "BlockPos patchOrigin"),
    ("private java.util.List<BlockPatch> patchList", "java.util.List<BlockPatch> patchList"),
    ("private java.util.List<BlockPatch> rejectedPatchList", "java.util.List<BlockPatch> rejectedPatchList"),
    ("private java.util.List<String> patchWarnings", "java.util.List<String> patchWarnings"),
    ("private boolean awaitingPatchApplyResult", "boolean awaitingPatchApplyResult"),
    ("private boolean patchCanUndo", "boolean patchCanUndo"),
    ("private boolean patchCanRedo", "boolean patchCanRedo"),
    ("private UUID previewTicketId", "UUID previewTicketId"),
    ("private UUID buildId", "UUID buildId"),
    ("private final PatchPanelPlacement patchPlacement", "final PatchPanelPlacement patchPlacement"),
    ("private boolean draggingPatch", "boolean draggingPatch"),
    ("private double patchDragX,patchDragY", "double patchDragX,patchDragY"),
    ("private ButtonWidget confirmButton", "ButtonWidget confirmButton"),
    ("private ButtonWidget cancelButton", "ButtonWidget cancelButton"),
    ("private ButtonWidget applyPatchButton", "ButtonWidget applyPatchButton"),
    ("private ButtonWidget undoPatchButton", "ButtonWidget undoPatchButton"),
    ("private ButtonWidget redoPatchButton", "ButtonWidget redoPatchButton"),
    ("private enum Mode", "enum Mode"),
    ("private Mode mode", "Mode mode"),
    ("private boolean previewQualityErrorHint", "boolean previewQualityErrorHint"),
    ("private boolean previewQualityError", "boolean previewQualityError"),
    ("private boolean previewForceArmed", "boolean previewForceArmed"),
    ("private void applyPatch()", "void applyPatch()"),
]
for a, b in subs:
    if a not in t:
        print("missing", a)
    t = t.replace(a, b, 1)


def replace_method(text, sig_start_regex, replacement):
    m = re.search(sig_start_regex, text, re.M)
    if not m:
        raise SystemExit("not found: " + sig_start_regex)
    start = m.start()
    brace = text.find("{", m.end() - 1)
    depth = 0
    i = brace
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[:start] + replacement + text[i + 1 :]
        i += 1
    raise SystemExit("unbalanced")


replacements = [
    (r"private boolean clickButton\(ButtonWidget button, Click click\)\s*\{",
     "boolean clickButton(ButtonWidget button, Click click) { return BuildConfirmUi.clickButton(button, click); }"),
    (r"private void layoutBuildButtons\(int screenW, int screenH\)\s*\{",
     "void layoutBuildButtons(int screenW, int screenH) { BuildConfirmUi.layoutBuildButtons(this, screenW, screenH); }"),
    (r"private void ensureWidgets\(\)\s*\{",
     "void ensureWidgets() { BuildConfirmUi.ensureWidgets(this); }"),
    (r"public void render\(DrawContext context\)\s*\{",
     "public void render(DrawContext context) { BuildConfirmUi.render(this, context); }"),
    (r"private PatchPanelPlacement\.Bounds patchBounds\(\)\s*\{",
     "PatchPanelPlacement.Bounds patchBounds() { return BuildConfirmUi.patchBounds(this); }"),
    (r"private void layoutPatchButtons\(PatchPanelPlacement\.Bounds box\)\s*\{",
     "void layoutPatchButtons(PatchPanelPlacement.Bounds box) { BuildConfirmUi.layoutPatchButtons(this, box); }"),
    (r"private void drawFirstMatchingHudTooltip\(DrawContext ctx, int mouseX, int mouseY,\s*ClickableWidget\[\] widgets, Text\[\] tooltips\)\s*\{",
     "void drawFirstMatchingHudTooltip(DrawContext ctx, int mouseX, int mouseY, ClickableWidget[] widgets, Text[] tooltips) { BuildConfirmUi.drawFirstMatchingHudTooltip(this, ctx, mouseX, mouseY, widgets, tooltips); }"),
    (r"public boolean mouseClicked\(double mouseX,double mouseY,int button\)\s*\{",
     "public boolean mouseClicked(double mouseX, double mouseY, int button) { return BuildConfirmUi.mouseClicked(this, mouseX, mouseY, button); }"),
    (r"private void runPreviewCommand\(String commandNoSlash\)\s*\{",
     "void runPreviewCommand(String commandNoSlash) { BuildConfirmUi.runPreviewCommand(this, commandNoSlash); }"),
]

for sig, body in replacements:
    t = replace_method(t, sig, body)

# Strip bulky javadocs to help budget further
t = re.sub(r"\n\s*/\*\*[\s\S]*?\*/", "", t)
t = re.sub(r"\n{3,}", "\n\n", t)

p.write_text(t, encoding="utf-8")
print("lines", t.count("\n") + 1)
