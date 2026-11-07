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
        if ("STRUCTURE".equalsIgnoreCase(c.componentType()) && c.params()!=null
                && c.params().get("from") instanceof Map<?,?> && c.params().get("to") instanceof Map<?,?>
                && (c.features()==null || c.features().stream().noneMatch(f->f!=null
                    && (f.startsWith("typology:") || f.startsWith("landmark:") || f.startsWith("module:"))))
                && c.params().get("stairs") instanceof String stairs && stairs.endsWith("_stairs")) return true;
        return "STRUCTURE".equalsIgnoreCase(c.componentType()) && c.features() != null
            && c.features().stream().filter(Objects::nonNull).anyMatch(f -> f.startsWith("stair:"));
    }
    public static List<BlockPatch> generate(SemanticComponent semantic) {
        Component c = semantic.source();
        try {
            var op = new HashMap<String, Object>(c.params() == null ? Map.of() : c.params());
            if (Boolean.TRUE.equals(op.get("exterior"))) op.put("support", false);
            if (!op.containsKey("from") || !op.containsKey("to")) {
                // Compatibility with the logged straight-flight contract only. Curves/turns need explicit ops.
                String features = String.join(" ", c.features() == null ? List.of() : c.features());
                if (!features.contains("stair:straight_single_run") || !features.contains("ascends_from_"))
                    throw new IllegalArgumentException("Straight stairs require explicit from/to endpoints; split turns into flights and landings");
                Dimensions d = Objects.requireNonNull(c.dimensions(), "Missing stair dimensions");
                int rise = Math.max(1, d.height() <= 1 ? 1 : d.height() - 1);
                int run = Math.max(rise, Math.max(1, d.depth() - 1));
                int midX = Math.max(0, d.width() / 2);
                op.put("width", Math.max(1, Math.min(5, d.width())));
                // Low at z=0, high at z=run — run always >= rise so flightError accepts 4×4×3 volumes.
                op.put("from", Map.of("x", midX, "y", 0, "z", 0));
                op.put("to", Map.of("x", midX, "y", rise, "z", run));
                op.put("landing_length", 1);
                String floor = String.valueOf(op.getOrDefault("material",
                        op.getOrDefault("floor", "minecraft:stone_bricks")));
                if (!floor.contains(":")) floor = "minecraft:" + floor;
                op.put("floor", floor);
                op.put("stairs", resolveStairsBlock(floor));
            } else if (!(op.get("stairs") instanceof String s) || !s.endsWith("_stairs")) {
                String floor = String.valueOf(op.getOrDefault("floor",
                        op.getOrDefault("material", "minecraft:stone_bricks")));
                if (!floor.contains(":")) floor = "minecraft:" + floor;
                op.putIfAbsent("floor", floor);
                op.put("stairs", resolveStairsBlock(floor));
            }
            int width = integer(op.getOrDefault("width", 3));
            if (point(op.get("from")).getY() > point(op.get("to")).getY()) {
                Object low = op.get("to");
                op.put("to", op.get("from"));
                op.put("from", low);
            }
            int landing = integer(op.getOrDefault("landing_length", 1));
            // Default 2 standing cells; upward-transition carve in AssemblyCirculationOps opens the
            // extra floor-band cell without forcing clearHeight=3 on every historical plan.
            int clear = integer(op.getOrDefault("clearHeight", op.getOrDefault("clear_h", 2)));
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

    /** stone_bricks → stone_brick_stairs; never leave a full cube as stairs. */
    static String resolveStairsBlock(String floorBlock) {
        if (floorBlock == null || floorBlock.isBlank()) return "minecraft:stone_brick_stairs";
        String id = floorBlock;
        int props = id.indexOf('[');
        if (props >= 0) id = id.substring(0, props);
        if (id.endsWith("_stairs")) return id;
        if (id.endsWith("_planks")) return id.substring(0, id.length() - 7) + "_stairs";
        if (id.endsWith("_bricks")) return id.substring(0, id.length() - 7) + "_brick_stairs";
        if (id.endsWith("_tiles")) return id.substring(0, id.length() - 6) + "_tile_stairs";
        if (id.equals("minecraft:stone") || id.endsWith(":stone")) return "minecraft:stone_stairs";
        if (id.endsWith("_block")) return id.substring(0, id.length() - 6) + "_stairs";
        return "minecraft:stone_brick_stairs";
    }
}
