package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.tool.ComponentDraftAutoFix;
import com.formacraft.client.tool.ComponentDraftHealthChecker;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.health.ComponentHealthAutoFix;
import com.formacraft.common.component.health.ComponentHealthChecker;
import com.formacraft.common.component.health.HealthCheckResult;
import com.formacraft.common.component.validate.ComponentValidator;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * 构件捕获面板的健康检查、自动修复与保存前校验。
 */
public final class ComponentCaptureHealthCoordinator {
    private static final boolean DEBUG = false;
    private static final long CHECK_DEBOUNCE_MS = 200;

    private final ComponentCaptureSelectionController selectionController;

    private boolean drawerExpanded;
    private long lastCheckTime;
    private int summaryStartY = -1;
    private int summaryEndY = -1;

    public ComponentCaptureHealthCoordinator(ComponentCaptureSelectionController selectionController) {
        this.selectionController = selectionController;
    }

    public boolean isDrawerExpanded() {
        return drawerExpanded;
    }

    public void setDrawerExpanded(boolean drawerExpanded) {
        this.drawerExpanded = drawerExpanded;
    }

    public int getSummaryStartY() {
        return summaryStartY;
    }

    public int getSummaryEndY() {
        return summaryEndY;
    }

    public void setSummaryBounds(int startY, int endY) {
        this.summaryStartY = startY;
        this.summaryEndY = endY;
    }

    public boolean handleSummaryClick(double mouseY, int panelY, int scrollY) {
        if (summaryStartY <= 0 || summaryEndY <= 0) {
            return false;
        }
        int actualStartY = panelY + summaryStartY - scrollY;
        int actualEndY = panelY + summaryEndY - scrollY;
        if (mouseY >= actualStartY && mouseY <= actualEndY) {
            drawerExpanded = !drawerExpanded;
            return true;
        }
        return false;
    }

