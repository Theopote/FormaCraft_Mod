package com.formacraft.client.ui.panel;

import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.component.ComponentThumbnailCache;
import com.formacraft.client.component.ComponentLibraryUsage;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentCategory;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 构件库面板（v1）：
 * - 独立于 ToolPanel，提供缩略图网格浏览
 * - 搜索（id/name/tags）
 * - 排序（最近保存 / 最近加载 / 名字 / 分类 / tags命中优先）
 * - 双击卡片：直接加载该构件（省一次“加载构件”按钮）
 */
public final class ComponentLibraryPanel extends BasePanel {
    private static final int CONTENT_PADDING = 10;
    private static final int LABEL_OFFSET = 18;
    private static final int BUTTON_HEIGHT = 16;

    private final HudTextInput searchInput = new HudTextInput();

    private ButtonWidget sortButton;
    private ButtonWidget categoryButton;
    private ButtonWidget prevPageButton;
    private ButtonWidget nextPageButton;

    // grid hitboxes
    private static final class GridItem {
        final String id;
        final String name;
        final int x, y, w, h;
        GridItem(String id, String name, int x, int y, int w, int h) {
            this.id = id; this.name = name; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        boolean hit(double mx, double my) {
            return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
        }
    }
    private final List<GridItem> grid = new ArrayList<>();
    private boolean gridValid = false;

    // search input bounds
    private int searchX, searchY, searchW, searchH;
    private boolean searchBoundsValid = false;

    // double-click
    private long lastClickMs = 0L;
    private String lastClickId = null;
    private static final long DOUBLE_CLICK_MS = 280L;

    public ComponentLibraryPanel() {
        searchInput.setMaxLength(64);
    }

