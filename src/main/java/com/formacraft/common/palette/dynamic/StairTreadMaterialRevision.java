package com.formacraft.common.palette.dynamic;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.design.DesignParamSource;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import com.formacraft.common.typology.UserConstraintContract;
import net.minecraft.block.StairsBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Planning-revision layer for unexecutable stair tread ids.
 * <ul>
 *   <li>Server-sealed USER_EXPLICIT invalid tread with no same-family registered stair → leave for capability gap</li>
 *   <li>LLM / unlocked invalid tread → map via material family, else stone-brick TECHNICAL_DEFAULT</li>
 * </ul>
 * Does not substitute during {@link MaterialFallbackPolicy} normalize — that path preserves authored values.
 * Provenance / verification metadata maps are opaque and never revised as block ids.
 */
public final class StairTreadMaterialRevision {
    private static final Set<String> ASSEMBLY_STAIR_TYPES = Set.of("STAIR_SYSTEM", "STAIRS_SYSTEM", "STAIRCASE");
    /** Same opaque provenance keys as {@link MaterialFallbackPolicy}, plus the server seal token. */
    private static final Set<String> PROVENANCE_METADATA_KEYS = Set.of(
            "design_sources",
            "_param_sources",
            UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY,
            UserConstraintContract.SERVER_SEAL_KEY
    );

    private StairTreadMaterialRevision() {}

