package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.network.OrchestratorErrorHumanizer;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.ReferenceImageChunk;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.orchestrator.AiPlanResult;
import com.formacraft.server.network.compat.LegacyBuildingEditHandler;
import com.formacraft.server.network.compat.LegacySpecPreviewHandler;
import com.formacraft.server.orchestrator.OrchestratorClient;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Canonical build request orchestration for {@link FormaCraftNetworking.RequestBuildPayload}.
 *
 * <p>Default path：{@link OrchestratorClient#requestAiPlan} → {@link AiPlanResult.LlmPlan} →
 * {@link LlmPlanPreviewBuilder}。显式 buildingspec / 整栋编辑委托
 * {@link LegacySpecPreviewHandler} / {@link LegacyBuildingEditHandler}（ACTIVE_COMPAT，勿扩展为默认）。
 */
public final class BuildRequestProcessor {
    private BuildRequestProcessor() {}

    private static final FcaLog LOG = FcaLog.of("BuildRequestProcessor");
    private static final ReferenceImageUploads REFERENCE_IMAGES = new ReferenceImageUploads();

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ReferenceImageChunk.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    if (BuildRequestGate.checkPermission(player, context.server()) != null) return;
                    REFERENCE_IMAGES.accept(player.getUuid(), payload);
                }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player != null) REFERENCE_IMAGES.clear(player.getUuid());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> REFERENCE_IMAGES.clearAll());
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.RequestBuildPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        FormacraftMod.LOGGER.warn("Received build request from null player");
                        return;
                    }

                    BuildRequestGate.AdmitResult admit = BuildRequestGate.tryAdmit(player, context.server());
                    if (admit.rejected()) {
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                admit.rejection().message().getString()));
                        return;
                    }
                    final BuildRequestGate.Ticket gateTicket = admit.ticket();
                    final boolean[] asyncStarted = {false};

                    // 使 AI 构件检索可见当前玩家私人库（ThreadLocal；异步回调线程需自行再设）
                    com.formacraft.common.component.query.ComponentRetriever.setViewerPlayer(player.getUuid());
                    try {
                    FormaRequest req = payload.request();
                    if (!REFERENCE_IMAGES.resolve(player.getUuid(), req)) {
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                net.minecraft.text.Text.translatable("formacraft.chat.reference.upload_failed").getString()));
                        return;
                    }
                    // Older clients can still label an explicit edit BUILD. Enforce intent
                    // before contacting the backend so its response cannot rebuild the host.
                    if ((req.getPromptMode() == null || "BUILD".equalsIgnoreCase(req.getPromptMode()))
                            && com.formacraft.common.network.IncrementalRequestIntent.isIncremental(req.getUserMessage())) {
                        req.setPromptMode("PATCH");
                    }
                    com.formacraft.server.config.ServerLlmConfig.apply(req, context.server().isDedicated());
                    FormacraftMod.LOGGER.info("Received build request from player {}: {}",
                            player.getName().getString(), com.formacraft.common.model.request.RequestIntentText.resolve(req));

                    // 补齐“世界上下文”字段：让 Python 侧可以稳定获取 biome/facing/origin，而不是只依赖 prompt 文本解析。
                    // 注意：客户端可能已填充这些字段；这里仅在缺失时兜底。
                    try {
                        enrichRequestFromPlayer(req, player);
                        BuildPromptContextEnricher.enrich(req, player);
                    } catch (Throwable t) {
                        LOG.playerId(player.getName().getString()).warn("enrichRequestFromPlayer failed", t);
                    }
                    com.formacraft.server.state.PlayerProtectedZoneStorage.syncFromRequest(player, req);
                    com.formacraft.server.state.PlayerOutlineStorage.syncFromRequest(player, req);

                    if (!"BUILD".equalsIgnoreCase(req.getPromptMode())
                            && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld editWorld
                            && com.formacraft.server.build.BuildExecutionService.getInstance()
                                .hasActiveBuildForPlayer(player.getUuid(), editWorld)) {
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                net.minecraft.text.Text.translatable("formacraft.build.error.active_build").getString()));
                        return;
                    }
                    if (WindowSealPreview.tryHandle(player, req)) return;
                    if (WindowInsertPreview.tryHandle(player, req)) return;

                    // 状态：服务端已收到请求
                    context.server().execute(() -> ServerPlayNetworking.send(player,
                            new FormaCraftNetworking.ResponseBuildStatusPayload("服务端已收到请求，正在请求后端…")));

                    // 默认/auto 请求统一走 LlmPlan；只有显式 buildingspec 保留旧分流，
                    // 响应中的 kind 判别字段经 AiPlanResult 分发（不再在客户端/服务端靠关键词猜类型）。

                    {
                        // Chat 显式 buildingspec 编辑 → compat；默认增量走下方 LlmPlan patch。
                        if (LegacyBuildingEditHandler.tryStart(
                                context.server(), player, req, gateTicket, asyncStarted)) {
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

                        // Obtain the future before marking async so a sync throw cannot leak the gate.
                        gateTicket.advance(BuildRequestGate.RequestState.PLANNING);
                        var aiPlanFuture = orchestrator.requestAiPlan(req, hbPhase::set)
                                .orTimeout(605, TimeUnit.SECONDS);
                        asyncStarted[0] = true;
                        aiPlanFuture
                                .exceptionally(ex -> {
                                    hbAlive.set(false);
                                    FormacraftMod.LOGGER.error("Orchestrator building request failed", ex);
                                    String msg = OrchestratorErrorHumanizer.humanize("AiPlan", req, ex);
                                    context.server().execute(() ->
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg)));
                                    BuildRequestGate.release(gateTicket);
                                    return null;
                                })
                                .thenAccept(aiResult -> {
                                    if (aiResult == null) return;

                                    // Hop to the server thread for world reads / preview delivery.
                                    // LlmPlan off-thread compile is owned by scheduleBuildPreview.
                                    context.server().execute(() -> {
                                        boolean releaseAfterPreview = true;
                                        try {
                                            hbPhase.set("已收到 AI 结果，正在生成建筑预览");
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已收到 AI 结果，正在生成建筑预览…"));
                                            BlockPos origin = req.getPlayerPos();
                                            if (origin == null || !(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                                                hbAlive.set(false);
                                                return;
                                            }

                                            switch (aiResult) {
                                                case AiPlanResult.Clarification(var clar) -> {
                                                    hbAlive.set(false);
                                                    String msg = clar.messageZh();
                                                    if (msg == null || msg.isBlank()) {
                                                        msg = "需要补充更多信息才能开始生成，请描述建筑类型、风格或具体名称。";
                                                    }
                                                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseClarificationPayload(msg));
                                                }
                                                case AiPlanResult.LlmPlan(var plan) -> {
                                                    // Quota covers compile/preview; release only when schedule finishes
                                                    // (scheduleBuildPreview always invokes this callback, even on throw).
                                                    releaseAfterPreview = false;
                                                    gateTicket.advance(BuildRequestGate.RequestState.COMPILING);
                                                    LlmPlanPreviewBuilder.scheduleBuildPreview(
                                                            context.server(), player, req, plan, origin, serverWorld, hbAlive,
                                                            handled -> {
                                                                try {
                                                                    if (handled) {
                                                                        gateTicket.advance(BuildRequestGate.RequestState.PREVIEW_READY);
                                                                        return;
                                                                    }
                                                                    gateTicket.advance(BuildRequestGate.RequestState.FAILED);
                                                                    // No structure fallback: count as preview failure, not structure_after_fallback.
                                                                    LlmPlanRoutingMetrics.recordError(
                                                                            (player == null ? null : player.getName().getString()),
                                                                            req,
                                                                            "preview_unhandled_no_structure_fallback");
                                                                    ServerPlayNetworking.send(player,
                                                                            new FormaCraftNetworking.ResponseBuildErrorPayload(
                                                                                    "LlmPlan 预览生成失败（已跳过假 BuildingSpec 回退）。请调整请求或检查后端输出。"
                                                                            ));
                                                                    hbAlive.set(false);
                                                                } finally {
                                                                    BuildRequestGate.release(gateTicket);
                                                                }
                                                            },
                                                            gateTicket);
                                                }
                                                default -> LegacySpecPreviewHandler.tryHandle(
                                                        aiResult, player, req, origin, serverWorld, hbAlive);
                                            }
                                        } catch (Throwable t) {
                                            hbAlive.set(false);
                                            FormacraftMod.LOGGER.error("Build preview delivery failed", t);
                                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                                                    "生成预览时发生内部错误，请重试。"));
                                        } finally {
                                            if (releaseAfterPreview) {
                                                BuildRequestGate.release(gateTicket);
                                            }
                                        }
                                    });
                                });
                    }
                    } finally {
                        com.formacraft.common.component.query.ComponentRetriever.clearViewerPlayer();
                        if (!asyncStarted[0]) {
                            BuildRequestGate.release(gateTicket);
                        }
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
}

