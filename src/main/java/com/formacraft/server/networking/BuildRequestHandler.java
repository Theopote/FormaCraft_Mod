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
 * 处理客户端发送的建筑请求数据包
 */
public class BuildRequestHandler {
    private static final String ORCHESTRATOR_ENDPOINT = "http://localhost:8000";
    private static final OrchestratorClient orchestratorClient = new OrchestratorClient(ORCHESTRATOR_ENDPOINT);

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
        orchestratorClient.requestBuildingSpec(request).thenAccept(spec -> {
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

