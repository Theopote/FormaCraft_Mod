package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MetaAssembly-backed facade emission for components that opted into assembly facade.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR3). Marking/eligibility
 * ({@code markAssemblyFacade} / {@code shouldUseAssemblyFacade}) remains in
 * {@link ComponentSatelliteInference}.
 */
final class AssemblyFacadeCompiler {
    private AssemblyFacadeCompiler() {}

    static List<BlockPatch> generateAssemblyFacadePatches(
            LlmPlan plan,
            SemanticComponent semantic,
            Slot slot,
            BlockPos globalAnchor,
            ServerWorld world
    ) {
        Component c = semantic != null ? semantic.source() : null;
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return List.of();
        }
        if (slot == null || slot.anchor() == null || world == null || globalAnchor == null) {
            return List.of();
        }

        Dimensions dims = c.dimensions();
        Vec3i rp = ComponentPlanCompiler.resolveMassOrigin(c);
        Vec3i slotAnchor = slot.anchor();

        int width = Math.max(3, dims.width());
        int depth = Math.max(3, dims.depth());
        int height = Math.max(3, dims.height());

        int shellW = (width % 2 == 0) ? Math.max(3, width - 1) : width;
        int shellD = (depth % 2 == 0) ? Math.max(3, depth - 1) : depth;

        int centerOffsetX = shellW / 2;
        int centerOffsetZ = shellD / 2;

        BlockPos slotWorld = globalAnchor.add(slotAnchor.x(), slotAnchor.y(), slotAnchor.z());
        BlockPos componentBase = slotWorld.add(rp.x(), rp.y(), rp.z());
        BlockPos origin = componentBase.add(centerOffsetX, 0, centerOffsetZ);

        Direction entranceDir = resolveEntranceFacing(plan, slot);
        String entranceFace = directionToFaceToken(entranceDir);

        Map<String, Object> assembly = new HashMap<>();
        String paletteId = resolveAssemblyPaletteId(plan, semantic);
        if (paletteId != null && !paletteId.isBlank()) {
            assembly.put("paletteId", paletteId);
        }
        if (!entranceFace.isBlank()) {
            assembly.put("entranceFacing", entranceFace);
        }

        Map<String, Object> macro = new HashMap<>();
        Map<String, Object> style = buildAssemblyMacroStyle(plan, semantic, width, depth, height, entranceFace);
        if (!style.isEmpty()) {
            macro.put("style", style);
        }
        Double windowRatio = ComponentParamParsers.doubleOrNull(c.params(), "window_ratio", "windowRatio");
        if (windowRatio != null) {
            macro.put("openness", clamp01(windowRatio));
        }
        if (!macro.isEmpty()) {
            assembly.put("macro", macro);
        }

        Map<String, Object> primary = new HashMap<>();
        primary.put("id", "MainVolume");
        primary.put("type", "SHELL_BOX");
        primary.put("w", shellW);
        primary.put("d", shellD);
        primary.put("h", height);

        Map<String, Object> door = buildDoorOpening(plan, semantic, width, depth, height, entranceFace);
        Map<String, Object> facade = new HashMap<>();
        List<Map<String, Object>> openings = new ArrayList<>();
        openings.add(door);
        facade.put("openings", openings);
        primary.put("facade", facade);

        List<Object> comps = new ArrayList<>();
        comps.add(primary);
        assembly.put("components", comps);

        AssemblySpecNormalizeResult norm = AssemblySpecNormalizer.normalize(assembly);
        AssemblyMacroApplyResult macroApplied = AssemblyMacroApplier.apply(norm.normalized());
        Object applied = macroApplied.applied();

