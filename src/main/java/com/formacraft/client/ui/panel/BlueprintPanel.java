package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.preview.BlueprintPreviewRenderer;
import com.formacraft.common.blueprint.BlueprintStorage;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图管理面板
 * 用于浏览、加载、删除已保存的蓝图
 */
public class BlueprintPanel extends BasePanel {

    private final MinecraftClient client = MinecraftClient.getInstance();

    private String searchText = "";
    private int scrollOffset = 0;

    // 原版风格按钮（渲染用，与 SettingsPanel 保持一致）
    private ButtonWidget loadButton;
    private ButtonWidget deleteButton;

    private static class Entry {
        String name;
        BuildingSpec spec;
    }

    private final List<Entry> entries = new ArrayList<>();

    public interface Listener {
        void onLoadBlueprint(BuildingSpec spec, String name);
    }

    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public BlueprintPanel() {
        BlueprintStorage.loadAll();
        reloadEntries();
        initWidgets();
    }

    private void initWidgets() {
        if (loadButton != null) return;
        // 这里按钮仅用于渲染“原版样式”，点击逻辑仍由 mouseClicked 按条目索引处理
        loadButton = ButtonWidget.builder(Text.literal("Load"), b -> {})
                .dimensions(0, 0, 38, 11)
                .build();
        deleteButton = ButtonWidget.builder(Text.literal("Del"), b -> {})
                .dimensions(0, 0, 38, 11)
                .build();
    }

    /**
     * 重新加载蓝图列表
     */
    public void reloadEntries() {
        entries.clear();
        for (String name : BlueprintStorage.listNames()) {
            Entry e = new Entry();
            e.name = name;
            e.spec = BlueprintStorage.get(name);
            entries.add(e);
        }
    }

    // ======================================================
    // 渲染
    // ======================================================

    @Override
    protected void drawContents(DrawContext ctx) {
        int x = panelX + 10;
        int y = getContentY() + 10;
        int w = panelWidth - 20;

        ctx.drawText(client.textRenderer,
                Text.literal("Blueprint Library"),
                x, y, 0xFFFFFF, false);
        y += 20;

        drawSearchBox(ctx, x, y, w);
        y += 22;

        drawBlueprintList(ctx, x, y, w);
    }

    // ======================================================
    // 搜索框
    // ======================================================

    private void drawSearchBox(DrawContext ctx, int x, int y, int w) {
        ctx.drawText(client.textRenderer, Text.literal("Search:"), x, y - 12, 0xAAAAAA, false);
        
        ctx.fill(x, y, x + w, y + 16, 0xFF222222);
        
        String displayText = searchText.isEmpty() ? "输入搜索关键词..." : searchText;
        int textColor = searchText.isEmpty() ? 0x888888 : 0xCCCCCC;
        ctx.drawText(client.textRenderer, displayText, x + 4, y + 4, textColor, false);
    }

    // ======================================================
    // 列表区域
    // ======================================================

    private void drawBlueprintList(DrawContext ctx, int x, int y, int w) {
        int entryHeight = 72; // 增加条目高度以容纳预览图
        int areaHeight = panelHeight - (y - panelY) - 16;
        int maxVisible = Math.max(1, areaHeight / entryHeight);

        // 过滤
        List<Entry> filtered = getFilteredEntries();

        int total = filtered.size();
        int start = Math.max(0, scrollOffset);
        int end = Math.min(total, start + maxVisible);

        int drawY = y;

        for (int i = start; i < end; i++) {
            Entry e = filtered.get(i);
            drawEntry(ctx, x, drawY, w, entryHeight, e);
            drawY += entryHeight;
        }

        // 如果没有蓝图，显示提示
        if (filtered.isEmpty()) {
            String hint = searchText.isEmpty() 
                ? "暂无蓝图，在 ChatPanel 中保存蓝图后这里会显示" 
                : "未找到匹配的蓝图";
            ctx.drawText(client.textRenderer, 
                    Text.literal(hint), 
                    x + 8, y + 10, 0x888888, false);
        }
    }

    /**
     * 获取过滤后的蓝图列表
     */
    private List<Entry> getFilteredEntries() {
        List<Entry> filtered = new ArrayList<>();
        String searchLower = searchText.toLowerCase();
        
        for (Entry e : entries) {
            if (searchText.isEmpty() || e.name.toLowerCase().contains(searchLower)) {
                filtered.add(e);
            }
        }
        
        return filtered;
    }

