package com.formacraft.client.ui.panel;

/**
 * 面板类型枚举
 */
public enum PanelType {
    CHAT,           // 聊天面板
    BLUEPRINT,      // 蓝图管理面板
    TOOLS,          // 工具面板（选区/刷子等）
    COMPONENT_LIBRARY, // 构件库面板（Prefab Library）
    COMPONENT_CAPTURE, // 构件拾取面板（新增）
    SETTINGS,       // 设置面板
    HISTORY,        // 对话历史面板
    NONE            // 无面板（隐藏所有面板）
}

