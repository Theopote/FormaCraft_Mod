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
            FacadeRhythmProfile rhythmProfile = FacadeRhythmProfile.defaultProfile();
            Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets = new HashMap<>();

            for (FloorLayer layer : layeredSockets.keySet()) {
                Map<Direction, List<Socket>> directionSockets = new HashMap<>();
                List<Socket> layerSockets = layeredSockets.get(layer);

                // 按朝向分组
                for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    List<Socket> facingSockets = layerSockets.stream()
                            .filter(s -> s.normal == facing)
                            .toList();

                    if (!facingSockets.isEmpty()) {
                        List<Socket> processed = FacadeRhythmProcessor.processRhythm(
                                new ArrayList<>(facingSockets),
                                layer,
                                facing,
                                rhythmProfile,
                                planSkeleton
                        );
                        directionSockets.put(facing, processed);
                    }
                }

                rhythmProcessedSockets.put(layer, directionSockets);
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
