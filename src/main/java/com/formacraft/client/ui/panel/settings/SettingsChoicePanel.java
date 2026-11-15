package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/** A bounded HUD chooser. Search and free-form values are deliberately separate. */
public final class SettingsChoicePanel {
    public record Option(String value, String label) {}
    private final HudTextInput search = new HudTextInput();
    private final HudTextInput custom = new HudTextInput();
    private final SettingsChoiceList list = new SettingsChoiceList();
    private Consumer<String> select;
    private String title, selected;
    private boolean editable;
    private int x, y, width, height, rows, listTop, footer;
    private static final int ROW = 20;

    public boolean isOpen() { return select != null; }
    public boolean isEditingValue() { return isOpen() && editable; }
    public void updateOptions(List<Option> options) { list.setOptions(options); }
    public void close() { select = null; search.setFocused(false); custom.setFocused(false); }

    public void open(String title, List<Option> options, String selected, boolean editable, Consumer<String> select) {
        this.title = title;
        list.reset(options);
        this.selected = selected;
        this.editable = editable;
        this.select = select;
        search.setText("");
        search.setPlaceholder("搜索选项…");
        custom.setMaxLength(128);
        custom.setText(selected);
        custom.setPlaceholder("输入模型 ID；留空为自动");
        custom.setFocused(false);
        search.setFocused(true);
    }

    private List<Option> visibleItems() {
        list.search(search.getText());
        return list.visible();
    }

    public void render(DrawContext ctx, MinecraftClient client, int right, int top) {
        if (!isOpen()) return;
        int sw = client.getWindow().getScaledWidth(), sh = client.getWindow().getScaledHeight();
        width = Math.min(300, Math.max(1, sw - 8));
        x = right + 4 + width <= sw ? right + 4 : Math.max(4, sw - width - 4);
        y = Math.min(top, Math.max(4, sh - 140));
        height = Math.max(1, Math.min(400, sh - y - 4));
        footer = y + height - (editable ? 66 : 26);
        listTop = y + 48;
        rows = Math.max(0, (footer - listTop) / ROW);
        list.viewport(rows);
        var items = visibleItems();
        ctx.fill(x, y, x + width, y + height, 0xFF171B24);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(title), x + 6, y + 6, 0xFFFFFFFF);
        label(ctx, client, "×", x + width - 18, y + 6);
        search.render(ctx, x + 6, y + 24, width - 12, 16);
        int mx = (int) (client.mouse.getX() / client.getWindow().getScaleFactor());
        int my = (int) (client.mouse.getY() / client.getWindow().getScaleFactor());
        for (int i = 0; i < rows && i < items.size(); i++) {
            Option item = items.get(i);
            int ry = listTop + i * ROW;
            boolean hover = mx >= x + 4 && mx < x + width - 4 && my >= ry && my < ry + ROW;
            ctx.fill(x + 4, ry, x + width - 4, ry + ROW - 1, hover ? 0xFF3C4960 : 0xFF252D3A);
            String text = (item.value().equals(selected) ? "✓ " : "") + item.label();
            label(ctx, client, client.textRenderer.trimToWidth(text, width - 16), x + 8, ry + 5);
        }
        if (items.isEmpty()) label(ctx, client, "没有匹配项" + (editable ? "，可在下方手动输入" : ""), x + 6, listTop + 4);
        label(ctx, client, list.size() + " 项 · 滚轮浏览", x + 6, footer);
        if (editable) {
            custom.render(ctx, x + 6, footer + 16, width - 12, 16);
            ctx.fill(x + 6, footer + 36, x + width - 6, footer + 54, 0xFF35445A);
            label(ctx, client, "使用输入值（留空 = 自动）", x + 10, footer + 41);
        }
    }

    private static void label(DrawContext ctx, MinecraftClient client, String text, int x, int y) {
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(text), x, y, 0xFFDDDDDD);
    }

    public boolean contains(double mx, double my) {
        return isOpen() && mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public String hoveredLabel(double mx, double my) {
        if (!contains(mx, my) || my < listTop) return null;
        int row = (int) ((my - listTop) / ROW);
        var items = visibleItems();
        return row < rows && row < items.size() ? items.get(row).label() : null;
    }

    public void click(double mx, double my, int button) {
        if (button != 0) return;
        if (!contains(mx, my) || (mx >= x + width - 24 && my < y + 22)) { close(); return; }
        if (search.mouseClicked(mx, my, x + 6, y + 24, width - 12, 16)) {
            custom.setFocused(false); return;
        }
        if (editable && custom.mouseClicked(mx, my, x + 6, footer + 16, width - 12, 16)) return;
        if (editable && my >= footer + 36 && my < footer + 54) { choose(custom.getText().trim()); return; }
        int row = (int) ((my - listTop) / ROW);
        var items = visibleItems();
        if (my >= listTop && row >= 0 && row < rows && row < items.size()) choose(items.get(row).value());
    }

    private void choose(String value) {
        Consumer<String> action = select;
        close();
        action.accept(value);
    }

    public void scroll(double amount) {
        list.search(search.getText());
        list.scroll(-(int) Math.signum(amount) * 3);
    }

    public void charTyped(char c) { search.charTyped(c); custom.charTyped(c); }
    public void keyPressed(int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return; }
        if (key == GLFW.GLFW_KEY_ENTER && editable && custom.isFocused()) { choose(custom.getText().trim()); return; }
        if (key == GLFW.GLFW_KEY_TAB && editable) {
            boolean wasSearch = search.isFocused();
            search.setFocused(!wasSearch); custom.setFocused(wasSearch); return;
        }
        search.keyPressed(key, modifiers); custom.keyPressed(key, modifiers);
    }
}
