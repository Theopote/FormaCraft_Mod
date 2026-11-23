package com.formacraft.client.ui.panel.library;

import java.util.ArrayList;

import java.util.Comparator;

import java.util.Locale;

import com.formacraft.client.component.ComponentThumbnailCache;

import com.formacraft.client.component.ClientComponentCatalogState;

import com.formacraft.client.ui.panel.ComponentLibraryPanel;
import com.formacraft.client.ui.UiTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.common.component.ComponentCatalog;

import java.util.*;

public final class LibraryLayoutRenderer {
    private LibraryLayoutRenderer() {}
    public static int drawLibraryScrollable(ComponentLibraryPanel host, DrawContext ctx, int x, int w, int y, ComponentToolState st) {

        y = host.drawImportStatus(ctx, x, w, y);
        ComponentCatalog cat = ClientComponentCatalogState.getCatalog();
        if (cat == null || cat.components == null || cat.components.isEmpty()) {
            return host.drawWrappedText(ctx, Text.literal("构件库为空：请先保存构件或等待 catalog 同步。"), x, y, w, 0xFFAAAAAA);
        }

        // filter
        String q = (st.librarySearch == null) ? "" : st.librarySearch.trim().toLowerCase(Locale.ROOT);
        List<ComponentCatalog.Entry> filtered = new ArrayList<>();
        for (var e : cat.components) {
            if (e == null || e.id == null || e.id.isBlank()) continue;
            if (e.recipe != st.libraryRecipes) continue;
            
            if (st.libraryFilterCategory != null && e.category != st.libraryFilterCategory) continue;
            if (q.isEmpty()) {
                filtered.add(e);
                continue;
            }
            String id = e.id.toLowerCase(Locale.ROOT);
            boolean ok = id.contains(q) || com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(q, e.name, e.aliases);
            if (!ok) ok = ComponentLibraryPanel.typeSearchMatches(e, q);
            if (!ok) ok = com.formacraft.common.component.query.ComponentQueryMatchUtil.tagSearchMatches(q, e.tags);
            if (ok) filtered.add(e);
        }

        // sort
        Comparator<ComponentCatalog.Entry> cmp = ComponentLibraryPanel.getEntryComparator(st, q);
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
        host.prevPageButton.setPosition(x, y);
        host.prevPageButton.setWidth(half);
        host.prevPageButton.visible = true;
        host.prevPageButton.active = (page > 0);
        host.prevPageButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);

