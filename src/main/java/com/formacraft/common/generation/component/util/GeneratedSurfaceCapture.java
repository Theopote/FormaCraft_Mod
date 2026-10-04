package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Consumer;

/** Generator decisions, captured only during compilation; not reconstructed from bounding boxes. */
public final class GeneratedSurfaceCapture {
    private GeneratedSurfaceCapture() {}
    public enum Role { WALL, WINDOW, ROOF, DOOR_OPENING, FACADE_OPENING }
    public record Cell(BlockPos position, Role role) {
        public Cell { position = position.toImmutable(); Objects.requireNonNull(role); }
        public boolean requiresBlock() { return role != Role.DOOR_OPENING && role != Role.FACADE_OPENING; }
        public Cell shift(BlockPos offset) { return new Cell(position.add(offset), role); }
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private static final ThreadLocal<Consumer<Cell>> CAPTURE = new ThreadLocal<>();
    public static boolean isCapturing() { return CAPTURE.get() != null; }
    public static Scope captureTo(Consumer<Cell> sink) {
        var previous = CAPTURE.get();
        CAPTURE.set(Objects.requireNonNull(sink));
        return () -> { if (previous == null) CAPTURE.remove(); else CAPTURE.set(previous); };
    }
    public static void record(int x, int y, int z, Role role) {
        var sink = CAPTURE.get();
        if (sink != null) sink.accept(new Cell(new BlockPos(x, y, z), role));
    }
    public static boolean occupied(BlockPatch patch) {
        if (patch == null || BlockPatch.REMOVE.equalsIgnoreCase(patch.action()) || patch.targetBlock() == null) return false;
        String value = patch.targetBlock();
        int properties = value.indexOf('[');
        String id = (properties < 0 ? value : value.substring(0, properties)).trim();
        return !id.isEmpty() && !AIR.contains(id);
    }
    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air", "minecraft:air",
            "minecraft:cave_air", "minecraft:void_air");
    /** Returns missing decisions, honoring the last decision and last operation at each coordinate. */
    public static List<Cell> missing(List<Cell> cells, List<BlockPatch> patches) {
        if (cells.isEmpty()) return List.of();
        var decisions = new LinkedHashMap<BlockPos, Cell>();
        for (var cell : cells) decisions.put(cell.position(), cell);
        var finalPatches = new HashMap<BlockPos, BlockPatch>();
        for (var patch : patches) if (patch != null)
            finalPatches.put(new BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
        return decisions.values().stream().filter(Cell::requiresBlock)
                .filter(cell -> !occupied(finalPatches.get(cell.position()))).toList();
    }
}
