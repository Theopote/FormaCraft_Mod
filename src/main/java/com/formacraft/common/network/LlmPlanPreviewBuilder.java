package com.formacraft.common.network;

import com.formacraft.FormacraftMod;
import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.common.compiler.ComponentPlanCompiler;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.generation.routing.BuildingSpecRoutingPolicy;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics;
import com.formacraft.common.network.metrics.LlmPlanRoutingMetrics.FallbackReason;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.LlmPlanTerrainBounds.Bounds;
import com.formacraft.server.build.BuildConstraintClipper;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.foundation.FoundationPlanner;
import com.formacraft.server.foundation.FoundationType;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.terrain.TerrainAdaptationEngine;
import com.formacraft.server.terrain.TerrainFit;

import java.util.ArrayList;
import java.util.List;
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

    private LlmPlanPreviewBuilder() {}

    /**
     * @return true if this was an LlmPlan request (handled or error sent to player); false if not LlmPlan
     */
    public static boolean tryBuildPreview(
            ServerPlayerEntity player,
            FormaRequest req,
            BuildingSpec spec,
            BlockPos origin,
            ServerWorld serverWorld,
            AtomicBoolean hbAlive
    ) {
        boolean isLlmPlan = spec.getExtra() != null &&
                Boolean.TRUE.equals(spec.getExtra().get("isLlmPlan"));

        if (!isLlmPlan) {
            return false;
        }

        LlmPlanRoutingMetrics.recordTaggedAttempt(player, req);

        if (BuildingSpecRoutingPolicy.shouldSkipLlmPlanPreview(spec, req)) {
            LlmPlanRoutingMetrics.recordFallback(FallbackReason.ROUTING_POLICY, player, req);
            return false;
        }

        try {
            String llmPlanJson = (String) spec.getExtra().get("llmPlanJson");
            if (llmPlanJson == null) {
                LlmPlanRoutingMetrics.recordFallback(FallbackReason.MISSING_LLM_PLAN_JSON, player, req);
                return false;
            }

            LlmPlan llmPlan = LlmPlanParser.parseAndValidate(llmPlanJson);

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

            if (llmPlan.usesPlanProgramMode()) {
                // 使用 PlanProgram → Skeleton 编译路径
                if (llmPlan.planSkeleton() != null) {
                    patches = com.formacraft.common.compiler.PlanProgramCompiler.compileFromPlanSkeleton(
                            llmPlan.planSkeleton(),
                            planOrigin,
                            serverWorld,
                            styleProfileId
                    );
                } else if (llmPlan.planProgram() != null) {
                    patches = com.formacraft.common.compiler.PlanProgramCompiler.compile(
                            llmPlan.planProgram(),
                            planOrigin,
                            serverWorld,
                            styleProfileId
                    );
                } else {
                    // 回退机制：如果 PlanProgram 模式数据不完整，回退到 ComponentPlanCompiler
                    FormacraftMod.LOGGER.warn(
                            "LlmPlan usesPlanProgramMode but no planSkeleton or planProgram found, " +
                                    "falling back to ComponentPlanCompiler"
                    );

                    // 创建地形采样器并回退到 ComponentPlanCompiler
                    com.formacraft.common.terrain.TerrainStrategySampler terrainSampler =
                            new com.formacraft.common.terrain.TerrainStrategySampler();
                    patches = ComponentPlanCompiler.compile(
                            llmPlan,
                            planOrigin,
                            serverWorld,
                            terrainSampler,
                            false
                    );
                }

                // 可选：如果启用 BuildingMass 路径
                if (com.formacraft.common.mass.integration.BuildingMassSystemIntegrator.shouldUseBuildingMassPath(llmPlan)) {
                    List<com.formacraft.common.patch.BlockPatch> buildingMassPatches = com.formacraft.common.mass.integration.BuildingMassSystemIntegrator.compileWithBuildingMass(
                            llmPlan,
                            planOrigin,
                            serverWorld
                    );
                    // v1: BuildingMass 路径暂时作为补充，未来可以完全替代
                    if (!buildingMassPatches.isEmpty()) {
                        FormacraftMod.LOGGER.info("BuildingMass path generated {} patches (supplementary)", buildingMassPatches.size());
                        // 合并 BuildingMass 生成的 patches
                        List<com.formacraft.common.patch.BlockPatch> mergedPatches = new java.util.ArrayList<>(patches.size() + buildingMassPatches.size());
                        mergedPatches.addAll(patches);
                        mergedPatches.addAll(buildingMassPatches);
                        patches = mergedPatches;
                    }
                }
            } else {
                // 使用传统 components[] 编译路径
                // 创建地形采样器
                com.formacraft.common.terrain.TerrainStrategySampler terrainSampler =
                        new com.formacraft.common.terrain.TerrainStrategySampler();

                // LlmPlan 的地形处理在后续流程统一执行，避免组件级别逐列抬升导致错位
                patches = ComponentPlanCompiler.compile(
                        llmPlan,
                        planOrigin,
                        serverWorld,
                        terrainSampler,
                        false  // 关闭逐列地形适应
                );
            }

            // 将 BlockPatch 转换为 PlannedBlock
            List<PlannedBlock> plannedBlocks = new ArrayList<>();
            int invalidBlockCount = 0;
            for (com.formacraft.common.patch.BlockPatch patch : patches) {
                BlockPos worldPos = planOrigin.add(patch.dx(), patch.dy(), patch.dz());
                String blockId = patch.targetBlock();
                if (blockId != null && !blockId.isEmpty()) {
                    try {
                        String baseId = blockId;
                        int propsStart = baseId.indexOf('[');
                        if (propsStart > 0) {
                            baseId = baseId.substring(0, propsStart);
                        }
                        net.minecraft.util.Identifier blockIdentifier = net.minecraft.util.Identifier.tryParse(baseId);
                        if (blockIdentifier == null) {
                            invalidBlockCount++;
                            if (invalidBlockCount <= 5) {
                                FormacraftMod.LOGGER.warn("Failed to parse block ID (invalid format): {}", blockId);
                            }
                            continue;
                        }
                        net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(blockIdentifier);
                        net.minecraft.block.BlockState state = block.getDefaultState();
                        plannedBlocks.add(new PlannedBlock(worldPos, state));
                    } catch (Exception e) {
                        invalidBlockCount++;
                        if (invalidBlockCount <= 5) {
                            FormacraftMod.LOGGER.warn("Failed to parse block ID: {} at position ({}, {}, {})",
                                    blockId, worldPos.getX(), worldPos.getY(), worldPos.getZ(), e);
                        }
                    }
                }
            }
            if (invalidBlockCount > 0) {
                FormacraftMod.LOGGER.warn("LlmPlan: {} invalid blocks out of {} total patches", invalidBlockCount, patches.size());
            }

            if (plannedBlocks.isEmpty()) {
                LlmPlanRoutingMetrics.recordFallback(FallbackReason.EMPTY_OUTPUT, player, req);
                return false;
            }

            FormacraftMod.LOGGER.info("LlmPlan: converted {} patches to {} planned blocks (origin: {})",
                    patches.size(), plannedBlocks.size(), planOrigin);

            // ========== 地形平整：在生成建筑之后平整地坪 ==========
            boolean terrainPadApplied = false;
            boolean wantsStiltFoundation = LlmPlanTerrainBounds.wantsStiltFoundation(req);
            // 获取地形策略
            GlobalConstraints.TerrainStrategy terrainStrategy =
                    (llmPlan.globalConstraints() != null && llmPlan.globalConstraints().terrainStrategy() != null)
                            ? llmPlan.globalConstraints().terrainStrategy()
                            : GlobalConstraints.TerrainStrategy.ADAPTIVE;

            // 如果策略是 ADAPTIVE 或 FLATTEN，进行地坪平整
            boolean b = llmPlan.styleAttributes() != null && llmPlan.styleAttributes().floorMaterial() != null;
            if (!plannedBlocks.isEmpty()
                    && (terrainStrategy == GlobalConstraints.TerrainStrategy.ADAPTIVE ||
                        terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN)
                    && !wantsStiltFoundation) {
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
                            } catch (Exception ignored) {}
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
                    List<PlannedBlock> terrainPadBlocks = BuildConstraintContext.withRequest(req, () -> {
                        List<PlannedBlock> out = new ArrayList<>();
                        // 计算建筑高度范围，确保清理整个建筑占用空间
                        int buildingHeight = Math.max(1, finalMaxY - finalMinY + 1);
                        int padDepth = terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN ? 4 : 1;
                        int clearHeight = terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN
                                ? buildingHeight + 8
                                : Math.min(6, buildingHeight + 2);

                        if (terrainStrategy == GlobalConstraints.TerrainStrategy.FLATTEN) {
                            // FLATTEN 策略：使用 balancedPad 进行较大范围平整
                            out.addAll(TerrainFit.balancedPad(
                                    serverWorld, finalCenter, finalWidth, finalDepth, finalTargetY, finalFillMaterial, padDepth, clearHeight, true, true));
                        } else if (analysis.range() > 1) {
                            // ADAPTIVE 策略：如果地形起伏较大（range > 1），使用 adaptivePad 轻微平整
                            out.addAll(TerrainFit.adaptivePad(
                                    serverWorld, finalCenter, finalWidth, finalDepth, finalTargetY, finalFillMaterial, padDepth, clearHeight, true, true));
                        }

                        // 额外清理：确保建筑占用空间内的所有地形方块都被清理
                        // 这是为了防止地形方块顶起建筑方块（关键修复）
                        int clearFromY = finalTargetY + 1;
                        // 使用 maxY 覆盖所有屋顶方块
                        int clearToY = Math.max(finalMaxY + 5, finalMinY + buildingHeight + 5); // 清理到建筑最高点 + 缓冲
                        for (int x = finalMinX; x <= finalMaxX; x++) {
                            for (int z = finalMinZ; z <= finalMaxZ; z++) {
                                for (int y = clearFromY; y <= clearToY; y++) {
                                    BlockPos clearPos = new BlockPos(x, y, z);
                                    if (!com.formacraft.server.build.BuildConstraintContext.allow(clearPos)) continue;
                                    net.minecraft.block.BlockState current = serverWorld.getBlockState(clearPos);
                                    // 清理所有非空气方块（包括地形方块）
                                    if (!current.isAir()) {
                                        out.add(new PlannedBlock(clearPos, Blocks.AIR.getDefaultState()));
                                    }
                                }
                            }
                        }
                        return out;
                    });

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
                FoundationType foundationType = FoundationPlanner.chooseType(analysis.range(), blockHeight, spec.getExtra());
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
                        } catch (Exception ignored) {}
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
                        && (finalFd.type() == FoundationType.FLAT_PAD || finalFd.type() == FoundationType.STEPPED);
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

            // 创建 GeneratedStructure
            com.formacraft.server.build.GeneratedStructure generated =
                    new com.formacraft.server.build.GeneratedStructure(
                            player.getUuid(),
                            planOrigin,
                            "LlmPlan generated structure",
                            plannedBlocks
                    );

            // 应用约束裁剪
            com.formacraft.server.build.GeneratedStructure structure =
                    new com.formacraft.server.build.GeneratedStructure(
                            player.getUuid(),
                            planOrigin,
                            generated.getDescription(),
                            BuildConstraintClipper.clipPlannedBlocks(plannedBlocks, req)
                    );

            // 存储结构用于预览
            PreviewStorage.storeStructure(player, structure);

            // 自动发送预览
            List<OutlineBlock> outline =
                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
            FormaCraftNetworking.sendPreviewOutline(player, outline);

            PreviewStorage.setPreview(player, true);

            player.sendMessage(net.minecraft.text.Text.translatable(
                            "formacraft.preview.ready.building"),
                    false);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "LlmPlan preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
            ));
            hbAlive.set(false);

            // 也发送 BuildingSpec 给客户端（用于 UI 显示）
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildSpecPayload(spec));

            LlmPlanRoutingMetrics.recordSuccess(player, req, patches.size(), plannedBlocks.size());
            return true;
        } catch (PlanParseException e) {
            FormacraftMod.LOGGER.error("Failed to parse LlmPlan from extra", e);
            LlmPlanRoutingMetrics.recordError(player, req, "parse:" + e.getClass().getSimpleName());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "Failed to parse LlmPlan: " + e.getMessage()
            ));
            return true;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to process LlmPlan", e);
            LlmPlanRoutingMetrics.recordError(player, req, "process:" + e.getClass().getSimpleName());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "Failed to process LlmPlan: " + e.getMessage()
            ));
            return true;
        }
    }
}

