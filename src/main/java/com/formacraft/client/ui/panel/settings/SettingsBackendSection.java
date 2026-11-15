package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.backend.BackendAutoStarter;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.nio.file.Path;
import java.util.List;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Draft configuration is saved by the footer. Process controls operate on committed settings. */
public final class SettingsBackendSection {
    private final HudTextInput directory = new HudTextInput(), python = new HudTextInput(), port = new HudTextInput(), packs = new HudTextInput();
    private final SettingsPanelRenderHost host;
    private final ButtonWidget start, stop, check, auto, validatePacks, details;
    private String packStatus = "风格包目录在保存后，重启游戏与后端生效";
    private boolean checkingPacks, autoStart;
    private long revision;

    public SettingsBackendSection(SettingsPanelRenderHost host) {
        this.host = host;
        directory.setMaxLength(1024); python.setMaxLength(1024); port.setMaxLength(5); packs.setMaxLength(1024);
        python.setPlaceholder("留空自动检测 Python");
        validatePacks = button("检查风格包（不保存）", () -> {
            final Path path;
            try { path = packsPath(); }
            catch (RuntimeException e) { host.showToast("风格包路径无效", true); return; }
            checkingPacks = true;
            long requestedRevision = revision;
            String requested = packs.getText();
            java.util.concurrent.CompletableFuture.supplyAsync(() -> com.formacraft.common.style.UserStylePacks.discover(path))
                    .whenComplete((report,error) -> host.client().execute(() -> {
                        if (revision != requestedRevision) return;
                        checkingPacks = false;
                        if (!requested.equals(packs.getText())) return;
                        if (error != null) { packStatus = "风格包检查失败：" + error.getMessage(); host.showToast(packStatus,true); return; }
                        packStatus = "通过 " + report.accepted().size() + " 个包" + (report.errors().isEmpty()
                                ? "；保存后重启游戏与后端生效" : "；拒绝：" + String.join("；",report.errors()));
                        host.showToast(packStatus,!report.errors().isEmpty());
                    }));
        });
        start = button("启动已保存配置", () -> BackendAutoStarter.startAsync(SettingsConfig.INSTANCE));
        stop = button("停止", () -> BackendAutoStarter.stopAsync());
        check = button("检查状态", () -> BackendAutoStarter.checkAsync(host.orchestratorInput().getText()));
        auto = button("", () -> autoStart = !autoStart);
        details = button("查看后端状态详情…", () -> host.openDetails(BackendAutoStarter.getStatus() + "\n\n" + packStatus
                + "\n\n日志：logs/formacraft_orchestrator.log"));
    }

    private static ButtonWidget button(String label, Runnable action) {
        return ButtonWidget.builder(Text.literal(label),b -> action.run()).dimensions(0,0,100,BUTTON_HEIGHT).build();
    }
    public void load(SettingsConfig cfg) {
        revision++; checkingPacks = false;
        directory.setText(cfg.backendWorkDir); python.setText(cfg.pythonExecutable);
        port.setText(Integer.toString(cfg.backendPort)); autoStart = cfg.autoStartBackend;
        packs.setText(cfg.userStylePacksDirectory);
        packStatus = "风格包目录在保存后，重启游戏与后端生效";
    }
    private Path packsPath() {
        return Path.of(packs.getText().trim().isEmpty() ? "config/formacraft/style_packs" : packs.getText().trim()).toAbsolutePath().normalize();
    }
    public void stage(SettingsConfig draft) {
        int number;
        try { number = Integer.parseInt(port.getText().trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("后端端口须为 1–65535 的整数"); }
        if (number < 1 || number > 65535) throw new IllegalArgumentException("后端端口须为 1–65535 的整数");
        final String path;
        try { path = packsPath().toString(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("风格包路径无效"); }
        draft.backendWorkDir = directory.getText().trim(); draft.pythonExecutable = python.getText().trim();
        draft.backendPort = number; draft.autoStartBackend = autoStart; draft.userStylePacksDirectory = path;
    }
    public int draw(DrawContext ctx, int x, int y, int w) {
        var ui = host.ui(); var client = host.client();
        for (var field : List.of(directory,python,port,packs)) {
            String label = field == directory ? "后端目录" : field == python ? "Python 路径" : field == packs ? "用户风格包目录" : "本机启动端口";
            ui.label(ctx,client,label,x,y,w,COLOR_GRAY);
            ui.input(ctx,field,x,y+LABEL_OFFSET,w,INPUT_HEIGHT); y += LABEL_OFFSET+FIELD_SPACING;
        }
        auto.setMessage(Text.literal("自动启动：" + (autoStart ? "开启" : "关闭") + "（保存生效）"));
        auto.active = true;
        ui.widget(ctx,client,auto,x,y,w,BUTTON_HEIGHT); y += BUTTON_ROW_HEIGHT;
        validatePacks.active = !checkingPacks;
        validatePacks.setMessage(Text.literal(checkingPacks ? "检查中…" : "检查风格包（不保存）"));
        ui.widget(ctx,client,validatePacks,x,y,w,BUTTON_HEIGHT); y += BUTTON_ROW_HEIGHT;
        ui.label(ctx,client,packStatus,x,y,w,COLOR_GRAY); y += LABEL_OFFSET;
        ui.label(ctx,client,"以下操作立即执行",x,y,w,COLOR_GRAY); y += LABEL_OFFSET;
        start.active = !BackendAutoStarter.isStarting();
        ui.widget(ctx,client,start,x,y,w,BUTTON_HEIGHT); y += BUTTON_ROW_HEIGHT;
        int half=(w-BUTTON_GAP)/2;
        stop.active = !BackendAutoStarter.isStarting() && BackendAutoStarter.ownsRunningProcess();
        check.active = !BackendAutoStarter.isStarting();
        ui.widget(ctx,client,stop,x,y,half,BUTTON_HEIGHT);
        ui.widget(ctx,client,check,x+half+BUTTON_GAP,y,w-half-BUTTON_GAP,BUTTON_HEIGHT); y += BUTTON_ROW_HEIGHT;
        ui.label(ctx,client,"状态："+BackendAutoStarter.getStatus().replace('\n',' '),x,y,w,COLOR_GRAY); y += LABEL_OFFSET;
        ui.widget(ctx,client,details,x,y,w,BUTTON_HEIGHT); y += BUTTON_ROW_HEIGHT;
        ui.label(ctx,client,"远程服务请在服务器端管理",x,y,w,COLOR_GRAY);
        return y+LABEL_OFFSET+GROUP_GAP;
    }
}
