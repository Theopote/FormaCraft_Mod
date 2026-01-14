package com.formacraft.client.ui.panel;

/**
 * 方向标记模式
 * 用于标记构件的内外、上下等方向
 */
public enum DirectionMarkingMode {
    /**
     * 无标记 - 正常操作模式
     */
    NONE,
    
    /**
     * 正在标记内侧
     */
    MARKING_INSIDE,
    
    /**
     * 正在标记外侧
     */
    MARKING_OUTSIDE,
    
    /**
     * 正在标记底端
     */
    MARKING_BOTTOM,
    
    /**
     * 正在标记顶端
     */
    MARKING_TOP;
    
    /**
     * 获取用户提示文本
     */
    public String getHint() {
        return switch (this) {
            case MARKING_INSIDE -> "请在世界中点击构件的内侧方块";
            case MARKING_OUTSIDE -> "请在世界中点击构件的外侧方块";
            case MARKING_BOTTOM -> "请在世界中点击构件的底端方块";
            case MARKING_TOP -> "请在世界中点击构件的顶端方块";
            default -> "";
        };
    }
}
