package com.formacraft.client.ui.panel.settings;

public enum SettingsGroup {
    CONNECTION("模型与连接"), SEARCH("建筑研究搜索"), PREFERENCES("界面与偏好"), BACKEND("本机后端服务");
    public final String label;
    SettingsGroup(String label) { this.label = label; }
}
