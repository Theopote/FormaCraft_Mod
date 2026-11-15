package com.formacraft.server.network.compat;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.OrchestratorErrorHumanizer;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.StructureGenerationResult;
import com.formacraft.server.network.BuildRequestGate;
import com.formacraft.server.network.BuildStatusHeartbeat;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.network.NetworkOrchestratorProvider;
import com.formacraft.server.orchestrator.OrchestratorClient;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACTIVE_COMPAT：显式 buildingspec + 非 BUILD prompt 时的整栋 BuildingSpec 编辑。
 * <p>
 * 新增量编辑走 {@code requestAiPlan} / LlmPlan patch。保留消费者：
 * Chat 显式 {@code outputFormat=buildingspec} 编辑分支（命令侧见 {@code forma_edit_building}）。
 * 禁止在此扩展新默认编辑能力。
 */
public final class LegacyBuildingEditHandler {
    private LegacyBuildingEditHandler() {}

    /**
     * @return true if the request was claimed as legacy edit (including early error returns)
     */
    public static boolean tryStart(
            MinecraftServer server,
            ServerPlayerEntity player,
            FormaRequest req,
            BuildRequestGate.Ticket gateTicket,
            boolean[] asyncStarted
    ) {
        String mode = req.getPromptMode();
        boolean isPatch = mode != null && !mode.isBlank() && !"BUILD".equalsIgnoreCase(mode.trim());
        if (!(isPatch && req.requestsLegacyBuildingSpec())) {
            return false;
        }

        String buildingId = com.formacraft.server.state.PlayerSpecRepository.getBuildingId(player);
        String currentJson = com.formacraft.server.state.PlayerSpecRepository.getBuildingJson(player);
        if (buildingId == null || currentJson == null) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "当前没有可编辑的 BuildingSpec。请用自然语言增量修改（LlmPlan patch），或先生成 BuildingSpec 建筑。"));
            return true;
        }

        AtomicBoolean hbAlive = new AtomicBoolean(true);
        AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成更新方案");
        long hbStartMs = System.currentTimeMillis();
        BuildStatusHeartbeat.start(server, player, hbAlive, hbStartMs, hbPhase);

        OrchestratorClient orchestrator = NetworkOrchestratorProvider.get();
        if (!orchestrator.checkHealth()) {
            String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
            String errorMsg = "后端服务不可用：无法连接到 " + endpoint + "。请检查后端是否正在运行。";
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(errorMsg));
            return true;
        }

        // 门禁覆盖“后端编排 + 解析 + 生成 + 预览交付”全过程，仅在结束时释放。
        final LegacyEditPreviewScope scope = new LegacyEditPreviewScope(() -> BuildRequestGate.release(gateTicket));
        asyncStarted[0] = true;
        orchestrator.editLegacyBuildingSpec(buildingId, currentJson, req.getRequestText())
                .orTimeout(605, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    hbAlive.set(false);
                    FormacraftMod.LOGGER.error("Orchestrator edit building request failed", ex);
                    String msg = OrchestratorErrorHumanizer.humanize("EditBuilding", req, ex);
                    server.execute(() ->
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                    return null;
                })
                .thenAccept(updatedJson -> {
                    if (updatedJson == null) {
                        scope.release();
                        return;
                    }

                    server.execute(() -> {
                        try {
                            hbPhase.set("已收到 AI 结果，正在生成更新后的预览");
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                    "已收到 AI 结果，正在生成更新后的预览…"));

                            // 先解析；无效 BuildingSpec 不覆盖玩家原有规格。
                            BuildingSpec updated = LegacyEditPreviewScope.parseSpec(updatedJson);
                            if (updated == null) {
                                hbAlive.set(false);
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                        "编辑结果不是有效的 BuildingSpec，已保留原有建筑规格。"));
                                return;
                            }

                            BlockPos origin = req.getPlayerPos();
                            if (origin == null || !(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                                hbAlive.set(false);
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                        "缺少玩家位置或世界上下文，已保留原有建筑规格。"));
                                return;
                            }

                            // 先解析生成器；无生成器时给出明确错误，不覆盖规格。
                            com.formacraft.server.generation.structure.StructureGenerator generator =
                                    com.formacraft.server.generation.GenerationHub.routeStructure(updated);
                            if (generator == null) {
                                hbAlive.set(false);
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                        "编辑结果无法路由到生成器（UNSUPPORTED），已保留原有建筑规格。"));
                                return;
                            }

                            final com.formacraft.server.build.BuildReportContext.Reported<StructureGenerationResult> reported =
                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                            BuildConstraintContext.withRequest(req, () -> StructureGenerationResult.capture(
                                                    () -> generator.generate(updated, origin, serverWorld)))
                                    );
                            final StructureGenerationResult generated = reported.value();

                            String terrainSummary = reported.report().summaryZh();
                            if (!terrainSummary.isBlank()) {
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                            }

                            boolean delivered = com.formacraft.server.build.BuildPreviewDelivery.deliver(
                                    player,
                                    req,
                                    generated,
                                    updated,
                                    serverWorld,
                                    java.util.Optional.ofNullable(updated.getStyle()),
                                    hbAlive,
                                    net.minecraft.text.Text.translatable("formacraft.preview.ready.updated_building"),
                                    "Updated building preview ready. Use /forma_confirm to rebuild or /forma_cancel to cancel.",
                                    (p, o) -> {
                                        if (updated.getExtra() != null) {
                                            FormaCraftServerNetworking.sendPreviewSkeleton(p, o, updated.getExtra());
                                        }
                                    }
                            );

                            if (delivered) {
                                scope.markPreviewDelivered();
                                // 仅在预览交付成功后写回规格仓库。
                                LegacyEditPreviewScope.commitSpec(
                                        player.getUuid(), buildingId, updatedJson, true);
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(updated));
                            } else {
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                        "预览生成失败，已保留原有建筑规格。"));
                            }
                        } catch (Throwable t) {
                            hbAlive.set(false);
                            FormacraftMod.LOGGER.error("Legacy building edit preview failed", t);
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                    "生成预览时发生内部错误，已保留原有建筑规格。"));
                        } finally {
                            scope.release();
                        }
                    });
                });
        return true;
    }
}
