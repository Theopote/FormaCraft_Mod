package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan-geometry occupancy from BlockPatch replay under an empty-world assumption.
 * Not a live-world snapshot: {@link CollisionProfile#UNTOUCHED} means never written by the plan.
 *
 * <p>Collision profiles distinguish full cubes from stairs/slabs/fences/carpets/nonsolids.
 * Default {@link #fromPatches(Iterable)} uses string heuristics only (bootstrap-safe).
 * {@link #fromPatches(Iterable, boolean) preferRegistry=true} refines via BlockState when the
 * registry is already initialized (compile path).
 */
public final class PlannedOccupancy {
    private static final Set<String> AIR_PATHS = Set.of("air", "cave_air", "void_air");
    private static final Set<String> NONSOLID_PATH_FRAGMENTS = Set.of(
            "water", "lava", "leaves", "grass", "fern", "vine", "snow", "seagrass",
            "kelp", "flower", "sapling", "mushroom", "torch", "fire", "bubble"
    );

    public enum CollisionProfile {
        UNTOUCHED,
        AIR,
        FULL,
        STAIR,
        SLAB_BOTTOM,
        SLAB_TOP,
        FENCE,
        CARPET,
        NONSOLID;

        public boolean isSupport() {
            return switch (this) {
                case FULL, STAIR, SLAB_BOTTOM, SLAB_TOP, CARPET -> true;
                default -> false;
            };
        }

        public boolean blocksBody() {
            return switch (this) {
                case FULL, STAIR, SLAB_BOTTOM, SLAB_TOP, FENCE -> true;
                default -> false;
            };
        }

        /**
         * Standing-surface height above the block's min Y, in eighths of a block (0..8).
         * Offline height table — not VoxelShape. Non-supports return 0.
         */
        public int supportTopEighths() {
            return switch (this) {
                case FULL, STAIR, SLAB_TOP -> 8;
                case SLAB_BOTTOM -> 4;
                case CARPET -> 1;
                default -> 0;
            };
        }
    }

    /**
     * Fast = string/type profiles + height table.
     * BlockState = every PLACE cell resolved via registry BlockState.
     * Mixed = preferRegistry path with at least one string-heuristic fallback.
     * None is VoxelShape / player-AABB collision proof.
     */
    public enum WalkabilityPrecision {
        FAST,
        BLOCKSTATE,
        MIXED
    }

    /**
     * Hook for future VoxelShape-based tops; today returns profile defaults for known types.
     */
    public static int supportTopEighths(net.minecraft.block.BlockState state, CollisionProfile fallback) {
        if (fallback != null && fallback.isSupport()) {
            return fallback.supportTopEighths();
        }
        if (state == null || state.isAir()) {
            return 0;
        }
        CollisionProfile fromState = classifyState(state, state.getBlock().getTranslationKey());
        return fromState.supportTopEighths();
    }

    /** @deprecated Use {@link CollisionProfile}; SOLID maps to FULL. */
    @Deprecated
    public enum Cell {
        SOLID,
        PLANNED_AIR,
        UNTOUCHED
    }

    private final Map<Vec3i, CollisionProfile> cells;
    private final WalkabilityPrecision precision;

    private PlannedOccupancy(Map<Vec3i, CollisionProfile> cells) {
        this(cells, WalkabilityPrecision.FAST);
    }

    private PlannedOccupancy(Map<Vec3i, CollisionProfile> cells, WalkabilityPrecision precision) {
        this.cells = cells;
        this.precision = precision == null ? WalkabilityPrecision.FAST : precision;
    }

    public WalkabilityPrecision precision() {
        return precision;
    }

    public static PlannedOccupancy fromPatches(Iterable<BlockPatch> patches) {
        return fromPatches(patches, false);
    }

    public static PlannedOccupancy fromPatches(Iterable<BlockPatch> patches, boolean preferRegistry) {
        Map<Vec3i, CollisionProfile> cells = new HashMap<>();
        if (!preferRegistry) {
            if (patches != null) {
                for (BlockPatch patch : patches) {
                    if (patch == null) {
                        continue;
                    }
                    Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
                    if (BlockPatch.REMOVE.equals(patch.action()) || isAirId(patch.targetBlock())) {
                        cells.put(pos, CollisionProfile.AIR);
                    } else {
                        cells.put(pos, classifyString(patch.targetBlock()));
                    }
                }
            }
            return new PlannedOccupancy(cells, WalkabilityPrecision.FAST);
        }
        boolean anyFallback = false;
        if (patches != null) {
            for (BlockPatch patch : patches) {
                if (patch == null) {
                    continue;
                }
                Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
                if (BlockPatch.REMOVE.equals(patch.action()) || isAirId(patch.targetBlock())) {
                    cells.put(pos, CollisionProfile.AIR);
                    continue;
                }
                Classified classified = classifyDetailed(patch.targetBlock(), true);
                cells.put(pos, classified.profile());
                if (!classified.usedBlockState()) {
                    anyFallback = true;
                }
            }
        }
        // Zero PLACE / all AIR with preferRegistry and no fallback → BLOCKSTATE (intent, no downgrade).
        WalkabilityPrecision precision = anyFallback
                ? WalkabilityPrecision.MIXED
                : WalkabilityPrecision.BLOCKSTATE;
        return new PlannedOccupancy(cells, precision);
    }

    private record Classified(CollisionProfile profile, boolean usedBlockState) {}

    private static Classified classifyDetailed(String blockId, boolean preferRegistry) {
        if (blockId == null || blockId.isBlank() || isAirId(blockId)) {
            return new Classified(CollisionProfile.AIR, preferRegistry);
        }
        if (preferRegistry) {
            try {
                BlockState state = BlockPatchTargetResolver.parse(blockId.trim());
                if (state != null) {
                    return new Classified(classifyState(state, blockId), true);
                }
            } catch (Throwable ignored) {
                // Fall through to string heuristics — do not sticky-poison Registries.
            }
        }
        return new Classified(classifyString(blockId), false);
    }

    public static PlannedOccupancy ofProfiles(Map<Vec3i, CollisionProfile> cells) {
        return new PlannedOccupancy(cells == null ? Map.of() : new HashMap<>(cells), WalkabilityPrecision.FAST);
    }

    /** @deprecated Prefer {@link #ofProfiles}. */
    @Deprecated
    public static PlannedOccupancy of(Map<Vec3i, Cell> cells) {
        Map<Vec3i, CollisionProfile> mapped = new HashMap<>();
        if (cells != null) {
            for (var e : cells.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                mapped.put(e.getKey(), switch (e.getValue()) {
                    case SOLID -> CollisionProfile.FULL;
                    case PLANNED_AIR -> CollisionProfile.AIR;
                    case UNTOUCHED -> CollisionProfile.UNTOUCHED;
                });
            }
        }
        return new PlannedOccupancy(mapped, WalkabilityPrecision.FAST);
    }

    public CollisionProfile profileAt(int x, int y, int z) {
        CollisionProfile c = cells.get(new Vec3i(x, y, z));
        return c == null ? CollisionProfile.UNTOUCHED : c;
    }

    public CollisionProfile profileAt(Vec3i pos) {
        if (pos == null) {
            return CollisionProfile.UNTOUCHED;
        }
        return profileAt(pos.x(), pos.y(), pos.z());
    }

    /** @deprecated Prefer {@link #profileAt(int, int, int)}. */
    @Deprecated
    public Cell get(int x, int y, int z) {
        CollisionProfile p = profileAt(x, y, z);
        return switch (p) {
            case AIR -> Cell.PLANNED_AIR;
            case UNTOUCHED -> Cell.UNTOUCHED;
            default -> Cell.SOLID;
        };
    }

    /** @deprecated Prefer {@link #profileAt(Vec3i)}. */
    @Deprecated
    public Cell get(Vec3i pos) {
        if (pos == null) {
            return Cell.UNTOUCHED;
        }
        return get(pos.x(), pos.y(), pos.z());
    }

    public boolean isSolid(int x, int y, int z) {
        CollisionProfile p = profileAt(x, y, z);
        return p == CollisionProfile.FULL || p == CollisionProfile.STAIR
                || p == CollisionProfile.SLAB_BOTTOM || p == CollisionProfile.SLAB_TOP;
    }

    public boolean isBodyClear(int x, int y, int z) {
        return !profileAt(x, y, z).blocksBody();
    }

    public boolean isSupport(int x, int y, int z) {
        return profileAt(x, y, z).isSupport();
    }

    public static CollisionProfile classify(String blockId, boolean preferRegistry) {
        return classifyDetailed(blockId, preferRegistry).profile();
    }

    static CollisionProfile classifyState(BlockState state, String fallbackId) {
        if (state == null || state.isAir()) {
            return CollisionProfile.AIR;
        }
        Block block = state.getBlock();
        if (block instanceof StairsBlock) {
            return CollisionProfile.STAIR;
        }
        if (block instanceof SlabBlock) {
            SlabType type = state.contains(Properties.SLAB_TYPE)
                    ? state.get(Properties.SLAB_TYPE) : SlabType.BOTTOM;
            return type == SlabType.TOP ? CollisionProfile.SLAB_TOP : CollisionProfile.SLAB_BOTTOM;
        }
        if (block instanceof FenceBlock || block instanceof WallBlock || block instanceof FenceGateBlock) {
            return CollisionProfile.FENCE;
        }
        if (block instanceof CarpetBlock) {
            return CollisionProfile.CARPET;
        }
        String path = pathOf(fallbackId);
        if (path.contains("pressure_plate") || path.contains("carpet")) {
            return CollisionProfile.CARPET;
        }
        if (isNonsolidPath(path)) {
            return CollisionProfile.NONSOLID;
        }
        return CollisionProfile.FULL;
    }

    static CollisionProfile classifyString(String blockId) {
        String path = pathOf(blockId);
        String props = propsOf(blockId);
        if (path.contains("stairs")) {
            return CollisionProfile.STAIR;
        }
        if (path.contains("slab")) {
            if (props.contains("half=top") || props.contains("type=top")) {
                return CollisionProfile.SLAB_TOP;
            }
            return CollisionProfile.SLAB_BOTTOM;
        }
        if (path.contains("fence") || path.contains("fence_gate")
                || path.endsWith("_wall") || path.equals("wall")) {
            return CollisionProfile.FENCE;
        }
        if (path.contains("carpet") || path.contains("pressure_plate")) {
            return CollisionProfile.CARPET;
        }
        if (isNonsolidPath(path)) {
            return CollisionProfile.NONSOLID;
        }
        return CollisionProfile.FULL;
    }

    private static boolean isNonsolidPath(String path) {
        for (String frag : NONSOLID_PATH_FRAGMENTS) {
            if (path.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    private static String pathOf(String blockId) {
        String raw = blockId.trim().toLowerCase(Locale.ROOT);
        int bracket = raw.indexOf('[');
        if (bracket >= 0) {
            raw = raw.substring(0, bracket);
        }
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }

    private static String propsOf(String blockId) {
        String raw = blockId.trim().toLowerCase(Locale.ROOT);
        int lb = raw.indexOf('[');
        int rb = raw.lastIndexOf(']');
        if (lb >= 0 && rb > lb) {
            return raw.substring(lb + 1, rb);
        }
        return "";
    }

    /**
     * Strict air-id check: path segment must be {@code air}, {@code cave_air}, or {@code void_air}.
     * Never substring-match {@code "air"} (mis-classifies {@code *_stairs}).
     */
    public static boolean isAirId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return true;
        }
        return AIR_PATHS.contains(pathOf(blockId));
    }
}
