package com.formacraft.server;

import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.command.FormaCraftCommands;
import com.formacraft.server.networking.ModPacket;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * 服务端初始化器
 * 注册服务端事件和数据包
 */
public class ServerInitializer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // 注册旧的网络数据包（保持兼容）
        ModPacket.registerServer();
        
        // 注册新的 FormaCraft 网络系统
        FormaCraftNetworking.registerC2S();
        
        // 注册建造执行服务的 Tick 处理器
        BuildExecutionService.registerTickHandler();
        
        // 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            FormaCraftCommands.register(dispatcher);
        });
    }
}

