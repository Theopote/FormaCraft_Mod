package com.formacraft.common.generator.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.generator.StructureGenerator;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 {@code server.generator.StructureGenerator} 适配为构件级 {@link BlockPatch} 输出。
 * <p>
 * Phase 2：由 {@link UnifiedGeneratorRouter} 在受控条件下调用（显式 landmark / 未注册整栋类型）。
 */
public class StructureGeneratorAdaptor implements ComponentGenerator {

    private final StructureGenerator delegate;
    private final BuildingType buildingType;

    public StructureGeneratorAdaptor(StructureGenerator delegate, BuildingType buildingType) {
        this.delegate = delegate;
        this.buildingType = buildingType;
    }

    /**
     * 受控回退入口：根据语义构件构建 {@link BuildingSpec} 并路由到整栋生成器。
     */
    public static List<BlockPatch> tryGenerate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null) {
            return List.of();
        }

        BuildingSpec spec = buildSpecFromSemantic(semantic);
        if (spec == null) {
            return List.of();
        }

        StructureGenerator generator = GenerationHub.routeStructure(spec);
        if (generator == null) {
            return List.of();
        }

        StructureGeneratorAdaptor adaptor = new StructureGeneratorAdaptor(generator, spec.getType());
        return adaptor.generate(semantic, world);
    }

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        FormacraftMod.LOGGER.warn("StructureGeneratorAdaptor: generate() called without ServerWorld, returning empty list");
        return new ArrayList<>();
    }

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

        BuildingSpec spec = buildSpecFromSemantic(semantic);
        BlockPos worldAnchor = new BlockPos(slot.anchor().x(), slot.anchor().y(), slot.anchor().z());

        try {
            GeneratedStructure structure = delegate.generate(spec, worldAnchor, world);
            if (structure == null || structure.getBlocks() == null) {
                return new ArrayList<>();
            }

            List<BlockPatch> patches = new ArrayList<>();
            for (var block : structure.getBlocks()) {
                BlockPos worldPos = block.getPos();
                BlockPos relativePos = worldPos.subtract(worldAnchor);

                String blockId = "minecraft:stone";
                try {
                    var registryKeyOpt = Registries.BLOCK.getKey(block.getTargetState().getBlock());
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

            FormacraftMod.LOGGER.debug("StructureGeneratorAdaptor: generated {} patches (type={})",
                    patches.size(), spec.getType());
            return patches;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("StructureGeneratorAdaptor: error generating structure", e);
            return new ArrayList<>();
        }
    }

    private static BuildingSpec buildSpecFromSemantic(SemanticComponent semantic) {
        Component c = semantic.source();
        Slot slot = semantic.slot();
        if (c == null) {
            return null;
        }

        String componentType = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
        BuildingType type = mapComponentTypeToBuildingType(componentType);
        BuildingSpec spec = createBuildingSpecFromComponent(c, slot, type);

        if (semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            spec.getExtra().putIfAbsent("styleProfileId", semantic.styleProfile());
        }

        BuildingGenome genome = semantic.genome();
        if (genome != null) {
            spec.getExtra().put("genome", JsonUtil.toJson(genome));
        }

        applyRoutingHints(c, spec);
        return spec;
    }

    private static void applyRoutingHints(Component c, BuildingSpec spec) {
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) {
            extra = new HashMap<>();
            spec.setExtra(extra);
        }

        Map<String, Object> params = c.params();
        if (params != null) {
            copyIfPresent(params, extra, "landmark");
            copyIfPresent(params, extra, "template");
            copyIfPresent(params, extra, "blueprint");
            copyIfPresent(params, extra, "assembly");
            copyIfPresent(params, extra, "styleProfileId");
        }

        List<String> features = c.features();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:")) {
                    extra.put("landmark", feature.substring("landmark:".length()).trim());
                } else if (lower.startsWith("structure_generator:")) {
                    String value = feature.substring("structure_generator:".length()).trim();
                    if (!value.isEmpty()) {
                        extra.put("landmark", value);
                    } else {
                        extra.put("useStructureGenerator", true);
                    }
                }
            }
        }
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private static BuildingType mapComponentTypeToBuildingType(String componentType) {
        return switch (componentType) {
            case "TOWER", "TOWER_BASE", "TOWER_MID", "TOWER_TOP" -> BuildingType.TOWER;
            case "HOUSE", "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING" -> BuildingType.HOUSE;
            case "WALL", "WALL_SEGMENT", "FENCE_OR_WALL" -> BuildingType.WALL;
            case "BRIDGE", "CONNECTOR", "BRIDGE_CONNECTOR" -> BuildingType.BRIDGE;
            case "CASTLE", "KEEP" -> BuildingType.CASTLE;
            default -> BuildingType.CUSTOM;
        };
    }

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
        spec.setExtra(new HashMap<>());
        return spec;
    }

    private static BuildingSpec createBuildingSpecFromComponent(Component c, Slot slot, BuildingType type) {
        BuildingSpec spec = createBuildingSpec(type);

        if (c.dimensions() != null) {
            spec.setHeight(c.dimensions().height());
            spec.getFootprint().setWidth(c.dimensions().width());
            spec.getFootprint().setDepth(c.dimensions().depth());
        }

        return spec;
    }
}
