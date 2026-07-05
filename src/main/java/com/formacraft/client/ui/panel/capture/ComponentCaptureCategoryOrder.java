package com.formacraft.client.ui.panel.capture;

import com.formacraft.common.component.ComponentCategory;

/**
 * 构件拾取面板常用的分类切换顺序（高频项在前）。
 */
public final class ComponentCaptureCategoryOrder {
    private static final ComponentCategory[] PICK_ORDER = {
            ComponentCategory.DOOR,
            ComponentCategory.WINDOW,
            ComponentCategory.BALCONY,
            ComponentCategory.RAILING,
            ComponentCategory.PANEL,
            ComponentCategory.COLUMN,
            ComponentCategory.STAIRS,
            ComponentCategory.ORNAMENT,
            ComponentCategory.ARCH,
            ComponentCategory.ROOF_DETAIL,
            ComponentCategory.BRACKET,
            ComponentCategory.GENERIC
    };

    private ComponentCaptureCategoryOrder() {}

    public static ComponentCategory next(ComponentCategory current) {
        if (current == null) {
            return PICK_ORDER[0];
        }
        for (int i = 0; i < PICK_ORDER.length; i++) {
            if (PICK_ORDER[i] == current) {
                return PICK_ORDER[(i + 1) % PICK_ORDER.length];
            }
        }
        return PICK_ORDER[0];
    }
}