        List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(applied);
        long errCount = issues.stream()
                .filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR)
                .count();
        if (errCount > 0) {
            FormacraftMod.LOGGER.warn("ComponentPlanCompiler: assembly facade validation failed ({} errors)", errCount);
            return List.of();
        }

        AssemblySpec spec = MetaAssemblyCompiler.compile(applied, null);
        if (spec == null || spec.ops == null || spec.ops.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> ops = filterAssemblyFacadeOps(spec.ops);
        if (ops.isEmpty()) {
            return List.of();
        }

        MetaAssemblyEngine engine = new MetaAssemblyEngine();
        AssemblySpec facadeSpec = AssemblySpec.of(spec.paletteId, spec.entranceFacing, ops);
        List<PlannedBlock> blocks = engine.execute(
                facadeSpec,
                new MetaAssemblyEngine.Context(world, origin, entranceDir, spec.paletteId)
        );
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<BlockPatch> out = new ArrayList<>(blocks.size());
        for (PlannedBlock pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            BlockPos pos = pb.getPos();
            int dx = pos.getX() - slotWorld.getX();
            int dy = pos.getY() - slotWorld.getY();
            int dz = pos.getZ() - slotWorld.getZ();
            String blockId = Registries.BLOCK.getId(pb.getTargetState().getBlock()).toString();
            String action = pb.getTargetState().isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE;
            if (action.equals(BlockPatch.REMOVE)) {
                blockId = "minecraft:air";
            }
            out.add(new BlockPatch(action, dx, dy, dz, blockId));
        }
        return out;
    }

    private static Map<String, Object> buildAssemblyMacroStyle(
            LlmPlan plan,
            SemanticComponent semantic,
            int width,
            int depth,
            int height,
            String entranceFace
    ) {
        Map<String, Object> style = new HashMap<>();
        String styleId = resolveAssemblyStyleId(plan, semantic);
        if (styleId != null) {
            style.put("styleId", styleId);
        }
        if (entranceFace != null && !entranceFace.isBlank()) {
            style.put("entranceFace", entranceFace);
        }
        boolean chinese = styleId != null && styleId.toUpperCase(Locale.ROOT).contains("CHINESE")
                || styleId != null && styleId.toUpperCase(Locale.ROOT).contains("HUIZHOU")
                || styleId != null && styleId.toUpperCase(Locale.ROOT).contains("JIANGNAN");
        boolean gothic = styleId != null && styleId.toUpperCase(Locale.ROOT).contains("GOTHIC");

        double footprint = Math.max(1.0, Math.max(width, depth));
        double verticality = clamp01((height / footprint) * 0.6 + 0.2);
        if (gothic) {
            verticality = Math.max(verticality, 0.7);
        }
        style.put("verticality", verticality);

        double density = 0.55;
        Double windowRatio = ComponentParamParsers.doubleOrNull(semantic.source().params(), "window_ratio", "windowRatio");
        if (windowRatio != null) {
            density = clamp01(0.3 + windowRatio * 0.8);
            style.put("transparency", clamp01(windowRatio));
        }
        if (chinese) {
            density = Math.max(density, 0.55);
            style.putIfAbsent("intent", "中式 传统");
        }
        style.put("density", density);

        double symmetry = 0.45;
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().symmetry() != null) {
            if (plan.globalConstraints().symmetry() != GlobalConstraints.Symmetry.NONE) {
                symmetry = 0.75;
            }
        }
        style.put("symmetry", symmetry);

        double structureExposure = 0.45 + Math.min(0.25, verticality * 0.2);
        if (chinese) {
            structureExposure = Math.max(structureExposure, 0.65);
        }
        style.put("structureExposure", clamp01(structureExposure));

        return style;
    }


    private static String resolveAssemblyStyleId(LlmPlan plan, SemanticComponent semantic) {
        String profile = plan != null ? plan.styleProfile() : null;
        StringBuilder merged = new StringBuilder(profile != null ? profile : "");
        if (plan != null && plan.distinguishingFeatures() != null) {
            for (String feature : plan.distinguishingFeatures()) {
                if (feature != null) {
                    merged.append(" ").append(feature);
                }
            }
        }
        if (semantic != null && semantic.source() != null && semantic.source().features() != null) {
            for (String f : semantic.source().features()) {
                if (f == null) continue;
                merged.append(" ").append(f);
            }
        }
        String hint = merged.toString().toUpperCase(Locale.ROOT);

        if (hint.contains("HUI") || hint.contains("HUIZHOU") || hint.contains("徽派")) {
            return "HUIZHOU_TRADITIONAL";
        }
        if (hint.contains("JIANGNAN") || hint.contains("WATERTOWN") || hint.contains("江南")) {
            return "JIANGNAN_WATERTOWN";
        }
        if (hint.contains("CHINESE") || hint.contains("ASIAN") || hint.contains("中式") || hint.contains("传统")) {
            return "CHINESE_TRADITIONAL";
        }
        if (hint.contains("GOTHIC") || hint.contains("CATHEDRAL")) {
            return "GOTHIC";
        }
        if (hint.contains("INDUSTRIAL")) {
            return "INDUSTRIAL";
        }
        if (hint.contains("MODERN")) {
            return "MODERN";
        }

        if (plan != null && plan.styleAttributes() != null) {
            com.formacraft.common.llm.dto.StyleAttributes attrs = plan.styleAttributes();
            if (attrs.decorativeElements() != null) {
                for (String deco : attrs.decorativeElements()) {
                    if (deco == null) continue;
                    String d = deco.toLowerCase(Locale.ROOT);
                    if (d.contains("lattice") || d.contains("dougong") || d.contains("飞檐") || d.contains("斗拱")) {
                        return "CHINESE_TRADITIONAL";
                    }
                    if (d.contains("rose_window") || d.contains("pointed") || d.contains("gothic")) {
                        return "GOTHIC";
                    }
                }
            }
            String roofMat = attrs.roofMaterial();
            String wallMat = attrs.wallMaterial();
            if (roofMat != null && wallMat != null) {
                String rm = roofMat.toLowerCase(Locale.ROOT);
                String wm = wallMat.toLowerCase(Locale.ROOT);
                if (rm.contains("tile") && (wm.contains("plaster") || wm.contains("lime") || wm.contains("white"))) {
                    return "CHINESE_TRADITIONAL";
                }
            }
        }

        if (plan != null && plan.genome() != null && plan.genome().culturalStyle != null) {
            String region = plan.genome().culturalStyle.region;
            if (region != null) {
                String r = region.toUpperCase(Locale.ROOT);
                if (r.contains("CHINESE")) return "CHINESE_TRADITIONAL";
                if (r.contains("EUROPEAN") && plan.genome().culturalStyle.era != null
                        && plan.genome().culturalStyle.era.toUpperCase(Locale.ROOT).contains("MEDIEVAL")) {
                    return "GOTHIC";
                }
            }
        }
        return null;
    }

    private static String resolveAssemblyPaletteId(LlmPlan plan, SemanticComponent semantic) {
        if (plan != null && plan.styleAttributes() != null) {
            com.formacraft.common.llm.dto.StyleAttributes attrs = plan.styleAttributes();
            String wall = attrs.wallColor();
            String roof = attrs.roofColor();
            if (wall != null && roof != null) {
                String wl = wall.toLowerCase(Locale.ROOT);
                String rl = roof.toLowerCase(Locale.ROOT);
                if (wl.contains("white") && (rl.contains("black") || rl.contains("dark") || rl.contains("gray") || rl.contains("grey"))) {
                    return "PALETTE_HUIZHOU_WHITE_BLACK_A";
                }
            }
            String wallMat = attrs.wallMaterial();
            String roofMat = attrs.roofMaterial();
            if (wallMat != null && roofMat != null) {
                String wm = wallMat.toLowerCase(Locale.ROOT);
                String rm = roofMat.toLowerCase(Locale.ROOT);
                if ((wm.contains("plaster") || wm.contains("lime") || wm.contains("white"))
                        && (rm.contains("tile") || rm.contains("slate") || rm.contains("gray") || rm.contains("grey"))) {
                    return "PALETTE_HUIZHOU_WHITE_BLACK_A";
                }
            }
        }
        String styleId = resolveAssemblyStyleId(plan, semantic);
        if (styleId != null) {
            String s = styleId.toUpperCase(Locale.ROOT);
            if (s.contains("HUIZHOU")) return "PALETTE_HUIZHOU_WHITE_BLACK_A";
            if (s.contains("JIANGNAN")) return "PALETTE_JIANGNAN_WATERTOWN_A";
            if (s.contains("CHINESE")) return "PALETTE_CHINESE_IMPERIAL_A";
            if (s.contains("GOTHIC")) return "PALETTE_GOTHIC_CATHEDRAL_A";
            if (s.contains("INDUSTRIAL")) return "PALETTE_INDUSTRIAL_STEEL_A";
            if (s.contains("MODERN")) return "PALETTE_MODERN_GLASS_B";
        }
        return null;
    }

    private static Direction resolveEntranceFacing(LlmPlan plan, Slot slot) {
        GlobalConstraints.Facing facing = null;
        if (slot != null && slot.facing() != null) {
            facing = slot.facing();
        } else if (plan != null && plan.globalConstraints() != null) {
            facing = plan.globalConstraints().facing();
        }
        if (facing == null) return Direction.SOUTH;
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            case SOUTH -> Direction.SOUTH;
        };
    }

    private static String directionToFaceToken(Direction direction) {
        if (direction == null) {
            return "SOUTH";
        }
        return switch (direction) {
            case NORTH -> "NORTH";
            case EAST -> "EAST";
            case WEST -> "WEST";
            default -> "SOUTH";
        };
    }

    private static List<Map<String, Object>> filterAssemblyFacadeOps(List<Map<String, Object>> ops) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ops == null) return out;
        for (Map<String, Object> op : ops) {
            if (op == null) continue;
            Object opName = op.get("op");
            if (opName == null) continue;
            String name = String.valueOf(opName).trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (name.equals("SHELL_BOX")
                    || name.equals("EXTRUDE_POLYGON")
                    || name.equals("CLEAR_BOX")
                    || name.equals("ANCHOR_FOOTPRINT")
                    || name.equals("ANCHORAGE")
                    || name.equals("ROOF_COVER")
                    || name.equals("BSP_FLOOR_PLAN")) {
                continue;
            }
            out.add(op);
        }
        return out;
    }

    private static Map<String, Object> buildDoorOpening(
            LlmPlan plan,
            SemanticComponent semantic,
            int width,
            int depth,
            int height,
            String entranceFace
    ) {
        Component c = semantic != null ? semantic.source() : null;
        Map<String, Object> params = c != null ? c.params() : null;
        int doorW = ComponentParamParsers.intParam(params, "door_width", "doorWidth");
        int doorH = ComponentParamParsers.intParam(params, "door_height", "doorHeight");
        if (doorW <= 0) {
            doorW = Math.max(2, Math.min(5, width / 4));
        }
        if (doorH <= 0) {
            doorH = Math.max(3, Math.min(6, height / 3));
        }

        Map<String, Object> door = new HashMap<>();
        door.put("face", (entranceFace == null || entranceFace.isBlank()) ? "SOUTH" : entranceFace);
        door.put("kind", "DOOR");
        door.put("doorW", doorW);
        door.put("doorH", doorH);
        door.put("sillY", 1);
        return door;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

}
