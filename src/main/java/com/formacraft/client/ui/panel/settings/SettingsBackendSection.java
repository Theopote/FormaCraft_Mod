package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.backend.BackendAutoStarter;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import java.util.List;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Local process controls. Backend-only save does not require an LLM API key. */
public final class SettingsBackendSection {
    private final HudTextInput directory = new HudTextInput(), python = new HudTextInput(), port = new HudTextInput();
    private final SettingsPanelRenderHost host;
    private final ButtonWidget start, stop, check, auto;
    private int x, top, width;
    private boolean autoStart;

    public SettingsBackendSection(SettingsPanelRenderHost host) {
        this.host = host;
        directory.setMaxLength(1024); python.setMaxLength(1024); port.setMaxLength(5);
        start = ButtonWidget.builder(Text.literal("保存并启动"), b -> {
            if (save()) { SettingsConfig.save(); BackendAutoStarter.startAsync(SettingsConfig.INSTANCE); }
        }).dimensions(0,0,80,BUTTON_HEIGHT).build();
        stop = ButtonWidget.builder(Text.literal("停止"), b -> BackendAutoStarter.stopAsync())
                .dimensions(0,0,60,BUTTON_HEIGHT).build();
        check = ButtonWidget.builder(Text.literal("检查状态"), b -> BackendAutoStarter.checkAsync(host.orchestratorInput().getText()))
                .dimensions(0,0,80,BUTTON_HEIGHT).build();
        auto = ButtonWidget.builder(Text.empty(), b -> {
            autoStart = !autoStart;
            if (save()) SettingsConfig.save();
            else autoStart = !autoStart;
        }).dimensions(0,0,100,BUTTON_HEIGHT).build();
    }

    public void load() {
        var cfg = SettingsConfig.INSTANCE;
        directory.setText(cfg.backendWorkDir); python.setText(cfg.pythonExecutable);
        port.setText(Integer.toString(cfg.backendPort)); autoStart = cfg.autoStartBackend;
    }

    public boolean save() {
        final int number;
        try { number = Integer.parseInt(port.getText().trim()); }
        catch (RuntimeException e) { host.showToast("后端端口必须为 1–65535",true); return false; }
        if (number < 1 || number > 65535) { host.showToast("后端端口必须为 1–65535",true); return false; }
        var cfg = SettingsConfig.INSTANCE;
        cfg.backendWorkDir = directory.getText().trim(); cfg.pythonExecutable = python.getText().trim();
        cfg.backendPort = number; cfg.autoStartBackend = autoStart;
        String endpoint = host.orchestratorInput().getText().trim();
        cfg.orchestratorEndpoint = endpoint.isEmpty() ? "http://localhost:"+number
                : endpoint.contains("://") ? endpoint : "http://"+endpoint;
        host.orchestratorInput().setText(cfg.orchestratorEndpoint);
        return true;
    }

    public int draw(DrawContext ctx, int x, int y, int w) {
        this.x=x; top=y; width=w;
        var client=host.client();
        for (var field : List.of(directory,python,port)) {
            String label=field==directory ? "后端目录（python_backend）" : field==python ? "Python 路径（留空自动检测）" : "启动端口（与后端地址一致）";
            SettingsPanelDrawSupport.drawSmallLabel(client,ctx,Text.literal(label),x,y);
            field.render(ctx,x,y+LABEL_OFFSET,w,INPUT_HEIGHT); y+=LABEL_OFFSET+FIELD_SPACING;
        }
        int buttonWidth=Math.max(0,(w-8)/3), index=0;
        for (var button : List.of(start,stop,check)) {
            button.setPosition(x+index*(buttonWidth+4),y); button.setWidth(buttonWidth);
            button.active=!BackendAutoStarter.isStarting() && (button!=stop || BackendAutoStarter.ownsRunningProcess());
            button.render(ctx,(int)SettingsPanelDrawSupport.scaledMouseX(client),(int)SettingsPanelDrawSupport.scaledMouseY(client),0);
            index++;
        }
        y+=BUTTON_ROW_HEIGHT;
        auto.setMessage(Text.literal("自动启动："+(autoStart ? "开启" : "关闭")));
        auto.setPosition(x,y); auto.setWidth(w); auto.active=!BackendAutoStarter.isStarting();
        auto.render(ctx,(int)SettingsPanelDrawSupport.scaledMouseX(client),(int)SettingsPanelDrawSupport.scaledMouseY(client),0);
        y+=BUTTON_ROW_HEIGHT;
        String status=BackendAutoStarter.getStatus();
        start.setTooltip(Tooltip.of(Text.literal(status)));
        ctx.drawTextWithShadow(client.textRenderer,client.textRenderer.trimToWidth("状态："+status.replace('\n',' '),w),x,y,COLOR_GRAY);
        y+=LABEL_OFFSET;
        for (String line : List.of("首次需安装 Python 和后端依赖", "日志：logs/formacraft_orchestrator.log", "仅管理本机进程；远程服务器请单独启动")) {
            ctx.drawTextWithShadow(client.textRenderer,client.textRenderer.trimToWidth(line,w),x,y,COLOR_GRAY); y+=LABEL_OFFSET;
        }
        return y+GROUP_GAP;
    }

    public boolean click(double mx,double my,int button) {
        if (button!=0) return false;
        int y=top;
        for (var field : List.of(directory,python,port)) {
            if (field.mouseClicked(mx,my,x,y+LABEL_OFFSET,width,INPUT_HEIGHT)) {
                for (var other : List.of(directory,python,port)) if (other!=field) other.setFocused(false);
                return true;
            }
            y+=LABEL_OFFSET+FIELD_SPACING;
        }
        var click=new Click(mx,my,new MouseInput(button,0));
        for (var widget : List.of(start,stop,check,auto)) if (HudClickSupport.click(widget,click)) return true;
        return false;
    }
    public String tooltip(double mx,double my) {
        return start.isMouseOver(mx,my) ? BackendAutoStarter.getStatus() : null;
    }
    public boolean focused() { return directory.isFocused() || python.isFocused() || port.isFocused(); }
    public void blur() { directory.setFocused(false); python.setFocused(false); port.setFocused(false); }
    public void charTyped(char c) { for (var field : List.of(directory,python,port)) if (field.isFocused()) field.charTyped(c); }
    public void keyPressed(int key,int modifiers) { for (var field : List.of(directory,python,port)) if (field.isFocused()) field.keyPressed(key,modifiers); }
}
