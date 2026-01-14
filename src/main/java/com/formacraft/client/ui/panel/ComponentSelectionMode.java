package com.formacraft.client.ui.panel;

/**
 * 构件拾取面板的选择模式
 */
public enum ComponentSelectionMode {
    /**
     * 框选模式 - 拖拽框选区域（默认）
     * 复用 SelectionTool 的逻辑和渲染
     */
    BOX_SELECT("框选", "拖拽框选区域", "左键拖拽框选，可见实时预览"),
    
    /**
     * 点选模式 - 点击切换单个方块的选中状态
     * - 点击未选方块 → 加选
     * - 点击已选方块 → 减选
     * - Ctrl+点击 → 强制加选
     */
    POINT_SELECT("点选", "点击切换方块选择状态", "左键切换选择，Ctrl+左键强制加选");
    
    private final String displayName;
    private final String description;
    private final String hint;
    
    ComponentSelectionMode(String displayName, String description, String hint) {
        this.displayName = displayName;
        this.description = description;
        this.hint = hint;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getHint() {
        return hint;
    }
    
    /**
     * 获取下一个模式（循环）
     */
    public ComponentSelectionMode next() {
        ComponentSelectionMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
