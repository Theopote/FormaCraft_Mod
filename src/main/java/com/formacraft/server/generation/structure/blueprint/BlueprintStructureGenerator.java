package com.formacraft.server.generation.structure.blueprint;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.skeleton.compound.CompoundInterpreter;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import com.formacraft.server.skeleton.path.PathRoadInterpreter;
import com.formacraft.server.skeleton.rect.RectEnclosureInterpreter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BlueprintStructureGenerator:
 * - Compiles spec.extra.blueprint into a SkeletonPlan via BlueprintCompilerRegistry.
 * - Interprets the plan into PlannedBlocks using existing interpreters + GeneratorBackedPlan delegation.
 *
 * This is the generic bridge that lets LLM output semantic blueprints instead of block placements.
 */
public final class BlueprintStructureGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("BlueprintStructureGenerator");
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        if (spec == null) return new GeneratedStructure(null, origin, "Empty Blueprint", new ArrayList<>());
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return new GeneratedStructure(null, origin, "No Blueprint", new ArrayList<>());

        Map<String, Object> blueprint = parseBlueprint(extra.get("blueprint"));
        if (blueprint == null || blueprint.isEmpty()) {
            return new GeneratedStructure(null, origin, "No Blueprint", new ArrayList<>());
        }

        // Validate early to avoid silent mismatch.
        BlueprintSchema.Validation v = BlueprintSchema.validateV1(blueprint);
        if (!v.ok()) {
            FormacraftMod.LOGGER.warn("BlueprintStructureGenerator: invalid blueprint: {}", v.error());
            return new GeneratedStructure(null, origin, "Blueprint Invalid: " + v.error(), new ArrayList<>());
        }

        SkeletonPlan plan = BlueprintCompilerRegistry.tryCompile(spec, blueprint);
        if (plan == null) {
            FormacraftMod.LOGGER.warn("BlueprintStructureGenerator: no compiler matched for blueprint_type={}, version={}", v.type(), v.version());
            return new GeneratedStructure(null, origin, "Blueprint Unsupported: " + v.type(), new ArrayList<>());
        }

        StyleProfile styleProfile = StyleProfileRegistry.resolve(spec);
        Materials mats = spec.getMaterials() != null ? spec.getMaterials() : new Materials();
        String paletteId = getString(extra);
        if ((paletteId == null || paletteId.isBlank()) && styleProfile != null && styleProfile.details() != null
                && styleProfile.details().paletteId != null && !styleProfile.details().paletteId.isBlank()) {
            paletteId = styleProfile.details().paletteId.trim();
        }
        final String paletteIdFinal = paletteId;

        PlanDispatcher dispatcher = (child, o, w) -> interpretChild(spec, styleProfile, mats, paletteIdFinal, child, o, w);

        List<PlannedBlock> blocks;
        try {
            if (plan instanceof CompoundPlan cp) {
                blocks = new CompoundInterpreter(dispatcher).interpret(cp, origin, world);
            } else {
                blocks = dispatcher.interpretChild(plan, origin, world);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("BlueprintStructureGenerator: failed to interpret plan", t);
            blocks = new ArrayList<>();
        }

        String desc = "Blueprint(" + plan.type() + ")";
        return new GeneratedStructure(null, origin, desc, blocks != null ? blocks : new ArrayList<>());
    }

    private static List<PlannedBlock> interpretChild(BuildingSpec parentSpec,
                                                     StyleProfile styleProfile,
                                                     Materials mats,
                                                     String paletteId,
                                                     SkeletonPlan plan,
                                                     BlockPos origin,
                                                     ServerWorld world) {
        switch (plan) {
            case null -> {
                return List.of();
            }

            // 1) Delegate to concrete generators
            case GeneratorBackedPlan gbp -> {
                if (gbp.spec == null) return List.of();
                StructureGenerator g = GenerationHub.routeStructure(gbp.spec);
                GeneratedStructure out = g.generate(gbp.spec, origin, world);
                return out != null ? out.getBlocks() : List.of();
            }


            // 2) Rect enclosure (palette-aware)
            case RectEnclosurePlan rp -> {
                BlockState wall = resolveBlockState(world,
                        firstNonBlank(mats.getWall(), styleProfile != null ? styleProfile.palette().wall : null),
                        Blocks.STONE_BRICKS.getDefaultState());
                // cap: prefer style palette cap -> material roof -> wall
                String capId = firstNonBlank(styleProfile != null ? styleProfile.palette().cap : null, mats.getRoof(), mats.getWall());
                BlockState cap = resolveBlockState(world, capId, wall);

                int capLayers = styleProfile != null && styleProfile.rules() != null ? styleProfile.rules().capLayers : 1;
                int capOverhang = styleProfile != null && styleProfile.rules() != null ? styleProfile.rules().capOverhang : 0;

                RectEnclosureInterpreter it = new RectEnclosureInterpreter(
                        wall, cap, cap, capLayers, capOverhang,
                        wall, false,
                        paletteId
                );
                return it.interpret(rp, origin, world);
            }


            // 3) Polyline roads (simple road for now)
            case PolylinePathPlan pp -> {
                BlockState road = Blocks.GRAVEL.getDefaultState();
                BlockState border = Blocks.COBBLESTONE.getDefaultState();
                var details = styleProfile != null ? styleProfile.details() : null;
                String eavesProfile = details != null ? details.eavesProfile : null;
                String ornamentProfile = details != null ? details.ornamentProfile : null;
                boolean neon = eavesProfile != null && eavesProfile.toLowerCase(Locale.ROOT).contains("neon");
                boolean cyber = ornamentProfile != null && (ornamentProfile.toLowerCase(Locale.ROOT).contains("cyber") || ornamentProfile.toLowerCase(Locale.ROOT).contains("sign"));
                BlockState lamp = neon ? Blocks.SEA_LANTERN.getDefaultState() : Blocks.LANTERN.getDefaultState();
                BlockState post = cyber ? Blocks.IRON_BARS.getDefaultState() : Blocks.COBBLESTONE_WALL.getDefaultState();
                return new PathRoadInterpreter(road, border, true, paletteId, lamp, post, ornamentProfile).interpret(pp, origin, world);
            }


            // 4) Nested compounds
            case CompoundPlan cp -> {
                PlanDispatcher dispatcher = (child, o, w) -> interpretChild(parentSpec, styleProfile, mats, paletteId, child, o, w);
                return new CompoundInterpreter(dispatcher).interpret(cp, origin, world);
            }
            default -> {
            }
        }

        // Unknown plan type: ignore (best-effort)
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBlueprint(Object raw) {
        switch (raw) {
            case null -> {
                return null;
            }
            case Map<?, ?> m -> {
                return (Map<String, Object>) m;
            }
            case String s -> {
                String json = s.trim();
                if (json.isEmpty() || "{}".equals(json)) return null;
                try {
                    return JsonUtil.fromJson(json, Map.class);
                } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
            }
            default -> {
            }
        }
        // last resort: serialize then parse
        try {
            String json = JsonUtil.toJson(raw);
            if (json == null || json.isBlank()) return null;
            return JsonUtil.fromJson(json, Map.class);
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return null;
    }

    private static String getString(Map<String, Object> extra) {
        if (extra == null) return null;
        Object v = extra.get("paletteId");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String x = firstNonBlank(a, b);
        return firstNonBlank(x, c);
    }

    private static BlockState resolveBlockState(ServerWorld world, String blockId, BlockState fallback) {
        if (world == null) return fallback;
        if (blockId == null || blockId.isBlank()) return fallback;
        String s = blockId.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        try {
            Identifier id = Identifier.tryParse(s);
            if (id == null) return fallback;
            Block b = Registries.BLOCK.get(id);
            BlockState st = b.getDefaultState();
            return st != null ? st : fallback;
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return fallback;
    }
}


