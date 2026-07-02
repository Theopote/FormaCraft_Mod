package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.common.component.health.HealthCheckResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 构件捕获面板健康检查区域的 UI 绘制。
 */
public final class ComponentCaptureHealthDrawer {
    private static final int LABEL_OFFSET = 18;

    private ComponentCaptureHealthDrawer() {
    }

    @FunctionalInterface
    public interface WrappedTextDrawer {
        int draw(DrawContext ctx, Text text, int x, int y, int maxWidth, int color);
    }

    public static int drawSection(
            DrawContext ctx,
            ComponentCaptureHealthCoordinator coordinator,
            WrappedTextDrawer textDrawer,
            int scrollY,
            int x,
            int y,
            int w,
            int mouseX,
            int mouseY,
            ButtonWidget autoFixButton,
            ButtonWidget undoAutoFixButton
    ) {
        coordinator.tickDebounce();
        var healthResult = coordinator.check();
        var counts = coordinator.count(healthResult);
        var st = ComponentTool.INSTANCE.getState();

        y += 4;
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

        String summaryText = "健康状态：";
        if (counts.ok() > 0) {
            summaryText += "✅ " + counts.ok() + "  ";
        }
        if (counts.warn() > 0) {
            summaryText += "⚠ " + counts.warn() + "  ";
        }
        if (counts.error() > 0) {
            summaryText += "⛔ " + counts.error() + "  ";
        }
        if (counts.ok() == 0 && counts.warn() == 0 && counts.error() == 0) {
            summaryText += "✅ 全部通过";
        }
        summaryText += "  （点击查看）";

        int summaryColor = counts.error() > 0 ? 0xFFFF5555 : (counts.warn() > 0 ? 0xFFFFAA00 : 0xFF55FF55);
        int summaryStartY = y;
        y = textDrawer.draw(ctx, Text.literal(summaryText), x, y, w, summaryColor);
        coordinator.setSummaryBounds(summaryStartY + scrollY, y + scrollY);

        if (counts.error() > 0) {
            ctx.fill(x, y, x + w, y + 1, 0x44FF5555);
            y += 2;
        }

        if (!coordinator.isDrawerExpanded()) {
            int chipCount = 0;
            for (var item : healthResult.getItems()) {
                if (chipCount >= 3) {
                    break;
                }
                if (item.level == HealthCheckResult.Level.ERROR
                        || (item.level == HealthCheckResult.Level.WARN && chipCount < 2)) {
                    String chipIcon = item.level == HealthCheckResult.Level.ERROR ? "⛔" : "⚠";
                    int chipColor = item.level == HealthCheckResult.Level.ERROR ? 0xFFFF5555 : 0xFFFFAA00;
                    y = textDrawer.draw(ctx, Text.literal(chipIcon + " " + item.title), x, y, w, chipColor);
                    chipCount++;
                }
            }
        } else {
            y += 2;
            if (healthResult.hasAutoFixable() || counts.error() > 0) {
                int buttonW = (w - 4) / 2;
                if (healthResult.hasAutoFixable()) {
                    autoFixButton.setPosition(x, y);
                    autoFixButton.setWidth(buttonW);
                    autoFixButton.visible = true;
                    autoFixButton.active = true;
                    autoFixButton.render(ctx, mouseX, mouseY, 0f);

                    undoAutoFixButton.setPosition(x + buttonW + 4, y);
                    undoAutoFixButton.setWidth(w - buttonW - 4);
                    undoAutoFixButton.visible = true;
                    undoAutoFixButton.active = st.captureDraft.snapshotBeforeAutoFix != null;
                    undoAutoFixButton.render(ctx, mouseX, mouseY, 0f);
                }
                y += LABEL_OFFSET;
            }

            if (st.captureDraft.lastAutoFixReport != null && !st.captureDraft.lastAutoFixReport.isEmpty()) {
                y = textDrawer.draw(ctx, Text.literal("最近自动修复："), x, y, w, 0xFF88CCFF);
                y += 2;
                int reportCount = 0;
                for (String fix : st.captureDraft.lastAutoFixReport) {
                    if (fix == null || fix.isBlank()) {
                        continue;
                    }
                    y = textDrawer.draw(ctx, Text.literal("- " + fix), x, y, w, 0xFFAAAAAA);
                    if (++reportCount >= 6) {
                        break;
                    }
                }
                y += 2;
            }

            for (var item : healthResult.getItems()) {
                if (item.level == HealthCheckResult.Level.OK) {
                    continue;
                }

                String icon = switch (item.level) {
                    case WARN -> "⚠";
                    case ERROR -> "⛔";
                    default -> "ℹ";
                };
                int color = switch (item.level) {
                    case WARN -> 0xFFFFAA00;
                    case ERROR -> 0xFFFF5555;
                    default -> 0xFF55FFFF;
                };

                if (item.fixAction == HealthCheckResult.FixAction.AUTO) {
                    icon += "✨";
                }
                if (item.ruleId.startsWith("H2-") && item.level == HealthCheckResult.Level.ERROR) {
                    icon += "🎯";
                }

                y = textDrawer.draw(ctx, Text.literal("[" + icon + "] " + item.title), x, y, w, color);
                if (!item.impact.isEmpty()) {
                    y = textDrawer.draw(ctx, Text.literal("  影响：" + item.impact), x, y, w, 0xFFFFAA00);
                }
                if (!item.fixSuggestion.isEmpty()) {
                    String suggestionIcon = item.fixAction == HealthCheckResult.FixAction.AUTO ? "🤖" : "💡";
                    y = textDrawer.draw(ctx, Text.literal("  建议：" + suggestionIcon + " " + item.fixSuggestion), x, y, w, 0xFF88CCFF);
                }
                y += 2;
            }
        }

        y += 4;
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;
        return y;
    }

