package com.formacraft.server.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.*;
import com.formacraft.server.assembly.*;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Executable straight stair component; narrative STRUCTURE stairs never fall back to a house. */
public final class StraightStairComponentGenerator {
    private StraightStairComponentGenerator() {}
    public static boolean accepts(Component c) {
        if (c == null || c.componentType() == null) return false;
        if (Set.of("STAIR_SYSTEM", "STAIRCASE", "STAIRS").contains(c.componentType().toUpperCase(Locale.ROOT))) return true;
        return "STRUCTURE".equalsIgnoreCase(c.componentType()) && c.features() != null
            && c.features().stream().filter(Objects::nonNull).anyMatch(f -> f.startsWith("stair:"));
    }
    public static List<BlockPatch> generate(SemanticComponent semantic) {
        Component c = semantic.source();
        try {
            var op = new HashMap<String, Object>(c.params() == null ? Map.of() : c.params());
            if (!op.containsKey("from") || !op.containsKey("to")) {
                // Compatibility with the logged straight-flight contract only. Curves/turns need explicit ops.
                String features = String.join(" ", c.features() == null ? List.of() : c.features());
                if (!features.contains("stair:straight_single_run") || !features.contains("ascends_from_north_low_end_to_south_high_end"))
                    throw new IllegalArgumentException("Straight stairs require explicit from/to endpoints; split turns into flights and landings");
                Dimensions d = Objects.requireNonNull(c.dimensions(), "Missing stair dimensions");
                op.put("width", d.width());
                op.put("from", Map.of("x", d.width() / 2, "y", 0, "z", 0));
                op.put("to", Map.of("x", d.width() / 2, "y", d.height() - 1, "z", d.depth() - 4));
                op.put("landing_length", 3);
                String floor = String.valueOf(op.getOrDefault("material", "minecraft:oak_planks"));
                if (!floor.contains(":")) floor = "minecraft:" + floor;
                op.put("floor", floor);
                op.put("stairs", floor.replace("_planks", "_stairs"));
            }
            int width = integer(op.getOrDefault("width", 3));
            int landing = integer(op.getOrDefault("landing_length", 1));
            int clear = integer(op.getOrDefault("clearHeight", 2));
            if (width < 1 || width > 15 || landing < 1 || landing > 16 || clear < 2 || clear > 16)
                throw new IllegalArgumentException("Stair width must be 1..15, landing_length 1..16 and clearHeight 2..16");
            op.put("carve", true); op.put("clearHeight", clear);
            var rp = Objects.requireNonNull(c.relativePosition(), "Missing stair position");
            var origin = new BlockPos(rp.x(), rp.y(), rp.z());
            var blocks = new ArrayList<PlannedBlock>();
            var adapter = new AssemblyCirculationOps.Adapter() {
                public void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos base, int x, int y, int z, BlockState state) {
                    out.add(new PlannedBlock(base.add(x, y, z), state));
                }
                public BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> values, String key, String semanticKey, long salt, BlockState fallback) {
                    Object value = values.get(key);
                    if (value == null) return fallback;
                    var state = BlockPatchTargetResolver.resolve(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, value.toString()));
                    if (state == null) throw new IllegalArgumentException("Invalid stair material: " + value);
                    return state;
                }
                public int i(Object value, int fallback) { return value == null ? fallback : integer(value); }
                public boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
                public int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
            };
            AssemblyCirculationOps.applyStairSystem(blocks, null, origin, op, adapter);
            if (landing > 1) {
                var from = point(op.get("from")); var to = point(op.get("to"));
                int dx = Integer.signum(to.getX() - from.getX()), dz = Integer.signum(to.getZ() - from.getZ());
                var flat = new HashMap<>(op);
                flat.put("from", Map.of("x", to.getX() + dx, "y", to.getY(), "z", to.getZ() + dz));
                flat.put("to", Map.of("x", to.getX() + dx * landing, "y", to.getY(), "z", to.getZ() + dz * landing));
                AssemblyCirculationOps.applyStairSystem(blocks, null, origin, flat, adapter);
            }
            var flight = AssemblyCirculationConstraints.capture(blocks, 0);
            AssemblyCirculationConstraints.validate(blocks, List.of(flight));
            AssemblyCirculationConstraints.publish(List.of(flight));
            return AssemblyPatchGenerator.toPatches(blocks);
        } catch (RuntimeException failure) {
            AssemblyCompileDiagnostics.set(new CapabilityGap("E_STAIR_COMPONENT_INVALID", failure.getMessage(),
                "components[].params", List.of("Use straight from/to endpoints, width, stairs, floor and landing_length.")));
            return List.of();
        }
    }
    private static int integer(Object value) { return new java.math.BigDecimal(String.valueOf(value)).intValueExact(); }
    private static BlockPos point(Object value) {
        var map = (Map<?, ?>) value;
        return new BlockPos(integer(map.get("x")), integer(map.get("y")), integer(map.get("z")));
    }
}
