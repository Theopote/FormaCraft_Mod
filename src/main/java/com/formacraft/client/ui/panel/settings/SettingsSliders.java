package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * 设置面板的滑条（温度 / 交互距离）。
 * <p>
 * 从 {@code SettingsPanel} 的内部类拆出，仅通过 {@link SettingsPanelRenderHost} 回写草稿态，
 * 数值→草稿的换算逻辑保留在面板侧（{@code applyXxxFromSlider}）以复用其 clamp 常量。
 */
public final class SettingsSliders {
    private SettingsSliders() {}

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** HUD 面板内手动拖拽：与原版 SliderWidget 内边距一致（左右各 4px）。 */
    public abstract static class Base extends SliderWidget {
        private static final double HANDLE_PAD = 4.0;

        protected Base(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
        }

        /** 按鼠标 X 更新滑条（不依赖 Screen 级拖拽状态机）。 */
        public void dragWithMouse(double mouseX) {
            double range = Math.max(1.0, this.getWidth() - 2.0 * HANDLE_PAD);
            setCustomValue((mouseX - (this.getX() + HANDLE_PAD)) / range);
        }

        public void setCustomValue(double value) {
            this.value = clamp01(value);
            applyValue();
        }
    }

    /** 温度滑条：滑块内只显示数值，标题由上方 label 绘制。 */
    public static final class Temperature extends Base {
        private final SettingsPanelRenderHost host;

        public Temperature(SettingsPanelRenderHost host, int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            this.host = host;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String t = host.cachedTemperatureText();
            setMessage(Text.literal(t != null ? t : ""));
        }

        @Override
        protected void applyValue() {
            host.applyTemperatureFromSlider(this.value);
            updateMessage();
        }
    }

    public static final class InteractionReach extends Base {
        private final SettingsPanelRenderHost host;

        public InteractionReach(SettingsPanelRenderHost host, int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            this.host = host;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(host.draftInteractionReach())));
        }

        @Override
        protected void applyValue() {
            host.applyInteractionReachFromSlider(this.value);
            updateMessage();
        }
    }
}
