package com.formacraft.common.generator.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.generator.StructureGenerator;
import com.formacraft.server.generator.StructureGeneratorFactory;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * StructureGeneratorAdaptor（传统生成器适配器）
 * 
 * 将传统系统的 StructureGenerator 适配为新系统的 ComponentGenerator
 * 让新系统可以调用传统系统的完整功能
 * 
 * 使用场景：
 * - 当新系统的生成器功能不够完整时
 * - 当需要生成复杂建筑（如房屋、城堡等）时
 * - 当检测到特定建筑类型（如土楼、埃菲尔铁塔）时
 */
public class StructureGeneratorAdaptor implements ComponentGenerator {

    private final StructureGenerator delegate;
    private final BuildingType buildingType;

    /**
     * 创建适配器
     * 
     * @param delegate 传统系统的生成器
     * @param buildingType 建筑类型（用于创建 BuildingSpec）
     */
    public StructureGeneratorAdaptor(StructureGenerator delegate, BuildingType buildingType) {
        this.delegate = delegate;
        this.buildingType = buildingType;
    }

    /**
     * 根据 componentType 自动创建适配器
     * 
     * @param componentType 组件类型（如 "MASS_MAIN", "TOWER", "HOUSE" 等）
     * @return 适配器实例，如果无法创建则返回 null
     */
    public static StructureGeneratorAdaptor createFor(String componentType) {
        if (componentType == null) return null;

        String type = componentType.toUpperCase();
        
        // 映射 componentType 到 BuildingType
        BuildingType buildingType = mapComponentTypeToBuildingType(type);
        if (buildingType == null) return null;

        // 创建 BuildingSpec（简化版，只设置必要的字段）
        BuildingSpec spec = createBuildingSpec(buildingType);
        
        // 使用 StructureGeneratorFactory 获取生成器
        StructureGenerator generator = StructureGeneratorFactory.getGenerator(spec);
        if (generator == null) return null;

        return new StructureGeneratorAdaptor(generator, buildingType);
    }

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        // ComponentGenerator 接口没有提供 ServerWorld
        // 这个方法不会被直接调用，应该使用 generate(SemanticComponent, ServerWorld)
        FormacraftMod.LOGGER.warn("StructureGeneratorAdaptor: generate() called without ServerWorld, returning empty list");
        return new ArrayList<>();
    }

    /**
     * 生成（需要 ServerWorld）
     */
    public List<BlockPatch> generate(SemanticComponent semantic, ServerWorld world) {
        if (delegate == null || world == null) {
            return new ArrayList<>();
        }

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return new ArrayList<>();
        }

        Slot slot = semantic.slot();
        if (slot == null || slot.anchor() == null) {
            return new ArrayList<>();
        }

        // 创建 BuildingSpec
        BuildingSpec spec = createBuildingSpecFromComponent(c, slot, buildingType);
        
        // 计算世界坐标 anchor
        BlockPos worldAnchor = new BlockPos(
                slot.anchor().x(),
                slot.anchor().y(),
                slot.anchor().z()
        );

        try {
            // 调用传统生成器
            GeneratedStructure structure = delegate.generate(spec, worldAnchor, world);
            if (structure == null || structure.getBlocks() == null) {
                return new ArrayList<>();
            }

            // 转换为 BlockPatch（相对坐标）
            List<BlockPatch> patches = new ArrayList<>();
            for (var block : structure.getBlocks()) {
                BlockPos worldPos = block.getPos();
                BlockPos relativePos = worldPos.subtract(worldAnchor);
                
                // 获取方块 ID（使用新的 API）
                String blockId = "minecraft:stone"; // 默认值
                try {
                    var registryKeyOpt = net.minecraft.registry.Registries.BLOCK.getKey(block.getTargetState().getBlock());
                    if (registryKeyOpt.isPresent()) {
                        blockId = registryKeyOpt.get().getValue().toString();
                    }
                } catch (Exception e) {
                    FormacraftMod.LOGGER.warn("Failed to get block ID from BlockState", e);
                }
                
                patches.add(new BlockPatch(
                        BlockPatch.PLACE,
                        relativePos.getX(),
                        relativePos.getY(),
                        relativePos.getZ(),
                        blockId
                ));
            }

            FormacraftMod.LOGGER.debug("StructureGeneratorAdaptor: generated {} patches from {}", 
                    patches.size(), buildingType);
            return patches;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("StructureGeneratorAdaptor: error generating structure", e);
            return new ArrayList<>();
        }
    }

    /**
     * 映射 componentType 到 BuildingType
     */
    private static BuildingType mapComponentTypeToBuildingType(String componentType) {
        return switch (componentType) {
            case "TOWER" -> BuildingType.TOWER;
            case "HOUSE", "MASS_MAIN", "MASS_SECONDARY" -> BuildingType.HOUSE;
            case "WALL" -> BuildingType.WALL;
            case "BRIDGE" -> BuildingType.BRIDGE;
            case "CASTLE", "KEEP" -> BuildingType.CASTLE;
            default -> BuildingType.CUSTOM;
        };
    }

    /**
     * 创建 BuildingSpec（简化版）
     */
    private static BuildingSpec createBuildingSpec(BuildingType type) {
        BuildingSpec spec = new BuildingSpec();
        spec.setType(type);
        spec.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
        spec.setHeight(10);
        spec.setFloors(1);
        
        Footprint footprint = new Footprint();
        footprint.setShape("rectangle");
        footprint.setWidth(10);
        footprint.setDepth(10);
        spec.setFootprint(footprint);
        
        spec.setMaterials(new com.formacraft.common.model.build.Materials());
        spec.setFeatures(new com.formacraft.common.model.build.Features());
        
        return spec;
    }

    /**
     * 从 Component 创建 BuildingSpec
     */
    private static BuildingSpec createBuildingSpecFromComponent(
            Component c, Slot slot, BuildingType type) {
        BuildingSpec spec = createBuildingSpec(type);
        
        // 设置尺寸
        if (c.dimensions() != null) {
            spec.setHeight(c.dimensions().height());
            spec.getFootprint().setWidth(c.dimensions().width());
            spec.getFootprint().setDepth(c.dimensions().depth());
        }
        
        // 设置风格
        if (slot.program() != null) {
            // 可以根据 program 设置风格
        }
        
        return spec;
    }
}

