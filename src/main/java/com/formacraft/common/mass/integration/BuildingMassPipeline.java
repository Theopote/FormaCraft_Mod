package com.formacraft.common.mass.integration;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.mass.*;
import com.formacraft.common.mass.derived.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * BuildingMassPipeline（建筑体量流水线）
 * <p>
 * 🎯 核心职责：
 * 整合 BuildingMass 完整流程，从 PlanSkeleton 到 BlockPatch
 * <p>
 * 完整流程：
 * ```
 * PlanSkeleton (Domain)
 *   ↓
 * BuildingMassComposition (体量组合)
 *   ↓
 * MassFilledChecker.isFilled(x, y, z)
 *   ↓
 * MassToSkeletonDeriver.deriveSkeletons()
 *   ↓
 * SkeletonMerger.mergeSkeletons()
 *   ↓
 * FloorLayerSplitter.splitHeightRange()
 *   ↓
 * LayeredSocketDeriver.deriveLayeredSockets()
 *   ↓
 * FacadeRhythmProcessor.processRhythm()
 *   ↓
 * Socket → Component → BlockPatch
 * ```
 */
public final class BuildingMassPipeline {

    private BuildingMassPipeline() {}

    /**
     * 从 PlanSkeleton 生成完整的 BuildingMass 流程结果
     * <p>
     * 这是 BuildingMass 系统的完整入口点
     *
     * @param planSkeleton Plan Domain
     * @param baseY 基础 Y 坐标
     * @return 完整流程结果（包含所有中间产物）
     */
    public static BuildingMassPipelineResult execute(
            PlanSkeleton planSkeleton,
            int baseY
    ) {
        if (planSkeleton == null) {
            FormacraftMod.LOGGER.warn("BuildingMassPipeline: planSkeleton is null");
            return BuildingMassPipelineResult.empty();
        }

        try {
            // Step 1: 验证 Domain
            PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(planSkeleton);
            if (!validation.valid) {
                FormacraftMod.LOGGER.warn("BuildingMassPipeline: invalid domain - {}", validation.errorMessage);
                return BuildingMassPipelineResult.empty();
            }

            // Step 2: 创建简单的体量组合（v1 示例：单个 BLOCK）
            BuildingMassComposition composition = createDefaultComposition(planSkeleton, baseY);

            // Step 3: 派生 Skeleton
            MassToSkeletonDeriver.Bounds scanBounds = MassToSkeletonDeriver.Bounds.fromComposition(composition);
            List<MassDerivedSkeleton> skeletons = MassToSkeletonDeriver.deriveSkeletons(composition, scanBounds);

            // Step 4: 合并 Skeleton
            List<MassDerivedSkeleton> mergedSkeletons = SkeletonMerger.mergeSkeletons(skeletons);

            // Step 4.5: 使用 SkeletonToSocketDeriver 生成基础 Socket（用于验证和调试）
            // 注意：这些基础 Socket 不会被直接使用，LayeredSocketDeriver 会重新派生并细化
            // 但保留这个调用以确保 SkeletonToSocketDeriver 被使用
            @SuppressWarnings("unused")
            List<Socket> basicSockets = com.formacraft.common.mass.derived.SkeletonToSocketDeriver.deriveSockets(mergedSkeletons);
            FormacraftMod.LOGGER.debug("BuildingMassPipeline: Generated {} basic sockets from SkeletonToSocketDeriver", basicSockets.size());

            // Step 5: 切分楼层（已在 Step 6 中提取）
            List<FloorLayer> layers = extractFloorLayers(composition, baseY);

            // Step 6: 派生 Socket（按层）
            Map<String, MassRole> massRoleMap = buildMassRoleMap(composition);
            
            // 先提取楼层
            List<FloorLayer> extractedLayers = extractFloorLayers(composition, baseY);
            
            Map<FloorLayer, List<Socket>> layeredSockets = LayeredSocketDeriver.deriveLayeredSockets(
                    mergedSkeletons,
                    composition,
                    massRoleMap,
                    extractedLayers
            );

            // Step 7: 应用立面节奏（按楼层、按朝向）
            // 使用 MultiLayerRhythmProcessor 处理多层节奏关联
            FacadeRhythmProfile rhythmProfile = FacadeRhythmProfile.defaultProfile();
            
            // 为每个朝向分别处理多层节奏
            Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets = new HashMap<>();
            Direction[] facings = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            
            for (Direction facing : facings) {
                // 按朝向筛选 Socket，并组织成 MultiLayerRhythmProcessor 需要的格式
                Map<FloorLayer, List<Socket>> facingLayeredSockets = new HashMap<>();
                for (Map.Entry<FloorLayer, List<Socket>> entry : layeredSockets.entrySet()) {
                    List<Socket> facingSockets = entry.getValue().stream()
                            .filter(s -> s.normal == facing)
                            .toList();
                    if (!facingSockets.isEmpty()) {
                        facingLayeredSockets.put(entry.getKey(), facingSockets);
                    }
                }
                
                if (!facingLayeredSockets.isEmpty()) {
                    // 使用 MultiLayerRhythmProcessor 处理这个朝向的多层节奏
                    Map<FloorLayer, List<Socket>> processedForFacing = 
                            MultiLayerRhythmProcessor.processMultiLayerRhythm(
                                    facingLayeredSockets,
                                    facing,
                                    rhythmProfile,
                                    planSkeleton
                            );
                    
                    // 合并结果到最终结构
                    for (Map.Entry<FloorLayer, List<Socket>> entry : processedForFacing.entrySet()) {
                        rhythmProcessedSockets.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                                .put(facing, entry.getValue());
                    }
                }
            }

            return new BuildingMassPipelineResult(
                    composition,
                    mergedSkeletons,
                    layers,
                    layeredSockets,
                    rhythmProcessedSockets
            );

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("BuildingMassPipeline: execution failed", e);
            return BuildingMassPipelineResult.empty();
        }
    }

