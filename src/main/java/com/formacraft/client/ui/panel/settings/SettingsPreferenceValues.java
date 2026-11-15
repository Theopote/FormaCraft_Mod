package com.formacraft.client.ui.panel.settings;

/** Strict validation for typed preferences; invalid text stays in the draft. */
public record SettingsPreferenceValues(int reach, float temperature) {
    public static SettingsPreferenceValues parse(String reach, String temperature) {
        final int r;
        final float t;
        try { r = Integer.parseInt(reach.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("交互距离须为 5–100 的整数"); }
        if (r < SettingsPanelLayout.MIN_INTERACTION_REACH || r > SettingsPanelLayout.MAX_INTERACTION_REACH)
            throw new IllegalArgumentException("交互距离须为 5–100 的整数");
        try { t = Float.parseFloat(temperature.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("模型温度须为 0–1 的数字"); }
        if (!Float.isFinite(t) || t < 0 || t > 1) throw new IllegalArgumentException("模型温度须为 0–1 的数字");
        return new SettingsPreferenceValues(r,t);
    }
}
