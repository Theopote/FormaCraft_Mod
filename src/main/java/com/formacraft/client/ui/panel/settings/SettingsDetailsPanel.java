package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Readable, scrollable status details outside the narrow sidebar. */
public final class SettingsDetailsPanel {
    private String text;
    private int scroll, maxScroll, x, y, w, h;
    public boolean isOpen() { return text != null; }
    public void open(String text) { this.text = text; scroll = 0; }
    public void close() { text = null; }
    public void scroll(double amount) { scroll = Math.max(0,Math.min(maxScroll,scroll-(int)Math.signum(amount)*36)); }
    public void click(double mx,double my,int button) {
        if (button == 0 && (!contains(mx,my) || mx >= x+w-24 && my < y+22)) close();
    }
    public boolean contains(double mx,double my) { return isOpen() && mx>=x && mx<x+w && my>=y && my<y+h; }
    public void render(DrawContext ctx,MinecraftClient client,int right,int top) {
        if (!isOpen()) return;
        int sw=client.getWindow().getScaledWidth(), sh=client.getWindow().getScaledHeight();
        w=Math.min(380,sw-8); x=right+4+w<=sw ? right+4 : Math.max(4,sw-w-4);
        y=Math.min(top,Math.max(4,sh-100)); h=Math.min(360,sh-y-4);
        ctx.fill(x,y,x+w,y+h,0xFF171B24);
        ctx.drawTextWithShadow(client.textRenderer,Text.literal("状态详情（滚轮浏览）"),x+8,y+6,0xFFFFFFFF);
        ctx.drawTextWithShadow(client.textRenderer,Text.literal("×"),x+w-18,y+6,0xFFFFFFFF);
        var lines=client.textRenderer.wrapLines(Text.literal(text),Math.max(1,w-16));
        maxScroll=Math.max(0,lines.size()*13-(h-32)); scroll=Math.min(scroll,maxScroll);
        ctx.enableScissor(x+4,y+26,x+w-4,y+h-4);
        try {
            int lineY=y+28-scroll;
            for(var line:lines) { ctx.drawTextWithShadow(client.textRenderer,line,x+8,lineY,0xFFDDDDDD); lineY+=13; }
        } finally { ctx.disableScissor(); }
    }
}
