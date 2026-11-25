package com.formacraft.common.compiler.postprocess;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.SubVoxelBlockStates;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.AestheticPassHints;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PLACE_ONLY architectural lighting: entrances, eaves, and interior ceiling fixtures.
 * Disabled when {@code lighting_style=none} or plan/component {@code no_complex_decor}.
 */
public class LightingPostProcessor implements PostProcessor {
    private static final int MAX_LIGHTS = 400;

    @Override
    public PostProcessWritePolicy writePolicy() {
        return PostProcessWritePolicy.PLACE_ONLY;
    }

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty() || context == null) return patches;

        AestheticPassHints.Resolved hints = AestheticPassHints.resolve(context.plan(), null);
        // null = INVALID/unresolved aesthetic token — treat as opt-out (no NPE, no invented style).
        if (hints.lightingStyle() == null
                || hints.lightingStyle() == AestheticPassHints.LightingStyle.NONE) {
            return patches;
        }
        if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(context.plan(), null)) {
            return patches;
        }
        // Terrain-preserve / underground halls: do not emit exterior wall torches outside
        // the authored excavation envelope.
        if (context.plan() != null && context.plan().globalConstraints() != null
                && context.plan().globalConstraints().terrainStrategy()
                == com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.PRESERVE) {
            return patches;
        }
        // Per-mass lighting_style=none / no_complex_decor must win even when plan-level
        // AestheticPassHints still resolve to a style default (DesignDecisionPriority.USER_EXPLICIT).
        // Phase 01.9: AABB contains against compile-frame volumes — no HashSet cell expand.
        List<PostProcessContext.BuildingVolume> decorOptOutVolumes = lightingOptOutVolumes(context);

        Map<Long, String> solid = new HashMap<>();
        Set<Long> air = new HashSet<>();
        List<DoorHint> doors = new ArrayList<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            long key = pack(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action()) || isAir(p.targetBlock())) {
                solid.remove(key);
                air.add(key);
                continue;
            }
            air.remove(key);
            solid.put(key, p.targetBlock());
            if (isDoor(p.targetBlock()) && !isUpperDoorHalf(p.targetBlock())) {
                doors.add(new DoorHint(p.dx(), p.dy(), p.dz(), p.targetBlock()));
            }
        }

        String hanging = lightBlock(hints.lightingStyle(), true);
        String standing = lightBlock(hints.lightingStyle(), false);
        AestheticPassHints.LightingDensity density = hints.lightingDensity() == null
                ? AestheticPassHints.LightingDensity.NORMAL : hints.lightingDensity();
        int cadence = switch (density) {
            case LOW -> 10;
            case HIGH -> 5;
            default -> 7;
        };

        List<BlockPatch> singles = new ArrayList<>();
        List<HangPair> hangPairs = new ArrayList<>();
        Set<BlockPos> clearance = context.protectedClearance() != null ? context.protectedClearance() : Set.of();

        // Entrance flanking lights (outside the door plane, lower half only)
        for (DoorHint door : doors) {
            if (inOptOutVolume(decorOptOutVolumes, door.x, door.y, door.z)) continue;
            Direction out = guessDoorOutward(door, solid);
            int lx = door.x + out.getOffsetX();
            int lz = door.z + out.getOffsetZ();
            int ly = door.y + 2;
            placeSupportedLight(singles, solid, clearance, lx + perpX(out), ly, lz + perpZ(out),
                    hanging, standing, out);
            placeSupportedLight(singles, solid, clearance, lx - perpX(out), ly, lz - perpZ(out),
                    hanging, standing, out);
            if (lightBudget(singles, hangPairs) >= MAX_LIGHTS) break;
        }

        // Eaves / volume perimeter lights — skip only opted-out mass AABBs (same-slot siblings stay lit).
        if (context.buildingVolumes() != null) {
            for (var volume : context.buildingVolumes()) {
                if (volume == null) continue;
                if (isOptedOutBuildingVolume(decorOptOutVolumes, volume)) continue;
                var b = volume.bounds();
                int minX = b.minX(), maxX = b.maxX() - 1;
                int minY = b.minY(), maxY = b.maxY() - 1;
                int minZ = b.minZ(), maxZ = b.maxZ() - 1;
                int y = Math.max(minY + 2, maxY - 1);
                for (int x = minX; x <= maxX; x += cadence) {
                    placeSupportedLight(singles, solid, clearance, x, y, minZ - 1, hanging, standing, Direction.NORTH);
                    placeSupportedLight(singles, solid, clearance, x, y, maxZ + 1, hanging, standing, Direction.SOUTH);
                    if (lightBudget(singles, hangPairs) >= MAX_LIGHTS) break;
                }
                for (int z = minZ; z <= maxZ; z += cadence) {
                    placeSupportedLight(singles, solid, clearance, minX - 1, y, z, hanging, standing, Direction.WEST);
                    placeSupportedLight(singles, solid, clearance, maxX + 1, y, z, hanging, standing, Direction.EAST);
                    if (lightBudget(singles, hangPairs) >= MAX_LIGHTS) break;
                }

                // Interior ceiling grid
                int floorH = Math.max(3, volume.floorHeight());
                for (int floorY = minY; floorY + floorH <= maxY + 1; floorY += floorH) {
                    int ceilY = Math.min(maxY, floorY + floorH - 1);
                    int storeyH = ceilY - floorY + 1;
                    for (int x = minX + 2; x <= maxX - 2; x += cadence) {
                        for (int z = minZ + 2; z <= maxZ - 2; z += cadence) {
                            if (!solid.containsKey(pack(x, ceilY, z))) continue;
                            if (storeyH >= 5) {
                                // Hang link + lantern as one atomic fixture (P1-05).
                                placeHangFixture(hangPairs, singles, solid, clearance, x, ceilY, z,
                                        hangLinkBlock(), hanging);
                            } else if (storeyH >= 4) {
                                placeIfAir(singles, solid, clearance, x, ceilY - 1, z, hanging);
                            } else {
                                // headroom: flush / glow item under ceiling
                                placeIfAir(singles, solid, clearance, x, ceilY - 1, z,
                                        hints.lightingStyle() == AestheticPassHints.LightingStyle.MODERN_SLEEK
                                                ? "minecraft:sea_lantern" : hanging);
                            }
                            if (lightBudget(singles, hangPairs) >= MAX_LIGHTS) break;
                        }
                    }
                }
            }
        }

        if (singles.isEmpty() && hangPairs.isEmpty()) return patches;

        Set<BlockPos> seeds = new HashSet<>();
        for (BlockPatch p : patches) {
            if (p == null || BlockPatch.REMOVE.equals(p.action())) continue;
            seeds.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        }
        // Prefer pipeline-frozen authority (P1-04); step seeds are fallback for direct calls.
        PostProcessWriteScope scope = PostProcessWriteScope.forStep(context, seeds);
        singles.removeIf(p -> p == null
                || !scope.allowsPlace(new BlockPos(p.dx(), p.dy(), p.dz())));
        // Hang pair: both cells must be in scope or the whole fixture is dropped.
        hangPairs.removeIf(pair -> pair == null
                || !scope.allowsPlace(new BlockPos(pair.link().dx(), pair.link().dy(), pair.link().dz()))
                || !scope.allowsPlace(new BlockPos(pair.fixture().dx(), pair.fixture().dy(), pair.fixture().dz())));
        if (singles.isEmpty() && hangPairs.isEmpty()) return patches;

        List<BlockPatch> lights = new ArrayList<>(singles.size() + hangPairs.size() * 2);
        lights.addAll(singles);
        for (HangPair pair : hangPairs) {
            lights.add(pair.link());
            lights.add(pair.fixture());
        }

        List<BlockPatch> merged = new ArrayList<>(patches.size() + lights.size());
        merged.addAll(patches);
        merged.addAll(lights);
        FormacraftMod.LOGGER.debug("LightingPostProcessor: added {} lights", lights.size());
        return merged;
    }

    private static String hangLinkBlock() {
        var id = net.minecraft.util.Identifier.of("minecraft:chain");
        if (net.minecraft.registry.Registries.BLOCK.containsId(id)) {
            return "minecraft:chain";
        }
        return "minecraft:iron_bars";
    }

    private static String lightBlock(AestheticPassHints.LightingStyle style, boolean hanging) {
        return switch (style) {
            case SOUL_GOTHIC -> hanging
                    ? SubVoxelBlockStates.hangingLantern(true)
                    : SubVoxelBlockStates.standingLantern(true);
            case MODERN_SLEEK -> hanging ? "minecraft:end_rod[facing=down]" : "minecraft:end_rod[facing=up]";
            case WARM_LANTERN, NONE -> hanging
                    ? SubVoxelBlockStates.hangingLantern(false)
                    : SubVoxelBlockStates.standingLantern(false);
        };
    }

    /**
     * Place a light only when it has Minecraft-legal support: floor for standing,
     * block above for hanging, else wall torch on the façade behind; otherwise skip
     * (bare hanging lanterns drop as items on block updates).
     */
    /**
     * Volumes that must not receive auto lights (Phase 01 closeout P1-02).
     * Prefer compile-time {@link PostProcessContext.BuildingVolume#lightingAllowed()} —
     * never guess ownership by overlapping local Component AABB vs slot-shifted volume.
     */
    private static List<PostProcessContext.BuildingVolume> lightingOptOutVolumes(PostProcessContext context) {
        List<PostProcessContext.BuildingVolume> out = new ArrayList<>();
        if (context == null || context.buildingVolumes() == null) return out;
        boolean anyIdentity = false;
        for (var volume : context.buildingVolumes()) {
            if (volume == null) continue;
            if (volume.componentId() != null || volume.buildingId() != null || !volume.lightingAllowed()) {
                anyIdentity = true;
            }
            if (!volume.lightingAllowed()) {
                out.add(volume);
            }
        }
        if (anyIdentity) {
            return out;
        }
        // Legacy volumes without identity: fall back to plan-param opt-out matched by
        // componentId/buildingId when present on the plan, else skip (do not AABB-guess).
        if (context.plan() == null || context.plan().components() == null) return out;
        Set<String> optOutIds = new HashSet<>();
        for (var c : context.plan().components()) {
            if (c == null || c.params() == null) continue;
            boolean decorOff = com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(
                    context.plan(), c.params());
            AestheticPassHints.Resolved local = AestheticPassHints.resolve(context.plan(), c.params());
            boolean lightsOff = local.lightingStyle() == AestheticPassHints.LightingStyle.NONE;
            if (!decorOff && !lightsOff) continue;
            String cid = c.params().get("component_id") == null ? null
                    : String.valueOf(c.params().get("component_id"));
            String bid = c.params().get("building_id") == null ? null
                    : String.valueOf(c.params().get("building_id"));
            if (cid != null && !cid.isBlank()) optOutIds.add(cid);
            if (bid != null && !bid.isBlank()) optOutIds.add(bid);
            if (c.slotId() != null && !c.slotId().isBlank()) optOutIds.add(c.slotId().trim());
        }
        if (optOutIds.isEmpty()) return out;
        for (var volume : context.buildingVolumes()) {
            if (volume == null) continue;
            String slot = volume.slotId() == null ? "" : volume.slotId().trim();
            if (optOutIds.contains(slot)) out.add(volume);
        }
        return out;
    }

    private static boolean isOptedOutBuildingVolume(
            List<PostProcessContext.BuildingVolume> optOut, PostProcessContext.BuildingVolume volume) {
        if (optOut == null || volume == null || optOut.isEmpty()) return false;
        if (!volume.lightingAllowed()) return true;
        for (var o : optOut) {
            if (o == null) continue;
            if (o.equals(volume)) return true;
            if (volume.componentId() != null && volume.componentId().equals(o.componentId())) return true;
            if (volume.buildingId() != null && volume.buildingId().equals(o.buildingId())
                    && java.util.Objects.equals(volume.bounds(), o.bounds())) return true;
        }
        return false;
    }

    private static boolean inOptOutVolume(List<PostProcessContext.BuildingVolume> volumes, int x, int y, int z) {
        if (volumes == null || volumes.isEmpty()) return false;
        for (var v : volumes) {
            if (v == null) continue;
            // Include a 1-block horizontal pad for door/eave adjacency without cell enumeration.
            var b = v.bounds().expandHorizontal(1);
            if (x >= b.minX() && x < b.maxX() && y >= b.minY() && y < b.maxY()
                    && z >= b.minZ() && z < b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static void placeSupportedLight(List<BlockPatch> out, Map<Long, String> solid, Set<BlockPos> clearance,
                                            int x, int y, int z, String hanging, String standing, Direction outDir) {
        if (looksLikeOpenYardColumn(solid, x, y, z)) return;
        if (solid.containsKey(pack(x, y - 1, z))) {
            placeIfAir(out, solid, clearance, x, y, z, standing);
            return;
        }
        if (solid.containsKey(pack(x, y + 1, z))) {
            placeIfAir(out, solid, clearance, x, y, z, hanging);
            return;
        }
        if (solid.containsKey(pack(x - outDir.getOffsetX(), y, z - outDir.getOffsetZ()))) {
            placeIfAir(out, solid, clearance, x, y, z, SubVoxelBlockStates.wallTorch(outDir));
        }
    }

    private static void placeIfAir(List<BlockPatch> out, Map<Long, String> solid, Set<BlockPos> clearance,
                                   int x, int y, int z, String block) {
        if (block == null || block.isBlank() || out.size() >= MAX_LIGHTS) return; // singles-only budget check at call sites
        BlockPos pos = new BlockPos(x, y, z);
        if (clearance.contains(pos)) return;
        if (looksLikeOpenYardColumn(solid, x, y, z)) return;
        long key = pack(x, y, z);
        if (solid.containsKey(key)) return;
        out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
        solid.put(key, block);
    }

    /**
     * Ceiling hang-link + fixture must commit together. If either cell is blocked /
     * cleared / at capacity, place neither (P1-05 atomicity).
     */
    private static void placeHangFixture(List<HangPair> pairs, List<BlockPatch> singles,
                                         Map<Long, String> solid, Set<BlockPos> clearance,
                                         int x, int ceilY, int z, String link, String fixture) {
        if (link == null || link.isBlank() || fixture == null || fixture.isBlank()) return;
        if (lightBudget(singles, pairs) + 2 > MAX_LIGHTS) return;
        int linkY = ceilY - 1;
        int fixtureY = ceilY - 2;
        if (!canOccupyAir(solid, clearance, x, linkY, z)) return;
        if (!canOccupyAir(solid, clearance, x, fixtureY, z)) return;
        BlockPatch linkPatch = new BlockPatch(BlockPatch.PLACE, x, linkY, z, link);
        BlockPatch fixturePatch = new BlockPatch(BlockPatch.PLACE, x, fixtureY, z, fixture);
        pairs.add(new HangPair(linkPatch, fixturePatch));
        solid.put(pack(x, linkY, z), link);
        solid.put(pack(x, fixtureY, z), fixture);
    }

    private static boolean canOccupyAir(Map<Long, String> solid, Set<BlockPos> clearance, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (clearance.contains(pos)) return false;
        if (looksLikeOpenYardColumn(solid, x, y, z)) return false;
        return !solid.containsKey(pack(x, y, z));
    }

    private static int lightBudget(List<BlockPatch> singles, List<HangPair> pairs) {
        int n = singles == null ? 0 : singles.size();
        n += pairs == null ? 0 : pairs.size() * 2;
        return n;
    }

    private record HangPair(BlockPatch link, BlockPatch fixture) {}

    /**
     * Skywell / courtyard: authored paving under an open air column. Wing-facing eaves must
     * not hang wall torches into that void (ConstraintParity huizhou open-sky assertion).
     */
    private static boolean looksLikeOpenYardColumn(Map<Long, String> solid, int x, int y, int z) {
        int padY = Integer.MIN_VALUE;
        for (int gy = Math.min(y, 4); gy >= -8; gy--) {
            if (solid.containsKey(pack(x, gy, z))) {
                padY = gy;
                break;
            }
        }
        if (padY == Integer.MIN_VALUE) return false;
        int openTo = Math.max(y + 6, padY + 8);
        for (int iy = padY + 1; iy <= openTo; iy++) {
            if (solid.containsKey(pack(x, iy, z))) return false;
        }
        return true;
    }

    private static Direction guessDoorOutward(DoorHint door, Map<Long, String> solid) {
        // Prefer horizontal facing from block state; else toward empty neighbour.
        String id = door.blockId.toLowerCase(Locale.ROOT);
        for (Direction d : new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}) {
            if (id.contains("facing=" + d.asString())) return d;
        }
        for (Direction d : new Direction[]{Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}) {
            if (!solid.containsKey(pack(door.x + d.getOffsetX(), door.y, door.z + d.getOffsetZ()))) {
                return d;
            }
        }
        return Direction.SOUTH;
    }

    private static int perpX(Direction out) {
        return out.getAxis() == Direction.Axis.Z ? 1 : 0;
    }

    private static int perpZ(Direction out) {
        return out.getAxis() == Direction.Axis.X ? 1 : 0;
    }

    private static boolean isDoor(String id) {
        if (id == null) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("_door") && !lower.contains("trapdoor");
    }

    private static boolean isUpperDoorHalf(String id) {
        if (id == null) return false;
        return id.toLowerCase(Locale.ROOT).contains("half=upper");
    }

    private static boolean isAir(String id) {
        return id == null || id.isBlank()
                || id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1fffffL);
    }

    private record DoorHint(int x, int y, int z, String blockId) {}
}
