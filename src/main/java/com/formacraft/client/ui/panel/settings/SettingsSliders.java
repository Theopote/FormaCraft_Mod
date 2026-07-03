package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * 设置面板的三个滑条（温度 / 字体大小 / 交互距离）。
 * <p>
 * 从 {@code SettingsPanel} 的内部类拆出，仅通过 {@link SettingsPanelRenderHost} 回写草稿态，
 * 数值→草稿的换算逻辑保留在面板侧（{@code applyXxxFromSlider}）以复用其 clamp 常量。
 */
public final class SettingsSliders {
    private SettingsSliders() {}

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 温度滑条：滑块内只显示数值，标题由上方 label 绘制。 */
    public static final class Temperature extends SliderWidget {
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

        public void setCustomValue(double value) {
            this.value = clamp01(value);
            applyValue();
        }
    }

    public static final class FontSize extends SliderWidget {
        private final SettingsPanelRenderHost host;

        public FontSize(SettingsPanelRenderHost host, int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
            this.host = host;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.valueOf(host.draftFontSize())));
        }

        @Override
        protected void applyValue() {
            host.applyFontSizeFromSlider(this.value);
            updateMessage();
        }

        public void setCustomValue(double value) {
            this.value = clamp01(value);
            applyValue();
        }
    }

    public static final class InteractionReach extends SliderWidget {
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

        public void setCustomValue(double value) {
            this.value = clamp01(value);
            applyValue();
        }
    }
}
