package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext.BuildingVolume;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Checks circulation writes against the shell already emitted, in plan coordinates. */
final class ExteriorCirculationValidator {
    record Collision(BlockPos position, String role) {}

    static Optional<Collision> findCollision(List<BlockPatch> shell, List<Flight> flights,
                                             List<BuildingVolume> volumes) {
        return findCollision(shell, flights, volumes, Set.of());
    }

    static Optional<Collision> findCollision(List<BlockPatch> shell, List<Flight> flights,
                                             List<BuildingVolume> volumes, Set<BlockPos> connectorAccess) {
        if (flights.isEmpty() || volumes.isEmpty()) return Optional.empty();
        var relevant = new LinkedHashMap<BlockPos, String>();
        for (var flight : flights) {
            for (var pos : flight.occupied()) relevant.put(pos, "tread/support");
            for (var pos : flight.clearance()) relevant.put(pos, "clearance");
        }
        var finalShell = new HashMap<BlockPos, BlockPatch>();
        for (var patch : shell) {
            if (patch == null) continue;
            var pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (relevant.containsKey(pos)) finalShell.put(pos, patch);
        }
        for (var entry : relevant.entrySet()) {
            BlockPos p = entry.getKey();
            if (connectorAccess.contains(p)) continue;
            if (!contains(p, volumes) || (contains(p.east(), volumes) && contains(p.west(), volumes)
                    && contains(p.north(), volumes) && contains(p.south(), volumes))) continue;
            var patch = finalShell.get(p);
            if (patch == null) continue; // Authored doorway or other absent surface.
            var state = BlockPatchTargetResolver.resolve(patch);
            if (state != null && !state.isAir()) return Optional.of(new Collision(p, entry.getValue()));
        }
        return Optional.empty();
    }

    /** Only the last step/landing may open a connection into a corridor body. */
    static Set<BlockPos> connectorAccess(Component stair, List<Component> components, Map<String, Slot> slots,
                                         BlockPos offset, PatchOccupancyIndex occupancy) {
        if (stair.params() == null || !Boolean.TRUE.equals(stair.params().get("exterior"))
                || !com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(stair)
                || !(stair.params().get("from") instanceof Map<?, ?> from)
                || !(stair.params().get("to") instanceof Map<?, ?> to)) return Set.of();
        int dx = Integer.signum(number(to.get("x"))-number(from.get("x")));
        int dz = Integer.signum(number(to.get("z"))-number(from.get("z")));
        int width = number(stair.params().getOrDefault("width", 3));
        int landing = number(stair.params().getOrDefault("landing_length", 1));
        int clear = number(stair.params().getOrDefault("clearHeight", 2));
        var rp = stair.relativePosition();
        var result = new HashSet<BlockPos>();
        for (Component connector : components) {
            if (!"MASS_SECONDARY".equals(connector.componentType()) || connector.params() == null
                    || "plate".equals(connector.params().get("extrude_mode"))) continue;
            var bounds = ComponentFootprintUtil.bounds(connector);
            if (bounds == null) continue;
            Slot slot = slots.get(connector.slotId());
            var anchor = slot == null ? null : slot.anchor();
            int ox = anchor == null ? 0 : anchor.x(), oy = anchor == null ? 0 : anchor.y(), oz = anchor == null ? 0 : anchor.z();
            for (int step = -1; step <= landing; step++) for (int side = -width/2; side < width-width/2; side++)
                for (int y = -1; y <= clear; y++) {
                    var pos = new BlockPos(offset.getX()+rp.x()+number(to.get("x"))+dx*step+(dz != 0 ? side : 0),
                            offset.getY()+rp.y()+number(to.get("y"))+y,
                            offset.getZ()+rp.z()+number(to.get("z"))+dz*step+(dx != 0 ? side : 0));
                    var previous = occupancy.placeAt(pos);
                    if (pos.getX() >= bounds.minX()+ox && pos.getX() < bounds.maxX()+ox
                            && pos.getY() >= bounds.minY()+oy && pos.getY() < bounds.maxY()+oy
                            && pos.getZ() >= bounds.minZ()+oz && pos.getZ() < bounds.maxZ()+oz
                            && previous != null && previous.owner() != null
                            && (previous.owner() == connector || (previous.owner().params() != null
                                && java.util.Objects.equals(connector.params().get("component_id"),
                                        previous.owner().params().get("host_id"))))) result.add(pos);
                }
        }
        return result;
    }

    private static int number(Object value) { return new java.math.BigDecimal(String.valueOf(value)).intValueExact(); }

    private static boolean contains(BlockPos p, List<BuildingVolume> volumes) {
        return volumes.stream().anyMatch(v -> v.contains(p.getX(), p.getY(), p.getZ()));
    }
}
