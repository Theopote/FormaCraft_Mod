package com.formacraft.client;

import com.formacraft.client.preview.OutlineRenderer;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.InputEventHandler;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.config.SettingsConfig;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端初始化器
 * 注册客户端事件和网络数据包
 */
public class ClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 加载设置配置
        SettingsConfig.load();
        
        // 注册 S2C 数据包（服务端 → 客户端）
        FormaCraftNetworking.registerS2C();
        // 注册预览线框渲染器
        OutlineRenderer.register();
        // 注册 HUD Overlay
        FormaCraftHudOverlay.register();
        // 初始化面板（设置监听器等）
        FormaCraftHudOverlay.initialize();
        // 注册输入事件处理器
        InputEventHandler.register();
    }
}

