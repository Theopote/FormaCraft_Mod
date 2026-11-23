package com.formacraft.client.ui.panel.library;

import com.formacraft.client.ui.panel.ComponentLibraryPanel;
import com.formacraft.client.network.FormaCraftClientNetworking;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import com.formacraft.client.tool.ComponentTool;

import java.util.*;

public final class LibraryWidgetBuilder {
    private LibraryWidgetBuilder() {}
    public static void ensureWidgets(ComponentLibraryPanel host) {

        if (host.sortButton != null) return;

        host.communityToggle = ButtonWidget.builder(Text.literal("浏览社区构件"), b -> {
            host.communityMode = !host.communityMode; host.scrollY = host.maxScrollY = 0;
            host.searchInput.setFocused(false);
            if (!host.communityMode) host.communityPane.leave();
        }).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();

        host.importButton = ButtonWidget.builder(Text.literal("导入构件包…"), b -> { host.scrollY = 0; com.formacraft.client.component.ClientComponentImport.chooseFile(); }).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.refreshButton = ButtonWidget.builder(Text.literal("刷新构件库"), b -> {
            FormaCraftClientNetworking.sendComponentCatalogRequest();
            com.formacraft.client.component.ClientComponentSourceState.INSTANCE.clear(); host.scrollY = 0;
        }).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.confirmImportButton = ButtonWidget.builder(Text.literal("确认入库"), b -> com.formacraft.client.component.ClientComponentImport.confirm()).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.cancelImportButton = ButtonWidget.builder(Text.literal("取消导入"), b -> com.formacraft.client.component.ClientComponentImport.cancel()).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.recipeViewButton = ButtonWidget.builder(Text.literal("查看收藏配方"), b -> {
            var st = ComponentTool.INSTANCE.getState(); st.libraryRecipes = !st.libraryRecipes; st.libraryPage = 0; st.librarySelectedId = null; st.librarySelectedName = null; host.scrollY = 0;
        }).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.saveRecipeButton = ButtonWidget.builder(Text.literal("收藏尺寸配方"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.recipe(host.selectedEntry(),com.formacraft.common.network.ComponentLibraryNetworking.Action.SAVE_RECIPE,host.recipeDraft.spec())).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.replayRecipeButton = ButtonWidget.builder(Text.literal("重放校验配方"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.recipe(host.selectedEntry(),com.formacraft.common.network.ComponentLibraryNetworking.Action.REPLAY_RECIPE,null)).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.restoreRecipeButton = ButtonWidget.builder(Text.literal("恢复 / 复用原件"), b -> {
            var entry=host.selectedEntry();
            if(entry!=null && entry.communityRecipe) {
                try {
                    com.formacraft.client.component.ClientCommunityTasks.restoreRecipe(entry.id);
                    host.communityMode=true; host.scrollY=host.maxScrollY=0; host.searchInput.setFocused(false);
                } catch(java.io.IOException invalid) { com.formacraft.client.ui.toast.HudToast.show("社区配方身份无效，请刷新目录",true); }
            } else com.formacraft.client.component.ClientComponentLibraryOperations.recipe(entry,com.formacraft.common.network.ComponentLibraryNetworking.Action.RESTORE_RECIPE,null);
        }).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        for (int i = 0; i < 6; i++) {
            final int axis = i / 2, delta = i % 2 == 0 ? -1 : 1;
            host.recipeSizeButtons[i] = ButtonWidget.builder(Text.literal(delta < 0 ? "−" : "+"),b -> host.recipeDraft.adjust(axis,delta)).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        }

        host.exportButton = ButtonWidget.builder(Text.literal("导出当前来源包…"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.start(host.selectedEntry(), false)).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.cancelExportButton = ButtonWidget.builder(Text.literal("取消下载"), b -> com.formacraft.client.component.ClientComponentLibraryOperations.cancel()).dimensions(0,0,0,ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.sourceButton = ButtonWidget.builder(Text.literal("查看 / 刷新来源"), b -> host.requestSource(0)).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.prevSourceButton = ButtonWidget.builder(Text.literal("上一份来源"), b -> host.stepSource(-1)).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();
        host.nextSourceButton = ButtonWidget.builder(Text.literal("下一份来源"), b -> host.stepSource(1)).dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT).build();

        host.sortButton = ButtonWidget.builder(Text.literal("排序：最近"), b -> host.cycleSort())
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换排序：最近保存 / 最近加载 / 标签命中 / 名字 / 分类")))
                .build();
        host.categoryButton = ButtonWidget.builder(Text.literal("分类：ALL"), b -> host.cycleCategory())
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("切换分类过滤")))
                .build();
        host.prevPageButton = ButtonWidget.builder(Text.literal("上一页"), b -> {
                    var st = ComponentTool.INSTANCE.getState();
                    st.libraryPage = Math.max(0, st.libraryPage - 1);
                })
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .build();
        host.nextPageButton = ButtonWidget.builder(Text.literal("下一页"), b -> {
                    var st = ComponentTool.INSTANCE.getState();
                    st.libraryPage = st.libraryPage + 1;
                })
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .build();
        
        // 验证和修复按钮
        host.validateButton = ButtonWidget.builder(
                Text.literal("验证"),
                b -> ComponentTool.INSTANCE.validateLoadedComponent()
        )
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("验证当前加载的构件")))
                .build();
        
        host.autoFixButton = ButtonWidget.builder(
                Text.literal("自动修复"),
                b -> ComponentTool.INSTANCE.autoFixLoadedComponent()
        )
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("自动修复构件的明显错误")))
                .build();
        
        host.deleteButton = ButtonWidget.builder(
                Text.literal("删除构件"),
                b -> host.deleteSelectedComponent()
        )
                .dimensions(0, 0, 0, ComponentLibraryPanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("删除选中的构件（不可恢复）")))
                .build();
    
    }
}
