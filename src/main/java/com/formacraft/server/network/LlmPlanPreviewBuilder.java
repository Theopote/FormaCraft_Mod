package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.preview.OutlineBlock;
import com.formacraft.server.assembly.CapabilityGapMessages;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.LlmPlanTerrainBounds;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics.FallbackReason;
import com.formacraft.common.network.LlmPlanTerrainBounds.Bounds;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.BuildPreviewPipeline;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.foundation.FoundationPlanner;
import com.formacraft.server.foundation.FoundationType;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.terrain.TerrainAdaptationEngine;
import com.formacraft.server.terrain.TerrainFit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Extracted LlmPlan preview pipeline for the building-spec flow.
 */
public final class LlmPlanPreviewBuilder {

    private static final FcaLog LOG = FcaLog.of("LlmPlanPreviewBuilder");

    private LlmPlanPreviewBuilder() {}

    /**
     * @param llmPlan 编排器返回的 LlmPlan（非伪装 BuildingSpec）
     * @return true 表示已处理（成功、错误或主动回退）；false 表示应回退整栋 {@code GenerationHub.routeStructure()}
     */
    public static boolean tryBuildPreview(
            ServerPlayerEntity player,
            FormaRequest req,
            LlmPlan llmPlan,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        if (llmPlan == null) {
            return false;
        }

        if (llmPlan.hasCapabilityGap()) {
            reportCapabilityGap(player, req, hbAlive, resolvePlanGap(llmPlan));
            return true;
        }

        LlmPlanRoutingMetrics.recordTaggedAttempt(player, req);
        com.formacraft.common.network.metrics.LandmarkRoutingMetrics.recordFromPlan(player, req, llmPlan);
        com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordFromPlan(player, req, llmPlan);
        String landmarkModule = com.formacraft.common.network.metrics.LandmarkRoutingMetrics.extractLandmarkModuleId(llmPlan);
        if (landmarkModule != null) {
            String typWarn = com.formacraft.common.network.metrics.TypologyRoutingMetrics
                    .playerDeprecationWarningZh(landmarkModule);
            if (typWarn != null) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(typWarn));
            } else {
                String warn = com.formacraft.common.network.metrics.LandmarkRoutingMetrics.playerWarningZh(
                        BuildingSpecRoutingPolicy.userIntentText(req), landmarkModule);
                if (warn != null) {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(warn));
                }
            }
        } else if (llmPlan.playerFidelityNoticeZh() != null && !llmPlan.playerFidelityNoticeZh().isBlank()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    llmPlan.playerFidelityNoticeZh().trim()));
        }

        if (BuildingSpecRoutingPolicy.shouldSkipLlmPlanPreview(req)) {
            LlmPlanRoutingMetrics.recordFallback(FallbackReason.ROUTING_POLICY, player, req);
            return false;
        }

        String worldKey = com.formacraft.server.generation.typology.builder.PalacePreviewPlan.worldKeyOf(serverWorld);
        com.formacraft.server.preview.GenerationRequestContext.begin(
                player.getUuid(), worldKey, origin);
        try {
            boolean patchRequest = req.getPromptMode() != null
                    && !"BUILD".equalsIgnoreCase(req.getPromptMode().trim());

            // Phase 1 building-aware: roof material replace is server-owned (not unconstrained LLM patches).
            if (patchRequest
                    && com.formacraft.server.edit.RoofMaterialReplaceIntent.matches(req.getUserMessage())) {
                return tryServerOwnedRoofMaterialReplace(player, req, origin, serverWorld, hbAlive);
            }

            // patch.blocks 专用：走 PreviewTicket（增量 Patch 预览 + ticket 确认）
            if (llmPlan.mode() == LlmPlan.Mode.patch
                    && llmPlan.patch() != null
                    && llmPlan.patch().blocks() != null
                    && !llmPlan.patch().blocks().isEmpty()
                    && !llmPlan.usesPlanProgramMode()
                    && (llmPlan.components() == null || llmPlan.components().isEmpty())) {
                return issueComponentOrBlockPatchPreview(player, req, origin, llmPlan, hbAlive,
                        com.formacraft.common.llm.bridge.BlockPatchBridge.toBlockPatches(llmPlan.patch()));
            }

            // mode=patch + 局部 components[]（如 FACADE_WINDOWS）：编译为 BlockPatch 后走同一 Ticket 路径
            if (llmPlan.mode() == LlmPlan.Mode.patch
                    && llmPlan.usesComponentMode()
                    && isIncrementalComponentPatch(llmPlan)
                    && !llmPlan.usesPlanProgramMode()) {
                LlmPlan patchPlan = sanitizeIncrementalPatchPlan(llmPlan);
                BlockPos patchOrigin = resolvePatchOrigin(player, req, origin, patchPlan);
                com.formacraft.common.terrain.TerrainStrategySampler terrainSampler =
                        new com.formacraft.common.terrain.TerrainStrategySampler();
                var compilation = ComponentPlanCompiler.compileWithCirculation(
                        patchPlan, patchOrigin, serverWorld, terrainSampler, false);
                var compileFailure = com.formacraft.server.assembly.AssemblyCompileDiagnostics.get();
                if (compileFailure != null) {
                    reportCapabilityGap(player, req, hbAlive, compileFailure);
                    return true;
                }
                List<com.formacraft.common.patch.BlockPatch> compiled = compilation.patches();
                if (compiled == null || compiled.isEmpty()) {
                    hbAlive.set(false);
                    LlmPlanRoutingMetrics.recordError(player, req, "patch_components_empty");
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                            "增量 components[] 编译结果为空，无法应用局部修改。"));
                    return true;
                }
                return issueComponentOrBlockPatchPreview(player, req, origin, patchPlan, hbAlive, compiled);
            }

            // Phase 1 A2：增量请求不得静默整栋重建（屋顶原点、二次生成等）。
            if (patchRequest) {
                hbAlive.set(false);
                LlmPlanRoutingMetrics.recordError(player, req, "patch_expected_rebuild_rejected");
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "增量修改需要 LlmPlan mode=patch 且提供 patch.blocks[] 或局部 components[]（如开窗）。"
                                + "后端返回了整栋方案，已拒绝落地以免在屋顶/脚下重建。"
                                + "请改用「只改局部：…」并确保后端输出 patch，或框选区域后重试。"));
                return true;
            }

            // 获取 anchor（全局或第一个 slot）
            // 注意：LLM返回的anchor通常是建筑底平面的中心点
            BlockPos planOrigin = origin;
            if (llmPlan.anchor() != null) {
                planOrigin = new BlockPos(
                        llmPlan.anchor().x(),
                        llmPlan.anchor().y(),
                        llmPlan.anchor().z()
                );
            }

            // 对于使用BuildingSpec路径的情况，需要检查是否需要将锚点（中心）转换为左下角
            // 但对于LlmPlan系统，ComponentPlanCompiler和PlanProgramCompiler已经处理了相对坐标
            // 所以这里不需要额外转换（patches中的dx/dy/dz已经是相对于planOrigin的）

            // 提取风格信息（用于传递给编译器）
            String styleProfileId = llmPlan.styleProfile();

            // 检查应该使用哪种编译路径
            List<com.formacraft.common.patch.BlockPatch> patches;
            var circulation = new ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();

            if (llmPlan.usesPlanProgramMode()) {
                if (!llmPlan.hasValidPlanProgramContent()) {
                    if (llmPlan.usesComponentMode()) {
                        FormacraftMod.LOGGER.warn(
                                "LlmPlan PlanProgram/Skeleton has empty zones; explicitly degrading to components[]");
                        com.formacraft.common.terrain.TerrainStrategySampler terrainSampler =
                                new com.formacraft.common.terrain.TerrainStrategySampler();
                        var compilation = ComponentPlanCompiler.compileWithCirculation(
                                llmPlan, planOrigin, serverWorld, terrainSampler, false);
                        patches = compilation.patches();
                        circulation.addAll(compilation.circulation());
                    } else {
                        reportCapabilityGap(player, req, hbAlive, new com.formacraft.common.llm.dto.CapabilityGap(
                                "E_PLAN_PROGRAM_MISSING",
                                "PlanProgram 模式缺少有效 zones，且无 components[] 可降级",
                                "planSkeleton.zones|planProgram.zones",
                                List.of("提供非空 zones", "或同时提供完整 components[] 以显式降级")
                        ));
                        return true;
                    }
                } else if (llmPlan.planSkeleton() != null
                        && llmPlan.planSkeleton().zones() != null
                        && !llmPlan.planSkeleton().zones().isEmpty()) {
                    patches = com.formacraft.common.compiler.PlanProgramCompiler.compileFromPlanSkeleton(
                            llmPlan.planSkeleton(),
                            planOrigin,
                            serverWorld,
                            styleProfileId,
                            req != null ? req.getOutline() : null
                    );
                } else {
                    patches = com.formacraft.common.compiler.PlanProgramCompiler.compile(
                            llmPlan.planProgram(),
                            planOrigin,
                            serverWorld,
                            styleProfileId
                    );
                }

                // BuildingMass：显式启用时作为体块壳替代层，冲突感知合并（禁止裸 addAll）
                if (com.formacraft.common.mass.integration.BuildingMassSystemIntegrator.shouldUseBuildingMassPath(llmPlan)) {
                    List<com.formacraft.common.patch.BlockPatch> buildingMassPatches =
                            com.formacraft.common.mass.integration.BuildingMassSystemIntegrator.compileWithBuildingMass(
                                    llmPlan, planOrigin, serverWorld);
                    if (!buildingMassPatches.isEmpty()) {
                        var merged = com.formacraft.common.patch.PatchMergePolicy
                                .mergeSkeletonWithBuildingMass(patches, buildingMassPatches);
                        patches = merged.patches();
                        FormacraftMod.LOGGER.info(
                                "BuildingMass merge: skeletonOnly={} massOnly={} overridden={} total={}",
                                merged.stats().skeletonOnly(), merged.stats().massOnly(),
                                merged.stats().overridden(), merged.stats().total());
                    }
                }
            } else {
                com.formacraft.common.terrain.TerrainStrategySampler terrainSampler =
                        new com.formacraft.common.terrain.TerrainStrategySampler();
                var compilation = ComponentPlanCompiler.compileWithCirculation(
                        llmPlan, planOrigin, serverWorld, terrainSampler, false);
                patches = compilation.patches();
                circulation.addAll(compilation.circulation());
            }

            // Compilation failures invalidate mixed plans as well as assembly-only plans.
            var compileFailure = com.formacraft.server.assembly.AssemblyCompileDiagnostics.get();
            if (compileFailure != null) {
                reportCapabilityGap(player, req, hbAlive, compileFailure);
                return true;
            }
            var conversion = PlanPatchConverter.convert(patches, planOrigin);
            List<PlannedBlock> plannedBlocks = conversion.blocks();
            if (conversion.invalid() > 0) {
                FormacraftMod.LOGGER.warn(
                        "LlmPlan: {} invalid blocks (critical={}, softRecovered={}) out of {} patches",
                        conversion.invalid(), conversion.criticalInvalid(), conversion.softRecovered(),
                        patches.size());
            }
            if (conversion.hasCriticalFailure()) {
                String sample = conversion.samples().stream()
                        .filter(PlanPatchConverter.Diagnostic::critical)
                        .map(d -> String.format("(%d,%d,%d):%s", d.dx(), d.dy(), d.dz(), d.targetBlock()))
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("n/a");
                reportCapabilityGap(player, req, hbAlive, new com.formacraft.common.llm.dto.CapabilityGap(
                        "E_PLAN_INVALID_CRITICAL_PATCH",
                        "关键 Patch 无效，已拒绝生成（critical=" + conversion.criticalInvalid() + "）样例: " + sample,
                        "patches[].targetBlock",
                        List.of("修正非法方块 ID / 属性", "检查楼梯/屋顶/基础等结构构件材质")
                ));
                return true;
            }

            if (plannedBlocks.isEmpty()) {
                com.formacraft.common.llm.dto.CapabilityGap compileGap =
                        com.formacraft.server.assembly.AssemblyCompileDiagnostics.get();
                if (compileGap != null || com.formacraft.server.assembly.AssemblyPlanCapability.isAssemblyOnly(llmPlan)) {
                    reportCapabilityGap(player, req, hbAlive, compileGap != null
                            ? compileGap
                            : new com.formacraft.common.llm.dto.CapabilityGap(
                                    "E_ASSEMBLY_EMPTY_OUTPUT",
                                    "ASSEMBLY plan compiled to zero blocks",
                                    "components[]",
                                    List.of("Check params.assembly preset/graph.", "Use exported schema types and ports.")
                            ));
                    return true;
                }
                LlmPlanRoutingMetrics.recordFallback(FallbackReason.EMPTY_OUTPUT, player, req);
                return false;
            }

            FormacraftMod.LOGGER.info("LlmPlan: converted {} patches to {} planned blocks (origin: {})",
                    patches.size(), plannedBlocks.size(), planOrigin);

            var landingSites = com.formacraft.server.terrain.BuildingLandingPlanner.sites(llmPlan, planOrigin);
            boolean authoredSupports = LlmPlanTerrainBounds.hasAuthoredSupports(llmPlan);
            if (!landingSites.isEmpty() && !authoredSupports) {
                var landingStrategy = llmPlan.globalConstraints() != null && llmPlan.globalConstraints().terrainStrategy() != null
                        ? llmPlan.globalConstraints().terrainStrategy() : GlobalConstraints.TerrainStrategy.ADAPTIVE;
                final List<PlannedBlock> landingInput = plannedBlocks;
                var landing = BuildConstraintContext.withRequest(req, () ->
                        com.formacraft.server.terrain.BuildingLandingPlanner.prepare(landingInput, landingSites,
                                com.formacraft.server.terrain.BuildingLandingPlanner.ground(serverWorld), landingStrategy,
                                LlmPlanTerrainBounds.wantsStiltFoundation(req), Blocks.COBBLESTONE.getDefaultState(),
                                com.formacraft.server.terrain.TerrainSupportPolicy.fromRequest(req)));
                if (landing.problem() != null) {
                    hbAlive.set(false);
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(landing.problem()));
                    LlmPlanRoutingMetrics.recordError(player, req, "terrain:" + landing.problem());
                    return true;
                }
                plannedBlocks = landing.blocks();
                if(landing.deferredAccess()>0) {
                    ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildStatusPayload(
                            "建筑可以生成；有"+landing.deferredAccess()+"处入口尚未连接自然地面，可在之后补建栈道、楼梯或连廊。"));
                    FormacraftMod.LOGGER.info("LlmPlan landing: deferredAccess={}",landing.deferredAccess());
                }
                if(!landing.siteShifts().isEmpty()) {
                    var adjusted=new ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();
                    for(var flight:circulation) {
                        var points=new java.util.LinkedHashSet<BlockPos>(flight.occupied());
                        points.addAll(flight.clearance());
                        var displacements=new java.util.HashSet<Integer>();
                        for(var point:points) displacements.add(landing.displacement(point.add(planOrigin)));
                        if(displacements.size()>1)
                            throw new IllegalArgumentException("跨建筑楼梯不能使用不同的落地高度，请使用连接平台。");
                        int shift=displacements.isEmpty()?0:displacements.iterator().next();
                        adjusted.add(com.formacraft.server.assembly.AssemblyCirculationConstraints.shift(flight,new BlockPos(0,shift,0)));
                    }
                    circulation=adjusted;
                }
                planOrigin = planOrigin.up(landing.dy());
                FormacraftMod.LOGGER.info("LlmPlan landing: sites={}, dy={}, supportBlocks={}, accessSteps={}",
                        landingSites.size(), landing.dy(), landing.supports(), landing.steps());
            } else {
                // ========== 地形平整：在生成建筑之后平整地坪 ==========
                boolean terrainPadApplied = false;
                boolean procedural = llmPlan.components() != null && llmPlan.components().stream().anyMatch(c ->
                        c != null && c.params() != null && c.params().containsKey("fbd_program"));
                boolean wantsStiltFoundation = LlmPlanTerrainBounds.wantsStiltFoundation(req);
                // 获取地形策略
                GlobalConstraints.TerrainStrategy terrainStrategy =
                        (llmPlan.globalConstraints() != null && llmPlan.globalConstraints().terrainStrategy() != null)
                                ? llmPlan.globalConstraints().terrainStrategy()
                                : GlobalConstraints.TerrainStrategy.ADAPTIVE;

                // 如果策略是 ADAPTIVE 或 FLATTEN，进行地坪平整（栅栏/栏杆等线性构件跳过，避免 10×10 石台）
                boolean b = llmPlan.styleAttributes() != null && llmPlan.styleAttributes().floorMaterial() != null;
                if ((terrainStrategy == GlobalConstraints.TerrainStrategy.ADAPTIVE || terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN)
                        && !wantsStiltFoundation
                        && !LlmPlanTerrainBounds.isLightweightLinearPlan(llmPlan)) {
                    Bounds blockBounds = LlmPlanTerrainBounds.computePlannedBlockBounds(plannedBlocks);
                    Bounds componentBounds = LlmPlanTerrainBounds.computeComponentBounds(llmPlan, planOrigin);
                    Bounds padBounds = LlmPlanTerrainBounds.chooseTerrainPadBounds(componentBounds, blockBounds);
                    if (padBounds == null) {
                        padBounds = blockBounds;
                    }
                    if (padBounds == null) {
                        FormacraftMod.LOGGER.warn("LlmPlan: failed to compute terrain pad bounds, skipping terrain flattening");
                    } else {
                        int minX = padBounds.minX();
                        int minY = padBounds.minY();
                        int minZ = padBounds.minZ();
                        int maxX = padBounds.maxX();
                        int maxY = padBounds.maxY();
                        int maxZ = padBounds.maxZ();

                        int width = Math.max(10, padBounds.width());
                        int depth = Math.max(10, padBounds.depth());
                        BlockPos center = padBounds.centerAtY(planOrigin.getY());

                        TerrainFit.FootprintAnalysis analysis = TerrainFit.analyze(serverWorld, center, width, depth);

                        // 计算目标高度：优先使用 anchor，高度偏差过大时回落到地形中位数
                        int targetY = planOrigin.getY();
                        int minAllowed = analysis.minY() - 2;
                        int maxAllowed = analysis.maxY() + 6;
                        if (targetY < minAllowed || targetY > maxAllowed) {
                            targetY = analysis.medianY() + 1;
                        }

                        // A terrain base change is one rigid translation of the whole plan.
                        int terrainDy = targetY - planOrigin.getY();
                        if (terrainDy != 0) {
                            plannedBlocks = LlmPlanTerrainBounds.translateBlocks(plannedBlocks, terrainDy);
                            planOrigin = planOrigin.up(terrainDy);
                            minY += terrainDy;
                            maxY += terrainDy;
                            center = center.up(terrainDy);
                        }

                        // 选择填充材料
                        BlockState fillMaterial = Blocks.COBBLESTONE.getDefaultState();
                        if (b) {
                            String mat = llmPlan.styleAttributes().floorMaterial().trim();
                            if (!mat.isEmpty()) {
                                String id = mat.startsWith("minecraft:") ? mat : "minecraft:" + mat;
                                try {
                                    net.minecraft.util.Identifier bid = net.minecraft.util.Identifier.tryParse(id);
                                    if (bid != null) {
                                        fillMaterial = net.minecraft.registry.Registries.BLOCK.get(bid).getDefaultState();
                                    }
                                } catch (Exception e) {
                                    LOG.debug("resolve fill material failed mat={}", mat, e);
                                }
                            }
                        }

                        // 生成平整地坪的 PlannedBlock
                        final int finalMinX = minX;
                        final int finalMinZ = minZ;
                        final int finalMaxX = maxX;
                        final int finalMaxZ = maxZ;
                        final int finalMinY = minY;
                        final int finalMaxY = maxY;
                        final int finalTargetY = targetY;
                        final int finalWidth = width;
                        final int finalDepth = depth;
                        final BlockPos finalCenter = center;
                        final BlockState finalFillMaterial = fillMaterial;
                        final com.formacraft.server.terrain.BuildingOccupancyMask occupancy =
                                com.formacraft.server.terrain.BuildingOccupancyMask.fromSolidBlocks(plannedBlocks);
                        final List<BlockPos> proceduralFootprint = procedural
                                ? com.formacraft.server.build.StructureSupportAnalysis.unsupportedBases(
                                        plannedBlocks, p -> Blocks.AIR.getDefaultState()) : List.of();
                        List<PlannedBlock> terrainPadBlocks = BuildConstraintContext.withRequest(req, () -> {
                            List<PlannedBlock> out = new ArrayList<>();
                            java.util.LinkedHashSet<BlockPos> cleared = new java.util.LinkedHashSet<>();
                            int buildingHeight = Math.max(1, finalMaxY - finalMinY + 1);
                            int padDepth = terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN ? 4 : 1;
                            int clearHeight = terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN
                                    ? buildingHeight + 8
                                    : Math.min(6, buildingHeight + 2);

                            if (terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN) {
                                out.addAll(TerrainFit.balancedPad(
                                        serverWorld, finalCenter, finalWidth, finalDepth, finalTargetY, finalFillMaterial, padDepth, clearHeight, true, true));
                            } else if (procedural) {
                                // Only bearing columns, not roof overhangs or gaps between independent pavilions.
                                out.addAll(TerrainFit.adaptivePad(serverWorld, proceduralFootprint, finalTargetY,
                                        finalFillMaterial, padDepth, 0, true, true));
                            } else if (analysis.range() > 1) {
                                out.addAll(TerrainFit.adaptivePad(
                                        serverWorld, finalCenter, finalWidth, finalDepth, finalTargetY, finalFillMaterial, padDepth, clearHeight, true, true));
                            }

                            // 占地掩码驱动清净空：不清理包围盒内庭院/L 形空白列
                            int clearFromY = finalTargetY + 1;
                            int clearBuffer = 5;
                            if (!occupancy.isEmpty()) {
                                for (long col : occupancy.columnKeys()) {
                                    int x = com.formacraft.server.terrain.BuildingOccupancyMask.unpackX(col);
                                    int z = com.formacraft.server.terrain.BuildingOccupancyMask.unpackZ(col);
                                    int columnTop = occupancy.maxYInColumn(x, z, finalMaxY);
                                    int clearToY = Math.min(columnTop + clearBuffer,
                                            Math.max(finalMaxY + clearBuffer, finalMinY + buildingHeight + clearBuffer));
                                    for (int y = clearFromY; y <= clearToY; y++) {
                                        BlockPos clearPos = new BlockPos(x, y, z);
                                        if (!cleared.add(clearPos.toImmutable())) continue;
                                        if (!com.formacraft.server.build.BuildConstraintContext.allow(clearPos)) continue;
                                        net.minecraft.block.BlockState current = serverWorld.getBlockState(clearPos);
                                        if (!current.isAir()) {
                                            out.add(new PlannedBlock(clearPos, Blocks.AIR.getDefaultState(), com.formacraft.common.build.GeometrySource.generated("terrain_clearance")));
                                        }
                                    }
                                }
                            }
                            return out;
                        });
                        if (procedural && terrainStrategy == GlobalConstraints.TerrainStrategy.ADAPTIVE)
                            terrainPadApplied = true; // A second rectangular pad would fill the gaps again.

                        // 将地坪平整的方块添加到结果的最前面（确保先清理地形，再放置建筑）
                        if (!terrainPadBlocks.isEmpty()) {
                            List<PlannedBlock> merged = new ArrayList<>(terrainPadBlocks.size() + plannedBlocks.size());
                            merged.addAll(terrainPadBlocks);
                            merged.addAll(plannedBlocks);
                            plannedBlocks = merged;
                            terrainPadApplied = true;

                            FormacraftMod.LOGGER.info("LlmPlan: flattened terrain area {}x{} at Y={}, cleared building space up to Y={}, added {} pad blocks",
                                    width, depth, targetY, Math.max(maxY + 5, minY + Math.max(1, maxY - minY + 1) + 5), terrainPadBlocks.size());
                        }
                    }
                }

                // 地形基础处理（仅 LlmPlan）：根据地形起伏决定台阶/支柱/覆土
                if (!plannedBlocks.isEmpty()
                        && !authoredSupports
                        && !LlmPlanTerrainBounds.isLightweightLinearPlan(llmPlan)
                        && llmPlan.globalConstraints() != null
                        && llmPlan.globalConstraints().terrainStrategy() != null
                        && llmPlan.globalConstraints().terrainStrategy()
                        != GlobalConstraints.TerrainStrategy.PRESERVE) {
                    int minX = Integer.MAX_VALUE;
                    int minY = Integer.MAX_VALUE;
                    int minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE;
                    int maxY = Integer.MIN_VALUE;
                    int maxZ = Integer.MIN_VALUE;
                    for (PlannedBlock pb : plannedBlocks) {
                        if (pb == null || pb.getPos() == null) continue;
                        BlockPos p = pb.getPos();
                        minX = Math.min(minX, p.getX());
                        minY = Math.min(minY, p.getY());
                        minZ = Math.min(minZ, p.getZ());
                        maxX = Math.max(maxX, p.getX());
                        maxY = Math.max(maxY, p.getY());
                        maxZ = Math.max(maxZ, p.getZ());
                    }

                    int width = Math.max(1, maxX - minX + 1);
                    int depth = Math.max(1, maxZ - minZ + 1);
                    int blockHeight = Math.max(1, maxY - minY + 1);
                    BlockPos center = new BlockPos((minX + maxX) / 2, planOrigin.getY(), (minZ + maxZ) / 2);

                    TerrainFit.FootprintAnalysis analysis = TerrainFit.analyze(serverWorld, center, width, depth);
                    FoundationType foundationType = FoundationPlanner.chooseType(analysis.range(), blockHeight, null);
                    GlobalConstraints.TerrainStrategy strategy =
                            llmPlan.globalConstraints().terrainStrategy();
                    if (wantsStiltFoundation) {
                        foundationType = FoundationType.STILT;
                    } else if (strategy == GlobalConstraints.TerrainStrategy.TERRACE) {
                        foundationType = FoundationType.STEPPED;
                    } else if (strategy == GlobalConstraints.TerrainStrategy.FLATTEN) {
                        foundationType = FoundationType.FLAT_PAD;
                    }

                    FoundationPlanner.Decision fd = FoundationPlanner.knobsFor(
                            foundationType,
                            analysis.range(),
                            blockHeight,
                            2,
                            6
                    );
                    if (!wantsStiltFoundation
                            && strategy == GlobalConstraints.TerrainStrategy.ADAPTIVE
                            && (fd.type() == FoundationType.FLAT_PAD || fd.type() == FoundationType.STEPPED)
                            && fd.padDepth() > 1) {
                        fd = new FoundationPlanner.Decision(fd.type(), 1, fd.clearHeight(), fd.stilt());
                    }

                    BlockState fillMaterial = Blocks.COBBLESTONE.getDefaultState();
                    if (b) {
                        String mat = llmPlan.styleAttributes().floorMaterial().trim();
                        if (!mat.isEmpty()) {
                            String id = mat.startsWith("minecraft:") ? mat : "minecraft:" + mat;
                            try {
                                net.minecraft.util.Identifier bid = net.minecraft.util.Identifier.tryParse(id);
                                if (bid != null) {
                                    fillMaterial = net.minecraft.registry.Registries.BLOCK.get(bid).getDefaultState();
                                }
                            } catch (Exception e) {
                                LOG.debug("resolve floor fill material failed mat={}", mat, e);
                            }
                        }
                    }

                    int baseY = minY;
                    // 创建 final 变量供 lambda 使用
                    final int finalMinY = Math.min(minY, analysis.minY());
                    final int finalMaxY = Math.max(maxY, analysis.maxY());
                    final int finalBaseY = baseY;
                    final BlockState finalFillMaterial = fillMaterial;
                    final FoundationPlanner.Decision finalFd = fd;

                    TerrainAdaptationEngine.Bounds bounds = new TerrainAdaptationEngine.Bounds(
                            new BlockPos(minX, finalMinY, minZ),
                            new BlockPos(maxX, finalMaxY, maxZ),
                            false
                    );

                    boolean skipPadFoundation = terrainPadApplied
                            && (finalFd.type() == FoundationType.FLAT_PAD || finalFd.type() == FoundationType.STEPPED
                                || procedural && strategy == GlobalConstraints.TerrainStrategy.ADAPTIVE && !wantsStiltFoundation);
                    List<PlannedBlock> terrainPrep = skipPadFoundation ? List.of()
                            : BuildConstraintContext.withRequest(req, () -> switch (finalFd.type()) {
                                case STILT -> TerrainAdaptationEngine.anchorPillars(
                                        serverWorld,
                                        bounds,
                                        finalBaseY,
                                        finalFillMaterial,
                                        Math.max(6, finalFd.clearHeight() + 4),
                                        true,
                                        true
                                );
                                case EMBEDDED -> TerrainAdaptationEngine.carve(
                                        serverWorld,
                                        bounds,
                                        finalBaseY - Math.max(0, finalFd.padDepth()),
                                        finalFd.clearHeight()
                                );
                                case STEPPED, FLAT_PAD -> TerrainFit.balancedPad(
                                        serverWorld,
                                        center,
                                        width,
                                        depth,
                                        finalBaseY,
                                        finalFillMaterial,
                                        finalFd.padDepth(),
                                        finalFd.clearHeight(),
                                        true,
                                        true
                                );
                            });

                    if (terrainPrep != null && !terrainPrep.isEmpty()) {
                        List<PlannedBlock> merged = new ArrayList<>(terrainPrep.size() + plannedBlocks.size());
                        merged.addAll(terrainPrep);
                        merged.addAll(plannedBlocks);
                        plannedBlocks = merged;
                    }
                }

            }

            com.formacraft.common.build.GeneratedStructure generated =
                    new com.formacraft.common.build.GeneratedStructure(
                            player.getUuid(),
                            planOrigin,
                            "LlmPlan generated structure",
                            plannedBlocks
                    );

            var worldCirculation = new ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();
            for (var flight : circulation)
                worldCirculation.add(com.formacraft.server.assembly.AssemblyCirculationConstraints.shift(flight, planOrigin));
            BuildPreviewPipeline.Result pipeline = BuildPreviewPipeline.prepare(
                    player,
                    serverWorld,
                    generated,
                    null,
                    req,
                    java.util.Optional.empty(),
                    worldCirculation,
                    !landingSites.isEmpty()
            );

            if (pipeline.report() != null) {
                var softs = com.formacraft.server.assembly.AssemblyCompileDiagnostics.softWarnings();
                if (!softs.isEmpty()) {
                    for (var warn : softs) {
                        if (warn == null) continue;
                        pipeline.report().add(
                                com.formacraft.server.build.quality.BuildQualitySeverity.WARNING,
                                warn.code() == null ? "DECOR_COMPONENT_SKIPPED" : warn.code(),
                                warn.message()
                        );
                    }
                    PreviewStorage.storeQualityReport(player, pipeline.report());
                }
            }

            if (pipeline.report()!=null && com.formacraft.server.dsl.FbdRepairLoop.hasProgram(llmPlan)
                    && !pipeline.report().activeDiagnostics().isEmpty()) {
                var assessment=com.formacraft.server.dsl.FbdGeometryRepairGuard.assess(llmPlan,pipeline.report());
                FormacraftMod.LOGGER.info("FBD geometry repair assessment: eligible={} reason={} path={} instances={}",
                        assessment.eligible(),assessment.reason(),assessment.eligible()?assessment.permit().path():"-",
                        assessment.eligible()?assessment.permit().affectedInstances():0);
            }

            if (!pipeline.delivered() || pipeline.structure() == null) {
                if (pipeline.report() != null) {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                            pipeline.report().summaryZh()
                    ));
                }
                hbAlive.set(false);
                if (pipeline.report() != null) {
                    LlmPlanRoutingMetrics.recordError(player, req, "quality:" + pipeline.report().summaryZh());
                }
                return true;
            }

            com.formacraft.common.build.GeneratedStructure structure = pipeline.structure();
            PreviewStorage.storeStructure(player, structure);

            List<OutlineBlock> outline =
                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
            FormaCraftServerNetworking.sendPreviewOutline(player, outline);

            PreviewStorage.setPreview(player, true);

            player.sendMessage(net.minecraft.text.Text.translatable(
                            "formacraft.preview.ready.building"),
                    false);
            if (pipeline.report() != null) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                        pipeline.report().summaryZh()
                ));
            }
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "LlmPlan preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
            ));
            hbAlive.set(false);

            LlmPlanRoutingMetrics.recordSuccess(player, req, patches.size(), plannedBlocks.size());
            return true;
        } catch (Exception e) {
            hbAlive.set(false);
            FormacraftMod.LOGGER.error("Failed to process LlmPlan", e);
            LlmPlanRoutingMetrics.recordError(player, req, "process:" + e.getClass().getSimpleName());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "Failed to process LlmPlan: " + e.getMessage()
            ));
            return true;
        } finally {
            com.formacraft.server.preview.GenerationRequestContext.clear();
        }
    }

    /**
     * Opening / facade satellite types that are safe as incremental patch.components[] —
     * never MASS / ASSEMBLY / full-shell rebuilds.
     */
    private static final Set<String> INCREMENTAL_COMPONENT_TYPES = Set.of(
            "FACADE_WINDOWS", "WINDOW", "WINDOWS", "FACADE_OPENING", "FACADE",
            "ENTRANCE", "DOOR",
            "SIGNAGE", "DECOR_DETAIL", "DECOR"
    );

    /** Types that imply whole-building rebuild — reject under mode=patch + components[]. */
    private static final Set<String> REBUILD_COMPONENT_TYPES = Set.of(
            "MASS_MAIN", "MAIN_MASS", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "TOWER",
            "ASSEMBLY", "FOUNDATION", "ROOF", "ROOF_STRUCTURE", "WALL", "SHELL_BOX", "BOX_SHELL"
    );

    private static boolean isIncrementalComponentPatch(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return false;
        }
        for (com.formacraft.common.llm.dto.Component c : plan.components()) {
            if (c == null || c.componentType() == null || c.componentType().isBlank()) {
                return false;
            }
            String type = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (REBUILD_COMPONENT_TYPES.contains(type) || type.startsWith("MASS_") || type.contains("ASSEMBLY")) {
                return false;
            }
            if (!INCREMENTAL_COMPONENT_TYPES.contains(type)
                    && !com.formacraft.common.network.IncrementalPrimitivePolicy.allows(
                            com.formacraft.common.network.IncrementalWindowQuery.normalize(c))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Incremental patch satellites often arrive with style library tags
     * ({@code component_request:…}). Those conflict with envelope protection on
     * FACADE_WINDOWS/ENTRANCE. Strip library requests so the registry generator runs.
     */
    private static LlmPlan sanitizeIncrementalPatchPlan(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<com.formacraft.common.llm.dto.Component> cleaned = new ArrayList<>(plan.components().size());
        boolean anyChanged = false;
        for (com.formacraft.common.llm.dto.Component c : plan.components()) {
            if (c == null) {
                cleaned.add(null);
                continue;
            }
            var normalizedWindow = com.formacraft.common.network.IncrementalWindowQuery.normalize(c);
            boolean dirty = normalizedWindow != c;
            c = normalizedWindow;
            List<String> features = c.features();
            List<String> keptFeatures = features;
            if (features != null && !features.isEmpty()) {
                List<String> kept = new ArrayList<>(features.size());
                for (String f : features) {
                    if (f == null) continue;
                    String lower = f.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("component_request:") || lower.startsWith("group_request:")) {
                        dirty = true;
                        continue;
                    }
                    kept.add(f);
                }
                if (dirty) {
                    keptFeatures = kept;
                }
            }
            java.util.Map<String, Object> params = c.params() == null
                    ? new java.util.HashMap<>()
                    : new java.util.HashMap<>(c.params());
            if (!Boolean.TRUE.equals(params.get("allow_envelope_attach"))) {
                params.put("allow_envelope_attach", true);
                dirty = true;
            }
            if (dirty) {
                anyChanged = true;
                cleaned.add(new com.formacraft.common.llm.dto.Component(
                        c.componentType(),
                        c.slotId(),
                        c.relativePosition(),
                        c.dimensions(),
                        keptFeatures,
                        params));
            } else {
                cleaned.add(c);
            }
        }
        if (!anyChanged) {
            return plan;
        }
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                cleaned,
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap(),
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                plan.planMeta(),
                plan.designSources()
        );
    }

    /**
     * Server-owned Phase-1 roof material replace: AI message supplies material intent only.
     * Always returns true once the intent matches (success preview or explicit refusal).
     */
    private static boolean tryServerOwnedRoofMaterialReplace(
            ServerPlayerEntity player,
            FormaRequest req,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        hbAlive.set(false);
        var memoryManager = com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
        if (memoryManager == null) {
            LlmPlanRoutingMetrics.recordError(player, req, "building_edit_memory_unavailable");
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "建筑记忆系统未就绪，无法安全执行屋顶材质替换。"));
            return true;
        }

        String material = com.formacraft.server.edit.RoofMaterialReplaceIntent
                .materialPhrase(req.getUserMessage())
                .orElse(req.getUserMessage());
        var dim = serverWorld.getRegistryKey().getValue();
        BlockPos lookAt = origin != null ? origin : player.getBlockPos();
        var query = new com.formacraft.server.edit.BuildingTargetResolver.Query(
                dim,
                null,
                req.getSelectionMin(),
                req.getSelectionMax(),
                lookAt,
                32.0
        );

        var service = new com.formacraft.server.edit.BuildingEditService(memoryManager);
        var target = service.targets().resolve(query);
        if (!target.ok()) {
            LlmPlanRoutingMetrics.recordError(player, req, "building_edit_target_" + target.failure());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    com.formacraft.server.edit.BuildingTargetResolver.refusalMessage(target)
                            .orElse("无法确定要修改的建筑。")));
            return true;
        }

        var memory = memoryManager.getMemory(target.instance().buildingId().toString());
        var roof = com.formacraft.server.edit.BuildingPartIndex.fromMemory(memory).roof();
        java.util.Map<BlockPos, String> live = new java.util.LinkedHashMap<>();
        for (BlockPos cell : roof.cells()) {
            BlockState state = serverWorld.getBlockState(cell);
            live.put(cell, com.formacraft.common.component.transform.BlockStateStringUtil.fromState(state));
        }

        var prepared = service.prepareRoofMaterialReplace(query, material, live, null);
        if (!prepared.ok()) {
            LlmPlanRoutingMetrics.recordError(player, req, "building_edit_roof_refused");
            String msg = prepared.refusals().isEmpty()
                    ? "屋顶材质替换被拒绝。"
                    : String.join("；", prepared.refusals());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg));
            return true;
        }

        com.formacraft.server.state.PlayerProtectedZoneStorage.syncFromRequest(player, req);
        com.formacraft.server.state.PlayerOutlineStorage.syncFromRequest(player, req);
        boolean restrict = req.getPromptMode() != null
                && "MODIFY_REGION".equalsIgnoreCase(req.getPromptMode().trim());
        var ticket = com.formacraft.server.patch.PatchPreviewService.issuePreview(
                player,
                prepared.building().origin(),
                prepared.patches(),
                com.formacraft.server.state.PlayerProtectedZoneStorage.get(player),
                com.formacraft.server.state.PlayerOutlineStorage.get(player),
                restrict,
                req.getSelectionMin(),
                req.getSelectionMax(),
                true,
                null,
                com.formacraft.server.preview.PreviewEditKind.BUILDING_ROOF_MATERIAL
        );
        if (ticket == null) {
            LlmPlanRoutingMetrics.recordFallback(FallbackReason.EMPTY_OUTPUT, player, req);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "屋顶材质替换预览创建失败。"));
            return true;
        }
        com.formacraft.server.edit.BuildingEditService.bindPendingToTicket(
                ticket.id(), player.getUuid(), ticket.expiresAtMillis(), prepared);
        if (com.formacraft.server.edit.BuildingEditPendingRegistry.get(ticket.id()) == null) {
            com.formacraft.server.patch.PatchPreviewService.cancel(player, ticket.id());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "屋顶编辑上下文绑定失败，已取消预览。"));
            return true;
        }
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                "屋顶材质替换预览已就绪（仅屋顶方块；确认后可 Undo/Redo）"));
        LlmPlanRoutingMetrics.recordSuccess(player, req, prepared.patches().size(), prepared.patches().size());
        return true;
    }

    /**
     * Shared PreviewTicket path for patch.blocks[] and compiled incremental components[].
     */
    private static boolean issueComponentOrBlockPatchPreview(
            ServerPlayerEntity player,
            FormaRequest req,
            BlockPos origin,
            LlmPlan llmPlan,
            AtomicBoolean hbAlive,
            List<com.formacraft.common.patch.BlockPatch> patchList
    ) {
        if (patchList == null || patchList.isEmpty()) {
            LlmPlanRoutingMetrics.recordFallback(FallbackReason.EMPTY_OUTPUT, player, req);
            return false;
        }
        com.formacraft.server.state.PlayerProtectedZoneStorage.syncFromRequest(player, req);
        com.formacraft.server.state.PlayerOutlineStorage.syncFromRequest(player, req);

        BlockPos patchOrigin = resolvePatchOrigin(player, req, origin, llmPlan);
        boolean restrict = req.getPromptMode() != null
                && "MODIFY_REGION".equalsIgnoreCase(req.getPromptMode().trim());
        var ticket = com.formacraft.server.patch.PatchPreviewService.issuePreview(
                player,
                patchOrigin,
                patchList,
                com.formacraft.server.state.PlayerProtectedZoneStorage.get(player),
                com.formacraft.server.state.PlayerOutlineStorage.get(player),
                restrict,
                req.getSelectionMin(),
                req.getSelectionMax()
        );
        if (ticket == null) {
            LlmPlanRoutingMetrics.recordFallback(FallbackReason.EMPTY_OUTPUT, player, req);
            return false;
        }

        hbAlive.set(false);
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                "Patch 预览已就绪：检查改动后点击「应用」（可 Undo/Redo）"));
        LlmPlanRoutingMetrics.recordSuccess(player, req, patchList.size(), patchList.size());
        return true;
    }

    /**
     * Prefer explicit patch.origin; otherwise nearest remembered building origin so
     * standing on a roof does not become the new building bottom-center (Phase 1 A2).
     */
    private static BlockPos resolvePatchOrigin(
            ServerPlayerEntity player,
            FormaRequest req,
            BlockPos requestOrigin,
            LlmPlan llmPlan
    ) {
        BlockPos fromPatch = com.formacraft.common.llm.bridge.BlockPatchBridge.toOrigin(llmPlan.patch());
        if (fromPatch != null && !fromPatch.equals(BlockPos.ORIGIN)) {
            return fromPatch;
        }
        if (llmPlan.anchor() != null) {
            return new BlockPos(llmPlan.anchor().x(), llmPlan.anchor().y(), llmPlan.anchor().z());
        }
        try {
            var memory = com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
            if (memory != null && player.getEntityWorld() instanceof ServerWorld sw) {
                var nearest = memory.findNearest(
                        sw.getRegistryKey().getValue(),
                        requestOrigin != null ? requestOrigin : player.getBlockPos(),
                        96.0);
                if (nearest != null && nearest.getBounds() != null) {
                    BlockPos anchored = nearest.getBounds().getAnchor("origin");
                    if (anchored != null) return anchored;
                    BlockPos min = nearest.getBounds().getMinPos();
                    if (min != null) return min;
                }
            }
        } catch (Throwable t) {
            LOG.debug("resolvePatchOrigin memory lookup failed", t);
        }
        return requestOrigin != null ? requestOrigin : BlockPos.ORIGIN;
    }

    private static com.formacraft.common.llm.dto.CapabilityGap resolvePlanGap(LlmPlan plan) {
        if (plan.capabilityGap() != null) {
            return plan.capabilityGap();
        }
        String msg = plan.error();
        if (msg == null || msg.isBlank()) {
            msg = "Requested geometry is outside current MetaAssembly capabilities.";
        }
        return new com.formacraft.common.llm.dto.CapabilityGap(
                "E_CAPABILITY_GAP",
                msg,
                "plan",
                List.of("Use ASSEMBLY preset/graph from ai-assembly-schema.json.", "Or simplify to MASS_* + ROOF.")
        );
    }

    private static void reportCapabilityGap(
            ServerPlayerEntity player,
            FormaRequest req,
            AtomicBoolean hbAlive,
            com.formacraft.common.llm.dto.CapabilityGap gap
    ) {
        hbAlive.set(false);
        LlmPlanRoutingMetrics.recordError(player, req, gap.code() != null ? gap.code() : "capability_gap");
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                CapabilityGapMessages.formatPlayerMessage(gap)
        ));
    }
}