    public static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        boolean changed = false;
        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            if (c == null) {
                out.add(null);
                continue;
            }
            Map<String, Object> params = reviseParams(c, c.params());
            if (params != c.params()) {
                changed = true;
                out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), params));
            } else {
                out.add(c);
            }
        }
        if (!changed) return plan;
        return new LlmPlan(plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                out, plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static Map<String, Object> reviseParams(Component component, Map<String, Object> params) {
        if (params == null) return null;
        boolean stair = StairComponentIdentity.isStairComponent(component);
        Object revisedTree = reviseValue(params, params, null, params, stair);
        if (revisedTree == params) return params;
        @SuppressWarnings("unchecked")
        Map<String, Object> revised = (Map<String, Object>) revisedTree;
        return revised;
    }

    @SuppressWarnings("unchecked")
    private static Object reviseValue(
            Map<String, Object> componentParams,
            Map<?, ?> materialScope,
            String key,
            Object value,
            boolean stairContext
    ) {
        // Provenance tags and seals are not block ids — never recurse or remap them.
        if (isProvenanceMetadataKey(key)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            boolean assemblyStair = ASSEMBLY_STAIR_TYPES.contains(
                    Objects.toString(map.get("op"), "").trim().toUpperCase(Locale.ROOT))
                    || ASSEMBLY_STAIR_TYPES.contains(
                    Objects.toString(map.get("type"), "").trim().toUpperCase(Locale.ROOT));
            boolean childStair = stairContext || assemblyStair;
            // Nested STAIR_SYSTEM ops own their materials and sealed sources; otherwise keep the
            // nearest stair material scope while descending geometry fields.
            Map<?, ?> childScope = assemblyStair ? map : (stairContext ? map : materialScope);
            LinkedHashMap<String, Object> copy = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String nk = String.valueOf(e.getKey());
                Object revised = reviseValue(componentParams, childScope, nk, e.getValue(), childStair);
                if (revised != e.getValue()) {
                    if (copy == null) {
                        LinkedHashMap<String, Object> seeded = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> src : map.entrySet()) {
                            seeded.put(String.valueOf(src.getKey()), src.getValue());
                        }
                        copy = seeded;
                    }
                    copy.put(nk, revised);
                }
            }
            if (copy != null && childStair && copy.get("stairs") instanceof String stairs
                    && !Objects.equals(stairs, map.get("stairs"))) {
                copy.put("_stair_tread_revised_from", String.valueOf(map.get("stairs")));
                copy.put("_stair_tread_revised_to", stairs);
                annotateDesignSource(copy);
            }
            return copy == null ? value : copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = null;
            for (int i = 0; i < list.size(); i++) {
                Object revised = reviseValue(componentParams, materialScope, key, list.get(i), stairContext);
                if (revised != list.get(i)) {
                    if (copy == null) copy = new ArrayList<>((List<Object>) list);
                    copy.set(i, revised);
                }
            }
            return copy == null ? value : copy;
        }
        if (stairContext && "stairs".equals(key) && value instanceof String requested) {
            String revised = reviseTread(componentParams, materialScope, requested);
            return Objects.equals(revised, requested) ? value : revised;
        }
        return value;
    }

    private static boolean isProvenanceMetadataKey(String key) {
        return key != null && PROVENANCE_METADATA_KEYS.contains(key);
    }

    private static void annotateDesignSource(Map<String, Object> target) {
        Object raw = target.get("design_sources");
        Map<String, Object> sources = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null) sources.put(String.valueOf(k), v);
            });
        }
        DesignParamSource existing = DesignParamSource.parse(
                sources.get("stairs") == null ? null : String.valueOf(sources.get("stairs")));
        if (existing == null || existing == DesignParamSource.TECHNICAL_DEFAULT
                || existing == DesignParamSource.COMPILER_INFERRED) {
            sources.put("stairs", DesignParamSource.TECHNICAL_DEFAULT.name());
            target.put("design_sources", sources);
        }
    }

    private static String reviseTread(
            Map<String, Object> componentParams,
            Map<?, ?> materialScope,
            String requested
    ) {
        // Direct stair components: materialScope is the component params.
        // Nested STAIR_SYSTEM ops: materialScope is the op node only — never inherit a parent
        // Assembly component's stairs seal.
        if (isSealedUserExplicitStairs(materialScope)
                || isModelPlannedStairs(materialScope)
                || isRegisteredStairs(requested)) {
            return requested;
        }
        String familyFromRequested = MaterialBlockFamily.stairs(requested);
        if (isRegisteredStairs(familyFromRequested)) {
            FormacraftMod.LOGGER.info(
                    "StairTreadMaterialRevision: {} → {} (same-family shape)", requested, familyFromRequested);
            return familyFromRequested;
        }
        String floor = firstString(materialScope, "floor", "material");
        if (floor == null) floor = firstString(componentParams, "floor", "material");
        String familyFromFloor = floor == null ? null : MaterialBlockFamily.stairs(floor);
        if (isRegisteredStairs(familyFromFloor)) {
            FormacraftMod.LOGGER.info(
                    "StairTreadMaterialRevision: {} → {} (floor family)", requested, familyFromFloor);
            return familyFromFloor;
        }
        String fallback = MaterialBlockFamily.stairsOrStoneBrick(
                floor == null || floor.isBlank() ? "minecraft:stone_bricks" : floor);
        FormacraftMod.LOGGER.warn(
                "StairTreadMaterialRevision: {} → {} (TECHNICAL_DEFAULT; no family stair)",
                requested, fallback);
        return fallback;
    }

    /**
     * Only a server-minted seal proves that the user explicitly selected this value.
     */
    private static boolean isSealedUserExplicitStairs(Map<?, ?> params) {
        if (params == null || params.isEmpty()) return false;
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : params.entrySet()) {
            if (e.getKey() != null) typed.put(String.valueOf(e.getKey()), e.getValue());
        }
        return UserConstraintContract.isServerVerified(typed, "stairs");
    }

    private static boolean isModelPlannedStairs(Map<?, ?> params) {
        return params.get("design_sources") instanceof Map<?, ?> sources
                && DesignParamSource.parse(Objects.toString(sources.get("stairs"), null))
                == DesignParamSource.AI_PLANNED;
    }

    private static boolean isRegisteredStairs(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        var state = BlockPatchTargetResolver.resolve(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, blockId));
        return state != null && state.getBlock() instanceof StairsBlock;
    }

    private static String firstString(Map<?, ?> params, String... keys) {
        if (params == null) return null;
        for (String key : keys) {
            Object v = params.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}