    /**
     * 绘制单个蓝图条目
     */
    private void drawEntry(DrawContext ctx, int x, int y, int w, int h, Entry e) {
        // 背景
        ctx.fill(x, y, x + w, y + h, 0x33222222);

        // 预览图区域（左侧 64x64）
        int previewSize = 64;
        int previewX = x + 4;
        int previewY = y + 4;
        BlueprintPreviewRenderer.drawPreview(ctx, e.spec, previewX, previewY, previewSize, previewSize);

        // 文本信息区域（预览图右侧）
        int textX = x + previewSize + 12;
        int textY = y + 6;

        // 蓝图名称
        ctx.drawText(client.textRenderer,
                Text.literal(e.name),
                textX, textY, 0xFFFFFF, false);
        textY += 12;

        // Spec 简要信息
        if (e.spec != null) {
            String typeInfo = "Type: " + (e.spec.getType() != null ? e.spec.getType().name() : "Unknown");
            ctx.drawText(client.textRenderer,
                    Text.literal(typeInfo),
                    textX, textY, 0xAAAAAA, false);
            textY += 12;

            // 显示高度信息
            if (e.spec.getHeight() > 0) {
                ctx.drawText(client.textRenderer,
                        Text.literal("Height: " + e.spec.getHeight()),
                        textX, textY, 0x888888, false);
            }
        }

        initWidgets();
        // 检查鼠标位置（用于悬停效果，使用正确的缩放因子）
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        
        // 加载按钮
        int loadW = 38;
        int loadX = x + w - loadW - 6;
        int loadY = y + 5;
        loadButton.setPosition(loadX, loadY);
        loadButton.setWidth(loadW);
        loadButton.visible = true;
        loadButton.active = (e.spec != null);
        loadButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);

        // 删除按钮
        int delW = 38;
        int delX = x + w - delW - 6;
        int delY = y + 18;
        deleteButton.setPosition(delX, delY);
        deleteButton.setWidth(delW);
        deleteButton.visible = true;
        deleteButton.active = true;
        deleteButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
    }

    // ======================================================
    // 处理输入
    // ======================================================

    @Override
    public void charTyped(char chr) {
        if (chr >= 32 && chr <= 126) {
            searchText += chr;
            scrollOffset = 0; // 搜索时重置滚动
        }
    }

    @Override
    public void keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (!searchText.isEmpty()) {
                searchText = searchText.substring(0, searchText.length() - 1);
                scrollOffset = 0; // 搜索时重置滚动
            }
        }
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        // 只在面板区域内滚动
        if (mouseX >= panelX && mouseX <= panelX + panelWidth) {
            List<Entry> filtered = getFilteredEntries();
            int maxOffset = Math.max(0, filtered.size() - 1);
            
            if (amount > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (amount < 0) {
                scrollOffset = Math.min(maxOffset, scrollOffset + 1);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // 处理顶部 Tab 切换

        if (button != 0) return false;

        int x = panelX + 10;
        int y = getContentY() + 10 + 20 + 22; // 标题 + 搜索框
        int w = panelWidth - 20;
        int entryHeight = 72; // 与渲染时保持一致

        // 获取过滤后的列表
        List<Entry> filtered = getFilteredEntries();
        if (filtered.isEmpty()) return false;

        // 计算点击的条目索引
        int relativeY = (int)(mouseY - y);
        if (relativeY < 0) return false;

        int index = (relativeY / entryHeight) + scrollOffset;
        if (index < 0 || index >= filtered.size()) return false;

        Entry e = filtered.get(index);

        // 计算条目在屏幕上的实际位置
        int entryScreenY = y + (index - scrollOffset) * entryHeight;
        int localY = (int)(mouseY - entryScreenY);

        // 点击 Load 按钮
        int loadW = 38;
        int loadX = x + w - loadW - 6;
        int loadH = 11;
        if (mouseX >= loadX && mouseX <= loadX + loadW &&
            localY >= 5 && localY < 5 + loadH) {
            if (listener != null && e.spec != null) {
                listener.onLoadBlueprint(e.spec, e.name);
            }
            return true;
        }

        // 点击 Delete 按钮
        int delW = 38;
        int delX = x + w - delW - 6;
        int delH = 11;
        if (mouseX >= delX && mouseX <= delX + delW &&
            localY >= 18 && localY < 18 + delH) {
            BlueprintStorage.delete(e.name);
            reloadEntries();
            scrollOffset = 0; // 删除后重置滚动
            return true;
        }

        return false;
    }
}
