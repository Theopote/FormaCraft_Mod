# -*- coding: utf-8 -*-
from pathlib import Path

actions = r'''package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.transform.Mirror;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Placement/state helpers extracted from ComponentCapturePanel (behavior-preserving). */
public final class ComponentCapturePanelActions {
    private ComponentCapturePanelActions() {}

    public static String getAttachmentModeDisplay(ComponentCapturePanel p) {
        return switch (p.attachmentMode) {
            case NONE -> "无附着";
            case FLOOR -> "地面";
            case WALL_SURFACE -> "墙面";
            case WALL_OPENING -> "墙洞";
            case ROOF_SURFACE -> "屋面";
            case ROOF_EDGE -> "屋檐";
            case ROOF_RIDGE -> "屋脊";
            case EDGE -> "边缘";
            case CORNER -> "转角";
        };
    }

    public static void cycleAttachmentMode(ComponentCapturePanel p) {
        AttachmentType[] values = AttachmentType.values();
        int index = p.attachmentMode.ordinal();
        p.attachmentMode = values[(index + 1) % values.length];
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        draft.host.manualAttachment = true;
        syncPlacementHintsToState(p);
        p.semanticPreview.invalidate();
        if (ComponentCapturePanel.DEBUG_CAPTURE) {
            FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 切换附着模式: {}", p.attachmentMode);
        }
    }

    public static void cycleDirectionality(ComponentCapturePanel p) {
        p.orientationController.cycleDirectionality();
        p.semanticPreview.invalidate();
        if (ComponentCapturePanel.DEBUG_CAPTURE) {
            FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 切换方向性: {}",
                    p.orientationController.getDirectionalityMode().getDisplayName());
        }
    }

    public static void syncPlacementHintsToState(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        draft.host.attachment = p.attachmentMode;
        p.orientationController.applyDirectionalityToDraft();
    }

    public static void typeChanged(ComponentCapturePanel p) {
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        p.attachmentMode = draft.host.attachment;
        p.orientationController.stopMarking();
        p.orientationController.loadFromDraft(draft);
        p.semanticPreview.invalidate();
        p.adaptationSection.invalidate();
    }

    public static void cycleFacing(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        Direction base = st.captureDraft.orientation.facing != null ? st.captureDraft.orientation.facing : Direction.SOUTH;
        st.captureDraft.orientation.facing = base.rotateYClockwise();
        st.syncDraftToState();
    }

    public static void cycleMirror(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        var values = Mirror.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.captureDraft.orientation.mirror) {
                idx = i;
                break;
            }
        }
        st.captureDraft.orientation.mirror = values[(idx + 1) % values.length];
        st.syncDraftToState();
    }

    public static void updateTagsFromInput(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        String text = p.tagsInput.getText();
        if (text == null || text.isBlank()) {
            st.tags.clear();
            return;
        }
        st.tags.clear();
        for (String tag : text.split("[,，]")) {
            tag = tag.trim();
            if (!tag.isEmpty()) {
                st.tags.add(tag);
            }
        }
    }

    public static void setAnchor(ComponentCapturePanel p, BlockPos pos) {
        if (pos == null) return;
        var st = ComponentTool.INSTANCE.getState();
        if (!p.selectionController.isAnchorLocationAllowed(pos)) {
            HudToast.show("锚点必须在选区内或紧贴选区", true);
            return;
        }
        var draft = st.captureDraft;
        draft.anchor.worldPos = pos.toImmutable();
        st.syncDraftToState();
        st.pickingAnchor = false;
        if (ComponentCapturePanel.DEBUG_CAPTURE) {
            FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 设置锚点: {}", pos);
        }
        HudToast.show("锚点已设置: " + pos.toShortString());
    }

    public static void setAutoAnchor(ComponentCapturePanel p) {
        if (!p.selectionController.hasValidSelection()) {
            HudToast.show("请先选择构件区域", true);
            return;
        }
        BlockPos min = p.selectionController.getSelectionMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            HudToast.show("选区无效，无法自动设置锚点", true);
            return;
        }
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        setAnchor(p, new BlockPos(cx, min.getY(), cz));
    }

    public static void clearSelection(ComponentCapturePanel p) {
        p.selectionController.clearSelection();
        p.thumbnailService.invalidate();
        p.orientationController.clearMarks();
        if (ComponentCapturePanel.DEBUG_CAPTURE) {
            FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 清除选区");
        }
    }

    public static void tick(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        if (st.isCaptureSavePending()) return;
        boolean wasActive = st.captureActive;
        st.beginCapture();
        if (!wasActive && st.captureActive) {
            syncLocalFromState(p);
        }
        st.syncDraftToState();
        p.selectionController.tick(p.client());
        p.attachmentMode = st.captureDraft.host.attachment;
        p.orientationController.loadFromDraft(st.captureDraft);
    }

    public static void syncLocalFromState(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        p.selectionController.loadFromDraft(st.captureDraft, p.client());
        p.orientationController.loadFromDraft(st.captureDraft);
        if (!st.captureDraft.selection.explicit) {
            st.captureDraft.selection.aabbMin = SelectionTool.INSTANCE.getMin();
            st.captureDraft.selection.aabbMax = SelectionTool.INSTANCE.getMax();
        }
        p.attachmentMode = st.captureDraft.host.attachment;
    }
}
'''

