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
 * 固定左侧栏模式：320px 宽度，工具栏集成在面板顶部
 */
@Environment(EnvType.CLIENT)
@SuppressWarnings("deprecation")
public class FormaCraftHudOverlay implements HudRenderCallback {
    
    // 面板实例（公开访问，供 InputRouter 使用）
    public static ChatPanel CHAT_PANEL;
    public static BlueprintPanel BLUEPRINT_PANEL;
    public static SettingsPanel SETTINGS_PANEL;
    public static HistoryPanel HISTORY_PANEL;
    public static final BuildConfirmPanel BUILD_CONFIRM_PANEL = BuildConfirmPanel.INSTANCE;

    private static boolean initialized = false;
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
        if (BLUEPRINT_PANEL == null) BLUEPRINT_PANEL = new BlueprintPanel();
        if (SETTINGS_PANEL == null) SETTINGS_PANEL = new SettingsPanel();
        if (HISTORY_PANEL == null) HISTORY_PANEL = new HistoryPanel();

        // 初始化 BlueprintPanel 的监听器（只做一次）
        if (initialized) {
            BLUEPRINT_PANEL.setListener((spec, name) -> {
                if (spec != null) {
                    BUILD_CONFIRM_PANEL.show(spec);
                }
            });
        }

        panelsReady = true;
        return true;
    }

    /**
     * 初始化面板（在客户端初始化时调用）
     */
    public static void initialize() {
        initialized = true;
        // 如果面板已经初始化完，则立即挂 listener；否则等 ensurePanelsReady() 时挂
        if (BLUEPRINT_PANEL != null) {
            BLUEPRINT_PANEL.setListener((spec, name) -> {
                if (spec != null) {
                    BUILD_CONFIRM_PANEL.show(spec);
                }
            });
        }
    }
    
    // 当前激活的面板
    public static PanelType activePanel = PanelType.CHAT;
    
    // UI 是否可见（已废弃，使用 FormacraftUIState.isOpen）
    @Deprecated
    public static boolean uiVisible = true;
    
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
            case BLUEPRINT -> { if (BLUEPRINT_PANEL != null) BLUEPRINT_PANEL.render(context); }
            case SETTINGS -> { if (SETTINGS_PANEL != null) SETTINGS_PANEL.render(context); }
            case HISTORY -> { if (HISTORY_PANEL != null) HISTORY_PANEL.render(context); }
            case NONE -> {} // 无操作
        }
        
        // 建造确认浮层（居中显示）
        BUILD_CONFIRM_PANEL.render(context);
    }

    
    /**
     * 显示/隐藏 UI（已废弃，使用 FormacraftUIState.toggle()）
     */
    @Deprecated
    public static void toggleUI() {
        FormacraftUIState.toggle();
        uiVisible = FormacraftUIState.isOpen; // 保持向后兼容
    }
    
    /**
     * 处理鼠标点击（从事件处理器调用）
     */
    public static boolean handleMouseClick(double mouseX, double mouseY, int button) {
        if (!FormacraftUIState.isOpen) return false;
        if (!ensurePanelsReady()) return false;
        
        // 面板点击（工具栏点击已集成在面板中）
        if (activePanel != PanelType.NONE) {
            switch (activePanel) {
                case CHAT -> {
                    if (CHAT_PANEL != null && CHAT_PANEL.mouseClicked(mouseX, mouseY, button)) return true;
                }
                case BLUEPRINT -> {
                    if (BLUEPRINT_PANEL != null && BLUEPRINT_PANEL.mouseClicked(mouseX, mouseY, button)) return true;
                }
                case SETTINGS -> {
                    if (SETTINGS_PANEL != null && SETTINGS_PANEL.mouseClicked(mouseX, mouseY, button)) return true;
                }
                case HISTORY -> {
                    if (HISTORY_PANEL != null && HISTORY_PANEL.mouseClicked(mouseX, mouseY, button)) return true;
                }
                case NONE -> { }
                // 无操作
            }
        }
        
        // 确认面板点击
        if (BUILD_CONFIRM_PANEL.isVisible()) {
            return BUILD_CONFIRM_PANEL.mouseClicked(mouseX, mouseY, button);
        }
        
        return false;
    }
    
    /**
     * 处理键盘输入（从事件处理器调用）
     */
    public static boolean handleKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!FormacraftUIState.isOpen) return false;
        if (!ensurePanelsReady()) return false;
        
        // 如果当前面板有输入焦点，处理键盘事件
        if (activePanel != PanelType.NONE) {
            switch (activePanel) {
                case CHAT -> {
                    if (CHAT_PANEL != null) CHAT_PANEL.keyPressed(keyCode, scanCode, modifiers);
                    return true;
                }
                case BLUEPRINT -> {
                    if (BLUEPRINT_PANEL != null) BLUEPRINT_PANEL.keyPressed(keyCode, scanCode, modifiers);
                    return true;
                }
                case SETTINGS -> {
                    if (SETTINGS_PANEL != null) SETTINGS_PANEL.keyPressed(keyCode, scanCode, modifiers);
                    return true;
                }
                case HISTORY -> {
                    if (HISTORY_PANEL != null) HISTORY_PANEL.keyPressed(keyCode, scanCode, modifiers);
                    return true;
                }
                case NONE -> { }
                // 无操作
            }
        }
        
        return false;
    }
    
    /**
     * 处理字符输入（从事件处理器调用）
     */
    public static boolean handleCharTyped(char chr, int modifiers) {
        if (!FormacraftUIState.isOpen) return false;
        if (!ensurePanelsReady()) return false;
        
        // 如果当前面板有输入焦点，处理字符输入
        if (activePanel != PanelType.NONE) {
            switch (activePanel) {
                case CHAT -> {
                    if (CHAT_PANEL != null) CHAT_PANEL.charTyped(chr);
                    return true;
                }
                case BLUEPRINT -> {
                    if (BLUEPRINT_PANEL != null) BLUEPRINT_PANEL.charTyped(chr);
                    return true;
                }
                case SETTINGS -> {
                    if (SETTINGS_PANEL != null) SETTINGS_PANEL.charTyped(chr);
                    return true;
                }
                case HISTORY -> {
                    if (HISTORY_PANEL != null) HISTORY_PANEL.charTyped(chr);
                    return true;
                }
                case NONE -> { }
                // 无操作
            }
        }
        
        return false;
    }
}

