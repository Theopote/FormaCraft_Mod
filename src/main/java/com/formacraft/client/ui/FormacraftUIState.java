package com.formacraft.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

public class FormacraftUIState {

    public static boolean isOpen = false;

    public static void toggle() {
        isOpen = !isOpen;
        updateCursor();
        if (!isOpen) {
            // UI关闭时，清除所有按键状态，防止残留状态导致玩家继续移动
            clearAllKeyBindings();
        }
    }

    public static void open() {
        isOpen = true;
        updateCursor();
    }

    public static void close() {
        isOpen = false;
        updateCursor();
        // UI关闭时，清除所有按键状态，防止残留状态导致玩家继续移动
        clearAllKeyBindings();
    }
    
    /**
     * 清除所有KeyBinding的按下状态
     * 防止UI关闭后，残留的按键状态导致玩家继续移动
     */
    private static void clearAllKeyBindings() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        
        // 清除所有KeyBinding的按下状态
        // 通过设置所有KeyBinding的pressed为false
        try {
            // 使用反射访问KeyBinding的所有实例
            java.lang.reflect.Field allKeysField = KeyBinding.class.getDeclaredField("KEYS_BY_ID");
            allKeysField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, KeyBinding> allKeys = (java.util.Map<String, KeyBinding>) allKeysField.get(null);
            
            if (allKeys != null) {
                for (KeyBinding keyBinding : allKeys.values()) {
                    // 使用反射设置pressed为false
                    try {
                        java.lang.reflect.Field pressedField = KeyBinding.class.getDeclaredField("pressed");
                        pressedField.setAccessible(true);
                        pressedField.setBoolean(keyBinding, false);
                    } catch (Exception e) {
                        // 如果字段名不对，尝试其他可能的字段名
                        String[] possibleFields = {"pressed", "f_pressed", "pressed_", "pressed$", "isPressed", "isPressed_"};
                        for (String fieldName : possibleFields) {
                            try {
                                java.lang.reflect.Field field = KeyBinding.class.getDeclaredField(fieldName);
                                field.setAccessible(true);
                                if (field.getType() == boolean.class) {
                                    field.setBoolean(keyBinding, false);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 反射失败，静默处理（不影响游戏）
        }
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
