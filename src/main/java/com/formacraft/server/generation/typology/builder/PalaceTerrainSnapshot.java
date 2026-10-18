package com.formacraft.server.generation.typology.builder;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable approach-corridor terrain profile shared by Preview and Apply.
 * {@code schematicFlat=true} is only for explicitly marked non-executable schematic previews.
 * <p>
 * {@link #contentHash()} is a deterministic hash of terrain samples only (sorted by local X,Z).
 * It must not be used alone as a build-plan identity — see {@link PalaceBuildContextFingerprint}.
 */
public final class PalaceTerrainSnapshot {

    /** Bump when sample encoding changes. */
    public static final int HASH_VERSION = 1;

    public record Sample(int localX, int localZ, int groundLocalY, boolean water) {}

    private final Map<Long, Sample> cells;
    private final String contentHash;
    private final boolean schematicFlat;

    private PalaceTerrainSnapshot(Map<Long, Sample> cells, boolean schematicFlat) {
        // Store in sorted LinkedHashMap so iteration order is always deterministic.
        List<Sample> sorted = new ArrayList<>(cells.values());
        sorted.sort(Comparator.comparingInt(Sample::localX).thenComparingInt(Sample::localZ));
        LinkedHashMap<Long, Sample> ordered = new LinkedHashMap<>(sorted.size());
        for (Sample s : sorted) {
            ordered.put(pack(s.localX(), s.localZ()), s);
        }
        this.cells = Map.copyOf(ordered);
        this.schematicFlat = schematicFlat;
        this.contentHash = computeContentHash(sorted, schematicFlat);
    }

    /** Deterministic terrain-content hash (not a full build-context identity). */
    public String contentHash() {
        return contentHash;
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
        // Insert in reverse order deliberately — hash must ignore insertion order.
        for (int dx = 1; dx >= -1; dx--) {
            for (int s = maxDrop; s >= 0; s--) {
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

    /** Rebuild from samples (any insertion order) — for determinism tests. */
    public static PalaceTerrainSnapshot fromSamples(List<Sample> samples, boolean schematicFlat) {
        Map<Long, Sample> map = new LinkedHashMap<>();
        if (samples != null) {
            for (Sample s : samples) {
                if (s != null) {
                    map.put(pack(s.localX(), s.localZ()), s);
                }
            }
        }
        return new PalaceTerrainSnapshot(map, schematicFlat);
    }

    @FunctionalInterface
    public interface LocalTransform {
        BlockPos localToWorld(BlockPos origin, Direction entrance, int x, int y, int z);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * Fixed binary encoding: version | schematic | n | repeating (x:i32,z:i32,y:i32,water:u8),
     * over samples sorted by (x,z).
     */
    static String computeContentHash(List<Sample> sortedSamples, boolean schematicFlat) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer hdr = ByteBuffer.allocate(9);
            hdr.putInt(HASH_VERSION);
            hdr.put(schematicFlat ? (byte) 1 : (byte) 0);
            hdr.putInt(sortedSamples.size());
            md.update(hdr.array());
            ByteBuffer row = ByteBuffer.allocate(13);
            for (Sample s : sortedSamples) {
                row.clear();
                row.putInt(s.localX());
                row.putInt(s.localZ());
                row.putInt(s.groundLocalY());
                row.put(s.water() ? (byte) 1 : (byte) 0);
                md.update(row.array());
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            return "fp-error";
        }
    }

    /** Package helper for tests that need the raw encoding path. */
    static String hashSamplesForTest(List<Sample> samples, boolean schematicFlat) {
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingInt(Sample::localX).thenComparingInt(Sample::localZ));
        return computeContentHash(sorted, schematicFlat);
    }

    @Override
    public String toString() {
        return "PalaceTerrainSnapshot{schematic=" + schematicFlat
                + ", n=" + cells.size()
                + ", contentHash=" + contentHash + "}";
    }
}
