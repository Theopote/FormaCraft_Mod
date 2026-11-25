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
        if (hints.lightingStyle() == AestheticPassHints.LightingStyle.NONE) {
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
        Set<Long> decorOptOutVolumes = decorOptOutCells(context);
        decorOptOutVolumes.addAll(lightingOptOutCells(context));

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
        int cadence = switch (hints.lightingDensity()) {
            case LOW -> 10;
            case HIGH -> 5;
            default -> 7;
        };

        List<BlockPatch> lights = new ArrayList<>();
        Set<BlockPos> clearance = context.protectedClearance() != null ? context.protectedClearance() : Set.of();

        // Entrance flanking lights (outside the door plane, lower half only)
        for (DoorHint door : doors) {
            Direction out = guessDoorOutward(door, solid);
            int lx = door.x + out.getOffsetX();
            int lz = door.z + out.getOffsetZ();
            int ly = door.y + 2;
            placeSupportedLight(lights, solid, clearance, lx + perpX(out), ly, lz + perpZ(out),
                    hanging, standing, out);
            placeSupportedLight(lights, solid, clearance, lx - perpX(out), ly, lz - perpZ(out),
                    hanging, standing, out);
            if (lights.size() >= MAX_LIGHTS) break;
        }

        // Eaves / volume perimeter lights
        if (context.buildingVolumes() != null) {
            for (var volume : context.buildingVolumes()) {
                var b = volume.bounds();
                int minX = b.minX(), maxX = b.maxX() - 1;
                int minY = b.minY(), maxY = b.maxY() - 1;
                int minZ = b.minZ(), maxZ = b.maxZ() - 1;
                int y = Math.max(minY + 2, maxY - 1);
                for (int x = minX; x <= maxX; x += cadence) {
                    placeSupportedLight(lights, solid, clearance, x, y, minZ - 1, hanging, standing, Direction.NORTH);
                    placeSupportedLight(lights, solid, clearance, x, y, maxZ + 1, hanging, standing, Direction.SOUTH);
                    if (lights.size() >= MAX_LIGHTS) break;
                }
                for (int z = minZ; z <= maxZ; z += cadence) {
                    placeSupportedLight(lights, solid, clearance, minX - 1, y, z, hanging, standing, Direction.WEST);
                    placeSupportedLight(lights, solid, clearance, maxX + 1, y, z, hanging, standing, Direction.EAST);
                    if (lights.size() >= MAX_LIGHTS) break;
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
                                // Hang link + lantern. Yarn test runtime may omit minecraft:chain
                                // (DenseEavesPagodaBuilder same fallback) — never emit an unresolvable id.
                                placeIfAir(lights, solid, clearance, x, ceilY - 1, z, hangLinkBlock());
                                placeIfAir(lights, solid, clearance, x, ceilY - 2, z, hanging);
                            } else if (storeyH >= 4) {
                                placeIfAir(lights, solid, clearance, x, ceilY - 1, z, hanging);
                            } else {
                                // headroom: flush / glow item under ceiling
                                placeIfAir(lights, solid, clearance, x, ceilY - 1, z,
                                        hints.lightingStyle() == AestheticPassHints.LightingStyle.MODERN_SLEEK
                                                ? "minecraft:sea_lantern" : hanging);
                            }
                            if (lights.size() >= MAX_LIGHTS) break;
                        }
                    }
                }
            }
        }

        if (lights.isEmpty()) return patches;

        if (!decorOptOutVolumes.isEmpty()) {
            lights.removeIf(p -> p != null && decorOptOutVolumes.contains(pack(p.dx(), p.dy(), p.dz())));
            if (lights.isEmpty()) return patches;
        }

        Set<BlockPos> seeds = new HashSet<>();
        for (BlockPatch p : patches) {
            if (p == null || BlockPatch.REMOVE.equals(p.action())) continue;
            seeds.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        }
        PostProcessWriteScope scope = PostProcessWriteScope.fromContext(context, seeds);
        lights.removeIf(p -> p == null
                || !scope.allowsPlace(new BlockPos(p.dx(), p.dy(), p.dz())));
        if (lights.isEmpty()) return patches;

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
    private static Set<Long> decorOptOutCells(PostProcessContext context) {
        Set<Long> out = new HashSet<>();
        if (context == null || context.plan() == null || context.plan().components() == null) return out;
        for (var c : context.plan().components()) {
            if (c == null || c.params() == null || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(context.plan(), c.params())) {
                continue;
            }
            addVolumeCells(out, c);
        }
        return out;
    }

    /** Masses with explicit lighting_style=none (component scope) suppress PLACE lights in their AABB. */
    private static Set<Long> lightingOptOutCells(PostProcessContext context) {
        Set<Long> out = new HashSet<>();
        if (context == null || context.plan() == null || context.plan().components() == null) return out;
        for (var c : context.plan().components()) {
            if (c == null || c.params() == null || c.relativePosition() == null || c.dimensions() == null) continue;
            AestheticPassHints.Resolved local = AestheticPassHints.resolve(context.plan(), c.params());
            if (local.lightingStyle() != AestheticPassHints.LightingStyle.NONE) continue;
            addVolumeCells(out, c);
        }
        return out;
    }

    private static void addVolumeCells(Set<Long> out, com.formacraft.common.llm.dto.Component c) {
        int minX = c.relativePosition().x() - 1;
        int maxX = c.relativePosition().x() + Math.max(1, c.dimensions().width());
        int minY = c.relativePosition().y();
        int maxY = c.relativePosition().y() + Math.max(1, c.dimensions().height());
        int minZ = c.relativePosition().z() - 1;
        int maxZ = c.relativePosition().z() + Math.max(1, c.dimensions().depth());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    out.add(pack(x, y, z));
                }
            }
        }
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
        if (block == null || block.isBlank() || out.size() >= MAX_LIGHTS) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (clearance.contains(pos)) return;
        if (looksLikeOpenYardColumn(solid, x, y, z)) return;
        long key = pack(x, y, z);
        if (solid.containsKey(key)) return;
        out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
        solid.put(key, block);
    }

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
