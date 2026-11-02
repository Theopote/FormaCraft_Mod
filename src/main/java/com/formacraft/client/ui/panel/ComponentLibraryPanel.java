package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.component.ComponentThumbnailCache;
import com.formacraft.client.component.ComponentLibraryUsage;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.client.network.FormaCraftClientNetworking;
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
    private static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
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
    private ButtonWidget deleteButton, exportButton, cancelExportButton, refreshButton;
    private ButtonWidget sourceButton, prevSourceButton, nextSourceButton;
    private ButtonWidget importButton, confirmImportButton, cancelImportButton;
    private com.formacraft.client.component.ComponentImportClientState.Phase lastImportPhase = com.formacraft.client.component.ComponentImportClientState.Phase.IDLE;


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

    // 滚动（固定搜索/排序行，下方内容可滚）
    private int scrollY = 0;
    private int maxScrollY = 0;
    private int scrollAreaTop = 0;

    // double-click
    private long lastClickMs = 0L;
    private String lastClickId = null;
    private static final long DOUBLE_CLICK_MS = 280L;

    private static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    public ComponentLibraryPanel() {
        searchInput.setMaxLength(64);
    }

    private void ensureWidgets() {
        if (sortButton != null) return;

        importButton = ButtonWidget.builder(Text.literal("导入构件包…"), b -> { scrollY = 0; com.formacraft.client.component.ClientComponentImport.chooseFile(); }).dimensions(0, 0, 0, BUTTON_HEIGHT).build();
        refreshButton = ButtonWidget.builder(Text.literal("刷新构件库"), b -> {
            FormaCraftClientNetworking.sendComponentCatalogRequest();
            com.formacraft.client.component.ClientComponentSourceState.INSTANCE.clear(); scrollY = 0;
        }).dimensions(0,0,0,BUTTON_HEIGHT).build();
        confirmImportButton = ButtonWidget.builder(Text.literal("确认入库"), b -> com.formacraft.client.component.ClientComponentImport.confirm()).dimensions(0, 0, 0, BUTTON_HEIGHT).build();
        cancelImportButton = ButtonWidget.builder(Text.literal("取消导入"), b -> com.formacraft.client.component.ClientComponentImport.cancel()).dimensions(0, 0, 0, BUTTON_HEIGHT).build();

        exportButton = ButtonWidget.builder(Text.literal("导出当前来源包…"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.start(selectedEntry(), false)).dimensions(0,0,0,BUTTON_HEIGHT).build();
        cancelExportButton = ButtonWidget.builder(Text.literal("取消下载"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.cancel()).dimensions(0,0,0,BUTTON_HEIGHT).build();
        sourceButton = ButtonWidget.builder(Text.literal("查看 / 刷新来源"), b -> requestSource(0)).dimensions(0, 0, 0, BUTTON_HEIGHT).build();
        prevSourceButton = ButtonWidget.builder(Text.literal("上一份来源"), b -> stepSource(-1)).dimensions(0, 0, 0, BUTTON_HEIGHT).build();
        nextSourceButton = ButtonWidget.builder(Text.literal("下一份来源"), b -> stepSource(1)).dimensions(0, 0, 0, BUTTON_HEIGHT).build();

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

    private ComponentCatalog.Entry selectedEntry() {
        var catalog = ClientComponentCatalogState.getCatalog();
        var id = ComponentTool.INSTANCE.getState().librarySelectedId;
        return catalog == null || catalog.components == null ? null : catalog.components.stream()
                .filter(e -> e != null && java.util.Objects.equals(id,e.id)).findFirst().orElse(null);
    }

    private void deleteSelectedComponent() {
        com.formacraft.client.component.ClientComponentLibraryOperations.start(selectedEntry(), true);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();
        var importPhase = com.formacraft.client.component.ClientComponentImport.STATE.phase();
        if (importPhase != lastImportPhase) {
            if (importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.PREVIEW
                    || importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.ERROR
                    || importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.DONE) scrollY = 0;
            lastImportPhase = importPhase;
        }

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;
        int y = getContentY() + CONTENT_PADDING;

        // reset hitboxes
        grid.clear();
        gridValid = false;
        searchBoundsValid = false;
        sourceButton.visible = prevSourceButton.visible = nextSourceButton.visible = false;
        confirmImportButton.visible = cancelImportButton.visible = false;
        prevPageButton.visible = nextPageButton.visible = validateButton.visible = autoFixButton.visible = deleteButton.visible = false;
        exportButton.visible = cancelExportButton.visible = false;

        drawContentBackground(ctx);

        var st = ComponentTool.INSTANCE.getState();
        int clipBottom = panelY + panelHeight - 1;

        int headerBottom = drawLibraryFixedHeader(ctx, x, w, y, st);
        scrollAreaTop = headerBottom;

        ctx.enableScissor(contentScissorLeft(), scrollAreaTop, contentScissorRight(), clipBottom);
        try {
            int sy = scrollAreaTop - scrollY;
            sy = drawLibraryScrollable(ctx, x, w, sy, st);
            int visibleHeight = Math.max(1, clipBottom - scrollAreaTop);
            int totalHeight = (sy + scrollY) - scrollAreaTop;
            maxScrollY = Math.max(0, totalHeight - visibleHeight);
            if (scrollY > maxScrollY) scrollY = maxScrollY;
            if (scrollY < 0) scrollY = 0;
        } finally {
            ctx.disableScissor();
        }
    }

    /** 固定区：搜索 + 排序/分类（不随滚动移动） */
    private int drawLibraryFixedHeader(DrawContext ctx, int x, int w, int y, ComponentToolState st) {
        String curSearch = st.librarySearch != null ? st.librarySearch : "";
        if (!searchInput.isFocused() && !curSearch.equals(searchInput.getText())) {
            searchInput.setText(curSearch);
        }

        ctx.drawTextWithShadow(client.textRenderer, Text.literal("搜索："), x, y, 0xFFAAAAAA);
        int inY = y + LABEL_OFFSET - 2;
        searchInput.render(ctx, x, inY, w, 14);
        searchX = x;
        searchY = inY;
        searchW = w;
        searchH = 14;
        searchBoundsValid = true;
        String newSearch = searchInput.getText();
        if (newSearch == null) newSearch = "";
        if (!newSearch.equals(st.librarySearch)) {
            st.librarySearch = newSearch;
            st.libraryPage = 0;
            scrollY = 0;
        }
        y += LABEL_OFFSET + 10;

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
        renderSourceButton(ctx, importButton, x, y, half, !com.formacraft.client.component.ClientComponentImport.STATE.busy());
        return renderSourceButton(ctx, refreshButton, x + half + 4, y, w - half - 4, client.getNetworkHandler() != null);
    }

    /** 可滚动区：分页、网格、选中/验证/修复等 */
    private int drawLibraryScrollable(DrawContext ctx, int x, int w, int y, ComponentToolState st) {
        y = drawImportStatus(ctx, x, w, y);
        ComponentCatalog cat = ClientComponentCatalogState.getCatalog();
        if (cat == null || cat.components == null || cat.components.isEmpty()) {
            return drawWrappedText(ctx, Text.literal("构件库为空：请先保存构件或等待 catalog 同步。"), x, y, w, 0xFFAAAAAA);
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
            boolean ok = id.contains(q) || com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(q, e.name, e.aliases);
            if (!ok) ok = typeSearchMatches(e, q);
            if (!ok) ok = com.formacraft.common.component.query.ComponentQueryMatchUtil.tagSearchMatches(q, e.tags);
            if (ok) filtered.add(e);
        }

        // sort
        Comparator<ComponentCatalog.Entry> cmp = getEntryComparator(st, q);
        filtered.sort(cmp.thenComparing(e -> e.id));


        // paging
        int cols = Math.max(1, Math.min(4, w / 44));
        int cell = Math.max(1, w / cols);
        int rowsPerPage = 6;
        int pageSize = Math.max(1, cols * rowsPerPage);
        int total = filtered.size();
        int pageCount = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int page = Math.max(0, Math.min(st.libraryPage, pageCount - 1));
        st.libraryPage = page;

        int start = page * pageSize;
        if (start >= total) { st.libraryPage = 0; page = 0; start = 0; }
        int end = Math.min(total, start + pageSize);

        int half = (w - 4) / 2;
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
        int row = 0, col = 0;

        for (int i = start; i < end; i++) {
            var e = filtered.get(i);
            if (e == null) continue;
            String id = e.id;

            int bx = x + col * cell;
            int by = y + row * cell;

            boolean selected = id != null && id.equals(st.librarySelectedId);
            int border = selected ? UiTheme.ACCENT : UiTheme.DIVIDER_SUBTLE;
            int bg = selected ? 0xAA223322 : 0xAA15151A;

            ctx.fill(bx, by, bx + bw, by + bh, bg);
            ctx.fill(bx, by, bx + bw, by + 1, border);
            ctx.fill(bx, by + bh - 1, bx + bw, by + bh, border);
            ctx.fill(bx, by, bx + 1, by + bh, border);
            ctx.fill(bx + bw - 1, by, bx + bw, by + bh, border);

            var image = ComponentThumbnailCache.getImage(id, e.updatedAtMs == null ? 0 : e.updatedAtMs);
            if (image != null) {
                com.formacraft.client.ui.render.ImageRenderer.renderCentered(ctx, image,
                        bx + 3, by + 3, bw - 6, bh - 6);
            } else {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("…"), bx + bw / 2 - 3, by + bh / 2 - 4, 0xFFAAAAAA);
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
            for (var entry : cat.components) {
                if (entry == null || !st.librarySelectedId.equals(entry.id)) continue;
                var selectedImage = ComponentThumbnailCache.getImage(entry.id, entry.updatedAtMs == null ? 0 : entry.updatedAtMs);
                int previewSize = Math.min(112, w);
                int previewX = x + (w - previewSize) / 2;
                ctx.fill(previewX, y, previewX + previewSize, y + previewSize, UiTheme.CHAT_MESSAGE_BG);
                ctx.fill(previewX, y, previewX + previewSize, y + 1, UiTheme.DIVIDER_SUBTLE);
                ctx.fill(previewX, y + previewSize - 1, previewX + previewSize, y + previewSize, UiTheme.DIVIDER_SUBTLE);
                ctx.fill(previewX, y, previewX + 1, y + previewSize, UiTheme.DIVIDER_SUBTLE);
                ctx.fill(previewX + previewSize - 1, y, previewX + previewSize, y + previewSize, UiTheme.DIVIDER_SUBTLE);
                if (selectedImage != null) com.formacraft.client.ui.render.ImageRenderer.renderCentered(
                        ctx, selectedImage, previewX + 2, y + 2, previewSize - 4, previewSize - 4);
                y += previewSize + 3;
                if (entry.size != null) y = drawWrappedText(ctx, Text.literal("尺寸: " + entry.size.w + " × " + entry.size.h + " × " + entry.size.d), x, y, w, 0xFFAAAAAA);
                if (entry.packageRef != null) {
                    boolean busy = com.formacraft.client.component.ClientComponentLibraryOperations.STATE.busy();
                    y = renderSourceButton(ctx, exportButton, x, y, w, !busy);
                    if (busy) {
                        y = drawWrappedText(ctx, Text.literal("正在处理构件操作…"), x, y, w, 0xFFAAAAAA);
                        if (com.formacraft.client.component.ClientComponentLibraryOperations.STATE.phase() == com.formacraft.client.component.ComponentLibraryClientState.Phase.DOWNLOADING)
                            y = renderSourceButton(ctx, cancelExportButton, x, y, w, true);
                    }
                    y = drawSourceDetails(ctx, x, w, y + 2, entry);
                }
                y += 2;
                break;
            }
            deleteButton.setPosition(x, y);
            deleteButton.setWidth(w);
            deleteButton.visible = true;
            deleteButton.active = !com.formacraft.client.component.ClientComponentLibraryOperations.STATE.busy();
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
        return y;
    }

    private static @NotNull Comparator<ComponentCatalog.Entry> getEntryComparator(ComponentToolState st, String q) {
        return com.formacraft.client.component.ComponentLibraryOrdering.comparator(st.librarySort,
                e -> matchScore(e,q), e -> ComponentLibraryUsage.getLastLoadedMs(e.id));
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
    private void requestSource(int index) {
        FormaCraftClientNetworking.sendComponentSourceRequest(ComponentTool.INSTANCE.getState().librarySelectedId, index);
    }

    private int drawImportStatus(DrawContext ctx, int x, int w, int y) {
        var state = com.formacraft.client.component.ClientComponentImport.STATE;
        var phase = state.phase();
        String status = switch (phase) {
            case IDLE -> ""; case READING -> "正在读取构件包…";
            case UPLOADING -> "正在上传构件包：" + state.progress() + "%";
            case VERIFYING -> "服务端正在校验构件包…"; case PREVIEW -> "导入信息预览";
            case COMMITTING -> "正在确认入库…"; case DONE -> "导入完成，已选中构件";
            case ERROR -> state.message();
        };
        if (status.isEmpty()) return y;
        y = drawWrappedText(ctx, Text.literal(status), x, y, w, phase == com.formacraft.client.component.ComponentImportClientState.Phase.ERROR ? 0xFFFFAA55 : 0xFFFFFFFF);
        var preview = state.preview();
        if (preview != null || state.busy()) {
            if (preview != null) {
                int half = (w - 4) / 2;
                renderSourceButton(ctx, confirmImportButton, x, y, half, phase == com.formacraft.client.component.ComponentImportClientState.Phase.PREVIEW);
                renderSourceButton(ctx, cancelImportButton, x + half + 4, y, w - half - 4, phase != com.formacraft.client.component.ComponentImportClientState.Phase.COMMITTING);
            } else renderSourceButton(ctx, cancelImportButton, x, y, w, phase != com.formacraft.client.component.ComponentImportClientState.Phase.COMMITTING);
            y += LABEL_OFFSET;
        }
        if (preview != null) {
            y = drawWrappedText(ctx, Text.literal("名称: " + preview.name()), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("尺寸: " + preview.width() + "×" + preview.height() + "×" + preview.depth() + "，方块 " + preview.blocks()), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("发布: " + preview.namespace() + " @ " + preview.version()), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("作者声明: " + preview.author()), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("目标: " + (preview.global() ? "全局库" : "私人库")), x, y, w, 0xFFCCCCCC);
            String training = switch (preview.trainingPermission()) { case ALLOWED -> "允许"; case DENIED -> "禁止"; case UNSPECIFIED -> "未声明"; };
            y = drawWrappedText(ctx, Text.literal("训练授权: " + training), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("许可: " + preview.license()), x, y, w, 0xFFCCCCCC);
            y = drawWrappedText(ctx, Text.literal("确认后按内容去重"), x, y, w, 0xFFAAAAAA);
        }
        return y + 6;
    }

    private void stepSource(int delta) {
        var state = com.formacraft.client.component.ClientComponentSourceState.INSTANCE;
        var page = state.page();
        if (page != null && java.util.Objects.equals(state.assetId(), ComponentTool.INSTANCE.getState().librarySelectedId))
            requestSource(page.index() + delta);
    }

    private int renderSourceButton(DrawContext ctx, ButtonWidget button, int x, int y, int w, boolean active) {
        button.setPosition(x, y); button.setWidth(w); button.visible = true; button.active = active;
        button.render(ctx, getScaledMouseXForSources(), (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);
        return y + LABEL_OFFSET;
    }

    private int getScaledMouseXForSources() { return (int) (client.mouse.getX() / client.getWindow().getScaleFactor()); }

    private int drawSourceDetails(DrawContext ctx, int x, int w, int y, ComponentCatalog.Entry entry) {
        if (entry.aliases != null && !entry.aliases.isEmpty())
            y = drawWrappedText(ctx, Text.literal("其他名称: " + String.join("、", entry.aliases.subList(0, Math.min(3, entry.aliases.size())))), x, y, w, 0xFFAAAAAA);
        if (entry.sourceMetadataError) y = drawWrappedText(ctx, Text.literal("附加来源信息待核验"), x, y, w, 0xFFFFAA55);
        else if (entry.sourceCount != null) y = drawWrappedText(ctx, Text.literal("来源记录: " + entry.sourceCount), x, y, w, 0xFFAAAAAA);
        var state = com.formacraft.client.component.ClientComponentSourceState.INSTANCE;
        boolean selected = entry.id.equals(state.assetId());
        boolean loading = selected && state.loading();
        y = renderSourceButton(ctx, sourceButton, x, y + 2, w, !loading);
        if (loading) return drawWrappedText(ctx, Text.literal("正在读取来源…"), x, y, w, 0xFFAAAAAA);
        if (!selected) return y;
        if (state.error() != null) return drawWrappedText(ctx, Text.literal(state.error()), x, y, w, 0xFFFFAA55);
        var page = state.page(); if (page == null) return y;
        int half = (w - 4) / 2;
        renderSourceButton(ctx, prevSourceButton, x, y, half, page.index() > 0);
        renderSourceButton(ctx, nextSourceButton, x + half + 4, y, w - half - 4, page.index() + 1 < page.total());
        y += LABEL_OFFSET;
        y = drawWrappedText(ctx, Text.literal("来源 " + (page.index() + 1) + "/" + page.total() + (page.primary() ? "（首份）" : "（附加）")), x, y, w, 0xFFFFFFFF);
        y = drawWrappedText(ctx, Text.literal("名称: " + page.name()), x, y, w, 0xFFCCCCCC);
        y = drawWrappedText(ctx, Text.literal("发布: " + page.namespace() + " @ " + page.version()), x, y, w, 0xFFCCCCCC);
        y = drawWrappedText(ctx, Text.literal("作者声明: " + page.author()), x, y, w, 0xFFCCCCCC);
        y = drawWrappedText(ctx, Text.literal("许可: " + page.license()), x, y, w, 0xFFCCCCCC);
        String permission = switch (page.trainingPermission()) { case ALLOWED -> "允许"; case DENIED -> "禁止"; case UNSPECIFIED -> "未声明"; };
        y = drawWrappedText(ctx, Text.literal("训练授权: " + permission), x, y, w, 0xFFCCCCCC);
        y = drawWrappedText(ctx, Text.literal("内容引用: " + page.contentRef()), x, y, w, 0xFFAAAAAA);
        return drawWrappedText(ctx, Text.literal("各来源授权独立，不自动合并"), x, y, w, 0xFFAAAAAA);
    }

    private static int matchScore(ComponentCatalog.Entry e, String q) {
        if (e == null) return 0;
        if (q == null || q.isBlank()) return 0;
        String qq = q.trim().toLowerCase(Locale.ROOT);

        if (com.formacraft.common.component.query.ComponentQueryMatchUtil.tagSearchMatches(qq, e.tags)) return 3;
        if (com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(qq, e.name, e.aliases)) return 2;
        if (typeSearchMatches(e, qq)) return 2;
        String id = (e.id != null ? e.id : "").toLowerCase(Locale.ROOT);
        if (id.contains(qq)) return 1;
        return 0;
    }

    private static String displayName(ComponentCatalog.Entry e) {
        if (e == null) return "";
        if (e.name != null && !e.name.isBlank()) return e.name;
        return e.id != null ? e.id : "";
    }

    private static boolean typeSearchMatches(ComponentCatalog.Entry entry, String text) {
        var type = com.formacraft.common.component.ComponentTypes.effective(entry.type, entry.category);
        var match = com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(text, type.label(), java.util.List.of(type.key()));
        return match || entry.typeAliases != null && entry.typeAliases.stream().anyMatch(alias ->
                com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(text, alias.label(), java.util.List.of(alias.key())));
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
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return;
        if (scrollAreaTop > 0 && mouseY < scrollAreaTop) return;
        scrollY = (int) Math.round(scrollY - amount * 12.0);
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
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
        if (clickButton(sortButton, click)) return true;
        if (clickButton(categoryButton, click)) return true;
        if (clickButton(importButton, click)) return true;
        if (clickButton(refreshButton, click)) return true;
        boolean inScroll = mouseY >= scrollAreaTop && mouseY < panelY + panelHeight - 1;
        if (inScroll && clickButton(exportButton, click)) return true;
        if (inScroll && clickButton(cancelExportButton, click)) return true;
        if (inScroll && clickButton(prevPageButton, click)) return true;
        if (inScroll && clickButton(nextPageButton, click)) return true;
        if (inScroll && clickButton(validateButton, click)) return true;
        if (inScroll && clickButton(autoFixButton, click)) return true;
        if (inScroll && clickButton(deleteButton, click)) return true;
        if (mouseY >= scrollAreaTop && (clickButton(confirmImportButton, click) || clickButton(cancelImportButton, click))) return true;
        if (mouseY >= scrollAreaTop && (clickButton(sourceButton, click) || clickButton(prevSourceButton, click) || clickButton(nextSourceButton, click))) return true;

        if (searchBoundsValid) {
            if (searchInput.mouseClicked(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
                return true;
            }
        }

        if (gridValid && inScroll) {
            for (GridItem it : grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    var st = ComponentTool.INSTANCE.getState();
                    st.librarySelectedId = it.entry.id;
                    com.formacraft.client.component.ClientComponentSourceState.INSTANCE.clear();
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
        if (gridValid && mouseY >= scrollAreaTop && mouseY < panelY + panelHeight - 1) {
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
        lines.add(Text.literal("类型: " + com.formacraft.common.component.ComponentTypes.effective(entry.type, entry.category).label()));
        if (entry.typeAliases != null && !entry.typeAliases.isEmpty())
            lines.add(Text.literal("其他类型: " + entry.typeAliases.stream().limit(3).map(com.formacraft.common.component.ComponentTypes.Ref::label).collect(java.util.stream.Collectors.joining("、"))));
        if (entry.aliases != null && !entry.aliases.isEmpty()) {
            lines.add(Text.literal("其他名称: " + String.join("、", entry.aliases.subList(0, Math.min(3, entry.aliases.size())))));
        }
        if (entry.sourceCount != null) lines.add(Text.literal("来源记录: " + entry.sourceCount));
        if (entry.sourceMetadataError) lines.add(Text.literal("来源信息待核验"));

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

