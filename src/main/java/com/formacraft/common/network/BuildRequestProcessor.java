package com.formacraft.common.network;

import com.formacraft.FormacraftMod;
import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.BuildConstraintClipper;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.orchestrator.OrchestratorClient;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extracted handler for {@link FormaCraftNetworking.RequestBuildPayload}.
 *
 * <p>Intentionally keeps behavior identical to {@code FormaCraftNetworking.registerC2S()}
 * (RequestBuildPayload receiver), but lives in a dedicated class for easier maintenance.
 *
 * <p><b>生成双链路</b>：收到 {@code BuildingSpec} 后优先 {@code LlmPlanPreviewBuilder}（构件层），
 * 失败则回退整栋 {@code GenerationHub.routeStructure()}。路由策略与覆盖度见
 * {@code docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md}。
 */
public final class BuildRequestProcessor {
    private BuildRequestProcessor() {}

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
                    } catch (Throwable ignored) {}

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

                    // 明清官式院落（四合院/宅院）已经有确定性生成器（ASIAN 大 footprint 会直接生成主殿+厢房+门楼+院墙）。
                    // 这类请求如果走 Composite，LLM 往往会产出“多栋相同默认房子”，不符合用户预期。
                    // 因此优先走单体 BuildingSpec 链路，让生成更稳定可控。
                    boolean isComposite = isIsComposite(requestText, isCity);

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
                                            // 生成城市结构
                                            com.formacraft.server.city.CityBuilder cityBuilder =
                                                    new com.formacraft.server.city.CityBuilder();
                                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                            BuildConstraintContext.withRequest(req, () -> cityBuilder.generate(citySpec, origin, serverWorld))
                                                    );
                                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();

                                            // 质量检查（CitySpec 没有 BuildingSpec，传递 null）
                                            com.formacraft.server.build.QualityChecker.QualityReport qualityReport =
                                                    com.formacraft.server.build.QualityChecker.checkQuality(generated, null, serverWorld);
                                            com.formacraft.server.build.QualityChecker.logQualityReport(qualityReport, generated.getDescription());

                                            // 如果有严重错误，记录但不阻止预览（让用户看到问题）
                                            if (!qualityReport.errors.isEmpty()) {
                                                FormacraftMod.LOGGER.warn("Quality check found errors for preview: {}", qualityReport.errors);
                                            }

                                            String terrainSummary = reported.report().summaryZh();
                                            if (!terrainSummary.isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                                            }

                                            // H-layer (MVP): auto validation & repair before preview
                                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                                    BuildConstraintContext.withRequest(req, () ->
                                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.empty(), generated.getBlocks())
                                                    );
                                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(repair.summary()));
                                            }

                                            // 设置玩家 UUID
                                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                                    player.getUuid(),
                                                    origin,
                                                    generated.getDescription(),
                                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                                            );

                                            // 存储结构用于预览
                                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                                            // 自动发送预览
                                            List<OutlineBlock> outline =
                                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                            FormaCraftNetworking.sendPreviewOutline(player, outline);
                                            // Send skeleton layout preview (if present in CitySpec's first structure extra)
                                            try {
                                                if (citySpec.getStructures() != null && !citySpec.getStructures().isEmpty()) {
                                                    var sp0 = citySpec.getStructures().getFirst();
                                                    if (sp0 != null && sp0.getSpec() != null && sp0.getSpec().getExtra() != null) {
                                                        FormaCraftNetworking.sendPreviewSkeleton(player, origin, sp0.getSpec().getExtra());
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                                            // 保存 CitySpec 到 PlayerSpecRepository
                                            String cityId = "player_" + player.getName().getString() + "_world_" +
                                                    serverWorld.getRegistryKey().getValue();
                                            String cityJson = JsonUtil.toJson(citySpec);
                                            com.formacraft.server.state.PlayerSpecRepository.setCitySpec(player, cityId, cityJson);

                                            player.sendMessage(net.minecraft.text.Text.literal(
                                                            String.format("City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                                                                    citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed")),
                                                    false);
                                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                                    String.format("City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                                                            citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed")
                                            ));
                                            hbAlive.set(false);

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
                                            // 生成结构
                                            com.formacraft.common.generation.structure.composite.CompositeStructureGenerator generator =
                                                    new com.formacraft.common.generation.structure.composite.CompositeStructureGenerator();
                                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                            BuildConstraintContext.withRequest(req, () -> generator.generate(compositeSpec, origin, serverWorld))
                                                    );
                                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();

                                            // 质量检查（CitySpec 没有 BuildingSpec，传递 null）
                                            com.formacraft.server.build.QualityChecker.QualityReport qualityReport =
                                                    com.formacraft.server.build.QualityChecker.checkQuality(generated, null, serverWorld);
                                            com.formacraft.server.build.QualityChecker.logQualityReport(qualityReport, generated.getDescription());

                                            // 如果有严重错误，记录但不阻止预览（让用户看到问题）
                                            if (!qualityReport.errors.isEmpty()) {
                                                FormacraftMod.LOGGER.warn("Quality check found errors for preview: {}", qualityReport.errors);
                                            }

                                            String terrainSummary = reported.report().summaryZh();
                                            if (!terrainSummary.isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                                            }

                                            // H-layer (MVP): auto validation & repair before preview
                                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                                    BuildConstraintContext.withRequest(req, () ->
                                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.empty(), generated.getBlocks())
                                                    );
                                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(repair.summary()));
                                            }

                                            // 设置玩家 UUID
                                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                                    player.getUuid(),
                                                    origin,
                                                    generated.getDescription(),
                                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                                            );

                                            // 存储结构用于预览
                                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                                            // 自动发送预览
                                            List<OutlineBlock> outline =
                                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                            FormaCraftNetworking.sendPreviewOutline(player, outline);
                                            // Send skeleton layout preview (if present in CompositeSpec's first structure extra)
                                            try {
                                                if (compositeSpec.getStructures() != null && !compositeSpec.getStructures().isEmpty()) {
                                                    var s0 = compositeSpec.getStructures().getFirst();
                                                    if (s0 != null && s0.getSpec() != null && s0.getSpec().getExtra() != null) {
                                                        FormaCraftNetworking.sendPreviewSkeleton(player, origin, s0.getSpec().getExtra());
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                                            player.sendMessage(net.minecraft.text.Text.translatable(
                                                    "formacraft.preview.ready.composite"),
                                                    false);
                                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                                    "Composite structure preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
                                            ));
                                            hbAlive.set(false);

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
                                                com.formacraft.common.generation.structure.StructureGenerator generator =
                                                        com.formacraft.server.generation.GenerationHub.routeStructure(updated);
                                                final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                                        com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                                BuildConstraintContext.withRequest(req, () -> generator.generate(updated, origin, serverWorld))
                                                        );
                                                final com.formacraft.server.build.GeneratedStructure generated = reported.value();

                                                // 质量检查
                                                com.formacraft.server.build.QualityChecker.QualityReport qualityReport =
                                                        com.formacraft.server.build.QualityChecker.checkQuality(generated, updated, serverWorld);
                                                com.formacraft.server.build.QualityChecker.logQualityReport(qualityReport, generated.getDescription());

                                                // 如果有严重错误，记录但不阻止预览（让用户看到问题）
                                                if (!qualityReport.errors.isEmpty()) {
                                                    FormacraftMod.LOGGER.warn("Quality check found errors for preview: {}", qualityReport.errors);
                                                }

                                                String terrainSummary = reported.report().summaryZh();
                                                if (!terrainSummary.isBlank()) {
                                                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                                                }

                                                // H-layer (MVP): auto validation & repair before preview
                                                com.formacraft.server.build.BuildAutoRepair.Result repair =
                                                        BuildConstraintContext.withRequest(req, () ->
                                                                com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.ofNullable(updated.getStyle()), generated.getBlocks())
                                                        );
                                                if (repair.summary() != null && !repair.summary().isBlank()) {
                                                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(repair.summary()));
                                                }

                                                // 生成阶段硬裁剪：禁区/轮廓/选区（由工具提供）
                                                com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                                        player.getUuid(),
                                                        origin,
                                                        generated.getDescription(),
                                                        BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                                                );

                                                com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);
                                                List<OutlineBlock> outline =
                                                        com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                                FormaCraftNetworking.sendPreviewOutline(player, outline);
                                                try {
                                                    if (updated.getExtra() != null) {
                                                        FormaCraftNetworking.sendPreviewSkeleton(player, origin, updated.getExtra());
                                                    }
                                                } catch (Throwable ignored) {}
                                                com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                                                player.sendMessage(net.minecraft.text.Text.translatable(
                                                        "formacraft.preview.ready.updated_building"),
                                                        false);
                                                // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                                        "Updated building preview ready. Use /forma_confirm to rebuild or /forma_cancel to cancel."
                                                ));
                                                hbAlive.set(false);
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

                        orchestrator.requestBuildingSpec(req)
                                .orTimeout(605, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    hbAlive.set(false);
                                    FormacraftMod.LOGGER.error("Orchestrator building request failed", ex);
                                    String msg = OrchestratorErrorHumanizer.humanize("BuildingSpec", req, ex);
                                    context.server().execute(() ->
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                    // IMPORTANT: returning null here will still flow into thenAccept(spec -> ...),
                                    // which would cause a second generic "未返回 BuildingSpec" error. We already sent the error above.
                                    return null;
                                })
                                .thenAccept(spec -> {
                                    // spec==null means we already handled an error in exceptionally() above.
                                    if (spec == null) return;

                                    // 在主线程中执行
                                    context.server().execute(() -> {
                                        hbPhase.set("已收到 AI 结果，正在生成建筑预览");
                                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成建筑预览…"));
                                        // 生成预览结构
                                        BlockPos origin = req.getPlayerPos();
                                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                            if (LlmPlanPreviewBuilder.tryBuildPreview(player, req, spec, origin, serverWorld, hbAlive)) {
                                                return;
                                            }

                                            // 传统的 BuildingSpec 处理流程
                                            // 生成结构用于预览
                                                    com.formacraft.common.generation.structure.StructureGenerator generator =
                                                    com.formacraft.server.generation.GenerationHub.routeStructure(spec);
                                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                            BuildConstraintContext.withRequest(req, () -> generator.generate(spec, origin, serverWorld))
                                                    );
                                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();

                                            // 质量检查（CitySpec 没有 BuildingSpec，传递 null）
                                            com.formacraft.server.build.QualityChecker.QualityReport qualityReport =
                                                    com.formacraft.server.build.QualityChecker.checkQuality(generated, null, serverWorld);
                                            com.formacraft.server.build.QualityChecker.logQualityReport(qualityReport, generated.getDescription());

                                            // 如果有严重错误，记录但不阻止预览（让用户看到问题）
                                            if (!qualityReport.errors.isEmpty()) {
                                                FormacraftMod.LOGGER.warn("Quality check found errors for preview: {}", qualityReport.errors);
                                            }

                                            String terrainSummary = reported.report().summaryZh();
                                            if (!terrainSummary.isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(terrainSummary));
                                            }

                                            // H-layer (MVP): auto validation & repair before preview
                                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                                    BuildConstraintContext.withRequest(req, () ->
                                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.ofNullable(spec.getStyle()), generated.getBlocks())
                                                    );
                                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(repair.summary()));
                                            }

                                            // 生成阶段硬裁剪：禁区/轮廓/选区（由工具提供）
                                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                                    player.getUuid(),
                                                    origin,
                                                    generated.getDescription(),
                                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                                            );

                                            // 存储结构用于预览
                                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                                            // 自动发送预览
                                            List<OutlineBlock> outline =
                                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                            FormaCraftNetworking.sendPreviewOutline(player, outline);
                                            try {
                                                if (spec.getExtra() != null) {
                                                    FormaCraftNetworking.sendPreviewSkeleton(player, origin, spec.getExtra());
                                                }
                                            } catch (Throwable ignored) {}
                                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                                            player.sendMessage(net.minecraft.text.Text.translatable(
                                                    "formacraft.preview.ready.building"),
                                                    false);
                                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                                    "Building preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
                                            ));
                                            hbAlive.set(false);
                                        }

                                        // 也发送 BuildingSpec 给客户端（用于 UI 显示）
                                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(spec));
                                    });
                                });
                    }
                }));
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

    // Copied from FormaCraftNetworking (lines ~2020-2040), with identical behavior.
    private static boolean isIsComposite(String requestText, boolean isCity) {
        boolean isMingQingCourtyard =
                (requestText.contains("明清") || requestText.contains("官式") || requestText.contains("ming") || requestText.contains("qing")) &&
                        (requestText.contains("四合院") || requestText.contains("院落") || requestText.contains("宅院") || requestText.contains("大院") ||
                                requestText.contains("courtyard"));
        boolean isComposite = !isCity && (
                requestText.contains("要塞") || requestText.contains("fort") ||
                        requestText.contains("复合") || requestText.contains("组合") ||
                        requestText.contains("village") || requestText.contains("multiple") ||
                        // 建筑群落/组团（避免被当成单体建筑，结果生成一个塔楼）
                        requestText.contains("群落") || requestText.contains("建筑群") ||
                        requestText.contains("建筑群落") || requestText.contains("组团") ||
                        requestText.contains("组群") || requestText.contains("聚落") ||
                        requestText.contains("多栋") || requestText.contains("多座") ||
                        requestText.contains("院落群")
        );
        if (isMingQingCourtyard) {
            isComposite = false;
        }
        return isComposite;
    }
}

