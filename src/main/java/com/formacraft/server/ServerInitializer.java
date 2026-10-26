package com.formacraft.server;

import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.asset.AssetLibrary;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.network.BuildRequestProcessor;
import com.formacraft.server.networking.ModPacket;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * 服务端初始化器
 * 注册服务端事件和数据包
 */
public class ServerInitializer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // 注册遗留 sync 通道（build_request 已移除，统一走 FormaCraftNetworking）
        ModPacket.registerServer();
        
        // 注册新的 FormaCraft 网络系统
        FormaCraftServerNetworking.registerC2S();
        BuildRequestProcessor.register();
        
        // 注册建造执行服务的 Tick 处理器
        BuildExecutionService.registerTickHandler();
        
        // Memory lifecycle is registered by the shared BuildExecutionService above,
        // including singleplayer integrated servers.
        
        // 加载预制件库
        AssetLibrary.loadAssets();
    }
}

