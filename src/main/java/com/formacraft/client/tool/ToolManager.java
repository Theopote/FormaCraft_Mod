package com.formacraft.client.tool;

import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具状态中心（HUD 模式）。
 */
public final class ToolManager {
    private ToolManager() {}

    private static final Map<String, FormacraftTool> tools = new LinkedHashMap<>();

    private static FormacraftTool activeTool = null;

    static {
        // 内建工具注册
        register(SelectionTool.INSTANCE);
    }

    public static void register(FormacraftTool tool) {
        if (tool == null) return;
        tools.put(tool.getId(), tool);
    }

    public static FormacraftTool getActiveTool() {
        return activeTool;
    }

    public static void setTool(String toolId) {
        FormacraftTool next = tools.get(toolId);
        if (next == activeTool) return;

        if (activeTool != null) {
            try { activeTool.onDeactivate(); } catch (Throwable ignored) {}
        }

        activeTool = next;

        if (activeTool != null) {
            try { activeTool.onActivate(); } catch (Throwable ignored) {}
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

