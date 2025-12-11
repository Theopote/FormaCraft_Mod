package com.formacraft.server.city;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.generator.BridgeGenerator;
import com.formacraft.server.generator.StructureGenerator;
import com.formacraft.server.generator.StructureGeneratorFactory;
import com.formacraft.server.generator.path.PathGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市生成器
 * 将 CitySpec 转换为实际的方块结构
 */
public class CityBuilder {
    
    private final PathGenerator pathGenerator = new PathGenerator();
    private final BridgeGenerator bridgeGenerator = new BridgeGenerator();

    /**
     * 生成整个城市
     * @param city 城市规格
     * @param origin 城市中心点
     * @param world 服务器世界
     * @return 生成的完整城市结构
     */
    public GeneratedStructure generate(CitySpec city, BlockPos origin, ServerWorld world) {
        if (city == null) {
            return new GeneratedStructure(null, origin, "Empty City", new ArrayList<>());
        }

        List<PlannedBlock> merged = new ArrayList<>();

        // 1. 生成建筑
        if (city.getStructures() != null) {
            for (CitySpec.StructurePlan sp : city.getStructures()) {
                if (sp == null || sp.getSpec() == null || sp.getOffset() == null) {
                    continue;
                }

                // 获取对应的生成器
                StructureGenerator generator = StructureGeneratorFactory.getGenerator(sp.getSpec());
                if (generator == null) {
                    continue;
                }

                // 计算建筑的绝对坐标
                CitySpec.Point offset = sp.getOffset();
                BlockPos buildingOrigin = origin.add(offset.x, offset.y, offset.z);

                // 生成建筑
                GeneratedStructure building = generator.generate(sp.getSpec(), buildingOrigin, world);
                
                // 为每个建筑应用地形整形
                BlockPos buildingMin = buildingOrigin;
                BlockPos buildingMax = buildingOrigin;
                
                if (sp.getSpec().getFootprint() != null) {
                    int width = sp.getSpec().getFootprint().getWidth() > 0 ? 
                        sp.getSpec().getFootprint().getWidth() : 8;
                    int depth = sp.getSpec().getFootprint().getDepth() > 0 ? 
                        sp.getSpec().getFootprint().getDepth() : 6;
                    int height = sp.getSpec().getHeight() > 0 ? sp.getSpec().getHeight() : 4;
                    
                    if ("circle".equals(sp.getSpec().getFootprint().getShape()) && 
                        sp.getSpec().getFootprint().getRadius() > 0) {
                        int radius = sp.getSpec().getFootprint().getRadius();
                        buildingMin = buildingOrigin.add(-radius, 0, -radius);
                        buildingMax = buildingOrigin.add(radius, height, radius);
                    } else {
                        buildingMax = buildingOrigin.add(width, height, depth);
                    }
                } else {
                    buildingMax = buildingOrigin.add(8, 4, 6);
                }
                
                // 应用地形整形
                net.minecraft.block.BlockState fillMaterial = net.minecraft.block.Blocks.DIRT.getDefaultState();
                if (sp.getSpec().getMaterials() != null && 
                    sp.getSpec().getMaterials().getFoundation() != null) {
                    try {
                        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(
                            sp.getSpec().getMaterials().getFoundation());
                        net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
                        if (block != null) {
                            fillMaterial = block.getDefaultState();
                        }
                    } catch (Exception e) {
                        // 使用默认材质
                    }
                }
                
                building = com.formacraft.server.terrain.TerrainShaper.preprocessStructure(
                    world, building, buildingMin, buildingMax, fillMaterial);
                
                merged.addAll(building.getBlocks());
            }
        }

        // 2. 生成道路
        if (city.getRoads() != null) {
            for (PathSpec road : city.getRoads()) {
                if (road == null) {
                    continue;
                }
                GeneratedStructure roadStructure = pathGenerator.generate(road, origin, world);
                merged.addAll(roadStructure.getBlocks());
            }
        }

        // 3. 生成桥梁
        if (city.getBridges() != null) {
            for (CitySpec.BridgePlan bp : city.getBridges()) {
                if (bp == null || bp.getFrom() == null || bp.getTo() == null) {
                    continue;
                }

                // 将 BridgePlan 转换为 BuildingSpec
                BuildingSpec bridgeSpec = createBridgeSpecFromPlan(bp, city);

                // 计算桥梁的起点
                CitySpec.Point from = bp.getFrom();
                BlockPos bridgeOrigin = origin.add(from.x, from.y, from.z);

                // 生成桥梁
                GeneratedStructure bridge = bridgeGenerator.generate(bridgeSpec, bridgeOrigin, world);
                merged.addAll(bridge.getBlocks());
            }
        }

        String description = String.format("City: %s (%d structures, %d roads, %d bridges)",
                city.getCityName() != null ? city.getCityName() : "Unnamed",
                city.getStructures() != null ? city.getStructures().size() : 0,
                city.getRoads() != null ? city.getRoads().size() : 0,
                city.getBridges() != null ? city.getBridges().size() : 0);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                merged
        );
    }

    /**
     * 从 BridgePlan 创建 BuildingSpec
     */
    private BuildingSpec createBridgeSpecFromPlan(CitySpec.BridgePlan plan, CitySpec city) {
        BuildingSpec spec = new BuildingSpec();
        spec.setType(BuildingType.BRIDGE);
        
        // 设置风格（从城市风格继承）
        if (city.getStyle() != null) {
            try {
                spec.setStyle(com.formacraft.common.model.build.BuildingStyle.valueOf(city.getStyle().toUpperCase()));
            } catch (IllegalArgumentException e) {
                spec.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
            }
        } else {
            spec.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
        }

        // 计算桥梁长度
        CitySpec.Point from = plan.getFrom();
        CitySpec.Point to = plan.getTo();
        int dx = Math.abs(to.x - from.x);
        int dz = Math.abs(to.z - from.z);
        int length = (int) Math.sqrt(dx * dx + dz * dz);

        // 设置 Footprint
        com.formacraft.common.model.build.Footprint footprint = new com.formacraft.common.model.build.Footprint();
        footprint.setShape("rectangle");
        footprint.setWidth(5);  // 默认桥梁宽度
        footprint.setDepth(length);
        spec.setFootprint(footprint);

        // 设置高度
        spec.setHeight(5);  // 默认桥梁高度

        // 设置材质
        com.formacraft.common.model.build.Materials materials = new com.formacraft.common.model.build.Materials();
        materials.setWall("minecraft:stone_bricks");
        materials.setRoof("minecraft:oak_planks");
        materials.setFloor("minecraft:oak_planks");
        materials.setWindow("minecraft:glass_pane");
        spec.setMaterials(materials);

        // 设置特性
        com.formacraft.common.model.build.Features features = new com.formacraft.common.model.build.Features();
        features.setHasWindows(false);
        features.setHasStairs(false);
        spec.setFeatures(features);

        // 设置样式选项（桥梁类型）
        com.formacraft.common.model.build.StyleOptions styleOptions = new com.formacraft.common.model.build.StyleOptions();
        styleOptions.setBridgeType(plan.getBridgeType() != null ? plan.getBridgeType() : "flat");
        spec.setStyleOptions(styleOptions);

        return spec;
    }
}