Path("src/main/java/com/formacraft/client/ui/panel/capture/ComponentCapturePanelActions.java").write_text(
    actions, encoding="utf-8"
)
print("wrote actions")

# Replace bulky methods on panel with thin delegates
panel = Path("src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java")
t = panel.read_text(encoding="utf-8")

# Ensure import for actions
if "ComponentCapturePanelActions" not in t:
    t = t.replace(
        "import com.formacraft.client.ui.panel.capture.ComponentCaptureActionRouter;",
        "import com.formacraft.client.ui.panel.capture.ComponentCaptureActionRouter;\n"
        "import com.formacraft.client.ui.panel.capture.ComponentCapturePanelActions;",
    )
    if "ComponentCapturePanelActions" not in t:
        # fallback: add after package
        t = t.replace(
            "package com.formacraft.client.ui.panel;\n",
            "package com.formacraft.client.ui.panel;\n\nimport com.formacraft.client.ui.panel.capture.ComponentCapturePanelActions;\n",
            1,
        )

import re

def replace_method(src: str, sig_regex: str, new_body: str) -> str:
    # Replace from method signature through matching closing brace at column 4
    m = re.search(sig_regex, src, re.M)
    if not m:
        raise SystemExit(f"missing method: {sig_regex}")
    start = m.start()
    # find opening brace of method
    brace = src.find("{", m.end() - 1 if src[m.end() - 1] == "{" else m.end())
    if brace < 0:
        # signature may not include brace
        brace = src.find("{", m.end())
    depth = 0
    i = brace
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
        i += 1
    else:
        raise SystemExit("unbalanced")
    return src[:start] + new_body + src[end:]

replacements = [
    (
        r"@Override\s+public String getAttachmentModeDisplay\(\)\s*\{",
        "@Override public String getAttachmentModeDisplay() { return ComponentCapturePanelActions.getAttachmentModeDisplay(this); }",
    ),
    (
        r"public void cycleAttachmentMode\(\)\s*\{",
        "public void cycleAttachmentMode() { ComponentCapturePanelActions.cycleAttachmentMode(this); }",
    ),
    (
        r"public void cycleDirectionality\(\)\s*\{",
        "public void cycleDirectionality() { ComponentCapturePanelActions.cycleDirectionality(this); }",
    ),
    (
        r"public void syncPlacementHintsToState\(\)\s*\{",
        "public void syncPlacementHintsToState() { ComponentCapturePanelActions.syncPlacementHintsToState(this); }",
    ),
    (
        r"public void typeChanged\(\)\s*\{",
        "public void typeChanged() { ComponentCapturePanelActions.typeChanged(this); }",
    ),
    (
        r"public void cycleFacing\(\)\s*\{",
        "public void cycleFacing() { ComponentCapturePanelActions.cycleFacing(this); }",
    ),
    (
        r"public void cycleMirror\(\)\s*\{",
        "public void cycleMirror() { ComponentCapturePanelActions.cycleMirror(this); }",
    ),
    (
        r"@Override\s+public void updateTagsFromInput\(\)\s*\{",
        "@Override public void updateTagsFromInput() { ComponentCapturePanelActions.updateTagsFromInput(this); }",
    ),
    (
        r"public void setAnchor\(net\.minecraft\.util\.math\.BlockPos pos\)\s*\{",
        "public void setAnchor(net.minecraft.util.math.BlockPos pos) { ComponentCapturePanelActions.setAnchor(this, pos); }",
    ),
    (
        r"public void setAutoAnchor\(\)\s*\{",
        "public void setAutoAnchor() { ComponentCapturePanelActions.setAutoAnchor(this); }",
    ),
    (
        r"public void clearSelection\(\)\s*\{",
        "public void clearSelection() { ComponentCapturePanelActions.clearSelection(this); }",
    ),
    (
        r"public void tick\(\)\s*\{",
        "public void tick() { ComponentCapturePanelActions.tick(this); }",
    ),
    (
        r"public void syncLocalFromState\(\)\s*\{",
        "public void syncLocalFromState() { ComponentCapturePanelActions.syncLocalFromState(this); }",
    ),
]

for sig, body in replacements:
    t = replace_method(t, sig, body)

# Remove unused private helpers and empty javadocs
t = re.sub(r"\s*private void ensureWidgets\(\)\s*\{[^}]+\}", "", t)
t = re.sub(r"\s*private void saveComponent\(\)\s*\{[^}]+\}", "", t)
t = re.sub(r"\s*private boolean canSave\(\)\s*\{[^}]+\}", "", t)
t = re.sub(r"\s*/\*\*[^*]*\*/\s*(?=\s*/\*\*)", "\n", t)  # empty stacked docs
t = re.sub(r"\s*/\*\*[^*]*计算当前应处于的阶段[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*某阶段是否完成[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*世界交互[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*处理世界点击[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*处理世界释放[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*设置锚点[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*获取附着[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*循环切换附着[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*循环切换方向[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*同步 UI[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*设置选择模式[^*]*\*/\s*", "\n", t)
t = re.sub(r"\s*/\*\*[^*]*将当前 state[^*]*\*/\s*", "\n", t)
t = re.sub(r"\n{3,}", "\n\n", t)

panel.write_text(t, encoding="utf-8")
print("panel lines", t.count("\n") + 1)
