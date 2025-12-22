package com.formacraft.client.tool;

/**
 * 工具插件入口：外部模组/模块可以通过 Fabric entrypoint 注入工具。
 * <p>
 * 在对方的 fabric.mod.json 中添加：
 * "entrypoints": { "formacraft_tools": ["your.pkg.YourToolEntrypoint"] }
 */
public interface ToolEntrypoint {
    void registerTools(ToolRegistry registry);
}


