package com.formacraft.client.ui.panel.library;

import java.util.ArrayList;

import com.formacraft.client.ui.panel.ComponentLibraryPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.common.component.ComponentCatalog;

import java.util.*;

public final class LibraryInteraction {
    private LibraryInteraction() {}
    public static boolean mouseClicked(ComponentLibraryPanel host, double mouseX, double mouseY, int button) {

        if (host.superMouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        // 注意：不在这里检查 isMouseOver，因为 super.mouseClicked 已经检查过了
        // 如果 super 返回 false，说明鼠标不在面板内或没有命中标签栏/按钮
        // 此时我们需要检查面板内的其他元素（按钮、输入框、网格项）
        if (!host.isMouseOver(mouseX, mouseY)) return false;

        host.ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        if (ComponentLibraryPanel.clickButton(host.communityToggle,click)) { host.blurKeyboardFocus(); return true; }
        if (host.communityMode) { host.communityPane.click(mouseX,mouseY,button); return true; }

        // 先处理搜索框（点击可获焦；点在外面会自行失焦）
        if (host.searchBoundsValid) {
            if (host.searchInput.mouseClicked(mouseX, mouseY, host.searchX, host.searchY, host.searchW, host.searchH)) {
                return true;
            }
        } else {
            host.searchInput.setFocused(false);
        }

        // allow buttons
        if (ComponentLibraryPanel.clickButton(host.sortButton, click)) return true;
        if (ComponentLibraryPanel.clickButton(host.categoryButton, click)) return true;
        if (ComponentLibraryPanel.clickButton(host.importButton, click)) return true;
        if (ComponentLibraryPanel.clickButton(host.refreshButton, click)) return true;
        if (ComponentLibraryPanel.clickButton(host.recipeViewButton, click)) return true;
        boolean inScroll = mouseY >= host.scrollAreaTop && mouseY < host.getPanelY() + host.getPanelHeight() - 1;
        if (inScroll && (ComponentLibraryPanel.clickButton(host.saveRecipeButton,click) || ComponentLibraryPanel.clickButton(host.replayRecipeButton,click) || ComponentLibraryPanel.clickButton(host.restoreRecipeButton,click))) return true;
        if (inScroll) for (var sizeButton : host.recipeSizeButtons) if (ComponentLibraryPanel.clickButton(sizeButton,click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.exportButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.cancelExportButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.prevPageButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.nextPageButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.validateButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.autoFixButton, click)) return true;
        if (inScroll && ComponentLibraryPanel.clickButton(host.deleteButton, click)) return true;
        if (mouseY >= host.scrollAreaTop && (ComponentLibraryPanel.clickButton(host.confirmImportButton, click) || ComponentLibraryPanel.clickButton(host.cancelImportButton, click))) return true;
        if (mouseY >= host.scrollAreaTop && (ComponentLibraryPanel.clickButton(host.sourceButton, click) || ComponentLibraryPanel.clickButton(host.prevSourceButton, click) || ComponentLibraryPanel.clickButton(host.nextSourceButton, click))) return true;

        if (host.gridValid && inScroll) {
            for (ComponentLibraryPanel.GridItem it : host.grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    var st = ComponentTool.INSTANCE.getState();
                    st.librarySelectedId = it.entry.id;
                    com.formacraft.client.component.ClientComponentSourceState.INSTANCE.clear();
                    st.librarySelectedName = ComponentLibraryPanel.displayName(it.entry);

                    long now = System.currentTimeMillis();
                    boolean dbl = (host.lastClickId != null && host.lastClickId.equals(it.entry.id) && (now - host.lastClickMs) <= ComponentLibraryPanel.DOUBLE_CLICK_MS);
                    host.lastClickId = it.entry.id;
                    host.lastClickMs = now;
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

    public static boolean drawCustomTooltip(ComponentLibraryPanel host, DrawContext ctx, double mouseX, double mouseY) {

        // 检查鼠标是否悬停在构件上
        if (host.gridValid && mouseY >= host.scrollAreaTop && mouseY < host.getPanelY() + host.getPanelHeight() - 1) {
            ComponentCatalog.Entry hoveredEntry = null;
            for (ComponentLibraryPanel.GridItem it : host.grid) {
                if (it != null && it.hit(mouseX, mouseY)) {
                    hoveredEntry = it.entry;
                    break;
                }
            }
            
            if (hoveredEntry != null) {
                List<Text> tooltipLines = host.buildComponentTooltip(hoveredEntry);
                if (!tooltipLines.isEmpty()) {
                    host.drawTooltipCompatPublic(ctx, tooltipLines, (int) mouseX, (int) mouseY);
                    return true;
                }
            }
        }
        return false;
    
    }

    public static List<Text> buildComponentTooltip(ComponentLibraryPanel host, ComponentCatalog.Entry entry) {

        List<Text> lines = new ArrayList<>();
        
        if (entry == null) return lines;
        
        // 名称（如果有）
        String name = ComponentLibraryPanel.displayName(entry);
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
            String ageStr = host.formatAge(ageMs);
            lines.add(Text.literal("§7更新: §f" + ageStr + "前"));
        }
        
        return lines;
    
    }
}
