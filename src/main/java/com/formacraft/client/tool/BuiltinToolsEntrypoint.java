package com.formacraft.client.tool;

/**
 * 内建工具入口：通过 entrypoint 自己注册自己，和外部插件走同一套机制。
 */
public final class BuiltinToolsEntrypoint implements ToolEntrypoint {
    @Override
    public void registerTools(ToolRegistry registry) {
        if (registry == null) return;
        registry.register(SelectionTool.INSTANCE);
        registry.register(ProtectedZoneTool.INSTANCE);
        registry.register(OutlineTool.INSTANCE);
        registry.register(PathTool.INSTANCE);
        registry.register(SymmetryTool.INSTANCE);
        registry.register(SemanticLabelTool.INSTANCE);
    }
}


