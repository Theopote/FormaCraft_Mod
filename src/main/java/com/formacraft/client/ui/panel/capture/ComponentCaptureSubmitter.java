package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.util.math.BlockPos;

public final class ComponentCaptureSubmitter {
    private ComponentCaptureSubmitter() {}

    public static boolean canSaveWithoutHealthCheck(ComponentCapturePanel p) {
        var st = ComponentTool.INSTANCE.getState();
        if (!com.formacraft.client.tool.ComponentCaptureTypes.ready(st) || st.isCaptureSavePending()) return false;
        if (!p.selectionController.hasValidSelection(p.client())) {
            return false;
        }
        if (st.name == null || st.name.isBlank()) {
            return false;
        }
        if (st.captureDraft.anchor.worldPos == null) {
            return false;
        }
        BlockPos anchor = st.captureDraft.anchor.worldPos;
        return p.selectionController.isAnchorLocationAllowed(anchor);
    }

    public static boolean canSave(ComponentCapturePanel p) {
        return canSaveWithoutHealthCheck(p) && !p.healthCoordinator.blocksSave();
    }

    public static void saveComponent(ComponentCapturePanel p) {
        if (!canSave(p)) {
            HudToast.show("保存失败：请检查选区和名称", true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        var health = p.healthCoordinator.check();
        if (health.hasErrors()) {
            HudToast.show("保存失败：存在错误项（请先修复）", true);
            return;
        }
        if (health.hasWarnings()) {
            HudToast.show("存在警告：仍可保存，但可能影响放置效果");
        }
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(p.client(), ComponentTool.INSTANCE.getState().captureDraft);
        if (json == null || json.isBlank()) {
            HudToast.show("保存失败：请检查选区", true);
            return;
        }

        ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
        String ruleError = com.formacraft.common.component.variant.CapturedComponentRuleValidator.validate(def);
        if (ruleError != null) {
            HudToast.show("保存失败：" + ruleError, true);
            return;
        }
        var saveOutcome = p.healthCoordinator.prepareForSave(p.client(), json);
        if (saveOutcome.blocked()) {
            HudToast.show(saveOutcome.blockToast(), true);
            return;
        }
        json = saveOutcome.json();
        def = saveOutcome.definition();
        if (saveOutcome.warnToast() != null) {
            HudToast.show(saveOutcome.warnToast());
        }

        // 生成缩略图（使用修复后的 def，如果 def 为 null 则重新解析）
        byte[] thumbnailPng = null;
        try {
            if (def == null) {
                def = JsonUtil.fromJson(json, ComponentDefinition.class);
            }
            thumbnailPng = p.thumbnailService.encodePng(def);
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to generate thumbnail", t);
        }

        // 等待服务端 ComponentSaveAck；catalog 仅刷新目录数据
        String componentName = st.name.trim();
        ComponentTool.INSTANCE.markSavePending(componentName);
        HudToast.show("正在保存构件「" + componentName + "」…");
        try { FormaCraftClientNetworking.sendSaveComponent(json, thumbnailPng); }
        catch (Exception failedSend) {
            com.formacraft.FormacraftMod.LOGGER.warn("Capture upload failed", failedSend);
            ComponentTool.INSTANCE.onSaveAckFromServer("", componentName, false, "保存构件失败：" + failedSend.getMessage());
        }
    }
}
