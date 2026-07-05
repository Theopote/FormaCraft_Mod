package com.formacraft.client.ui.widget;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * HUD 模式下转发 {@link ClickableWidget} 的按下/释放事件。
 * <p>
 * Minecraft 1.21+ 的 {@link net.minecraft.client.gui.widget.PressableWidget}
 * 在 {@code mouseReleased} 时才调用 {@code onPress}；仅调用 {@code mouseClicked} 会有音效但不执行逻辑。
 */
public final class HudClickSupport {
    private static ClickableWidget pendingPressWidget = null;

    private HudClickSupport() {}

    public static boolean click(ClickableWidget widget, Click click) {
        if (widget == null || !widget.active || !widget.visible) return false;
        if (!widget.mouseClicked(click, false)) return false;
        pendingPressWidget = widget;
        return true;
    }

    /** @return true 表示已消费释放事件（含触发了 pending 控件的 onPress） */
    public static boolean release(Click click) {
        if (pendingPressWidget == null) return false;
        boolean handled = pendingPressWidget.mouseReleased(click);
        pendingPressWidget = null;
        return handled;
    }

    public static void clearPending() {
        pendingPressWidget = null;
    }
}
