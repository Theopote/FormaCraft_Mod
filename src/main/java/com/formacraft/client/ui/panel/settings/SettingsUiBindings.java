package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/** The rendered controls are the source of truth for hit testing, focus and scrolling. */
public final class SettingsUiBindings {
    private record Region(int x, int y, int w, int h, boolean fixed, HudTextInput input, ClickableWidget widget, String tip) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private final List<Region> regions = new ArrayList<>();
    private final List<HudTextInput> knownInputs = new ArrayList<>();
    private final List<ClickableWidget> knownWidgets = new ArrayList<>();
    private int top, bottom;
    private boolean fixed;
    private SettingsSliders.Base dragging;

    public void begin(int top, int bottom) {
        this.top = top; this.bottom = bottom; fixed = false;
        regions.clear();
        knownWidgets.forEach(w -> w.visible = false);
    }
    public void fixed(boolean fixed) { this.fixed = fixed; }
    public void finish() {
        for (var input : knownInputs) if (regions.stream().noneMatch(r -> r.input == input)) input.setFocused(false);
    }
    public void blur() { knownInputs.forEach(i -> i.setFocused(false)); dragging = null; }
    public List<HudTextInput> inputs() { return regions.stream().map(Region::input).filter(i -> i != null).toList(); }
    public HudTextInput focused() { return knownInputs.stream().filter(HudTextInput::isFocused).findFirst().orElse(null); }
    private boolean accepts(Region r, double mx, double my) { return r.contains(mx, my) && (r.fixed || my >= top && my < bottom); }

    public void input(DrawContext ctx, HudTextInput input, int x, int y, int w, int h) {
        if (!knownInputs.contains(input)) knownInputs.add(input);
        regions.add(new Region(x,y,w,h,fixed,input,null,input.getPlaceholder()));
        input.render(ctx,x,y,w,h);
    }
    public void widget(DrawContext ctx, MinecraftClient client, ClickableWidget widget, int x, int y, int w, int h) {
        if (!knownWidgets.contains(widget)) knownWidgets.add(widget);
        widget.setPosition(x,y); widget.setWidth(w); widget.setHeight(h); widget.visible = true;
        regions.add(new Region(x,y,w,h,fixed,null,widget,widget.getMessage().getString()));
        widget.render(ctx,(int)SettingsPanelDrawSupport.scaledMouseX(client),(int)SettingsPanelDrawSupport.scaledMouseY(client),0);
    }
    public void label(DrawContext ctx, MinecraftClient client, String text, int x, int y, int w, int color) {
        regions.add(new Region(x,y,w,16,fixed,null,null,text));
        ctx.drawTextWithShadow(client.textRenderer,Text.literal(client.textRenderer.trimToWidth(text,w)),x,y+3,color);
    }
    public String tooltip(double mx, double my) {
        for (var r : regions) if (accepts(r,mx,my) && r.tip != null && !r.tip.isBlank()) return r.tip;
        return null;
    }
    public boolean click(double mx, double my, int button) {
        if (button != 0) return false;
        for (var r : regions) if (accepts(r,mx,my) && (r.input != null || r.widget != null)) {
            if (r.widget != null && !r.widget.active) return true;
            blur();
            if (r.input != null) return r.input.mouseClicked(mx,my,r.x,r.y,r.w,r.h);
            if (r.widget instanceof SettingsSliders.Base slider) {
                HudClickSupport.clearPending(); dragging = slider; slider.dragWithMouse(mx); return true;
            }
            return HudClickSupport.click(r.widget,new Click(mx,my,new MouseInput(button,0)));
        }
        blur();
        return false;
    }
    public boolean drag(double mx) {
        if (dragging == null) return false;
        dragging.dragWithMouse(mx); return true;
    }
    public void release() { dragging = null; }
    public boolean scrollInput(double mx, double my, double amount) {
        for (var r : regions) if (r.input != null && accepts(r,mx,my)
                && r.input.mouseScrolled(mx,my,amount,r.x,r.y,r.w,r.h)) return true;
        return false;
    }
    /** Return the scroll adjustment needed to keep the new focus in the viewport. */
    public int tab(boolean backwards) {
        var fields = regions.stream().filter(r -> r.input != null).toList();
        if (fields.isEmpty()) return 0;
        int current = -1;
        for (int i=0;i<fields.size();i++) if(fields.get(i).input.isFocused()) current=i;
        int next = current < 0 ? (backwards ? fields.size()-1 : 0) : Math.floorMod(current+(backwards?-1:1),fields.size());
        blur();
        Region r = fields.get(next); r.input.setFocused(true);
        return r.y < top ? r.y-top : Math.max(0,r.y+r.h-bottom);
    }
}
