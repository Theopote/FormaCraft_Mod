package com.formacraft.client.ui;

import com.formacraft.client.ui.panel.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * FormaCraft HUD Overlay 主入口
 * 管理所有 UI 面板的渲染和交互
 * 固定左侧栏模式：展开宽度见 {@link com.formacraft.client.ui.panel.BasePanel} 的 SIDEBAR_EXPANDED_WIDTH，
 * 工具栏（TabBar）集成在面板顶部。
 */
@Environment(EnvType.CLIENT)
@SuppressWarnings("deprecation")
public class FormaCraftHudOverlay implements HudRenderCallback {
    
    // 面板实例（公开访问，供 InputRouter 使用）
    public static ChatPanel CHAT_PANEL;
    public static ToolPanel TOOL_PANEL;
    public static ComponentLibraryPanel COMPONENT_LIBRARY_PANEL;
    public static ComponentCapturePanel COMPONENT_CAPTURE_PANEL;
    public static SettingsPanel SETTINGS_PANEL;
    public static final BuildConfirmPanel BUILD_CONFIRM_PANEL = BuildConfirmPanel.INSTANCE;

    private static boolean panelsReady = false;

    /**
     * 延迟初始化面板（避免在 MinecraftClient 构造阶段就触发 MinecraftClient.getInstance()/textRenderer）
     * @return true 表示面板已可用
     */
    public static boolean ensurePanelsReady() {
        if (panelsReady) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        // MinecraftClient 在启动早期可能尚未就绪（尤其是 entrypoint 阶段）
        if (mc == null || mc.getWindow() == null || mc.textRenderer == null) {
            return false;
        }

        if (CHAT_PANEL == null) CHAT_PANEL = new ChatPanel();
        if (TOOL_PANEL == null) TOOL_PANEL = new ToolPanel();
        if (COMPONENT_LIBRARY_PANEL == null) COMPONENT_LIBRARY_PANEL = new ComponentLibraryPanel();
        if (COMPONENT_CAPTURE_PANEL == null) COMPONENT_CAPTURE_PANEL = new ComponentCapturePanel();
        if (SETTINGS_PANEL == null) SETTINGS_PANEL = new SettingsPanel();

        panelsReady = true;
        return true;
    }

    /**
     * 初始化面板（在客户端初始化时调用）
     */
    public static void initialize() {
    }
    
    // 当前激活的面板
    public static PanelType activePanel = PanelType.CHAT;
    
    /**
     * 注册 HUD Overlay 渲染事件
     */
    public static void register() {
        HudRenderCallback.EVENT.register(new FormaCraftHudOverlay());
    }
    
    @Override
    public void onHudRender(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        
        // 如果当前有 Screen（比如 ESC 菜单），就先不显示我们的面板
        if (client.currentScreen != null) return;
        
        // 如果 UI 未打开，不渲染
        if (!FormacraftUIState.isOpen) return;

        if (!ensurePanelsReady()) return;
        
        // 根据激活的面板渲染对应内容
        switch (activePanel) {
            case CHAT -> { if (CHAT_PANEL != null) CHAT_PANEL.render(context); }
            case TOOLS -> { if (TOOL_PANEL != null) TOOL_PANEL.render(context); }
            case COMPONENT_LIBRARY -> { if (COMPONENT_LIBRARY_PANEL != null) COMPONENT_LIBRARY_PANEL.render(context); }
            case COMPONENT_CAPTURE -> { if (COMPONENT_CAPTURE_PANEL != null) COMPONENT_CAPTURE_PANEL.render(context); }
            case SETTINGS -> { if (SETTINGS_PANEL != null) SETTINGS_PANEL.render(context); }
            case NONE -> {} // 无操作
        }
        
        // 建造确认浮层（居中显示）
        BUILD_CONFIRM_PANEL.render(context);
    }
}

