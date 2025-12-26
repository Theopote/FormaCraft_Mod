package com.formacraft.server.generator.blueprint;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonPlan;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BlueprintCompilerRegistry:
 * - Registers known blueprint compilers (v1: castle).
 * - Provides best-effort selection based on blueprint "blueprint_type"/"type" and heuristic tags.
 */
public final class BlueprintCompilerRegistry {
    private BlueprintCompilerRegistry() {}

    // v1: keep static list; can be made data-driven later.
    private static final List<BlueprintCompiler> COMPILERS = List.of(
            new CastleBlueprintCompilerAdapter(),
            new TulouBlueprintCompiler(),
            new TempleOfHeavenBlueprintCompiler(),
            new GreatWallBlueprintCompiler(),
            new EiffelTowerBlueprintCompiler(),
            new GoldenGateBridgeBlueprintCompiler(),
            new GiantWildGoosePagodaBlueprintCompiler()
    );

    public static BlueprintCompiler resolve(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        if (blueprint == null) return null;

        // Normalize and validate schema (v1)
        BlueprintSchema.Validation v = BlueprintSchema.validateV1(blueprint);
        if (!v.ok()) return null;
        String t = v.type();
        if (!t.isBlank()) {
            for (BlueprintCompiler c : COMPILERS) {
                if (c != null && c.id().toLowerCase(Locale.ROOT).contains(t)) return c;
            }
        }

        // Otherwise try supports() heuristics.
        for (BlueprintCompiler c : COMPILERS) {
            if (c != null && c.supports(parentSpec, blueprint)) return c;
        }
        return null;
    }

    public static SkeletonPlan tryCompile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
        BlueprintCompiler c = resolve(parentSpec, blueprint);
        if (c == null) return null;
        return c.compile(parentSpec, blueprint);
    }

    private static String getBlueprintType(Map<String, Object> blueprint) {
        // Kept for backward compatibility; now delegates to BlueprintSchema normalization.
        Object v = blueprint.get("blueprint_type");
        if (v == null) v = blueprint.get("blueprintType");
        if (v == null) v = blueprint.get("type");
        return BlueprintSchema.normalizeType(v == null ? "" : String.valueOf(v));
    }

    private static final class CastleBlueprintCompilerAdapter implements BlueprintCompiler {
        @Override
        public String id() {
            return "castle_v1";
        }

        @Override
        public boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint) {
            if (blueprint == null) return false;
            String t = getBlueprintType(blueprint);
            if (t.contains("castle")) return true;

            // Heuristic: components contain KEEP/TOWER/WALL_CONNECTOR
            Object comps = blueprint.get("components");
            if (comps instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object tt = m.get("type");
                    String s = tt == null ? "" : String.valueOf(tt).trim().toUpperCase(Locale.ROOT);
                    if (s.contains("KEEP") || s.contains("TOWER") || s.contains("WALL")) return true;
                }
            }
            // Also accept when template hints castle_compound
            try {
                Map<String, Object> extra = parentSpec != null ? parentSpec.getExtra() : null;
                Object tpl = extra != null ? extra.get("template") : null;
                String ts = tpl == null ? "" : String.valueOf(tpl).trim().toLowerCase(Locale.ROOT);
                return ts.contains("castle");
            } catch (Throwable ignored) {}
            return false;
        }

        @Override
        public SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint) {
            // gate side fallback comes from parent spec if present; otherwise SOUTH.
            Direction gateSide = Direction.SOUTH;
            try {
                Map<String, Object> extra = parentSpec != null ? parentSpec.getExtra() : null;
                Object v = extra != null ? extra.get("gateSide") : null;
                if (v == null) v = extra != null ? extra.get("facing") : null;
                if (v != null) {
                    String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
                    gateSide = Direction.valueOf(s);
                }
            } catch (Throwable ignored) {}
            return CastleBlueprintCompiler.tryCompile(blueprint, parentSpec, gateSide);
        }
    }
}


