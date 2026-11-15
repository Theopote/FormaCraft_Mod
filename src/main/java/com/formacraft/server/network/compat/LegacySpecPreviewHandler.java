package com.formacraft.server.network.compat;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics;
import com.formacraft.common.orchestrator.AiPlanResult;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.BuildPreviewDelivery;
import com.formacraft.server.build.StructureGenerationResult;
import com.formacraft.server.network.FormaCraftServerNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACTIVE_COMPAT：显式 BuildingSpec / City / Composite 预览。
 * <p>
 * 从 {@code BuildRequestProcessor} 拆出，保持行为不变；新功能不得经此扩展默认规划链。
 * 消费者：显式 {@code outputFormat=buildingspec} 的 {@link AiPlanResult} 分支。
 */
public final class LegacySpecPreviewHandler {
    private LegacySpecPreviewHandler() {}

    /**
     * @return true if this result was a legacy spec kind and was handled
     */
    public static boolean tryHandle(
            AiPlanResult result,
            ServerPlayerEntity player,
            FormaRequest req,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        return switch (result) {
            case AiPlanResult.BuildingSpec(var spec) -> {
                LlmPlanRoutingMetrics.recordDirectStructurePreview(
                        (player == null ? null : player.getName().getString()), req);
                previewBuildingSpec(player, req, spec, origin, serverWorld, hbAlive);
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(spec));
                yield true;
            }
            case AiPlanResult.CompositeSpec(var composite) -> {
                previewCompositeSpec(player, req, composite, origin, serverWorld, hbAlive);
                yield true;
            }
            case AiPlanResult.CitySpec(var city) -> {
                previewCitySpec(player, req, city, origin, serverWorld, hbAlive);
                yield true;
            }
            default -> false;
        };
    }

    public static void previewBuildingSpec(
            ServerPlayerEntity player,
            FormaRequest req,
            BuildingSpec spec,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        var routeDecision = com.formacraft.server.generation.structure.router.GeneratorRouter.routeDecision(spec);
        if (!routeDecision.hasGeneratorKey()) {
            hbAlive.set(false);
            String msg = switch (routeDecision.capability()) {
                case NEEDS_REPLAN -> "无法匹配该建筑类型，请调整描述或让 AI 重新规划（NEEDS_REPLAN）。";
                case UNSUPPORTED -> "当前 BuildingSpec 无法路由到生成器（UNSUPPORTED）。";
                default -> "BuildingSpec 路由失败：" + routeDecision.reasonCode();
            };
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg));
            return;
        }
        com.formacraft.server.generation.structure.StructureGenerator generator =
                com.formacraft.server.generation.GenerationHub.routeStructure(spec);
        if (generator == null) {
            hbAlive.set(false);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "BuildingSpec 路由失败：" + routeDecision.reasonCode()));
            return;
        }
        final com.formacraft.server.build.BuildReportContext.Reported<StructureGenerationResult> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> StructureGenerationResult.capture(
                                () -> generator.generate(spec, origin, serverWorld)))
                );
        final StructureGenerationResult generated = reported.value();

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

    public static void previewCompositeSpec(
            ServerPlayerEntity player,
            FormaRequest req,
            CompositeSpec compositeSpec,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        com.formacraft.server.generation.structure.composite.CompositeStructureGenerator generator =
                new com.formacraft.server.generation.structure.composite.CompositeStructureGenerator();
        final com.formacraft.server.build.BuildReportContext.Reported<StructureGenerationResult> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> StructureGenerationResult.capture(
                                () -> generator.generate(compositeSpec, origin, serverWorld)))
                );
        final StructureGenerationResult generated = reported.value();

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

    public static void previewCitySpec(
            ServerPlayerEntity player,
            FormaRequest req,
            CitySpec citySpec,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        com.formacraft.server.city.CityBuilder cityBuilder = new com.formacraft.server.city.CityBuilder();
        final com.formacraft.server.build.BuildReportContext.Reported<StructureGenerationResult> reported =
                com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                        BuildConstraintContext.withRequest(req, () -> StructureGenerationResult.capture(
                                () -> cityBuilder.generate(citySpec, origin, serverWorld)))
                );
        final StructureGenerationResult generated = reported.value();

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
}