    private void ensureWidgets() {
        if (sortButton != null) return;

        sortButton = ButtonWidget.builder(Text.literal("排序：最近"), b -> cycleSort())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换排序：最近保存 / 最近加载 / 标签命中 / 名字 / 分类")))
                .build();
        categoryButton = ButtonWidget.builder(Text.literal("分类：ALL"), b -> cycleCategory())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换分类过滤")))
                .build();
        prevPageButton = ButtonWidget.builder(Text.literal("上一页"), b -> {
                    var st = ComponentTool.INSTANCE.getState();
                    st.libraryPage = Math.max(0, st.libraryPage - 1);
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .build();
        nextPageButton = ButtonWidget.builder(Text.literal("下一页"), b -> {
                    var st = ComponentTool.INSTANCE.getState();
                    st.libraryPage = st.libraryPage + 1;
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .build();
    }

    private void cycleSort() {
        var st = ComponentTool.INSTANCE.getState();
        String cur = st.librarySort != null ? st.librarySort : "RECENT";
        st.librarySort = switch (cur.toUpperCase(Locale.ROOT)) {
            case "RECENT" -> "LOADED";
            case "LOADED" -> "TAG_MATCH";
            case "TAG_MATCH" -> "NAME";
            case "NAME" -> "CATEGORY";
            default -> "RECENT";
        };
        st.libraryPage = 0;
    }

    private void cycleCategory() {
        var st = ComponentTool.INSTANCE.getState();
        ComponentCategory[] vals = ComponentCategory.values();
        // null = ALL
        if (st.libraryFilterCategory == null) {
            st.libraryFilterCategory = vals.length > 0 ? vals[0] : ComponentCategory.GENERIC;
            st.libraryPage = 0;
            return;
        }
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == st.libraryFilterCategory) { idx = i; break; }
        }
        if (idx >= vals.length - 1) {
            st.libraryFilterCategory = null;
        } else {
            st.libraryFilterCategory = vals[idx + 1];
        }
        st.libraryPage = 0;
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;
        int y = getContentY() + CONTENT_PADDING;

        // reset hitboxes
        grid.clear();
        gridValid = false;
        searchBoundsValid = false;

        // background
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        y = drawWrappedText(ctx, Text.literal("[ 构件库 ]"), x, y, w, 0xFFFFFFFF);
        y += 2;

        // search input (bind to ComponentToolState)
        var st = ComponentTool.INSTANCE.getState();
        String curSearch = st.librarySearch != null ? st.librarySearch : "";
        if (!searchInput.isFocused() && !curSearch.equals(searchInput.getText())) {
            searchInput.setText(curSearch);
        }

        ctx.drawTextWithShadow(client.textRenderer, Text.literal("搜索："), x, y, 0xFFAAAAAA);
        int inY = y + LABEL_OFFSET - 2;
        searchInput.render(ctx, x, inY, w, 14);
        searchX = x; searchY = inY; searchW = w; searchH = 14;
        searchBoundsValid = true;
        String newSearch = searchInput.getText();
        if (newSearch == null) newSearch = "";
        if (!newSearch.equals(st.librarySearch)) {
            st.librarySearch = newSearch;
            st.libraryPage = 0;
        }
        y += LABEL_OFFSET + 10;

        // sort + category row
        int half = (w - 4) / 2;
        sortButton.setMessage(Text.literal("排序：" + sortLabel(st.librarySort)));
        sortButton.setPosition(x, y);
        sortButton.setWidth(half);
        sortButton.visible = true;
        sortButton.active = true;
        sortButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);

        categoryButton.setMessage(Text.literal("分类：" + (st.libraryFilterCategory != null ? st.libraryFilterCategory.name() : "ALL")));
        categoryButton.setPosition(x + half + 4, y);
        categoryButton.setWidth(w - half - 4);
        categoryButton.visible = true;
        categoryButton.active = true;
        categoryButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);

        y += LABEL_OFFSET;

        // catalog
        ComponentCatalog cat = ClientComponentCatalogState.getCatalog();
        if (cat == null || cat.components == null || cat.components.isEmpty()) {
            y = drawWrappedText(ctx, Text.literal("构件库为空：请先保存构件或等待 catalog 同步。"), x, y, w, 0xFFAAAAAA);
            return;
        }

        // filter
        String q = (st.librarySearch == null) ? "" : st.librarySearch.trim().toLowerCase(Locale.ROOT);
        List<ComponentCatalog.Entry> filtered = new ArrayList<>();
        for (var e : cat.components) {
            if (e == null || e.id == null || e.id.isBlank()) continue;
            if (st.libraryFilterCategory != null && e.category != st.libraryFilterCategory) continue;
            if (q.isEmpty()) {
                filtered.add(e);
                continue;
            }
            String id = e.id.toLowerCase(Locale.ROOT);
            String nm = (e.name != null ? e.name : "").toLowerCase(Locale.ROOT);
            boolean ok = id.contains(q) || nm.contains(q);
            if (!ok && e.tags != null) {
                for (String t : e.tags) {
                    if (t != null && t.toLowerCase(Locale.ROOT).contains(q)) { ok = true; break; }
                }
            }
            if (ok) filtered.add(e);
        }

        // sort
        Comparator<ComponentCatalog.Entry> cmp = getEntryComparator(st, q);
        filtered.sort(cmp);

        // paging
        int cell = 56;
        int cols = Math.max(2, Math.min(4, w / cell));
        int rowsPerPage = 6;
        int pageSize = Math.max(1, cols * rowsPerPage);
        int total = filtered.size();
        int pageCount = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.max(0, Math.min(st.libraryPage, pageCount - 1));
        st.libraryPage = page;

        int start = page * pageSize;
        if (start >= total) { st.libraryPage = 0; page = 0; start = 0; }
        int end = Math.min(total, start + pageSize);

        prevPageButton.setPosition(x, y);
        prevPageButton.setWidth(half);
        prevPageButton.visible = true;
        prevPageButton.active = (page > 0);
        prevPageButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);

        nextPageButton.setPosition(x + half + 4, y);
        nextPageButton.setWidth(w - half - 4);
        nextPageButton.visible = true;
        nextPageButton.active = (page < pageCount - 1);
        nextPageButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);

