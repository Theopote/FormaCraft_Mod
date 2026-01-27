package com.formacraft.client.ui;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.PanelType;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

public class FormacraftUIState {

    public static boolean isOpen = false;

    // 当 UI 打开时临时禁用“失焦暂停”，关闭 UI 时恢复原值（不改变玩家的长期设置）。
    private static boolean pauseOnLostFocusOverridden = false;
    private static Boolean pauseOnLostFocusPrev = null;

    public static void toggle() {
        isOpen = !isOpen;
        updateCursor();
        updatePauseOnLostFocus();
        if (!isOpen) {
            if (FormaCraftHudOverlay.activePanel == PanelType.COMPONENT_CAPTURE) {
                ComponentTool.INSTANCE.getState().cancelCapture();
            }
            // UI关闭时，清除所有按键状态，防止残留状态导致玩家继续移动
            clearAllKeyBindings();
        }
    }

    public static void open() {
        isOpen = true;
        updateCursor();
        updatePauseOnLostFocus();
    }

    public static void close() {
        isOpen = false;
        updateCursor();
        updatePauseOnLostFocus();
        if (FormaCraftHudOverlay.activePanel == PanelType.COMPONENT_CAPTURE) {
            ComponentTool.INSTANCE.getState().cancelCapture();
        }
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
     * UI 打开期间：临时禁用“失焦暂停”(Pause on Lost Focus)。
     * <p>
     * 需求：打开 Formacraft 面板后，鼠标移出窗口去操作其他程序/屏幕外，不要自动弹出暂停菜单；
     * UI 关闭后恢复玩家原来的选项值。
     */
    private static void updatePauseOnLostFocus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        // 1.21.10 Yarn：GameOptions.pauseOnLostFocus 是 public boolean
        boolean current = client.options.pauseOnLostFocus;
        if (isOpen) {
            if (!pauseOnLostFocusOverridden) {
                pauseOnLostFocusPrev = current;
                pauseOnLostFocusOverridden = true;
            }
            client.options.pauseOnLostFocus = false;
        } else {
            if (pauseOnLostFocusOverridden) {
                client.options.pauseOnLostFocus = pauseOnLostFocusPrev != null ? pauseOnLostFocusPrev : true;
                pauseOnLostFocusPrev = null;
                pauseOnLostFocusOverridden = false;
            }
        }
    }

    /**
     * 确保光标状态正确（用于定期检查，确保光标状态与 UI 状态一致）
     */
    public static void ensureCursorState() {
        updateCursor();
        updatePauseOnLostFocus();
    }
}
