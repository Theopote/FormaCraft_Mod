package com.formacraft.common.typology;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.component.adaptor.StructureGeneratorAdaptor;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.router.StructureGeneratorRegistry;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 1 adapter: typology params → merged BuildingSpec.extra → legacy StructureGenerator.
 */
public final class LegacyDelegatingTypologyInterpreter implements TypologyInterpreter {

    private final String typologyId;
    private final String legacyGeneratorKey;
    private final Map<String, Object> defaultParams;

    public LegacyDelegatingTypologyInterpreter(
            String typologyId,
            String legacyGeneratorKey,
            Map<String, Object> defaultParams
    ) {
        this.typologyId = typologyId;
        this.legacyGeneratorKey = legacyGeneratorKey;
        this.defaultParams = defaultParams != null ? Map.copyOf(defaultParams) : Map.of();
    }

    @Override
    public String typologyId() {
        return typologyId;
    }

    @Override
    public List<BlockPatch> interpret(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }

        StructureGenerator generator = StructureGeneratorRegistry.create(legacyGeneratorKey);
        if (generator == null) {
            FormacraftMod.LOGGER.warn(
                    "LegacyDelegatingTypologyInterpreter: no generator for typology={} legacy={}",
                    typologyId, legacyGeneratorKey
            );
            return List.of();
        }

        SemanticComponent enriched = semantic.withMergedParams(mergeParams(semantic.source()));
        StructureGeneratorAdaptor adaptor = new StructureGeneratorAdaptor(generator, BuildingType.CUSTOM);
        List<BlockPatch> patches = adaptor.generate(enriched, world);
        return patches != null ? new ArrayList<>(patches) : List.of();
    }

    private Map<String, Object> mergeParams(Component component) {
        Map<String, Object> merged = new LinkedHashMap<>(defaultParams);
        Map<String, Object> params = component.params();
        if (params != null) {
            merged.putAll(params);
        }
        merged.putIfAbsent("typology_id", typologyId);
        merged.putIfAbsent("structural_typology", typologyId);

        String reference = TypologyComponentRouter.extractReferenceLandmark(component);
        if (reference != null && !reference.isBlank()) {
            merged.putIfAbsent("reference_landmark", reference);
        }

        merged.putIfAbsent("landmark", legacyGeneratorKey);
        merged.putIfAbsent("module_id", legacyGeneratorKey);
        return merged;
    }
}
