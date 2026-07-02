package com.formacraft.server.networking;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.formacraft.server.build.BuildExecutionService;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import com.formacraft.FormacraftMod;

/**
 * 处理客户端发送的建筑请求数据包（遗留路径）。
 * <p>
 * 当前客户端通过 {@link com.formacraft.common.network.FormaCraftNetworking#sendBuildRequest}
 * 发送请求，不再使用此类。保留仅供参考，将在未来版本移除。
 *
 * @deprecated 使用 {@link com.formacraft.common.network.FormaCraftNetworking} 代替。
 */
@Deprecated(forRemoval = true)
public class BuildRequestHandler {
    // 使用延迟初始化的客户端（从配置读取）
    private static volatile OrchestratorClient orchestratorClient = null;
    private static String lastEndpoint = null;
    
    private static OrchestratorClient getOrchestratorClient() {
        String currentEndpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
        // 如果端点改变或客户端未初始化，重新创建
        if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
            synchronized (BuildRequestHandler.class) {
                if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
                    orchestratorClient = new OrchestratorClient(currentEndpoint);
                    lastEndpoint = currentEndpoint;
                }
            }
        }
        return orchestratorClient;
    }

    public static void handle(BuildRequestPacket packet, ServerPlayerEntity player, PacketSender responseSender) {
        if (player == null) {
            FormacraftMod.LOGGER.warn("Received build request from null player");
            return;
        }

        net.minecraft.world.World playerWorld = player.getEntityWorld();
        if (!(playerWorld instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
            FormacraftMod.LOGGER.warn("Player world is not a ServerWorld");
            return;
        }

        MinecraftServer server = serverWorld.getServer();

        FormaRequest request = packet.request();
        FormacraftMod.LOGGER.info("Received build request from player {}: {}", player.getName().getString(), request.getRequestText());

        // 异步请求 AI 生成建筑规格
        getOrchestratorClient().requestBuildingSpec(request).thenAccept(spec -> {
            if (spec == null) {
                FormacraftMod.LOGGER.error("Failed to get BuildingSpec from orchestrator");
                return;
            }

            // 在主线程中执行
            server.execute(() -> {
                // 发送响应数据包给客户端
                ServerPlayNetworking.send(player, new BuildResponsePacket(spec));

                // 开始执行建造
                BlockPos origin = request.getPlayerPos();
                if (origin != null) {
                    BuildExecutionService.getInstance().queueBuild(
                            serverWorld,
                            origin,
                            spec
                    );
                }
            });
        });
    }
}

