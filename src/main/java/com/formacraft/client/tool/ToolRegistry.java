package com.formacraft.client.tool;

/**
 * 工具注册表（供 entrypoint 调用）。
 */
public final class ToolRegistry {
    public void register(FormacraftTool tool) {
        ToolManager.register(tool);
    }
}


