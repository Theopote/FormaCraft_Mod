package com.formacraft.server.generation.component.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.typology.StructuralTypologyRegistry;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.generation.structure.StructureGenerator;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 {@code server.generation.structure.StructureGenerator} 适配为构件级 {@link BlockPatch} 输出。
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
        List<BlockPatch> patches = adaptor.generate(semantic, world);
        if (!patches.isEmpty()) {
            recordStructureAdaptorHit(semantic, generator, spec);
        }
        return patches;
    }

    private static void recordStructureAdaptorHit(
            SemanticComponent semantic,
            StructureGenerator generator,
            BuildingSpec spec
    ) {
        Component c = semantic != null ? semantic.source() : null;
        if (generator instanceof com.formacraft.server.generation.structure.TypologyBackedStructureGenerator tbg) {
            com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordTypologyBuilderHit(
                    tbg.typologyId(), "structure_adaptor");
            return;
        }
        String moduleId = c != null
                ? com.formacraft.common.typology.TypologyComponentRouter.extractLandmarkModuleId(c)
                : null;
        if (moduleId != null
                && com.formacraft.common.network.metrics.TypologyRoutingMetrics.isModuleComponent(c)) {
            com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordModuleHit(
                    moduleId, "structure_adaptor");
            return;
        }
        String key = resolveGeneratorKey(generator, spec);
        com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordStructureGeneratorHit(
                key, "structure_adaptor");
    }

    private static String resolveGeneratorKey(StructureGenerator generator, BuildingSpec spec) {
        if (generator == null) {
            return "unknown";
        }
        Map<String, Object> extra = spec != null ? spec.getExtra() : null;
        if (extra != null) {
            Object landmark = extra.get("landmark");
            if (landmark != null && !String.valueOf(landmark).isBlank()) {
                return String.valueOf(landmark).trim();
            }
            Object template = extra.get("template");
            if (template != null && !String.valueOf(template).isBlank()) {
                return String.valueOf(template).trim();
            }
        }
        return generator.getClass().getSimpleName();
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

            if (spec != null) {
                FormacraftMod.LOGGER.debug("StructureGeneratorAdaptor: generated {} patches (type={})",
                        patches.size(), spec.getType());
            }
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
            boolean typologyParams = params.containsKey("typology_id")
                    || params.containsKey("structural_typology")
                    || params.containsKey("typologyId")
                    || params.containsKey("structuralTypologyId");
            if (typologyParams) {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        extra.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
            }
            for (String key : List.of(
                    "landmark", "template", "blueprint", "assembly", "styleProfileId",
                    "meshStructure", "facing", "gateSide", "designSeed", "paletteId",
                    "bowlSteepness", "tierStep", "width", "depth", "height", "elliptical",
                    "typology_id", "structural_typology", "reference_landmark",
                    "levels", "baseWidth", "towerHeight", "hallHeight", "baysX", "baysZ",
                    "includeSubEaves", "footprint", "setback_ratio", "niche_rhythm", "detailLevel",
                    "puzuoProfile", "roofType"
            )) {
                copyIfPresent(params, extra, key);
            }
            // Phase 7：module_id → 规范地标 id（走既有 landmark 路由）
            Object moduleId = params.get("module_id");
            if (moduleId != null) {
                putResolvedLandmark(extra, String.valueOf(moduleId));
            }
        }

        List<String> features = c.features();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:")) {
                    putResolvedLandmark(extra, feature.substring("landmark:".length()).trim());
                } else if (lower.startsWith("module:")) {
                    putResolvedLandmark(extra, feature.substring("module:".length()).trim());
                } else if (lower.startsWith("typology:")) {
                    String typologyId = feature.substring("typology:".length()).trim();
                    if (!typologyId.isEmpty()) {
                        extra.put("typology_id", typologyId);
                        extra.put("structural_typology", typologyId);
                    }
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

    /**
     * 把（可能是别名/中文的）模块引用解析为规范地标 id 写入 extra；解析失败时保留原值兜底。
     */
    private static void putResolvedLandmark(Map<String, Object> extra, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return;
        String resolved = com.formacraft.common.archetype.LandmarkModuleRegistry.resolveModuleId(rawValue);
        String moduleId = resolved != null ? resolved : rawValue.trim();
        String typology = StructuralTypologyRegistry.typologyForLegacyModule(moduleId);
        if (typology != null && !typology.isBlank()) {
            extra.put("typology_id", typology);
            extra.put("structural_typology", typology);
            extra.putIfAbsent("reference_landmark", moduleId);
            com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordLegacyRedirect(moduleId, typology);
            return;
        }
        extra.put("landmark", moduleId);
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
