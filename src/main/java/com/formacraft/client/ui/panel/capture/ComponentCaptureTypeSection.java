package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.*;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Type-first capture UI, with an open-ended custom label and independently chosen behavior base. */
public final class ComponentCaptureTypeSection {
    private final Runnable changed;
    private final HudTextInput customName = new HudTextInput();
    private final List<ButtonWidget> options = new ArrayList<>();
    private final ButtonWidget toggle, base, reset;
    private boolean expanded, inputVisible;
    private int inputX, inputY, inputW, clipTop, clipBottom;
    public ComponentCaptureTypeSection(Runnable changed) {
        this.changed = changed; customName.setMaxLength(64); customName.setPlaceholder("例如：飞檐挂落、组合花架");
        toggle = button("先选择捕获类型", () -> expanded = !expanded);
        for (var preset : ComponentTypes.PRESETS) options.add(button(preset.type().label(), () -> {
            ComponentCaptureTypes.select(state(), preset); expanded = false; changed.run();
        }));
        options.add(button("自定义类型…", () -> { ComponentCaptureTypes.custom(state()); expanded = false; customName.setText(""); changed.run(); }));
        base = button("行为基础", () -> {
            var categories = ComponentCategory.values(); var current = state().category;
            ComponentCaptureTypes.customBase(state(), categories[(current.ordinal() + 1) % categories.length]); changed.run();
        });
        reset = button("恢复类型建议", () -> { ComponentCaptureTypes.resetSuggestions(state()); changed.run(); });
    }
    private ComponentToolState state() { return ComponentTool.INSTANCE.getState(); }
    private static ButtonWidget button(String name, Runnable action) {
        return ButtonWidget.builder(Text.literal(name), b -> action.run()).dimensions(0, 0, 0, 16).build();
    }
    public void open() { expanded = true; }
    public int draw(DrawContext ctx, MinecraftClient client, ComponentCaptureHealthDrawer.WrappedTextDrawer text,
                    int mouseX, int mouseY, int x, int y, int w, int top, int bottom) {
        clipTop = top; clipBottom = bottom; inputVisible = false;
        options.forEach(b -> b.visible = false); base.visible = reset.visible = false;
        var st = state(); boolean pending = st.isCaptureSavePending();
        y = text.draw(ctx, Text.literal("捕获类型"), x, y, w, 0xFFFFFFFF);
        String label = !st.captureTypeChosen ? "先选择捕获类型" : st.customTypeMode
                ? "自定义：" + (st.type == null ? "待命名" : st.type.label()) : st.type.label();
        toggle.setMessage(Text.literal(label + (st.captureTypeChosen ? "（更改）" : "")));
        drawButton(ctx, toggle, x, y + 2, w, mouseX, mouseY, !pending); y += 20;
        if (pending) { customName.setFocused(false); return y; }
        if (!st.captureTypeChosen || expanded) {
            int columns = Math.max(1, Math.min(3, w / 64)); int cell = w / columns;
            for (int i = 0; i < options.size(); i++)
                drawButton(ctx, options.get(i), x + i % columns * cell, y + i / columns * 19, cell - 3, mouseX, mouseY, true);
            y += ((options.size() + columns - 1) / columns) * 19;
        }
        if (st.captureTypeChosen && st.customTypeMode) {
            y = text.draw(ctx, Text.literal("自定义类型名称"), x, y, w, 0xFFCCCCCC);
            if (!customName.isFocused() && !Objects.equals(customName.getText(), st.customTypeName)) customName.setText(st.customTypeName);
            inputX = x; inputY = y + 2; inputW = w; inputVisible = true;
            customName.render(ctx, x, inputY, w, 14); y += 21;
            if (!Objects.equals(customName.getText(), st.customTypeName)) {
                ComponentCaptureTypes.customName(st, customName.getText()); changed.run();
            }
            base.setMessage(Text.literal("行为基础：" + ComponentTypes.base(st.category).type().label()));
            drawButton(ctx, base, x, y, w, mouseX, mouseY, true); y += 19;
            if (!ComponentCaptureTypes.ready(st)) y = text.draw(ctx, Text.literal("填写类型名称后即可开始捕获"), x, y, w, 0xFFFFCC77);
        }
        if (ComponentCaptureTypes.ready(st)) {
            y = text.draw(ctx, Text.literal("类型用于检索；安装方式与变化权限可单独调整"), x, y, w, 0xFFAAAAAA);
            drawButton(ctx, reset, x, y + 2, w, mouseX, mouseY, true); y += 20;
        }
        if (!inputVisible) customName.setFocused(false);
        return y + 5;
    }
    private static void drawButton(DrawContext ctx, ButtonWidget b, int x, int y, int w, int mx, int my, boolean active) {
        b.setPosition(x, y); b.setWidth(w); b.visible = true; b.active = active; b.render(ctx, mx, my, 0f);
    }
    public boolean click(double x, double y, Click click) {
        if (y < clipTop || y >= clipBottom) { customName.setFocused(false); return false; }
        if (inputVisible && customName.mouseClicked(x, y, inputX, inputY, inputW, 14)) return true;
        customName.setFocused(false);
        if (toggle.visible && HudClickSupport.click(toggle, click)) return true;
        for (var b : options) if (b.visible && HudClickSupport.click(b, click)) return true;
        return base.visible && HudClickSupport.click(base, click) || reset.visible && HudClickSupport.click(reset, click);
    }
    public void keyPressed(int key, int modifiers) { if (inputVisible) customName.keyPressed(key, modifiers); }
    public void charTyped(char value) { if (inputVisible) customName.charTyped(value); }
    public boolean focused() { return inputVisible && customName.isFocused(); }
    public void unfocus() { customName.setFocused(false); }
}