        y += LABEL_OFFSET;
        y = drawWrappedText(ctx, Text.literal("第 " + (page + 1) + "/" + pageCount + " 页  (共 " + total + " 个)"), x, y, w, 0xFFAAAAAA);
        y += 2;

        // grid draw
        int pad = 4;
        int bw = cell - pad;
        int bh = cell - pad;
        int thumbSz = 42;
        int row = 0, col = 0;

        for (int i = start; i < end; i++) {
            var e = filtered.get(i);
            if (e == null) continue;
            String id = e.id;
            String nm0 = displayName(e);

            int bx = x + col * cell;
            int by = y + row * cell;

            boolean selected = id != null && id.equals(st.librarySelectedId);
            int border = selected ? 0xFF66FF66 : 0xFF444444;
            int bg = selected ? 0xAA223322 : 0xAA15151A;

            ctx.fill(bx, by, bx + bw, by + bh, bg);
            ctx.fill(bx, by, bx + bw, by + 1, border);
            ctx.fill(bx, by + bh - 1, bx + bw, by + bh, border);
            ctx.fill(bx, by, bx + 1, by + bh, border);
            ctx.fill(bx + bw - 1, by, bx + bw, by + bh, border);

            var t = ComponentThumbnailCache.getThumb(id, 24);
            if (t != null && t.argb() != null) {
                int tw = t.w();
                int th = t.h();
                int[] argb = t.argb();
                int scale = Math.max(1, thumbSz / Math.max(1, Math.max(tw, th)));
                int drawW = tw * scale;
                int ox = bx + (bw - drawW) / 2;
                int oy = by + 4;
                for (int yy = 0; yy < th; yy++) {
                    for (int xx = 0; xx < tw; xx++) {
                        int c = argb[yy * tw + xx];
                        int x0 = ox + xx * scale;
                        int y0 = oy + yy * scale;
                        ctx.fill(x0, y0, x0 + scale, y0 + scale, c);
                    }
                }
            } else {
                ctx.fill(bx + 6, by + 6, bx + bw - 6, by + 6 + thumbSz, 0x55222222);
            }

            String shortName = nm0.length() > 6 ? nm0.substring(0, 6) : nm0;
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(shortName), bx + 4, by + bh - client.textRenderer.fontHeight - 2, 0xFFDDDDDD);

