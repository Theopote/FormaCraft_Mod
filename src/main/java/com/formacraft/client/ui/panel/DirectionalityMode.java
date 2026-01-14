package com.formacraft.client.ui.panel;

/**
 * 构件方向性模式
 * 定义构件是否有特定的方向性（如内外、上下）
 */
public enum DirectionalityMode {
    /**
     * 无方向性 - 任意旋转放置
     */
    NONE("无方向", "构件可以任意旋转放置"),
    
    /**
     * 内外方向性 - 有明确的内外侧（门、窗等）
     */
    INSIDE_OUTSIDE("内外", "构件有明确的内外侧"),
    
    /**
     * 上下方向性 - 有明确的上下端（楼梯、梯子等）
     */
    BOTTOM_TOP("上下", "构件有明确的上下端"),
    
    /**
     * 双向方向性 - 同时有内外和上下
     */
    BOTH("双向", "构件同时有内外和上下方向");
    
    private final String displayName;
    private final String description;
    
    DirectionalityMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 获取下一个模式（循环）
     */
    public DirectionalityMode next() {
        DirectionalityMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
    
    /**
     * 是否需要内外标记
     */
    public boolean needsInsideOutside() {
        return this == INSIDE_OUTSIDE || this == BOTH;
    }
    
    /**
     * 是否需要上下标记
     */
    public boolean needsBottomTop() {
        return this == BOTTOM_TOP || this == BOTH;
    }
}
