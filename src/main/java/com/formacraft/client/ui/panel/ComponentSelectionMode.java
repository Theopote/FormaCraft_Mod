package com.formacraft.client.ui.panel;

/**
 * 构件拾取面板的选择模式
 */
public enum ComponentSelectionMode {
    /**
     * 框选模式 - 拖拽框选区域（默认）
     */
    BOX_SELECT("框选", "拖拽框选区域", "左键拖拽框选方块"),
    
    /**
     * 点选加模式 - 点击添加单个方块
     */
    ADD_SELECT("点选加", "添加单个方块", "Shift+左键添加方块"),
    
    /**
     * 点选减模式 - 点击移除单个方块
     */
    REMOVE_SELECT("点选减", "移除单个方块", "Ctrl+左键移除方块"),
    
    /**
     * 设置锚点模式 - 右键设置锚点
     */
    ANCHOR_SET("设置锚点", "右键点击设置锚点", "右键点击方块设为锚点");
    
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