        host.nextPageButton.setPosition(x + half + 4, y);
        host.nextPageButton.setWidth(w - half - 4);
        host.nextPageButton.visible = true;
        host.nextPageButton.active = (page < pageCount - 1);
        host.nextPageButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);

        y += ComponentLibraryPanel.LABEL_OFFSET;
        y = host.drawWrappedText(ctx, Text.literal("第 " + (page + 1) + "/" + pageCount + " 页  (共 " + total + " 个)"), x, y, w, 0xFFAAAAAA);
        y += 2;

        // host.grid draw
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
                ctx.drawTextWithShadow(host.client().textRenderer, Text.literal("…"), bx + bw / 2 - 3, by + bh / 2 - 4, 0xFFAAAAAA);
            }
            // 不再显示名称，改为在 tooltip 中显示
            host.grid.add(new ComponentLibraryPanel.GridItem(e, bx, by, bw, bh));
            col++;
            if (col >= cols) { col = 0; row++; }
        }

        host.gridValid = !host.grid.isEmpty();

        // hint
        y += Math.max(1, (int) Math.ceil((end - start) / (double) cols)) * cell;
        y += 4;
        y = host.drawWrappedText(ctx, Text.literal("提示：单击选中；双击加载构件到鼠标（可右键放置）。"), x, y, w, 0xFF888888);
        
        // 删除按钮（如果选中了构件）
        if (st.librarySelectedId != null && !st.librarySelectedId.isBlank()) {
            y += 8;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            String selectedName = st.librarySelectedName != null ? st.librarySelectedName : st.librarySelectedId;
            y = host.drawWrappedText(ctx, Text.literal("已选中: " + selectedName), x, y, w, 0xFFFFFFFF);
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
                if (entry.size != null) y = host.drawWrappedText(ctx, Text.literal("尺寸: " + entry.size.w + " × " + entry.size.h + " × " + entry.size.d), x, y, w, 0xFFAAAAAA);
                if (entry.packageRef != null) {
                    boolean busy = com.formacraft.client.component.ClientComponentLibraryOperations.STATE.busy();
                    y = host.renderSourceButton(ctx, host.exportButton, x, y, w, !busy);
                    y = host.drawRecipeEditor(ctx,x,w,y,entry,busy);
                    if (busy) {
                        y = host.drawWrappedText(ctx, Text.literal("正在处理构件操作…"), x, y, w, 0xFFAAAAAA);
                        if (com.formacraft.client.component.ClientComponentLibraryOperations.STATE.phase() == com.formacraft.client.component.ComponentLibraryClientState.Phase.DOWNLOADING)
                            y = host.renderSourceButton(ctx, host.cancelExportButton, x, y, w, true);
                    }
                    y = host.drawSourceDetails(ctx, x, w, y + 2, entry);
                }
                y += 2;
                break;
            }
            host.deleteButton.setPosition(x, y);
            host.deleteButton.setWidth(w);
            host.deleteButton.visible = true;
            host.deleteButton.active = !com.formacraft.client.component.ClientComponentLibraryOperations.STATE.busy();
            host.deleteButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                    (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);
            y += ComponentLibraryPanel.LABEL_OFFSET;
        }
        
        // 验证和修复区域（如果有加载的构件）
        var loadedComponent = ComponentTool.INSTANCE.getLoadedComponent();
        if (loadedComponent != null) {
            y += 8;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            
            // 构件信息
            String componentName = loadedComponent.name != null ? loadedComponent.name : loadedComponent.id;
            y = host.drawWrappedText(ctx, Text.literal("已加载: " + componentName), x, y, w, 0xFFFFFFFF);
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
                y = host.drawWrappedText(ctx, Text.literal(statusText), x, y, w, statusColor);
            } else {
                y = host.drawWrappedText(ctx, Text.literal("⏳ 未验证"), x, y, w, 0xFFAAAAAA);
            }
            y += 2;

            // 修复状态
            var autoFixReport = ComponentTool.INSTANCE.getState().getAutoFixReport();
            if (autoFixReport != null && !autoFixReport.empty()) {
                y = host.drawWrappedText(ctx, Text.literal("🛠 已修复 (" + autoFixReport.size() + " 项)"), x, y, w, 0xFF66CCFF);
                y += 2;
            }
            
            // 按钮
            int buttonW = (w - 4) / 2;
            host.validateButton.setPosition(x, y);
            host.validateButton.setWidth(buttonW);
            host.validateButton.visible = true;
            host.validateButton.active = true;
            host.validateButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                    (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);
            
            host.autoFixButton.setPosition(x + buttonW + 4, y);
            host.autoFixButton.setWidth(w - buttonW - 4);
            host.autoFixButton.visible = true;
            host.autoFixButton.active = true;
            host.autoFixButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                    (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);
            y += ComponentLibraryPanel.LABEL_OFFSET;
            
            // 显示验证结果详情（如果有错误或警告）
            if (validationResult != null) {
                if (validationResult.hasErrors()) {
                    y += 4;
                    y = host.drawWrappedText(ctx, Text.literal("错误:"), x, y, w, 0xFFFF5555);
                    y += 2;
                    for (var error : validationResult.errors()) {
                        String errorText = "  • " + error.path + ": " + error.message;
                        y = host.drawWrappedText(ctx, Text.literal(errorText), x + 6, y, w - 6, 0xFFDD7777);
                        y += 1;
                    }
                }
                if (validationResult.hasWarnings()) {
                    y += 4;
                    y = host.drawWrappedText(ctx, Text.literal("警告:"), x, y, w, 0xFFFFAA00);
                    y += 2;
                    int warnCount = 0;
                    for (var warning : validationResult.warnings()) {
                        if (warnCount >= 3) { // 最多显示3个警告
                            y = host.drawWrappedText(ctx, Text.literal("  ... 还有 " + (validationResult.warnings().size() - 3) + " 个警告"), 
                                    x + 6, y, w - 6, 0xFFDDCC88);
                            break;
                        }
                        String warnText = "  • " + warning.path + ": " + warning.message;
                        y = host.drawWrappedText(ctx, Text.literal(warnText), x + 6, y, w - 6, 0xFFDDCC88);
                        y += 1;
                        warnCount++;
                    }
                }
                
                // 显示修复报告（如果有）
                if (autoFixReport != null && !autoFixReport.empty()) {
                    y += 4;
                    y = host.drawWrappedText(ctx, Text.literal("修复内容:"), x, y, w, 0xFF66CCFF);
                    y += 2;
                    int fixCount = 0;
                    for (var fix : autoFixReport.fixes()) {
                        if (fixCount >= 3) { // 最多显示3个修复
                            host.drawWrappedText(ctx, Text.literal("  ... 还有 " + (autoFixReport.size() - 3) + " 个修复"),
                                    x + 6, y, w - 6, 0xFF99CCFF);
                            break;
                        }
                        String fixText = "  • " + fix.path + ": " + fix.message;
                        y = host.drawWrappedText(ctx, Text.literal(fixText), x + 6, y, w - 6, 0xFF99CCFF);
                        y += 1;
                        fixCount++;
                    }
                }
            }
        }
        return y;
    
    }

    public static int drawLibraryFixedHeader(ComponentLibraryPanel host, DrawContext ctx, int x, int w, int y, ComponentToolState st) {

        String curSearch = st.librarySearch != null ? st.librarySearch : "";
        if (!host.searchInput.isFocused() && !curSearch.equals(host.searchInput.getText())) {
            host.searchInput.setText(curSearch);
        }

        ctx.drawTextWithShadow(host.client().textRenderer, Text.literal("搜索："), x, y, 0xFFAAAAAA);
        int inY = y + ComponentLibraryPanel.LABEL_OFFSET - 2;
        host.searchInput.render(ctx, x, inY, w, 14);
        host.searchX = x;
        host.searchY = inY;
        host.searchW = w;
        host.searchH = 14;
        host.searchBoundsValid = true;
        String newSearch = host.searchInput.getText();
        if (newSearch == null) newSearch = "";
        if (!newSearch.equals(st.librarySearch)) {
            st.librarySearch = newSearch;
            st.libraryPage = 0;
            host.scrollY = 0;
        }
        y += ComponentLibraryPanel.LABEL_OFFSET + 10;

        int half = (w - 4) / 2;
        host.sortButton.setMessage(Text.literal("排序：" + ComponentLibraryPanel.sortLabel(st.librarySort)));
        host.sortButton.setPosition(x, y);
        host.sortButton.setWidth(half);
        host.sortButton.visible = true;
        host.sortButton.active = true;
        host.sortButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);

        host.categoryButton.setMessage(Text.literal("分类：" + (st.libraryFilterCategory != null ? st.libraryFilterCategory.name() : "ALL")));
        host.categoryButton.setPosition(x + half + 4, y);
        host.categoryButton.setWidth(w - half - 4);
        host.categoryButton.visible = true;
        host.categoryButton.active = true;
        host.categoryButton.render(ctx, (int) (host.client().mouse.getX() / host.client().getWindow().getScaleFactor()),
                (int) (host.client().mouse.getY() / host.client().getWindow().getScaleFactor()), 0f);

        y += ComponentLibraryPanel.LABEL_OFFSET;
        host.renderSourceButton(ctx, host.importButton, x, y, half, !com.formacraft.client.component.ClientComponentImport.STATE.busy());
        y = host.renderSourceButton(ctx, host.refreshButton, x + half + 4, y, w - half - 4, host.client().getNetworkHandler() != null);
        host.recipeViewButton.setMessage(Text.literal(st.libraryRecipes ? "返回构件库" : "查看收藏配方"));
        return host.renderSourceButton(ctx,host.recipeViewButton,x,y,w,true);
    
    }

    public static int drawImportStatus(ComponentLibraryPanel host, DrawContext ctx, int x, int w, int y) {

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
        y = host.drawWrappedText(ctx, Text.literal(status), x, y, w, phase == com.formacraft.client.component.ComponentImportClientState.Phase.ERROR ? 0xFFFFAA55 : 0xFFFFFFFF);
        var preview = state.preview();
        if (preview != null || state.busy()) {
            if (preview != null) {
                int half = (w - 4) / 2;
                host.renderSourceButton(ctx, host.confirmImportButton, x, y, half, phase == com.formacraft.client.component.ComponentImportClientState.Phase.PREVIEW);
                host.renderSourceButton(ctx, host.cancelImportButton, x + half + 4, y, w - half - 4, phase != com.formacraft.client.component.ComponentImportClientState.Phase.COMMITTING);
            } else host.renderSourceButton(ctx, host.cancelImportButton, x, y, w, phase != com.formacraft.client.component.ComponentImportClientState.Phase.COMMITTING);
            y += ComponentLibraryPanel.LABEL_OFFSET;
        }
        if (preview != null) {
            y = host.drawWrappedText(ctx, Text.literal("名称: " + preview.name()), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("尺寸: " + preview.width() + "×" + preview.height() + "×" + preview.depth() + "，方块 " + preview.blocks()), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("发布: " + preview.namespace() + " @ " + preview.version()), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("作者声明: " + preview.author()), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("目标: " + (preview.global() ? "全局库" : "私人库")), x, y, w, 0xFFCCCCCC);
            String training = switch (preview.trainingPermission()) { case ALLOWED -> "允许"; case DENIED -> "禁止"; case UNSPECIFIED -> "未声明"; };
            y = host.drawWrappedText(ctx, Text.literal("训练授权: " + training), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("许可: " + preview.license()), x, y, w, 0xFFCCCCCC);
            y = host.drawWrappedText(ctx, Text.literal("确认后按内容去重"), x, y, w, 0xFFAAAAAA);
        }
        return y + 6;
    
    }

    public static int drawSourceDetails(ComponentLibraryPanel host, DrawContext ctx, int x, int w, int y, ComponentCatalog.Entry entry) {

        if (entry.aliases != null && !entry.aliases.isEmpty())
            y = host.drawWrappedText(ctx, Text.literal("其他名称: " + String.join("、", entry.aliases.subList(0, Math.min(3, entry.aliases.size())))), x, y, w, 0xFFAAAAAA);
        if (entry.sourceMetadataError) y = host.drawWrappedText(ctx, Text.literal("附加来源信息待核验"), x, y, w, 0xFFFFAA55);
        else if (entry.sourceCount != null) y = host.drawWrappedText(ctx, Text.literal("来源记录: " + entry.sourceCount), x, y, w, 0xFFAAAAAA);
        var state = com.formacraft.client.component.ClientComponentSourceState.INSTANCE;
        boolean selected = entry.id.equals(state.assetId());
        boolean loading = selected && state.loading();
        y = host.renderSourceButton(ctx, host.sourceButton, x, y + 2, w, !loading);
        if (loading) return host.drawWrappedText(ctx, Text.literal("正在读取来源…"), x, y, w, 0xFFAAAAAA);
        if (!selected) return y;
        if (state.error() != null) return host.drawWrappedText(ctx, Text.literal(state.error()), x, y, w, 0xFFFFAA55);
        var page = state.page(); if (page == null) return y;
        int half = (w - 4) / 2;
        host.renderSourceButton(ctx, host.prevSourceButton, x, y, half, page.index() > 0);
        host.renderSourceButton(ctx, host.nextSourceButton, x + half + 4, y, w - half - 4, page.index() + 1 < page.total());
        y += ComponentLibraryPanel.LABEL_OFFSET;
        y = host.drawWrappedText(ctx, Text.literal("来源 " + (page.index() + 1) + "/" + page.total() + (page.primary() ? "（首份）" : "（附加）")), x, y, w, 0xFFFFFFFF);
        y = host.drawWrappedText(ctx, Text.literal("名称: " + page.name()), x, y, w, 0xFFCCCCCC);
        y = host.drawWrappedText(ctx, Text.literal("发布: " + page.namespace() + " @ " + page.version()), x, y, w, 0xFFCCCCCC);
        y = host.drawWrappedText(ctx, Text.literal("作者声明: " + page.author()), x, y, w, 0xFFCCCCCC);
        y = host.drawWrappedText(ctx, Text.literal("许可: " + page.license()), x, y, w, 0xFFCCCCCC);
        String permission = switch (page.trainingPermission()) { case ALLOWED -> "允许"; case DENIED -> "禁止"; case UNSPECIFIED -> "未声明"; };
        y = host.drawWrappedText(ctx, Text.literal("训练授权: " + permission), x, y, w, 0xFFCCCCCC);
        y = host.drawWrappedText(ctx, Text.literal("内容引用: " + page.contentRef()), x, y, w, 0xFFAAAAAA);
        return host.drawWrappedText(ctx, Text.literal("各来源授权独立，不自动合并"), x, y, w, 0xFFAAAAAA);
    
    }

    public static int drawRecipeEditor(ComponentLibraryPanel host, DrawContext ctx,int x,int w,int y,ComponentCatalog.Entry entry,boolean busy) {

        host.recipeDraft.bind(entry);
        if (entry.recipe) {
            host.restoreRecipeButton.setMessage(Text.literal(entry.communityRecipe ? "恢复社区原件并选择来源" : "恢复 / 复用原件"));
            if (entry.recipeSourceRef != null) y = host.drawWrappedText(ctx,Text.literal("原件版本: "+entry.recipeSourceRef.split("#sha256:",2)[0]),x,y,w,0xFFAAAAAA);
            y = host.drawWrappedText(ctx,Text.literal("结果尺寸已锁定；原件快照可离线恢复"),x,y,w,0xFF888888);
            y = host.drawWrappedText(ctx,Text.literal("新恢复的原件仅写入私人库"),x,y,w,0xFF888888);
            y = host.renderSourceButton(ctx,host.replayRecipeButton,x,y,w,!busy);
            return host.renderSourceButton(ctx,host.restoreRecipeButton,x,y,w,!busy);
        }
        y = host.drawWrappedText(ctx,Text.literal("目标尺寸（方块，X / Y / Z）"),x,y,w,0xFFAAAAAA);
        for (int axis = 0; axis < 3; axis++) {
            y = host.drawWrappedText(ctx,Text.literal(new String[]{"X","Y","Z"}[axis]+": "+host.recipeDraft.size(axis)),x,y,w,0xFFCCCCCC);
            int half = (w-4)/2;
            host.renderSourceButton(ctx,host.recipeSizeButtons[axis*2],x,y,half,!busy && host.recipeDraft.canAdjust(axis,-1));
            host.renderSourceButton(ctx,host.recipeSizeButtons[axis*2+1],x+half+4,y,w-half-4,!busy && host.recipeDraft.canAdjust(axis,1)); y += ComponentLibraryPanel.LABEL_OFFSET;
        }
        y = host.drawWrappedText(ctx,Text.literal("收藏锁定原件快照，保留作者规则"),x,y,w,0xFF888888);
        return host.renderSourceButton(ctx,host.saveRecipeButton,x,y,w,!busy);
    
    }
}
