package com.formacraft.client.ui;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * FormaCraft UI 全局状态管理器
 * 管理 UI 的打开/关闭状态
 */
public class FormacraftUIState {
    
    /**
     * UI 是否打开
     */
    public static boolean isOpen = false;
    
    /**
     * 切换 UI 状态（打开/关闭）
     */
    public static void toggle() {
        isOpen = !isOpen;
        updateCursorState();
    }
    
    /**
     * 打开 UI
     */
    public static void open() {
        isOpen = true;
        updateCursorState();
    }
    
    /**
     * 关闭 UI
     */
    public static void close() {
        isOpen = false;
        updateCursorState();
    }
    
    /**
     * 更新光标状态
     * 当 UI 打开时显示光标，关闭时隐藏光标
     */
    private static void updateCursorState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }
        
        long window = client.getWindow().getHandle();
        
        if (isOpen) {
            // UI 打开时，显示光标（正常光标）
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        } else {
            // UI 关闭时，隐藏光标（禁用光标，用于游戏视角控制）
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }
    
    /**
     * 确保光标状态正确（在客户端 Tick 时调用）
     * 用于处理某些情况下光标状态可能被重置的情况
     */
    public static void ensureCursorState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }
        
        long window = client.getWindow().getHandle();
        int currentMode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
        
        if (isOpen) {
            // UI 应该打开，但光标可能被隐藏了
            if (currentMode == GLFW.GLFW_CURSOR_DISABLED) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            }
        } else {
            // UI 应该关闭，但光标可能显示了
            if (currentMode == GLFW.GLFW_CURSOR_NORMAL && client.currentScreen == null) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            }
        }
    }
}

