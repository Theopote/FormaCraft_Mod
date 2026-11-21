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
 *   <li>USER_EXPLICIT invalid tread with no same-family registered stair → leave for capability gap</li>
 *   <li>LLM / unlocked invalid tread → map via material family, else stone-brick TECHNICAL_DEFAULT</li>
 * </ul>
 * Does not substitute during {@link MaterialFallbackPolicy} normalize — that path preserves authored values.
 */
public final class StairTreadMaterialRevision {
    private static final Set<String> ASSEMBLY_STAIR_TYPES = Set.of("STAIR_SYSTEM", "STAIRS_SYSTEM", "STAIRCASE");

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
            Map<String, Object> params = reviseParams(plan, c, c.params());
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

    private static Map<String, Object> reviseParams(LlmPlan plan, Component component, Map<String, Object> params) {
        if (params == null) return null;
        boolean stair = StairComponentIdentity.isStairComponent(component);
        Object revisedTree = reviseValue(plan, params, params, null, params, stair);
        if (revisedTree == params) return params;
        @SuppressWarnings("unchecked")
        Map<String, Object> revised = (Map<String, Object>) revisedTree;
        return revised;
    }

    @SuppressWarnings("unchecked")
    private static Object reviseValue(
            LlmPlan plan,
            Map<String, Object> componentParams,
            Map<?, ?> materialScope,
            String key,
            Object value,
            boolean stairContext
    ) {
        if (value instanceof Map<?, ?> map) {
            boolean assemblyStair = ASSEMBLY_STAIR_TYPES.contains(
                    Objects.toString(map.get("op"), "").trim().toUpperCase(Locale.ROOT))
                    || ASSEMBLY_STAIR_TYPES.contains(
                    Objects.toString(map.get("type"), "").trim().toUpperCase(Locale.ROOT));
            boolean childStair = stairContext || assemblyStair;
            Map<?, ?> childScope = (assemblyStair || stairContext) ? map : materialScope;
            LinkedHashMap<String, Object> copy = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String nk = String.valueOf(e.getKey());
                Object revised = reviseValue(plan, componentParams, childScope, nk, e.getValue(), childStair);
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
                Object revised = reviseValue(plan, componentParams, materialScope, key, list.get(i), stairContext);
                if (revised != list.get(i)) {
                    if (copy == null) copy = new ArrayList<>((List<Object>) list);
                    copy.set(i, revised);
                }
            }
            return copy == null ? value : copy;
        }
        if (stairContext && "stairs".equals(key) && value instanceof String requested) {
            String revised = reviseTread(plan, componentParams, materialScope, requested);
            return Objects.equals(revised, requested) ? value : revised;
        }
        return value;
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
            LlmPlan plan,
            Map<String, Object> componentParams,
            Map<?, ?> materialScope,
            String requested
    ) {
        if (isRegisteredStairs(requested)) return requested;
        boolean userExplicit = isUserExplicitStairs(plan, componentParams);
        String familyFromRequested = MaterialBlockFamily.stairs(requested);
        if (isRegisteredStairs(familyFromRequested)) {
            FormacraftMod.LOGGER.info(
                    "StairTreadMaterialRevision: {} → {} (same-family shape)", requested, familyFromRequested);
            return familyFromRequested;
        }
        if (userExplicit) {
            // Preserve invalid USER_EXPLICIT value so the executor emits E_STAIR_COMPONENT_INVALID.
            return requested;
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

    private static boolean isUserExplicitStairs(LlmPlan plan, Map<String, Object> params) {
        if (params != null) {
            if (UserConstraintContract.isServerVerified(params, "stairs")) return true;
            if (Boolean.TRUE.equals(params.get("stairs_explicit"))
                    || Boolean.TRUE.equals(params.get("stair_material_explicit"))) {
                return true;
            }
            Object nested = params.get("design_sources");
            if (nested instanceof Map<?, ?> map) {
                DesignParamSource src = DesignParamSource.parse(
                        map.get("stairs") == null ? null : String.valueOf(map.get("stairs")));
                if (src == DesignParamSource.USER_EXPLICIT) return true;
            }
        }
        if (plan != null && plan.designSources() != null) {
            DesignParamSource src = DesignParamSource.parse(plan.designSources().get("stairs"));
            return src == DesignParamSource.USER_EXPLICIT;
        }
        return false;
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
