package com.formacraft.client.tool;

import com.formacraft.common.logging.FcaLog;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具状态中心（HUD 模式）。
 */
public final class ToolManager {
    private ToolManager() {}

    private static final FcaLog LOG = FcaLog.of("ToolManager");

    private static final Map<String, FormacraftTool> tools = new LinkedHashMap<>();

    private static FormacraftTool activeTool = null;

    static {
        // 插件化加载（包含内建工具 BuiltinToolsEntrypoint）
        ToolPluginLoader.loadOnce();
    }

    public static void register(FormacraftTool tool) {
        if (tool == null) return;
        tools.put(tool.getId(), tool);
    }

    /** 获取已注册工具（按注册顺序）。 */
    public static java.util.List<FormacraftTool> getTools() {
        return new java.util.ArrayList<>(tools.values());
    }

    public static int toolCount() {
        return tools.size();
    }

    public static FormacraftTool getActiveTool() {
        return activeTool;
    }

    public static void setTool(String toolId) {
        FormacraftTool next = tools.get(toolId);
        if (next == activeTool) return;

        if (activeTool != null) {
            try { activeTool.onDeactivate(); } catch (Throwable t) {
                LOG.warn("tool onDeactivate failed toolId={}", activeTool.getId(), t);
            }
        }

        activeTool = next;

        if (activeTool != null) {
            try { activeTool.onActivate(); } catch (Throwable t) {
                LOG.warn("tool onActivate failed toolId={}", activeTool.getId(), t);
            }
        }
    }

    public static boolean isActive(String toolId) {
        return activeTool != null && activeTool.getId().equals(toolId);
    }

    public static boolean handleWorldClick(double mx, double my, int button) {
        if (activeTool == null) return false;
        return activeTool.onMouseClick(mx, my, button);
    }

    public static void tick() {
        if (activeTool == null) return;
        // 避免启动早期 NPE
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        activeTool.tick();
    }

    public static void renderWorld(ToolWorldRenderContext ctx) {
        if (activeTool == null) return;
        activeTool.renderWorld(ctx);
    }
}

