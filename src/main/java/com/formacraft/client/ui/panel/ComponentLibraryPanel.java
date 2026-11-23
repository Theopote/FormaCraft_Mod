package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.component.ComponentLibraryUsage;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.client.network.FormaCraftClientNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
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
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }
public static final int CONTENT_PADDING = UiTheme.CONTENT_PADDING;
    public static final int LABEL_OFFSET = 18;
    public static final int BUTTON_HEIGHT = 16;

    public final HudTextInput searchInput = new HudTextInput();
    public final CommunityLibraryPane communityPane = new CommunityLibraryPane();
    public boolean communityMode;
    public ButtonWidget communityToggle;

    public ButtonWidget sortButton;
    public ButtonWidget categoryButton;
    public ButtonWidget prevPageButton;
    public ButtonWidget nextPageButton;
    
    // 验证和修复按钮（当有加载的构件时显示）
    public ButtonWidget validateButton;
    public ButtonWidget autoFixButton;
    
    // 删除按钮（当选中构件时显示）
    public ButtonWidget deleteButton, exportButton, cancelExportButton, refreshButton;
    public ButtonWidget sourceButton, prevSourceButton, nextSourceButton;
    public ButtonWidget importButton, confirmImportButton, cancelImportButton;
    public ButtonWidget recipeViewButton, saveRecipeButton, replayRecipeButton, restoreRecipeButton;
    public final ButtonWidget[] recipeSizeButtons = new ButtonWidget[6];
    public final com.formacraft.client.component.ComponentRecipeDraft recipeDraft = new com.formacraft.client.component.ComponentRecipeDraft();
    public com.formacraft.client.component.ComponentImportClientState.Phase lastImportPhase = com.formacraft.client.component.ComponentImportClientState.Phase.IDLE;


    // grid hitboxes
    public static final class GridItem {
        public final ComponentCatalog.Entry entry;
        public final int x, y, w, h;
        public GridItem(ComponentCatalog.Entry entry, int x, int y, int w, int h) {
            this.entry = entry; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        public boolean hit(double mx, double my) {
            return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
        }
    }
    public final List<GridItem> grid = new ArrayList<>();
    public boolean gridValid = false;

    // search input bounds
    public int searchX, searchY, searchW, searchH;
    public boolean searchBoundsValid = false;

    // 滚动（固定搜索/排序行，下方内容可滚）
    public int scrollY = 0;
    public int maxScrollY = 0;
    public int scrollAreaTop = 0;

    // double-click
    public long lastClickMs = 0L;
    public String lastClickId = null;
    public static final long DOUBLE_CLICK_MS = 280L;

    public static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    public ComponentLibraryPanel() {
        searchInput.setMaxLength(64);
    }

    public void ensureWidgets() { com.formacraft.client.ui.panel.library.LibraryWidgetBuilder.ensureWidgets(this); }

    public void cycleSort() {
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

    public void cycleCategory() {
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

    public ComponentCatalog.Entry selectedEntry() {
        var catalog = ClientComponentCatalogState.getCatalog();
        var id = ComponentTool.INSTANCE.getState().librarySelectedId;
        return catalog == null || catalog.components == null ? null : catalog.components.stream()
                .filter(e -> e != null && java.util.Objects.equals(id,e.id)).findFirst().orElse(null);
    }

    public void deleteSelectedComponent() {
        com.formacraft.client.component.ClientComponentLibraryOperations.start(selectedEntry(), true);
    }

    @Override
    protected void drawContents(DrawContext ctx) { com.formacraft.client.ui.panel.library.LibraryContents.drawContents(this, ctx); }

    /** 固定区：搜索 + 排序/分类（不随滚动移动） */
    public int drawLibraryFixedHeader(DrawContext ctx, int x, int w, int y, ComponentToolState st) { return com.formacraft.client.ui.panel.library.LibraryLayoutRenderer.drawLibraryFixedHeader(this, ctx, x, w, y, st); }

    /** 可滚动区：分页、网格、选中/验证/修复等 */
    public int drawLibraryScrollable(DrawContext ctx, int x, int w, int y, ComponentToolState st) { return com.formacraft.client.ui.panel.library.LibraryLayoutRenderer.drawLibraryScrollable(this, ctx, x, w, y, st); }

    public static @NotNull Comparator<ComponentCatalog.Entry> getEntryComparator(ComponentToolState st, String q) {
        return com.formacraft.client.component.ComponentLibraryOrdering.comparator(st.librarySort,
                e -> matchScore(e,q), e -> ComponentLibraryUsage.getLastLoadedMs(e.id));
    }

    public static String sortLabel(String s) {
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
    public void requestSource(int index) {
        FormaCraftClientNetworking.sendComponentSourceRequest(ComponentTool.INSTANCE.getState().librarySelectedId, index);
    }

    public int drawRecipeEditor(DrawContext ctx,int x,int w,int y,ComponentCatalog.Entry entry,boolean busy) { return com.formacraft.client.ui.panel.library.LibraryLayoutRenderer.drawRecipeEditor(this, ctx, x, w, y, entry, busy); }

    public int drawImportStatus(DrawContext ctx, int x, int w, int y) { return com.formacraft.client.ui.panel.library.LibraryLayoutRenderer.drawImportStatus(this, ctx, x, w, y); }

    public void stepSource(int delta) {
        var state = com.formacraft.client.component.ClientComponentSourceState.INSTANCE;
        var page = state.page();
        if (page != null && java.util.Objects.equals(state.assetId(), ComponentTool.INSTANCE.getState().librarySelectedId))
            requestSource(page.index() + delta);
    }

    public int renderSourceButton(DrawContext ctx, ButtonWidget button, int x, int y, int w, boolean active) {
        button.setPosition(x, y); button.setWidth(w); button.visible = true; button.active = active;
        button.render(ctx, getScaledMouseXForSources(), (int) (client.mouse.getY() / client.getWindow().getScaleFactor()), 0f);
        return y + LABEL_OFFSET;
    }

    public int getScaledMouseXForSources() { return (int) (client.mouse.getX() / client.getWindow().getScaleFactor()); }

    public int drawSourceDetails(DrawContext ctx, int x, int w, int y, ComponentCatalog.Entry entry) { return com.formacraft.client.ui.panel.library.LibraryLayoutRenderer.drawSourceDetails(this, ctx, x, w, y, entry); }

    public static int matchScore(ComponentCatalog.Entry e, String q) {
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

    public static String displayName(ComponentCatalog.Entry e) {
        if (e == null) return "";
        if (e.name != null && !e.name.isBlank()) return e.name;
        return e.id != null ? e.id : "";
    }

    public static boolean typeSearchMatches(ComponentCatalog.Entry entry, String text) {
        var type = com.formacraft.common.component.ComponentTypes.effective(entry.type, entry.category);
        var match = com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(text, type.label(), java.util.List.of(type.key()));
        return match || entry.typeAliases != null && entry.typeAliases.stream().anyMatch(alias ->
                com.formacraft.common.component.query.ComponentQueryMatchUtil.nameSearchMatches(text, alias.label(), java.util.List.of(alias.key())));
    }

    // 简化复制 ToolPanel 的换行绘制（BasePanel 没提供该工具方法）
    public int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.library.LibraryInteraction.mouseClicked(this, mouseX, mouseY, button); }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        if (communityMode) { communityPane.key(keyCode,modifiers); return; }
        searchInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        if (communityMode) { communityPane.character(chr); return; }
        searchInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        if (communityMode) return communityPane.focused();
        return searchInput.isFocused();
    }

    @Override
    public void blurKeyboardFocus() {
        searchInput.setFocused(false);
        if (communityMode) communityPane.blur();
    }

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return com.formacraft.client.ui.panel.library.LibraryInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }
    
    /**
     * 构建构件的 tooltip 内容
     */
    public List<Text> buildComponentTooltip(ComponentCatalog.Entry entry) { return com.formacraft.client.ui.panel.library.LibraryInteraction.buildComponentTooltip(this, entry); }
    
    /**
     * 格式化时间差（毫秒 -> 人类可读）
     */
    public String formatAge(long ageMs) {
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

