package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.UiTheme;

/** Shared layout constants for {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public final class SettingsPanelLayout {
    public static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
    public static final int BUTTON_HEIGHT = 16;
    public static final int BUTTON_GAP = 6;
    public static final int BUTTON_GAP_SMALL = 4;
    public static final int INPUT_HEIGHT = 16;
    public static final int LABEL_OFFSET = INPUT_HEIGHT + 2;
    /** 相邻字段组之间的垂直间距（控件行顶 → 下一 label 顶，与两行字段一致）。 */
    public static final int GROUP_GAP = 6;
    public static final int FIELD_SPACING = LABEL_OFFSET + GROUP_GAP;

    /** 分组标题 + 水平线 的上下留白 */
    public static final int SECTION_DIVIDER_PAD = 5;

    public static final int TITLE_HEIGHT = 20;
    public static final int BUTTON_ROW_HEIGHT = BUTTON_HEIGHT + 4;

    /** 两行字段（label + 控件）之后，下一组 label 的 Y。 */
    public static int afterTwoRowField(int labelY) {
        return labelY + LABEL_OFFSET + FIELD_SPACING;
    }

    /** 三行字段（label + 输入 + 按钮行）之后，下一组 label 的 Y。 */
    public static int afterThreeRowField(int labelY) {
        return labelY + LABEL_OFFSET * 2 + FIELD_SPACING;
    }

    /** 控件行顶边 rowY 之后，下一组 label 的 Y（与两行字段尾行一致）。 */
    public static int nextLabelAfterRow(int rowY) {
        return rowY + FIELD_SPACING;
    }

    /** 分组标题区总高度（供命中测试与 drawSectionHeader 对齐）。 */
    public static int sectionHeaderHeight(net.minecraft.client.MinecraftClient client) {
        return SECTION_DIVIDER_PAD + client.textRenderer.fontHeight + 2 + 1 + SECTION_DIVIDER_PAD;
    }

    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_GRAY = 0xFFAAAAAA;
    public static final int COLOR_TOAST_SUCCESS = 0x88FF88;
    public static final int COLOR_TOAST_ERROR = 0xFF8888;

    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 26;

    public static final int MIN_INTERACTION_REACH = 5;
    public static final int MAX_INTERACTION_REACH = 100;
    public static final int DEFAULT_INTERACTION_REACH = 80;

    public static final int SHOW_HIDE_BUTTON_WIDTH = 44;
    public static final int PASTE_BUTTON_WIDTH = 44;

    private SettingsPanelLayout() {}
}
