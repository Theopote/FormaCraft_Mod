package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.network.OrchestratorErrorHumanizer;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.orchestrator.AiPlanResult;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.BuildPreviewDelivery;
import com.formacraft.server.network.NetworkOrchestratorProvider;
import com.formacraft.server.orchestrator.OrchestratorClient;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extracted handler for {@link FormaCraftNetworking.RequestBuildPayload}.
 *
 * <p>Intentionally keeps behavior identical to {@code FormaCraftServerNetworking.registerC2S()}
 * (RequestBuildPayload receiver), but lives in a dedicated class for easier maintenance.
 *
 * <p><b>生成双链路</b>：收到 {@link com.formacraft.common.orchestrator.AiPlanResult} 后按类型分发；
 * {@link AiPlanResult.LlmPlan} 优先 {@code LlmPlanPreviewBuilder}，失败则记录回退指标。
 */
public final class BuildRequestProcessor {
    private BuildRequestProcessor() {}

    private static final FcaLog LOG = FcaLog.of("BuildRequestProcessor");

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.RequestBuildPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        FormacraftMod.LOGGER.warn("Received build request from null player");
                        return;
                    }

                    FormaRequest req = payload.request();
                    FormacraftMod.LOGGER.info("Received build request from player {}: {}",
                            player.getName().getString(), req.getRequestText());

                    // 补齐“世界上下文”字段：让 Python 侧可以稳定获取 biome/facing/origin，而不是只依赖 prompt 文本解析。
                    // 注意：客户端可能已填充这些字段；这里仅在缺失时兜底。
                    try {
                        enrichRequestFromPlayer(req, player);
                    } catch (Throwable t) {
                        LOG.player(player).warn("enrichRequestFromPlayer failed", t);
                    }
                    com.formacraft.server.state.PlayerProtectedZoneStorage.syncFromRequest(player, req);
                    com.formacraft.server.state.PlayerOutlineStorage.syncFromRequest(player, req);

                    // 状态：服务端已收到请求
                    context.server().execute(() -> ServerPlayNetworking.send(player,
                            new FormaCraftNetworking.ResponseBuildStatusPayload("服务端已收到请求，正在请求后端…")));

                    // 检查应该请求什么类型的结构
                    String requestText = req.getRequestText().toLowerCase();
                    boolean isCity = requestText.contains("城市") || requestText.contains("城镇") ||
                            requestText.contains("city") || requestText.contains("town") ||
                            requestText.contains("settlement") || requestText.contains("urban") ||
                            requestText.contains("城区") || requestText.contains("市中心") ||
                            requestText.contains("广场") || requestText.contains("集市");

                    // 四合院等场景：强制单体 BuildingSpec（见 BuildingSpecRoutingPolicy）
                    boolean isComposite = BuildingSpecRoutingPolicy.shouldUseCompositeOrchestrator(requestText, isCity);

                    if (isCity) {
                        AtomicBoolean hbAlive = new AtomicBoolean(true);
                        AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成城市方案");
                        long hbStartMs = System.currentTimeMillis();
                        BuildStatusHeartbeat.start(context.server(), player, hbAlive, hbStartMs, hbPhase);

                        // 请求城市级结构
                        NetworkOrchestratorProvider.get().requestCitySpec(req)
                                .orTimeout(605, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    hbAlive.set(false);
                                    FormacraftMod.LOGGER.error("Orchestrator city request failed", ex);
                                    // IMPORTANT: always send packets on the server thread
                                    String msg = OrchestratorErrorHumanizer.humanize("CitySpec", req, ex);
                                    context.server().execute(() ->
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                    return null;
                                })
                                .thenAccept(citySpec -> {
                                    if (citySpec == null) return; // already handled in exceptionally

                                    // 在主线程中执行
                                    context.server().execute(() -> {
                                        hbPhase.set("已收到 AI 结果，正在生成城市预览");
                                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成城市预览…"));
                                        // 对于城市结构，生成预览而不是直接建造
                                        BlockPos origin = req.getPlayerPos();
                                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                            previewCitySpec(player, req, citySpec, origin, serverWorld, hbAlive);
                                            FormacraftMod.LOGGER.info("Generated city structure preview for player {}", player.getName().getString());
                                        }
                                    });
                                });
                    } else if (isComposite) {
                        AtomicBoolean hbAlive = new AtomicBoolean(true);
                        AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成复合结构方案");
                        long hbStartMs = System.currentTimeMillis();
                        BuildStatusHeartbeat.start(context.server(), player, hbAlive, hbStartMs, hbPhase);

                        // 请求复合结构
                        OrchestratorClient orchestrator = NetworkOrchestratorProvider.get();
                        if (!orchestrator.checkHealth()) {
                            String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                            String errorMsg = "后端服务不可用：无法连接到 " + endpoint + "。请检查后端是否正在运行。";
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(errorMsg));
                            return;
                        }
                        orchestrator.requestCompositeSpec(req)
                                .orTimeout(605, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    hbAlive.set(false);
                                    FormacraftMod.LOGGER.error("Orchestrator composite request failed", ex);
                                    String msg = OrchestratorErrorHumanizer.humanize("CompositeSpec", req, ex);
                                    context.server().execute(() ->
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                    return null;
                                })
                                .thenAccept(compositeSpec -> {
                                    if (compositeSpec == null) return; // already handled

                                    // 在主线程中执行
                                    context.server().execute(() -> {
                                        hbPhase.set("已收到 AI 结果，正在生成复合结构预览");
                                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成复合结构预览…"));
                                        // 对于复合结构，生成预览而不是直接建造
                                        BlockPos origin = req.getPlayerPos();
                                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                            previewCompositeSpec(player, req, compositeSpec, origin, serverWorld, hbAlive);
                                            FormacraftMod.LOGGER.info("Generated composite structure preview for player {}", player.getName().getString());
                                        }
                                    });
                                });
                    } else {
                        // 请求单个建筑
                        // 如果是 PATCH/MODIFY_REGION：走“增量编辑 BuildingSpec”链路
                        String mode = req.getPromptMode();
                        boolean isPatch = mode != null && !mode.isBlank() && !"BUILD".equalsIgnoreCase(mode.trim());
                        if (isPatch) {
                            String buildingId = com.formacraft.server.state.PlayerSpecRepository.getBuildingId(player);
                            String currentJson = com.formacraft.server.state.PlayerSpecRepository.getBuildingJson(player);
                            if (buildingId == null || currentJson == null) {
                                player.sendMessage(net.minecraft.text.Text.literal("No current building spec. Generate a building first."), false);
                                return;
                            }

                            AtomicBoolean hbAlive = new AtomicBoolean(true);
                            AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成更新方案");
                            long hbStartMs = System.currentTimeMillis();
                            BuildStatusHeartbeat.start(context.server(), player, hbAlive, hbStartMs, hbPhase);

                            OrchestratorClient orchestrator = NetworkOrchestratorProvider.get();
                            if (!orchestrator.checkHealth()) {
                                String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                                String errorMsg = "后端服务不可用：无法连接到 " + endpoint + "。请检查后端是否正在运行。";
                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(errorMsg));
                                return;
                            }
                            orchestrator.editBuilding(buildingId, currentJson, req.getRequestText())
                                    .orTimeout(605, TimeUnit.SECONDS)
                                    .exceptionally(ex -> {
                                        hbAlive.set(false);
                                        FormacraftMod.LOGGER.error("Orchestrator edit building request failed", ex);
                                        String msg = OrchestratorErrorHumanizer.humanize("EditBuilding", req, ex);
                                        context.server().execute(() ->
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                        return null;
                                    })
                                    .thenAccept(updatedJson -> {
                                        if (updatedJson == null) return; // already handled

                                        context.server().execute(() -> {
                                            hbPhase.set("已收到 AI 结果，正在生成更新后的预览");
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成更新后的预览…"));
                                            // 更新 PlayerSpecRepository
                                            com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(player, buildingId, updatedJson);

                                            BuildingSpec updated = JsonUtil.fromJson(updatedJson, BuildingSpec.class);
                                            if (updated == null) return;

                                            BlockPos origin = req.getPlayerPos();
                                            if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                                com.formacraft.server.generation.structure.StructureGenerator generator =
                                                        com.formacraft.server.generation.GenerationHub.routeStructure(updated);
                                                final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.common.build.GeneratedStructure> reported =
                                                        com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                                BuildConstraintContext.withRequest(req, () -> generator.generate(updated, origin, serverWorld))
                                                        );
                                                final com.formacraft.common.build.GeneratedStructure generated = reported.value();

                                                String terrainSummary = reported.report().summaryZh();
                                                if (!terrainSummary.isBlank()) {
                                                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                                                }

                                                com.formacraft.server.build.BuildPreviewDelivery.deliver(
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
                                            }

                                            // 同步给客户端，用于 UI 显示（notes 等）
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(updated));
                                        });
                                    });
                            return;
                        }

                        // 在发送请求前先检查后端健康状态
                        OrchestratorClient orchestrator = NetworkOrchestratorProvider.get();
                        if (!orchestrator.checkHealth()) {
                            String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                            String errorMsg = String.format(
                                    """
                                            后端服务不可用：无法连接到 %s
                                            请检查：
                                            1. Python 后端是否正在运行
                                            2. 后端地址是否正确（可在设置中修改）
                                            3. 防火墙是否允许连接
                                            4. 如果是远程服务器，请确保端口已开放""",
                                    endpoint
                            );
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(errorMsg));
                            FormacraftMod.LOGGER.error("Backend health check failed before sending request: {}", endpoint);
                            return;
                        }

                        AtomicBoolean hbAlive = new AtomicBoolean(true);
                        AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成建筑方案");
                        long hbStartMs = System.currentTimeMillis();
                        BuildStatusHeartbeat.start(context.server(), player, hbAlive, hbStartMs, hbPhase);

                        orchestrator.requestAiPlan(req)
                                .orTimeout(605, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    hbAlive.set(false);
                                    FormacraftMod.LOGGER.error("Orchestrator building request failed", ex);
                                    String msg = OrchestratorErrorHumanizer.humanize("AiPlan", req, ex);
                                    context.server().execute(() ->
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                    return null;
                                })
                                .thenAccept(aiResult -> {
                                    if (aiResult == null) return;

                                    context.server().execute(() -> {
                                        hbPhase.set("已收到 AI 结果，正在生成建筑预览");
                                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成建筑预览…"));
                                        BlockPos origin = req.getPlayerPos();
                                        if (origin == null || !(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                                            hbAlive.set(false);
                                            return;
                                        }

                                        switch (aiResult) {
                                            case AiPlanResult.LlmPlan(var plan) -> {
                                                if (LlmPlanPreviewBuilder.tryBuildPreview(player, req, plan, origin, serverWorld, hbAlive)) {
                                                    return;
                                                }
                                                LlmPlanRoutingMetrics.recordStructureAfterFallback(player, req);
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                                        "LlmPlan 预览生成失败（已跳过假 BuildingSpec 回退）。请调整请求或检查后端输出。"
                                                ));
                                                hbAlive.set(false);
                                            }
                                            case AiPlanResult.BuildingSpec(var spec) -> {
                                                BuildingSpecRoutingPolicy.applySpecDefaults(spec, req);
                                                LlmPlanRoutingMetrics.recordDirectStructurePreview(player, req);
                                                previewBuildingSpec(player, req, spec, origin, serverWorld, hbAlive);
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(spec));
                                            }
                                            case AiPlanResult.CompositeSpec(var composite) -> previewCompositeSpec(player, req, composite, origin, serverWorld, hbAlive);
                                            case AiPlanResult.CitySpec(var city) -> {
                                                FormacraftMod.LOGGER.warn(
                                                        "Unexpected CitySpec from building orchestrator for player {}",
                                                        player.getName().getString()
                                                );
                                                previewCitySpec(player, req, city, origin, serverWorld, hbAlive);
                                            }
                                        }
                                    });
                                });
                    }
                }));
    }

    private static void previewBuildingSpec(
            ServerPlayerEntity player,
            FormaRequest req,
            BuildingSpec spec,
            BlockPos origin,
            net.minecraft.server.world.ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        com.formacraft.server.generation.structure.StructureGenerator generator =
                com.formacraft.server.generation.GenerationHub.routeStructure(spec);
        final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.common.build.GeneratedStructure> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> generator.generate(spec, origin, serverWorld))
                );
        final com.formacraft.common.build.GeneratedStructure generated = reported.value();

        String terrainSummary = reported.report().summaryZh();
        if (!terrainSummary.isBlank()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
        }

        BuildPreviewDelivery.deliver(
                player,
                req,
                generated,
                spec,
                serverWorld,
                java.util.Optional.ofNullable(spec.getStyle()),
                hbAlive,
                net.minecraft.text.Text.translatable("formacraft.preview.ready.building"),
                "Building preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                (p, o) -> {
                    if (spec.getExtra() != null) {
                        FormaCraftServerNetworking.sendPreviewSkeleton(p, o, spec.getExtra());
                    }
                }
        );
    }

    private static void previewCompositeSpec(
            ServerPlayerEntity player,
            FormaRequest req,
            CompositeSpec compositeSpec,
            BlockPos origin,
            net.minecraft.server.world.ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        com.formacraft.server.generation.structure.composite.CompositeStructureGenerator generator =
                new com.formacraft.server.generation.structure.composite.CompositeStructureGenerator();
        final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.common.build.GeneratedStructure> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> generator.generate(compositeSpec, origin, serverWorld))
                );
        final com.formacraft.common.build.GeneratedStructure generated = reported.value();

        String terrainSummary = reported.report().summaryZh();
        if (!terrainSummary.isBlank()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
        }

        BuildPreviewDelivery.deliver(
                player,
                req,
                generated,
                null,
                serverWorld,
                java.util.Optional.empty(),
                hbAlive,
                net.minecraft.text.Text.translatable("formacraft.preview.ready.composite"),
                "Composite structure preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                (p, o) -> {
                    if (compositeSpec.getStructures() != null && !compositeSpec.getStructures().isEmpty()) {
                        var s0 = compositeSpec.getStructures().getFirst();
                        if (s0 != null && s0.getSpec() != null && s0.getSpec().getExtra() != null) {
                            FormaCraftServerNetworking.sendPreviewSkeleton(p, o, s0.getSpec().getExtra());
                        }
                    }
                }
        );
    }

    private static void previewCitySpec(
            ServerPlayerEntity player,
            FormaRequest req,
            CitySpec citySpec,
            BlockPos origin,
            net.minecraft.server.world.ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        com.formacraft.server.city.CityBuilder cityBuilder = new com.formacraft.server.city.CityBuilder();
        final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.common.build.GeneratedStructure> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> cityBuilder.generate(citySpec, origin, serverWorld))
                );
        final com.formacraft.common.build.GeneratedStructure generated = reported.value();

        String terrainSummary = reported.report().summaryZh();
        if (!terrainSummary.isBlank()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
        }

        String cityName = citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed";
        String statusTail = String.format(
                "City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.", cityName);

        boolean delivered = BuildPreviewDelivery.deliver(
                player,
                req,
                generated,
                null,
                serverWorld,
                java.util.Optional.empty(),
                hbAlive,
                net.minecraft.text.Text.literal(statusTail),
                statusTail,
                (p, o) -> {
                    if (citySpec.getStructures() != null && !citySpec.getStructures().isEmpty()) {
                        var sp0 = citySpec.getStructures().getFirst();
                        if (sp0 != null && sp0.getSpec() != null && sp0.getSpec().getExtra() != null) {
                            FormaCraftServerNetworking.sendPreviewSkeleton(p, o, sp0.getSpec().getExtra());
                        }
                    }
                }
        );

        if (delivered) {
            String cityId = "player_" + player.getName().getString() + "_world_" + serverWorld.getRegistryKey().getValue();
            com.formacraft.server.state.PlayerSpecRepository.setCitySpec(player, cityId, JsonUtil.toJson(citySpec));
        }
    }

    private static void enrichRequestFromPlayer(FormaRequest req, ServerPlayerEntity player) {
        if (req == null || player == null) return;

        // origin
        if (req.getPlayerPos() == null) {
            req.setPlayerPos(player.getBlockPos());
        }

        // facing
        if (req.getFacing() == null || req.getFacing().isBlank()) {
            req.setFacing(player.getHorizontalFacing().name());
        }

        // biome（服务端权威，避免客户端与服务端不同步）
        if ((req.getBiome() == null || req.getBiome().isBlank())
                && req.getPlayerPos() != null
                && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
            java.util.Optional<net.minecraft.registry.RegistryKey<net.minecraft.world.biome.Biome>> key =
                    sw.getBiome(req.getPlayerPos()).getKey();
            if (key != null && key.isPresent()) {
                req.setBiome(key.get().getValue().toString());
            }
        }
    }
}