    public void tickDebounce() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime > CHECK_DEBOUNCE_MS) {
            lastCheckTime = now;
        }
    }

    public HealthCheckResult check() {
        var st = ComponentTool.INSTANCE.getState();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        var result = ComponentDraftHealthChecker.check(st.captureDraft, st, min, max);
        st.captureDraft.lastHealth = result;
        return result;
    }

    public HealthCounts count(HealthCheckResult result) {
        int ok = 0;
        int warn = 0;
        int error = 0;
        for (var item : result.getItems()) {
            switch (item.level) {
                case OK -> ok++;
                case WARN -> warn++;
                case ERROR -> error++;
            }
        }
        return new HealthCounts(ok, warn, error);
    }

    public boolean blocksSave() {
        return check().hasErrors();
    }

    public void runAutoFix(MinecraftClient client) {
        if (client == null || client.world == null) {
            HudToast.show("无法修复：世界未加载", true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        st.captureDraft.snapshotBeforeAutoFix = st.captureDraft.copy();
        var draftFix = ComponentDraftAutoFix.apply(st.captureDraft, st, min, max);
        if (!draftFix.isEmpty()) {
            st.captureDraft.lastAutoFixReport = draftFix.getFixes();
            st.captureDraft.dirty = true;
            st.captureDraft.updatePhase();
            st.syncDraftToState();
            HudToast.show("完成: 已自动修复 " + draftFix.size() + " 个问题");
            return;
        }
        st.captureDraft.snapshotBeforeAutoFix = null;
        st.captureDraft.lastAutoFixReport = null;

        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client, st.captureDraft);
        if (json == null || json.isBlank()) {
            HudToast.show("无法修复：请先选择构件方块", true);
            return;
        }

        try {
            var def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def == null) {
                HudToast.show("无法修复：构件定义无效", true);
                return;
            }

            var healthResult = ComponentHealthChecker.check(def);
            var fixReport = ComponentHealthAutoFix.apply(def, healthResult);
            if (fixReport.isEmpty()) {
                HudToast.show("没有可自动修复的问题");
                return;
            }

            st.captureDraft.snapshotBeforeAutoFix = st.captureDraft.copy();
            applyDefinitionFixToDraft(def);
            st.captureDraft.lastAutoFixReport = fixReport.getFixes();
            st.captureDraft.dirty = true;
            st.captureDraft.updatePhase();
            st.syncDraftToState();

            if (DEBUG) {
                for (String fix : fixReport.getFixes()) {
                    FormacraftMod.LOGGER.debug("[HealthCoordinator] auto-fix: {}", fix);
                }
            }
            HudToast.show("完成: 已自动修复 " + fixReport.size() + " 个问题");
        } catch (Throwable t) {
            FormacraftMod.LOGGER.error("[HealthCoordinator] auto-fix failed", t);
            HudToast.show("自动修复失败: " + t.getMessage(), true);
        }
    }

    public void undoAutoFix() {
        var st = ComponentTool.INSTANCE.getState();
        if (st.captureDraft.snapshotBeforeAutoFix == null) {
            HudToast.show("没有可撤销的修复", true);
            return;
        }
        st.captureDraft.copyFrom(st.captureDraft.snapshotBeforeAutoFix);
        st.captureDraft.snapshotBeforeAutoFix = null;
        st.captureDraft.lastAutoFixReport = null;
        st.captureDraft.dirty = true;
        st.captureDraft.updatePhase();
        st.syncDraftToState();
        HudToast.show("已撤销自动修复");
    }

    public SavePrepareOutcome prepareForSave(MinecraftClient client, String json) {
        if (json == null || json.isBlank()) {
            return SavePrepareOutcome.blocked(json, null, "保存失败：请检查选区");
        }

        ComponentDefinition def = null;
        try {
            def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def == null) {
                return SavePrepareOutcome.continueWith(json, null, null);
            }

            var healthResult = ComponentHealthChecker.check(def);
            if (healthResult.hasErrors()) {
                int errorCount = (int) healthResult.getItems().stream()
                        .filter(item -> item.level == HealthCheckResult.Level.ERROR)
                        .count();
                return SavePrepareOutcome.blocked(json, def,
                        "保存失败：存在 " + errorCount + " 个阻断项，请先修复");
            }

            var fixReport = ComponentHealthAutoFix.apply(def, healthResult);
            if (!fixReport.isEmpty()) {
                FormacraftMod.LOGGER.debug("[HealthCoordinator] pre-save auto-fix: {} items", fixReport.size());
                for (var fix : fixReport.getFixes()) {
                    FormacraftMod.LOGGER.debug("  {}", fix);
                }
                json = JsonUtil.toJson(def);
                healthResult = ComponentHealthChecker.check(def);
            }

            String warnToast = null;
            if (healthResult.hasWarnings()) {
                int warnCount = (int) healthResult.getItems().stream()
                        .filter(item -> item.level == HealthCheckResult.Level.WARN)
                        .count();
                warnToast = "警告：构件有 " + warnCount + " 个风险项，但仍将保存";
            }

            var validationResult = ComponentValidator.validate(def);
            if (validationResult.hasErrors()) {
                FormacraftMod.LOGGER.warn("[HealthCoordinator] structural validation errors: {}",
                        validationResult.errors().size());
            }

            return SavePrepareOutcome.continueWith(json, def, warnToast);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.error("[HealthCoordinator] pre-save health check failed", t);
            return SavePrepareOutcome.continueWith(json, def, null);
        }
    }

    private void applyDefinitionFixToDraft(ComponentDefinition def) {
        var st = ComponentTool.INSTANCE.getState();
        if (def.anchor == null) {
            return;
        }

        BlockPos min = selectionController.getSelectionMin();
        if (min == null) {
            min = getSelectionMinFromDraft();
        }
        if (min == null) {
            return;
        }

        int worldX = min.getX() + def.anchor.dx;
        int worldY = min.getY() + def.anchor.dy;
        int worldZ = min.getZ() + def.anchor.dz;
        st.captureDraft.anchor.worldPos = new BlockPos(worldX, worldY, worldZ);
        st.syncDraftToState();
        if (DEBUG) {
            FormacraftMod.LOGGER.debug("[HealthCoordinator] anchor updated: {}", st.captureDraft.anchor.worldPos);
        }
    }

    private BlockPos getSelectionMinFromDraft() {
        var st = ComponentTool.INSTANCE.getState();
        if (!st.captureDraft.hasExplicitSelection()) {
            return SelectionTool.INSTANCE.getMin();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos pos : st.captureDraft.selection.blocks) {
            if (pos == null) {
                continue;
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }
        return minX == Integer.MAX_VALUE ? null : new BlockPos(minX, minY, minZ);
    }

    public record HealthCounts(int ok, int warn, int error) {
    }

    public record SavePrepareOutcome(
            String json,
            ComponentDefinition definition,
            boolean blocked,
            String blockToast,
            String warnToast
    ) {
        public static SavePrepareOutcome blocked(String json, ComponentDefinition def, String blockToast) {
            return new SavePrepareOutcome(json, def, true, blockToast, null);
        }

        public static SavePrepareOutcome continueWith(String json, ComponentDefinition def, String warnToast) {
            return new SavePrepareOutcome(json, def, false, null, warnToast);
        }
    }
}