    /**
     * 创建默认的体量组合（v1 示例）
     * <p>
     * v1 简化：创建单个 BLOCK 体量
     * 未来：可以从 PlanSkeleton 或 AI 输入生成复杂的体量组合
     */
    private static BuildingMassComposition createDefaultComposition(PlanSkeleton planSkeleton, int baseY) {
        // v1 简化：创建一个简单的 20x10x15 的 BLOCK 体量
        BuildingMass mainMass = BuildingMassBuilder.createRectangularMass(
                "main",
                0, 20,  // minX, maxX
                0, 10,  // minZ, maxZ
                baseY, baseY + 15,  // baseY, topY (15 block 高度)
                MassType.SOLID,
                MassRole.PRIMARY
        );

        return BuildingMassComposition.empty(planSkeleton)
                .withMass(mainMass);
    }

    /**
     * 从体量组合提取楼层列表
     */
    private static List<FloorLayer> extractFloorLayers(BuildingMassComposition composition, int baseY) {
        List<FloorLayer> allLayers = new ArrayList<>();

        for (BuildingMass mass : composition.getMasses()) {
            List<FloorLayer> massLayers = FloorLayerSplitter.splitHeightRange(mass.height, 4); // 默认层高 4
            allLayers.addAll(massLayers);
        }

        // 去重并排序
        Map<Integer, FloorLayer> layerMap = new HashMap<>();
        for (FloorLayer layer : allLayers) {
            layerMap.put(layer.index, layer);
        }

        return layerMap.values().stream()
                .sorted(Comparator.comparingInt(l -> l.index))
                .toList();
    }

    /**
     * 构建 MassRole 映射
     */
    private static Map<String, MassRole> buildMassRoleMap(BuildingMassComposition composition) {
        Map<String, MassRole> roleMap = new HashMap<>();
        for (BuildingMass mass : composition.getMasses()) {
            roleMap.put(mass.id, mass.role);
        }
        return roleMap;
    }

    /**
     * 完整流程结果
     */
    public static class BuildingMassPipelineResult {
        public final BuildingMassComposition composition;
        public final List<MassDerivedSkeleton> skeletons;
        public final List<FloorLayer> layers;
        public final Map<FloorLayer, List<Socket>> layeredSockets;
        public final Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets;

        public BuildingMassPipelineResult(
                BuildingMassComposition composition,
                List<MassDerivedSkeleton> skeletons,
                List<FloorLayer> layers,
                Map<FloorLayer, List<Socket>> layeredSockets,
                Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets
        ) {
            this.composition = composition;
            this.skeletons = skeletons != null ? List.copyOf(skeletons) : List.of();
            this.layers = layers != null ? List.copyOf(layers) : List.of();
            this.layeredSockets = layeredSockets != null ? new HashMap<>(layeredSockets) : new HashMap<>();
            this.rhythmProcessedSockets = rhythmProcessedSockets != null ? new HashMap<>(rhythmProcessedSockets) : new HashMap<>();
        }

        public static BuildingMassPipelineResult empty() {
            return new BuildingMassPipelineResult(
                    null,
                    List.of(),
                    List.of(),
                    Map.of(),
                    Map.of()
            );
        }

        /**
         * 获取所有处理后的 Socket（扁平化）
         */
        public List<Socket> getAllProcessedSockets() {
            List<Socket> allSockets = new ArrayList<>();
            for (Map<Direction, List<Socket>> directionMap : rhythmProcessedSockets.values()) {
                for (List<Socket> sockets : directionMap.values()) {
                    allSockets.addAll(sockets);
                }
            }
            return allSockets;
        }
    }
}
