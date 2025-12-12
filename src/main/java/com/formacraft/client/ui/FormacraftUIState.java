package com.formacraft.client.ui;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public class FormacraftUIState {

    public static boolean isOpen = false;

    public static void toggle() {
        isOpen = !isOpen;
        updateCursor();
    }

    public static void open() {
        isOpen = true;
        updateCursor();
    }

    public static void close() {
        isOpen = false;
        updateCursor();
    }

    private static void updateCursor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen != null) return;

        long win = client.getWindow().getHandle();

        if (isOpen) {
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        } else {
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }

    /**
     * 确保光标状态正确（用于定期检查，确保光标状态与 UI 状态一致）
     */
    public static void ensureCursorState() {
        updateCursor();
    }
}
