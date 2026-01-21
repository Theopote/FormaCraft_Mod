package com.formacraft.client.ui.panel;

/**
 * 构件拾取阶段
 * 定义构件拾取流程的四个阶段
 */
public enum CapturePhase {
    /**
     * 阶段 1：选区定义
     * 用户选择要拾取的方块范围
     */
    SELECTION("选区定义", "请选择构件的方块范围"),
    
    /**
     * 阶段 2：锚点与朝向
     * 设置构件的参考点和方向
     */
    ANCHOR_ORIENTATION("锚点与朝向", "设置构件的参考点与方向"),
    
    /**
     * 阶段 3：构件语义确认
     * 确认构件的类型和语义信息
     */
    SEMANTIC("构件语义确认", "确认这是一个什么构件"),
    
    /**
     * 阶段 4：AI 使用保障
     * 配置 Socket 和 Placement 以确保 AI 能正确使用
     */
    AI_GUARANTEE("AI 使用保障", "确保 AI 能正确使用该构件");
    
    private final String displayName;
    private final String description;
    
    CapturePhase(String displayName, String description) {
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
     * 获取阶段编号（1-based）
     */
    public int getPhaseNumber() {
        return ordinal() + 1;
    }
    
    /**
     * 获取总阶段数
     */
    public static int getTotalPhases() {
        return values().length;
    }
}
