package com.formacraft.client.ui.panel.library;

import com.formacraft.client.ui.panel.ComponentLibraryPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import com.formacraft.client.tool.ComponentTool;

import java.util.*;

public final class LibraryContents {
    private LibraryContents() {}
    public static void drawContents(ComponentLibraryPanel host, DrawContext ctx) {

        host.ensureWidgets();
        var importPhase = com.formacraft.client.component.ClientComponentImport.STATE.phase();
        if (importPhase != host.lastImportPhase) {
            if (importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.PREVIEW
                    || importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.ERROR
                    || importPhase == com.formacraft.client.component.ComponentImportClientState.Phase.DONE) host.scrollY = 0;
            host.lastImportPhase = importPhase;
        }

        int x = host.getPanelX() + ComponentLibraryPanel.CONTENT_PADDING;
        int w = host.getPanelWidth() - ComponentLibraryPanel.CONTENT_PADDING * 2;
        int y = host.getContentY() + ComponentLibraryPanel.CONTENT_PADDING;

        // reset hitboxes
        host.grid.clear();
        host.gridValid = false;
        host.searchBoundsValid = false;
        host.sourceButton.visible = host.prevSourceButton.visible = host.nextSourceButton.visible = false;
        host.confirmImportButton.visible = host.cancelImportButton.visible = false;
        host.prevPageButton.visible = host.nextPageButton.visible = host.validateButton.visible = host.autoFixButton.visible = host.deleteButton.visible = false;
        host.exportButton.visible = host.cancelExportButton.visible = false;
        host.saveRecipeButton.visible = host.replayRecipeButton.visible = host.restoreRecipeButton.visible = false;
        for (var button : host.recipeSizeButtons) button.visible = false;

        host.drawContentBackgroundPublic(ctx);

        host.communityToggle.setMessage(Text.literal(host.communityMode ? "返回本地构件库" : "浏览社区构件"));
        y = host.renderSourceButton(ctx,host.communityToggle,x,y,w,true);
        if (host.communityMode) {
            host.scrollAreaTop = y;
            int bottom = host.getPanelY() + host.getPanelHeight() - 1;
            ctx.enableScissor(host.contentScissorLeft(),host.scrollAreaTop,host.contentScissorRight(),bottom);
            try {
                int end = host.communityPane.draw(ctx,x,w,y-host.scrollY,host.scrollAreaTop,bottom);
                host.maxScrollY = Math.max(0,end+host.scrollY-y-Math.max(1,bottom-y));
                host.scrollY = Math.min(host.scrollY,host.maxScrollY);
            } finally { ctx.disableScissor(); }
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        int clipBottom = host.getPanelY() + host.getPanelHeight() - 1;

        int headerBottom = host.drawLibraryFixedHeader(ctx, x, w, y, st);
        host.scrollAreaTop = headerBottom;

        ctx.enableScissor(host.contentScissorLeft(), host.scrollAreaTop, host.contentScissorRight(), clipBottom);
        try {
            int sy = host.scrollAreaTop - host.scrollY;
            sy = host.drawLibraryScrollable(ctx, x, w, sy, st);
            int visibleHeight = Math.max(1, clipBottom - host.scrollAreaTop);
            int totalHeight = (sy + host.scrollY) - host.scrollAreaTop;
            host.maxScrollY = Math.max(0, totalHeight - visibleHeight);
            if (host.scrollY > host.maxScrollY) host.scrollY = host.maxScrollY;
            if (host.scrollY < 0) host.scrollY = 0;
        } finally {
            ctx.disableScissor();
        }
    
    }
}
