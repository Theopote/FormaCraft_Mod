package com.formacraft.client.ui.panel;

import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.component.ComponentThumbnailCache;
import com.formacraft.client.component.ComponentLibraryUsage;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
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
    
    // 验证和修复按钮（当有加载的构件时显示）
    private ButtonWidget validateButton;
    private ButtonWidget autoFixButton;
    
    // 删除按钮（当选中构件时显示）
    private ButtonWidget deleteButton;

    // 节流的构件文件存在性快照：避免每帧对每个 catalog 条目做磁盘 Files.exists 检查。
    private long lastExistScanMs = 0L;
    private java.util.Set<String> existingComponentIds = java.util.Collections.emptySet();

    // grid hitboxes
    private static final class GridItem {
        final ComponentCatalog.Entry entry;
        final int x, y, w, h;
        GridItem(ComponentCatalog.Entry entry, int x, int y, int w, int h) {
            this.entry = entry; this.x = x; this.y = y; this.w = w; this.h = h;
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
        
        // 验证和修复按钮
        validateButton = ButtonWidget.builder(
                Text.literal("验证"),
                b -> ComponentTool.INSTANCE.validateLoadedComponent()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("验证当前加载的构件")))
                .build();
        
        autoFixButton = ButtonWidget.builder(
                Text.literal("自动修复"),
                b -> ComponentTool.INSTANCE.autoFixLoadedComponent()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("自动修复构件的明显错误")))
                .build();
        
        deleteButton = ButtonWidget.builder(
                Text.literal("删除构件"),
                b -> deleteSelectedComponent()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("删除选中的构件（不可恢复）")))
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

    private void deleteSelectedComponent() {
        var st = ComponentTool.INSTANCE.getState();
        if (st.librarySelectedId == null || st.librarySelectedId.isBlank()) {
            HudToast.show("请先选择一个构件", true);
            return;
        }
        
        String componentId = st.librarySelectedId;
        String componentName = st.librarySelectedName != null ? st.librarySelectedName : componentId;
        
        // 删除构件（worldDir传null，因为全局目录不依赖worldDir）
        boolean success = ComponentStorage.deleteComponent(null, componentId);
        
        if (success) {
            // 清除缩略图缓存（确保UI立即更新）
            ComponentThumbnailCache.clearCache(componentId);
            
            // 立即从客户端 catalog 中移除该条目（UI 立即更新，无需等待服务端响应）
            ClientComponentCatalogState.removeComponent(componentId);
            
            // 清除选中状态
            st.librarySelectedId = null;
            st.librarySelectedName = null;
            
            // 如果当前加载的构件是被删除的构件，需要清除加载状态
            // 注意：ComponentTool没有公共的clearLoadedComponent方法，但可以通过重新加载来清除
            var loadedComponent = ComponentTool.INSTANCE.getLoadedComponent();
            if (loadedComponent != null && componentId.equals(loadedComponent.id)) {
                // 通过请求加载一个不存在的构件来清除当前加载的构件
                // 或者直接通过反射/内部方法清除，但更安全的方式是让用户手动重新加载
                // 这里先不处理，因为删除后用户通常不会继续使用已删除的构件
            }
            
            // 请求服务端重新发送catalog（作为最终同步，确保服务端和客户端一致）
            if (client.getNetworkHandler() != null) {
                FormaCraftClientNetworking.sendComponentCatalogRequest();
            }
            
            HudToast.show("已删除构件: " + componentName + "（包括缩略图）");
            
            // 重置到第一页
            st.libraryPage = 0;
        } else {
            HudToast.show("删除构件失败: " + componentName, true);
        }
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
            drawWrappedText(ctx, Text.literal("构件库为空：请先保存构件或等待 catalog 同步。"), x, y, w, 0xFFAAAAAA);
            return;
        }

        // filter
        String q = (st.librarySearch == null) ? "" : st.librarySearch.trim().toLowerCase(Locale.ROOT);
        List<ComponentCatalog.Entry> filtered = new ArrayList<>();
        Path globalDir = com.formacraft.common.component.ComponentStorage.getGlobalComponentDir();
        // 每帧一次目录快照（节流 500ms），替代逐条目的磁盘 exists 检查。
        refreshExistingIdsIfStale(globalDir);
        for (var e : cat.components) {
            if (e == null || e.id == null || e.id.isBlank()) continue;
            
            // 额外验证：确保构件文件确实存在（防止已删除的构件仍显示）
            // 这可以处理 catalog 更新延迟的情况
            if (!existingComponentIds.contains(e.id)) {
                // 文件不存在，跳过这个条目（可能已被删除但 catalog 还没更新）
                continue;
            }
            
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
        // 见 refreshExistingIdsIfStale：目录快照节流刷新，避免每帧磁盘 IO。

        // paging
        int cols = 4; // 固定每行4个
        int cell = (w - 12) / cols; // 计算每个单元格大小，留出12像素总间距
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
        int pad = 2; // 减小间距
        int bw = cell - pad;
        int bh = cell - pad;
        int thumbSz = 32; // 减小缩略图大小
        int row = 0, col = 0;

        for (int i = start; i < end; i++) {
            var e = filtered.get(i);
            if (e == null) continue;
            String id = e.id;

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

            // 不再显示名称，改为在 tooltip 中显示
            grid.add(new GridItem(e, bx, by, bw, bh));
            col++;
            if (col >= cols) { col = 0; row++; }
        }

        gridValid = !grid.isEmpty();

        // hint
        y += Math.max(1, (int) Math.ceil((end - start) / (double) cols)) * cell;
        y += 4;
        y = drawWrappedText(ctx, Text.literal("提示：单击选中；双击加载构件到鼠标（可右键放置）。"), x, y, w, 0xFF888888);
        
        // 删除按钮（如果选中了构件）
        if (st.librarySelectedId != null && !st.librarySelectedId.isBlank()) {
            y += 8;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            String selectedName = st.librarySelectedName != null ? st.librarySelectedName : st.librarySelectedId;
            y = drawWrappedText(ctx, Text.literal("已选中: " + selectedName), x, y, w, 0xFFFFFFFF);
            y += 2;
            deleteButton.setPosition(x, y);
            deleteButton.setWidth(w);
            deleteButton.visible = true;
            deleteButton.active = true;
            deleteButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                    (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);
            y += LABEL_OFFSET;
        }
        
        // 验证和修复区域（如果有加载的构件）
        var loadedComponent = ComponentTool.INSTANCE.getLoadedComponent();
        if (loadedComponent != null) {
            y += 8;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            
            // 构件信息
            String componentName = loadedComponent.name != null ? loadedComponent.name : loadedComponent.id;
            y = drawWrappedText(ctx, Text.literal("已加载: " + componentName), x, y, w, 0xFFFFFFFF);
            y += 2;
            
            // 验证状态
            var validationResult = ComponentTool.INSTANCE.getState().getValidationResult();
            if (validationResult != null) {
                String statusText;
                int statusColor;
                if (validationResult.hasErrors()) {
                    statusText = "❌ 错误 (" + validationResult.errors().size() + ")";
                    statusColor = 0xFFFF5555;
                } else if (validationResult.hasWarnings()) {
                    statusText = "⚠️ 警告 (" + validationResult.warnings().size() + ")";
                    statusColor = 0xFFFFAA00;
                } else {
                    statusText = "✅ 验证通过";
                    statusColor = 0xFF55FF55;
                }
                y = drawWrappedText(ctx, Text.literal(statusText), x, y, w, statusColor);
            } else {
                y = drawWrappedText(ctx, Text.literal("⏳ 未验证"), x, y, w, 0xFFAAAAAA);
            }
            y += 2;

            // 修复状态
            var autoFixReport = ComponentTool.INSTANCE.getState().getAutoFixReport();
            if (autoFixReport != null && !autoFixReport.empty()) {
                y = drawWrappedText(ctx, Text.literal("🛠 已修复 (" + autoFixReport.size() + " 项)"), x, y, w, 0xFF66CCFF);
                y += 2;
            }
            
            // 按钮
            int buttonW = (w - 4) / 2;
            validateButton.setPosition(x, y);
            validateButton.setWidth(buttonW);
            validateButton.visible = true;
            validateButton.active = true;
            validateButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                    (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);
            
            autoFixButton.setPosition(x + buttonW + 4, y);
            autoFixButton.setWidth(w - buttonW - 4);
            autoFixButton.visible = true;
            autoFixButton.active = true;
            autoFixButton.render(ctx, (int) (client.mouse.getX() / client.getWindow().getScaleFactor()),
                    (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);
            y += LABEL_OFFSET;
            
            // 显示验证结果详情（如果有错误或警告）
            if (validationResult != null) {
                if (validationResult.hasErrors()) {
                    y += 4;
                    y = drawWrappedText(ctx, Text.literal("错误:"), x, y, w, 0xFFFF5555);
                    y += 2;
                    for (var error : validationResult.errors()) {
                        String errorText = "  • " + error.path + ": " + error.message;
                        y = drawWrappedText(ctx, Text.literal(errorText), x + 6, y, w - 6, 0xFFDD7777);
                        y += 1;
                    }
                }
                if (validationResult.hasWarnings()) {
                    y += 4;
                    y = drawWrappedText(ctx, Text.literal("警告:"), x, y, w, 0xFFFFAA00);
                    y += 2;
                    int warnCount = 0;
                    for (var warning : validationResult.warnings()) {
                        if (warnCount >= 3) { // 最多显示3个警告
                            y = drawWrappedText(ctx, Text.literal("  ... 还有 " + (validationResult.warnings().size() - 3) + " 个警告"), 
                                    x + 6, y, w - 6, 0xFFDDCC88);
                            break;
                        }
                        String warnText = "  • " + warning.path + ": " + warning.message;
                        y = drawWrappedText(ctx, Text.literal(warnText), x + 6, y, w - 6, 0xFFDDCC88);
                        y += 1;
                        warnCount++;
                    }
                }
                
                // 显示修复报告（如果有）
                if (autoFixReport != null && !autoFixReport.empty()) {
                    y += 4;
                    y = drawWrappedText(ctx, Text.literal("修复内容:"), x, y, w, 0xFF66CCFF);
                    y += 2;
                    int fixCount = 0;
                    for (var fix : autoFixReport.fixes()) {
                        if (fixCount >= 3) { // 最多显示3个修复
                            drawWrappedText(ctx, Text.literal("  ... 还有 " + (autoFixReport.size() - 3) + " 个修复"),
                                    x + 6, y, w - 6, 0xFF99CCFF);
                            break;
                        }
                        String fixText = "  • " + fix.path + ": " + fix.message;
                        y = drawWrappedText(ctx, Text.literal(fixText), x + 6, y, w - 6, 0xFF99CCFF);
                        y += 1;
                        fixCount++;
                    }
                }
            }
        }
    }

    /**
     * 刷新"磁盘上实际存在的构件 id"快照。节流 500ms，避免每帧对每个 catalog 条目做 Files.exists。
     * 读取失败（目录缺失/瞬时异常）时保留上一次快照，宁可短暂多显示一条也不清空整列表。
     */
    private void refreshExistingIdsIfStale(Path globalDir) {
        long now = System.currentTimeMillis();
        if (now - lastExistScanMs < 500L && !existingComponentIds.isEmpty()) return;
        lastExistScanMs = now;
        if (globalDir == null) return;
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (var stream = java.nio.file.Files.list(globalDir)) {
            stream.forEach(p -> {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".json")) {
                    ids.add(fn.substring(0, fn.length() - ".json".length()));
                }
            });
            existingComponentIds = ids;
        } catch (java.io.IOException ignored) {
            // 目录不存在或读取失败：保留上次快照。
        }
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
        if (validateButton != null && validateButton.visible && validateButton.mouseClicked(click, false)) return true;
        if (autoFixButton != null && autoFixButton.visible && autoFixButton.mouseClicked(click, false)) return true;
        if (deleteButton != null && deleteButton.visible && deleteButton.mouseClicked(click, false)) return true;

        if (searchBoundsValid) {
            if (searchInput.mouseClicked(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
                return true;
            }
        }

        if (gridValid) {
            for (GridItem it : grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    var st = ComponentTool.INSTANCE.getState();
                    st.librarySelectedId = it.entry.id;
                    st.librarySelectedName = displayName(it.entry);

                    long now = System.currentTimeMillis();
                    boolean dbl = (lastClickId != null && lastClickId.equals(it.entry.id) && (now - lastClickMs) <= DOUBLE_CLICK_MS);
                    lastClickId = it.entry.id;
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

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        // 检查鼠标是否悬停在构件上
        if (gridValid) {
            ComponentCatalog.Entry hoveredEntry = null;
            for (GridItem it : grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    hoveredEntry = it.entry;
                    break;
                }
            }
            
            if (hoveredEntry != null) {
                List<Text> tooltipLines = buildComponentTooltip(hoveredEntry);
                if (!tooltipLines.isEmpty()) {
                    drawTooltipCompat(ctx, tooltipLines, (int) mouseX, (int) mouseY);
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 构建构件的 tooltip 内容
     */
    private List<Text> buildComponentTooltip(ComponentCatalog.Entry entry) {
        List<Text> lines = new ArrayList<>();
        
        if (entry == null) return lines;
        
        // 名称（如果有）
        String name = displayName(entry);
        if (name != null && !name.isBlank()) {
            lines.add(Text.literal("§6" + name)); // 金色标题
        }
        
        // ID
        if (entry.id != null && !entry.id.isBlank()) {
            lines.add(Text.literal("§7ID: §f" + entry.id)); // 灰色标签 + 白色值
        }
        
        // 分类
        if (entry.category != null) {
            lines.add(Text.literal("§7分类: §f" + entry.category.name()));
        }
        
        // 标签
        if (entry.tags != null && !entry.tags.isEmpty()) {
            String tagsStr = String.join(", ", entry.tags);
            lines.add(Text.literal("§7标签: §f" + tagsStr));
        }
        
        // 尺寸（如果有）
        if (entry.size != null) {
            lines.add(Text.literal("§7尺寸: §f" + entry.size.w + "×" + entry.size.h + "×" + entry.size.d));
        }
        
        // 最后更新时间（如果有）
        if (entry.updatedAtMs != null && entry.updatedAtMs > 0) {
            long ageMs = System.currentTimeMillis() - entry.updatedAtMs;
            String ageStr = formatAge(ageMs);
            lines.add(Text.literal("§7更新: §f" + ageStr + "前"));
        }
        
        return lines;
    }
    
    /**
     * 格式化时间差（毫秒 -> 人类可读）
     */
    private String formatAge(long ageMs) {
        long ageSec = ageMs / 1000;
        if (ageSec < 60) return ageSec + "秒";
        long ageMin = ageSec / 60;
        if (ageMin < 60) return ageMin + "分钟";
        long ageHour = ageMin / 60;
        if (ageHour < 24) return ageHour + "小时";
        long ageDay = ageHour / 24;
        if (ageDay < 30) return ageDay + "天";
        long ageMonth = ageDay / 30;
        if (ageMonth < 12) return ageMonth + "个月";
        return (ageDay / 365) + "年";
    }

}

