package com.formacraft.common.compiler.postprocess;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.SubVoxelBlockStates;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.AestheticPassHints;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Volume-aware façade relief: plinth, pilasters, belt courses, cornice brackets (+1 extrusion).
 */
public class DetailEnhancementPostProcessor implements PostProcessor {
    private static final int MAX_OUTWARD_PER_VOLUME = 800;

    @Override
    public PostProcessWritePolicy writePolicy() {
        return PostProcessWritePolicy.REPLACE_WITHIN_SCOPE;
    }

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty() || context == null) {
            return patches;
        }

        AestheticPassHints.Resolved hints = AestheticPassHints.resolve(context.plan(), null);
        if (hints.facadeRelief() == AestheticPassHints.FacadeRelief.FLAT) {
            return patches;
        }
        // Underground / terrain-preserve halls must not grow +1 exterior pilasters outside the
        // authored envelope (ConstraintParity dwarven excavation bounds).
        if (context.plan() != null && context.plan().globalConstraints() != null
                && context.plan().globalConstraints().terrainStrategy()
                == com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.PRESERVE) {
            return patches;
        }

        Map<Long, BlockPatch> solid = foldSolid(patches);
        if (solid.isEmpty()) return patches;
        // Courtyard/skywell detection must use pre-enhancement occupancy — a pilaster base
        // must not be mistaken for yard paving that would suppress the shaft above it.
        Map<Long, BlockPatch> authoredSolid = Map.copyOf(solid);

        String styleProfile = context.plan() != null && context.plan().styleProfile() != null
                ? context.plan().styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        String wallBase = palette.pick(SemanticPart.WALL_BASE);
        if (wallBase == null || wallBase.isBlank()) wallBase = "minecraft:stone_bricks";
        String accent = palette.pick(SemanticPart.WALL_ACCENT);
        if (accent == null || accent.isBlank()) accent = wallBase;
        String stairs = MaterialBlockFamily.stairs(wallBase);
        if (stairs == null) stairs = MaterialBlockFamily.stairsOrStoneBrick(wallBase);
        String slab = MaterialBlockFamily.slab(wallBase);
        if (slab == null) slab = "minecraft:stone_brick_slab";
        String wallShape = MaterialBlockFamily.wall(accent);
        if (wallShape == null) wallShape = accent;

        List<BlockPatch> enhancements = new ArrayList<>();
        List<PostProcessContext.BuildingVolume> volumes = context.buildingVolumes();
        if (volumes == null || volumes.isEmpty()) {
            // No compiler volume metadata → do not invent a global AABB ring
            // (StyleFeedbackRegressionTest: implicit decoration must not expand the footprint).
            return patches;
        }

        boolean deep = hints.facadeRelief() == AestheticPassHints.FacadeRelief.DEEP;
        Set<BlockPos> clearance = context.protectedClearance() != null ? context.protectedClearance() : Set.of();
        Set<BlockPos> decoRestrict = context.decorationRestrictions() != null
                ? context.decorationRestrictions() : Set.of();

        for (PostProcessContext.BuildingVolume volume : volumes) {
            int placed = 0;
            var b = volume.bounds();
            int minX = b.minX(), maxX = b.maxX() - 1;
            int minY = b.minY(), maxY = b.maxY() - 1;
            int minZ = b.minZ(), maxZ = b.maxZ() - 1;
            int floorH = Math.max(3, volume.floorHeight());

            // Corner pilasters before plinth so stair bases are not blocked by the slab ring
            if (deep || hints.facadeRelief() == AestheticPassHints.FacadeRelief.SUBTLE) {
                placed += emitCornerPilasters(enhancements, solid, authoredSolid, clearance, decoRestrict,
                        minX, maxX, minY, maxY, minZ, maxZ, wallShape, stairs, deep,
                        MAX_OUTWARD_PER_VOLUME - placed);
            }

            // Base plinth (+1 outward slabs)
            placed += emitPlinth(enhancements, solid, authoredSolid, clearance, decoRestrict,
                    minX, maxX, minY, maxY, minZ, maxZ, SubVoxelBlockStates.slab(slab, false),
                    MAX_OUTWARD_PER_VOLUME - placed);

            // Belt courses at each floor junction
            for (int y = minY + floorH; y < maxY; y += floorH) {
                placed += emitBelt(enhancements, solid, authoredSolid, clearance, decoRestrict,
                        minX, maxX, y, minZ, maxZ, minY, maxY, SubVoxelBlockStates.slab(slab, true),
                        MAX_OUTWARD_PER_VOLUME - placed);
            }

            // Cornice brackets under top edge (per-volume budget only — never break the volume loop)
            placed += emitCornice(enhancements, solid, authoredSolid, clearance, decoRestrict,
                    minX, maxX, maxY, minZ, maxZ, minY, stairs, MAX_OUTWARD_PER_VOLUME - placed);
        }

        if (enhancements.isEmpty()) return patches;

        Set<BlockPos> seeds = new HashSet<>();
        for (BlockPatch p : patches) {
            if (p == null || BlockPatch.REMOVE.equals(p.action())) continue;
            seeds.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        }
        // Prefer pipeline-frozen authority (P1-04); step seeds are fallback for direct calls.
        PostProcessWriteScope scope = PostProcessWriteScope.forStep(context, seeds);
        enhancements.removeIf(p -> p == null
                || !scope.allowsPlace(new BlockPos(p.dx(), p.dy(), p.dz())));
        if (enhancements.isEmpty()) return patches;

        List<BlockPatch> result = new ArrayList<>(patches.size() + enhancements.size());
        result.addAll(patches);
        result.addAll(enhancements);
        FormacraftMod.LOGGER.debug("DetailEnhancementPostProcessor: added {} relief patches", enhancements.size());
        return result;
    }

    private static int emitPlinth(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                  Map<Long, BlockPatch> authoredSolid,
                                  Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                  int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                  String block, int budget) {
        if (budget <= 0 || block == null) return 0;
        int n = 0;
        for (int x = minX; x <= maxX; x++) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, minY, minZ - 1, block, budget - n, minY, maxY);
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, minY, maxZ + 1, block, budget - n, minY, maxY);
        }
        for (int z = minZ; z <= maxZ; z++) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, minX - 1, minY, z, block, budget - n, minY, maxY);
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, maxX + 1, minY, z, block, budget - n, minY, maxY);
        }
        return n;
    }

    private static int emitCornerPilasters(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                           Map<Long, BlockPatch> authoredSolid,
                                           Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                           int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                           String shaft, String stairs, boolean deep, int budget) {
        int n = 0;
        int[][] corners = {{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}};
        int[][] outward = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
        for (int i = 0; i < corners.length; i++) {
            int cx = corners[i][0], cz = corners[i][1];
            int ox = outward[i][0];
            int oz = outward[i][1];
            // Both adjacent outward normals so N/S faces are not skipped
            Direction dirX = ox < 0 ? Direction.WEST : Direction.EAST;
            Direction dirZ = oz < 0 ? Direction.NORTH : Direction.SOUTH;
            n += emitOnePilaster(out, solid, authoredSolid, clearance, decoRestrict,
                    cx + dirX.getOffsetX(), cz + dirX.getOffsetZ(),
                    minY, maxY, shaft, stairs, deep, dirX, budget - n);
            n += emitOnePilaster(out, solid, authoredSolid, clearance, decoRestrict,
                    cx + dirZ.getOffsetX(), cz + dirZ.getOffsetZ(),
                    minY, maxY, shaft, stairs, deep, dirZ, budget - n);
        }
        return n;
    }

    private static int emitOnePilaster(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                       Map<Long, BlockPatch> authoredSolid,
                                       Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                       int px, int pz, int minY, int maxY,
                                       String shaft, String stairs, boolean deep, Direction dir, int budget) {
        if (budget <= 0) return 0;
        int n = 0;
        String base = SubVoxelBlockStates.outwardStairs(stairs, dir, false);
        String cap = SubVoxelBlockStates.outwardStairs(stairs, dir, true);
        if (base != null) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, px, minY, pz, base, budget - n, minY, maxY);
        }
        int top = deep ? maxY : Math.min(maxY, minY + Math.max(4, (maxY - minY) / 2));
        for (int y = minY + 1; y < top; y++) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, px, y, pz, shaft, budget - n, minY, maxY);
        }
        if (cap != null && top <= maxY) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, px, top, pz, cap, budget - n, minY, maxY);
        }
        return n;
    }

    private static int emitBelt(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                Map<Long, BlockPatch> authoredSolid,
                                Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                int minX, int maxX, int y, int minZ, int maxZ,
                                int minY, int maxY, String block, int budget) {
        if (budget <= 0 || block == null) return 0;
        int n = 0;
        for (int x = minX; x <= maxX; x++) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, y, minZ - 1, block, budget - n, minY, maxY);
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, y, maxZ + 1, block, budget - n, minY, maxY);
        }
        for (int z = minZ; z <= maxZ; z++) {
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, minX - 1, y, z, block, budget - n, minY, maxY);
            n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, maxX + 1, y, z, block, budget - n, minY, maxY);
        }
        return n;
    }

    private static int emitCornice(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                   Map<Long, BlockPatch> authoredSolid,
                                   Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                   int minX, int maxX, int maxY, int minZ, int maxZ,
                                   int minY, String stairs, int budget) {
        if (budget <= 0) return 0;
        int n = 0;
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction d : dirs) {
            String bracket = SubVoxelBlockStates.outwardStairs(stairs, d, true);
            if (bracket == null) continue;
            if (d == Direction.NORTH || d == Direction.SOUTH) {
                int z = d == Direction.NORTH ? minZ - 1 : maxZ + 1;
                for (int x = minX; x <= maxX; x += 2) {
                    // Skip only when roof already occupies the cornice cell's column;
                    // require the wall cell itself at (x, maxY, inward).
                    if (solid.containsKey(pack(x, maxY + 1, z))) continue;
                    if (!solid.containsKey(pack(x, maxY, z - d.getOffsetZ()))) continue;
                    n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, maxY, z, bracket, budget - n, minY, maxY);
                }
            } else {
                int x = d == Direction.WEST ? minX - 1 : maxX + 1;
                for (int z = minZ; z <= maxZ; z += 2) {
                    if (solid.containsKey(pack(x, maxY + 1, z))) continue;
                    if (!solid.containsKey(pack(x - d.getOffsetX(), maxY, z))) continue;
                    n += tryPlaceOutward(out, solid, authoredSolid, clearance, decoRestrict, x, maxY, z, bracket, budget - n, minY, maxY);
                }
            }
        }
        return n;
    }

    /**
     * Place one outward relief cell. Mid-air shafts into courtyard / skywell columns
     * (authored ground + open column, no wall attachment) are skipped so wing-facing
     * "outward" faces do not fill internal yards. Courtyard checks use {@code authoredSolid}.
     */
    private static int tryPlaceOutward(List<BlockPatch> out, Map<Long, BlockPatch> solid,
                                       Map<Long, BlockPatch> authoredSolid,
                                       Set<BlockPos> clearance, Set<BlockPos> decoRestrict,
                                       int x, int y, int z, String block, int budget,
                                       int volumeMinY, int volumeMaxY) {
        if (budget <= 0 || block == null || block.isBlank()) return 0;
        long key = pack(x, y, z);
        if (solid.containsKey(key)) return 0;
        BlockPos pos = new BlockPos(x, y, z);
        if (clearance.contains(pos) || decoRestrict.contains(pos)) return 0;
        // Avoid door bays: skip if adjacent to clearance
        for (Direction d : Direction.values()) {
            if (clearance.contains(pos.offset(d))) return 0;
        }
        // Courtyard / skywell: authored paving + open air above. Wing-adjacent courtyard
        // cells still have a wall neighbor — do NOT exempt them, or pilaster shafts fill the yard.
        // True exterior façades rarely have authored ground pad at the outward cell.
        if (y > volumeMinY
                && hasGroundPad(authoredSolid, x, z, volumeMinY)
                && openColumn(authoredSolid, x, z, y, volumeMaxY)) {
            return 0;
        }
        out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
        solid.put(key, out.get(out.size() - 1));
        return 1;
    }

    private static boolean hasGroundPad(Map<Long, BlockPatch> solid, int x, int z, int minY) {
        return solid.containsKey(pack(x, minY, z)) || solid.containsKey(pack(x, minY - 1, z));
    }

    private static boolean hasHorizontalNeighbor(Map<Long, BlockPatch> solid, int x, int y, int z) {
        return solid.containsKey(pack(x + 1, y, z))
                || solid.containsKey(pack(x - 1, y, z))
                || solid.containsKey(pack(x, y, z + 1))
                || solid.containsKey(pack(x, y, z - 1));
    }

    private static boolean openColumn(Map<Long, BlockPatch> solid, int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            if (solid.containsKey(pack(x, y, z))) return false;
        }
        return true;
    }

    private static Map<Long, BlockPatch> foldSolid(List<BlockPatch> patches) {
        Map<Long, BlockPatch> solid = new HashMap<>();
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            long key = pack(patch.dx(), patch.dy(), patch.dz());
            String target = patch.targetBlock();
            if (BlockPatch.REMOVE.equals(patch.action()) || "minecraft:air".equals(target)
                    || "minecraft:cave_air".equals(target) || "minecraft:void_air".equals(target)) {
                solid.remove(key);
            } else if (target != null && !target.isBlank()) {
                solid.put(key, patch);
            }
        }
        return solid;
    }

    private static int[] aabbOf(Map<Long, BlockPatch> solid) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPatch p : solid.values()) {
            minX = Math.min(minX, p.dx()); minY = Math.min(minY, p.dy()); minZ = Math.min(minZ, p.dz());
            maxX = Math.max(maxX, p.dx()); maxY = Math.max(maxY, p.dy()); maxZ = Math.max(maxZ, p.dz());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1fffffL);
    }
}
