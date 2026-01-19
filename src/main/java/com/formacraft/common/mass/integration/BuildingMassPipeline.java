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
            // 使用预设选择系统：AI 从预设库中选择，而不是生成
            // v1 简化：使用默认建筑特征（未来：从 PlanSkeleton 或 LlmPlan 提取）
            int floorCount = layers.size();
            com.formacraft.common.mass.rhythm.FacadeRhythmPresetSelector.PresetSelection presetSelection = 
                    com.formacraft.common.mass.rhythm.FacadeRhythmPresetSelector.selectPreset(
                            new com.formacraft.common.mass.rhythm.FacadeRhythmPresetSelector.BuildingCharacteristics(
                                    "residential",  // 默认建筑类型
                                    "modern",       // 默认风格
                                    floorCount,
                                    List.of(),      // 默认体量
                                    "none",         // 默认对称性
                                    "simple"        // 默认表达
                            )
                    );
            
            // 为每个朝向分别处理多层节奏
            // 注意：可以根据 presetSelection.facadeOverrides() 为不同立面选择不同预设
            Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets = new HashMap<>();
            Direction[] facings = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            String[] facadeNames = new String[]{"NORTH", "SOUTH", "EAST", "WEST"}; // 对应 facings
            
            for (int i = 0; i < facings.length; i++) {
                Direction facing = facings[i];
                String facadeName = facadeNames[i];
                
                // 为这个立面解析预设（如果有覆盖则使用覆盖，否则使用主要预设）
                FacadeRhythmProfile facadeProfile = com.formacraft.common.mass.rhythm.FacadeRhythmPresetResolver.resolve(
                        presetSelection,
                        composition,
                        floorCount,
                        facadeName
                );
                
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
                                    facadeProfile,  // 使用立面特定的 Profile
                                    planSkeleton
                            );
                    
                    // 合并结果到最终结构
                    for (Map.Entry<FloorLayer, List<Socket>> entry : processedForFacing.entrySet()) {
                        rhythmProcessedSockets.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                                .put(facing, entry.getValue());
                    }
                }
            }

            // Step 8: 应用立面层次处理（构件分级系统）
            // 执行顺序（不可打乱）：
            // 1️⃣ 计算 FacadeBay（柱距）✅
            // 2️⃣ 在 Bay 内放 Window Socket ✅（已完成）
            // 3️⃣ 给部分 Window 加 WindowSurround ✅
            // 4️⃣ 在 FloorLayer 边界加 HorizontalBand ✅
            // 5️⃣ 最后（可选）加 Decoration（未来）
            Map<Direction, com.formacraft.common.mass.facade.FacadeHierarchyProcessor.FacadeHierarchyResult> facadeHierarchies = new HashMap<>();
            com.formacraft.common.mass.facade.FacadeDetailLevel detailLevel = 
                    com.formacraft.common.mass.facade.FacadeDetailLevel.MEDIUM; // v1 简化：使用默认级别
            
            for (Direction facing : facings) {
                // 收集这个朝向的所有 Socket（跨所有楼层）
                List<Socket> facingSockets = new ArrayList<>();
                for (Map.Entry<FloorLayer, Map<Direction, List<Socket>>> layerEntry : rhythmProcessedSockets.entrySet()) {
                    Map<Direction, List<Socket>> directionMap = layerEntry.getValue();
                    if (directionMap.containsKey(facing)) {
                        facingSockets.addAll(directionMap.get(facing));
                    }
                }
                
                if (!facingSockets.isEmpty()) {
                    // 为这个立面获取对应的 Profile
                    String facadeName = switch (facing) {
                        case NORTH -> "NORTH";
                        case SOUTH -> "SOUTH";
                        case EAST -> "EAST";
                        case WEST -> "WEST";
                        default -> "FRONT";
                    };
                    FacadeRhythmProfile facadeProfile = com.formacraft.common.mass.rhythm.FacadeRhythmPresetResolver.resolve(
                            presetSelection,
                            composition,
                            floorCount,
                            facadeName
                    );
                    
                    // 处理立面层次
                    com.formacraft.common.mass.facade.FacadeHierarchyProcessor.FacadeHierarchyResult hierarchyResult = 
                            com.formacraft.common.mass.facade.FacadeHierarchyProcessor.processHierarchy(
                                    facingSockets,
                                    facing,
                                    layers,
                                    facadeProfile,
                                    detailLevel
                            );
                    facadeHierarchies.put(facing, hierarchyResult);
                }
            }

            return new BuildingMassPipelineResult(
                    composition,
                    mergedSkeletons,
                    layers,
                    layeredSockets,
                    rhythmProcessedSockets,
                    facadeHierarchies
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
        public final Map<Direction, com.formacraft.common.mass.facade.FacadeHierarchyProcessor.FacadeHierarchyResult> facadeHierarchies;

        public BuildingMassPipelineResult(
                BuildingMassComposition composition,
                List<MassDerivedSkeleton> skeletons,
                List<FloorLayer> layers,
                Map<FloorLayer, List<Socket>> layeredSockets,
                Map<FloorLayer, Map<Direction, List<Socket>>> rhythmProcessedSockets,
                Map<Direction, com.formacraft.common.mass.facade.FacadeHierarchyProcessor.FacadeHierarchyResult> facadeHierarchies
        ) {
            this.composition = composition;
            this.skeletons = skeletons != null ? List.copyOf(skeletons) : List.of();
            this.layers = layers != null ? List.copyOf(layers) : List.of();
            this.layeredSockets = layeredSockets != null ? new HashMap<>(layeredSockets) : new HashMap<>();
            this.rhythmProcessedSockets = rhythmProcessedSockets != null ? new HashMap<>(rhythmProcessedSockets) : new HashMap<>();
            this.facadeHierarchies = facadeHierarchies != null ? new HashMap<>(facadeHierarchies) : new HashMap<>();
        }

        public static BuildingMassPipelineResult empty() {
            return new BuildingMassPipelineResult(
                    null,
                    List.of(),
                    List.of(),
                    Map.of(),
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