            grid.add(new GridItem(id, nm0, bx, by, bw, bh));
            col++;
            if (col >= cols) { col = 0; row++; }
        }

        gridValid = !grid.isEmpty();

        // hint
        y += Math.max(1, (int) Math.ceil((end - start) / (double) cols)) * cell;
        y += 4;
        y = drawWrappedText(ctx, Text.literal("提示：单击选中；双击加载构件到鼠标（可右键放置）。"), x, y, w, 0xFF888888);
    }

    private static @NotNull Comparator<ComponentCatalog.Entry> getEntryComparator(ComponentToolState st, String q) {
        String sort = st.librarySort != null ? st.librarySort : "RECENT";
        return switch (sort.toUpperCase(Locale.ROOT)) {
            case "NAME" -> Comparator.comparing(ComponentLibraryPanel::displayName, String.CASE_INSENSITIVE_ORDER);
            case "CATEGORY" -> Comparator
                    .comparing((ComponentCatalog.Entry e) -> e.category != null ? e.category.name() : "ZZZ", String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ComponentLibraryPanel::displayName, String.CASE_INSENSITIVE_ORDER);
            case "LOADED" -> Comparator
                    .comparingLong((ComponentCatalog.Entry e) -> ComponentLibraryUsage.getLastLoadedMs(e.id))
                    .reversed()
                    .thenComparingLong((ComponentCatalog.Entry e) -> e.updatedAtMs != null ? e.updatedAtMs : 0L)
                    .reversed()
                    .thenComparing(ComponentLibraryPanel::displayName, String.CASE_INSENSITIVE_ORDER);
            case "TAG_MATCH" -> Comparator
                    .comparingInt((ComponentCatalog.Entry e) -> matchScore(e, q))
                    .reversed()
                    .thenComparingLong((ComponentCatalog.Entry e) -> e.updatedAtMs != null ? e.updatedAtMs : 0L)
                    .reversed()
                    .thenComparing(ComponentLibraryPanel::displayName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator
                    .comparingLong((ComponentCatalog.Entry e) -> e.updatedAtMs != null ? e.updatedAtMs : 0L)
                    .reversed()
                    .thenComparing(ComponentLibraryPanel::displayName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    private static String sortLabel(String s) {
        if (s == null) return "最近保存";
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "LOADED" -> "最近加载";
            case "TAG_MATCH" -> "标签命中";
            case "NAME" -> "名字";
            case "CATEGORY" -> "分类";
            default -> "最近保存";
        };
    }

    /**
     * tags 命中优先：在有搜索词时，tag 命中 > name 命中 > id 命中。
     * 返回 0~3，越大越靠前。
     */
    private static int matchScore(ComponentCatalog.Entry e, String q) {
        if (e == null) return 0;
        if (q == null || q.isBlank()) return 0;
        String qq = q.trim().toLowerCase(Locale.ROOT);

        if (e.tags != null) {
            for (String t : e.tags) {
                if (t != null && t.toLowerCase(Locale.ROOT).contains(qq)) return 3;
            }
        }
        String nm = (e.name != null ? e.name : "").toLowerCase(Locale.ROOT);
        if (nm.contains(qq)) return 2;
        String id = (e.id != null ? e.id : "").toLowerCase(Locale.ROOT);
        if (id.contains(qq)) return 1;
        return 0;
    }

    private static String displayName(ComponentCatalog.Entry e) {
        if (e == null) return "";
        if (e.name != null && !e.name.isBlank()) return e.name;
        return e.id != null ? e.id : "";
    }

    // 简化复制 ToolPanel 的换行绘制（BasePanel 没提供该工具方法）
    private int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = client.textRenderer.fontHeight;
        for (var line : client.textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        // 注意：不在这里检查 isMouseOver，因为 super.mouseClicked 已经检查过了
        // 如果 super 返回 false，说明鼠标不在面板内或没有命中标签栏/按钮
        // 此时我们需要检查面板内的其他元素（按钮、输入框、网格项）
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // allow buttons
        if (sortButton != null && sortButton.visible && sortButton.mouseClicked(click, false)) return true;
        if (categoryButton != null && categoryButton.visible && categoryButton.mouseClicked(click, false)) return true;
        if (prevPageButton != null && prevPageButton.visible && prevPageButton.mouseClicked(click, false)) return true;
        if (nextPageButton != null && nextPageButton.visible && nextPageButton.mouseClicked(click, false)) return true;

        if (searchBoundsValid) {
            if (searchInput.mouseClicked(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
                return true;
            }
        }

        if (gridValid) {
            for (GridItem it : grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    var st = ComponentTool.INSTANCE.getState();
                    st.librarySelectedId = it.id;
                    st.librarySelectedName = it.name;

                    long now = System.currentTimeMillis();
                    boolean dbl = (lastClickId != null && lastClickId.equals(it.id) && (now - lastClickMs) <= DOUBLE_CLICK_MS);
                    lastClickId = it.id;
                    lastClickMs = now;
                    if (dbl) {
                        ComponentTool.INSTANCE.requestLoadSelectedComponent();
                        // 双击加载后自动跳回“工具”标签，方便立刻放置
                        // 不切换面板，保持在构件库面板，方便继续浏览和拾取
                        var toolState = ComponentTool.INSTANCE.getState();
                        toolState.useLibrary = true; // 启用库模式
                    }
                    return true;
                }
            }
        }

        // 鼠标在面板内，消费点击事件（防止点击穿透到游戏世界）
        // 设计原则：面板内的所有点击都应该被UI处理，不应传递给游戏
        return true;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        searchInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        searchInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return searchInput.isFocused();
    }
}

