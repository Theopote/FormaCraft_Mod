package com.formacraft.client;

import com.formacraft.client.preview.OutlineRenderer;
import com.formacraft.client.backend.BackendAutoStarter;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.InputEventHandler;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.config.SettingsConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 客户端初始化器
 * 注册客户端事件和网络数据包
 */
public class ClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 加载设置配置
        SettingsConfig.load();

        // 尝试自动启动本地后端（异步，不阻塞渲染线程）
        BackendAutoStarter.ensureStartedAsync();

        // 周期性重试（例如首次启动时 python/网络尚未就绪，或用户之后修改了配置）
        ClientTickEvents.END_CLIENT_TICK.register(client -> BackendAutoStarter.ensureStartedAsync());
        
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

