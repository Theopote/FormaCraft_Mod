package com.formacraft.client.preview;

import com.formacraft.ai.prompt.PromptMode;

/**
 * 客户端 PromptMode 状态（用于把 UI 模式贯穿到 PatchFilter/Preview 等链路）。
 */
public final class PromptModeState {
    private PromptModeState() {}

    private static PromptMode lastMode = PromptMode.BUILD;

    public static void setLastMode(PromptMode mode) {
        lastMode = mode != null ? mode : PromptMode.BUILD;
    }

    public static PromptMode getLastMode() {
        return lastMode;
    }

    public static boolean restrictToSelection() {
        return lastMode == PromptMode.MODIFY_REGION;
    }
}


