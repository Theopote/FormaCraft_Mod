package com.formacraft.server.generation.typology.builder;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable approach-corridor terrain profile shared by Preview and Apply.
 * {@code schematicFlat=true} is only for explicitly marked non-executable schematic previews.
 */
public final class PalaceTerrainSnapshot {

    public record Sample(int localX, int localZ, int groundLocalY, boolean water) {}

    private final Map<Long, Sample> cells;
    private final String fingerprint;
    private final boolean schematicFlat;

    private PalaceTerrainSnapshot(Map<Long, Sample> cells, boolean schematicFlat) {
        this.cells = Map.copyOf(cells);
        this.schematicFlat = schematicFlat;
        this.fingerprint = computeFingerprint(this.cells, schematicFlat);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean schematicFlat() {
        return schematicFlat;
    }

    public Sample get(int localX, int localZ) {
        return cells.get(pack(localX, localZ));
    }

    public Map<Long, Sample> cells() {
        return cells;
    }

    /** Explicit schematic / unit-test flat pad at {@code padY} along the approach corridor. */
    public static PalaceTerrainSnapshot schematicFlat(
            int mx, int pathStartZ, int padY, int maxDrop) {
        Map<Long, Sample> map = new LinkedHashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int s = 0; s <= maxDrop; s++) {
                int x = mx + dx;
                int z = pathStartZ + s;
                map.put(pack(x, z), new Sample(x, z, padY, false));
            }
        }
        return new PalaceTerrainSnapshot(map, true);
    }

    /** Capture live heightmap along the approach corridor (local coords). */
    public static PalaceTerrainSnapshot capture(
            ServerWorld world,
            BlockPos origin,
            Direction entrance,
            int mx,
            int pathStartZ,
            int padY,
            int maxDrop,
            LocalTransform transform
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(origin, "origin");
        Map<Long, Sample> map = new LinkedHashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int s = 0; s <= maxDrop; s++) {
                int lx = mx + dx;
                int lz = pathStartZ + s;
                BlockPos worldPos = transform.localToWorld(origin, entrance, lx, padY, lz);
                int groundWorldY = world.getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldPos.getX(), worldPos.getZ());
                BlockPos below = new BlockPos(
                        worldPos.getX(),
                        Math.max(world.getBottomY(), groundWorldY - 1),
                        worldPos.getZ());
                var state = world.getBlockState(below);
                boolean water = state != null && (state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA));
                int groundLocalY = groundWorldY - origin.getY();
                map.put(pack(lx, lz), new Sample(lx, lz, groundLocalY, water));
            }
        }
        return new PalaceTerrainSnapshot(map, false);
    }

    @FunctionalInterface
    public interface LocalTransform {
        BlockPos localToWorld(BlockPos origin, Direction entrance, int x, int y, int z);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static String computeFingerprint(Map<Long, Sample> cells, boolean schematicFlat) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(schematicFlat ? (byte) 1 : (byte) 0);
            for (Sample s : cells.values()) {
                md.update(Integer.toString(s.localX()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
                md.update(Integer.toString(s.localZ()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
                md.update(Integer.toString(s.groundLocalY()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
                md.update(s.water() ? (byte) 1 : (byte) 0);
                md.update((byte) ';');
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "fp-error";
        }
    }
}
