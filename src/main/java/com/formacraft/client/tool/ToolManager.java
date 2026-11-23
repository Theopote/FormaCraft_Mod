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

    /** Tracks {@link MinecraftClient#world} identity for cross-world / disconnect cleanup. */
    private static Object worldScope;

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

    /**
     * Clear every tool's world-scoped draft + committed constraints and deactivate.
     * Call on disconnect and when {@code client.world} identity changes.
     */
    public static void resetWorldState() {
        if (activeTool != null) {
            try { activeTool.onDeactivate(); } catch (Throwable t) {
                LOG.warn("tool onDeactivate failed during resetWorldState toolId={}", activeTool.getId(), t);
            }
            activeTool = null;
        }
        for (FormacraftTool tool : tools.values()) {
            try {
                tool.resetWorldState();
            } catch (Throwable t) {
                LOG.warn("tool resetWorldState failed toolId={}", tool.getId(), t);
            }
        }
        worldScope = null;
    }

    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        Object world = client == null ? null : client.world;
        if (worldScope != world) {
            // Leaving a previous world (dimension / save / unload). null→world first attach keeps empty tools.
            if (worldScope != null) {
                resetWorldState();
            }
            worldScope = world;
        }
        if (activeTool == null) return;
        if (client == null || client.world == null) return;
        if (!com.formacraft.client.ui.FormacraftUIState.isOpen
                || com.formacraft.client.preview.PreviewModalState.isLocked()) {
            activeTool.cancelDraft();
            return;
        }
        activeTool.tick();
    }

    public static void renderWorld(ToolWorldRenderContext ctx) {
        for (FormacraftTool tool : tools.values()) {
            if (tool == activeTool || tool.hasPersistentOverlay()) tool.renderWorld(ctx);
        }
    }
}