    public static int drawStatusHealth(
            DrawContext ctx,
            MinecraftClient client,
            ComponentCaptureHealthCoordinator coordinator,
            int x,
            int y
    ) {
        TextRenderer textRenderer = client.textRenderer;
        var healthResult = coordinator.check();
        var counts = coordinator.count(healthResult);

        String healthText;
        int healthColor;
        if (counts.error() > 0) {
            healthText = String.format("ERR 健康: %d 个阻断项", counts.error());
            healthColor = 0xFFFF5555;
        } else if (counts.warn() > 0) {
            healthText = String.format("WARN 健康: %d 个风险项", counts.warn());
            healthColor = 0xFFFFAA00;
        } else {
            healthText = "OK 健康检查通过";
            healthColor = 0xFF55FF55;
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal(healthText), x, y, healthColor);
        y += textRenderer.fontHeight + 2;

        int shownCount = 0;
        for (var item : healthResult.getItems()) {
            if (shownCount >= 2) {
                break;
            }
            if (item.level == HealthCheckResult.Level.ERROR || item.level == HealthCheckResult.Level.WARN) {
                String icon = item.level == HealthCheckResult.Level.ERROR ? "ERR" : "WARN";
                int color = item.level == HealthCheckResult.Level.ERROR ? 0xFFFF5555 : 0xFFFFAA00;
                ctx.drawTextWithShadow(textRenderer, Text.literal("  " + icon + " " + item.title), x + 6, y, color);
                y += textRenderer.fontHeight + 1;
                shownCount++;
            }
        }
        return y;
    }

    public static void configureSaveButton(
            ButtonWidget saveButton,
            HealthCheckResult healthResult,
            boolean canSaveBase
    ) {
        boolean hasErrors = healthResult.hasErrors();
        boolean hasWarnings = healthResult.hasWarnings();
        boolean canSaveNow = canSaveBase && !hasErrors;

        long warnCount = healthResult.getItems().stream()
                .filter(item -> item.level == HealthCheckResult.Level.WARN)
                .count();
        long errorCount = healthResult.getItems().stream()
                .filter(item -> item.level == HealthCheckResult.Level.ERROR)
                .count();

        saveButton.active = canSaveNow;
        if (hasErrors) {
            saveButton.setMessage(Text.literal("💾 保存构件（需先解决 " + errorCount + " 个阻断项）"));
            saveButton.setTooltip(Tooltip.of(Text.literal("存在阻断项（⛔），请先修复后才能保存")));
        } else if (hasWarnings && canSaveNow) {
            saveButton.setMessage(Text.literal("💾 保存构件（建议先修复 " + warnCount + " 个风险项）"));
            saveButton.setTooltip(Tooltip.of(Text.literal("构件有 " + warnCount + " 个风险项，建议先修复但可以保存")));
        } else {
            saveButton.setMessage(Text.literal("💾 保存构件"));
            saveButton.setTooltip(Tooltip.of(Text.literal("""
                    保存构件到库
                    ━━━━━━━━━━━━
                    将构件保存到全局构件库

                    保存内容：
                    • 方块数据和结构
                    • 锚点和朝向
                    • 分类和标签
                    • 缩略图预览
                    • 连接位配置（如果有）

                    保存后自动跳转到构件库""")));
        }
    }
}
