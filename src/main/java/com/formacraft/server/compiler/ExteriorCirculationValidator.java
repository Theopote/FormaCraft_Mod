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
        return findCollision(shell, flights, volumes, Set.of(), false);
    }

    static Optional<Collision> findCollision(List<BlockPatch> shell, List<Flight> flights,
                                             List<BuildingVolume> volumes, Set<BlockPos> connectorAccess) {
        return findCollision(shell, flights, volumes, connectorAccess, false);
    }

    /**
     * @param rejectInteriorSolids when true (exterior stairs), collide with solid prior patches
     *        anywhere inside a {@link BuildingVolume}, including deep interior partitions/columns.
     *        when false (interior stairs), keep surface-oriented checks so host floor openings
     *        can still be cut without treating every interior cell as an exterior wall.
     */
    static Optional<Collision> findCollision(List<BlockPatch> shell, List<Flight> flights,
                                             List<BuildingVolume> volumes, Set<BlockPos> connectorAccess,
                                             boolean rejectInteriorSolids) {
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
            if (!contains(p, volumes)) continue;
            boolean deepInterior = contains(p.east(), volumes) && contains(p.west(), volumes)
                    && contains(p.north(), volumes) && contains(p.south(), volumes);
            // Interior flights: only surface/envelope cells (legacy). Exterior flights: all
            // in-volume solids — closes the partition/column blind spot.
            if (deepInterior && !rejectInteriorSolids) continue;
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
        // The first exterior tread may replace a flat public paving surface.
        // This does not authorize a flight through a courtyard wall or building.
        int run = Math.max(Math.abs(number(to.get("x"))-number(from.get("x"))),
                Math.abs(number(to.get("z"))-number(from.get("z"))));
        int rise = number(to.get("y"))-number(from.get("y"));
        for (int startStep=0;startStep<=run;startStep++) {
            if (Math.abs(Math.round(rise*(startStep/(double)Math.max(1,run)))) > 1) break;
            for (int side=-width/2;side<width-width/2;side++) for (int y=-1;y<=0;y++) {
            var pos = new BlockPos(offset.getX()+rp.x()+number(from.get("x"))+dx*startStep+(dz != 0 ? side : 0),
                    offset.getY()+rp.y()+number(from.get("y"))+y,
                    offset.getZ()+rp.z()+number(from.get("z"))+dz*startStep+(dx != 0 ? side : 0));
            var previous = occupancy.placeAt(pos);
            if (previous != null && previous.owner() != null && previous.owner().dimensions() != null
                    && previous.owner().dimensions().height() == 1
                    && Set.of("COURTYARD","COURTYARD_SPACE","PATH","ROAD","PAVING","PLAZA","TERRACE","BALCONY").contains(previous.writerType()))
                result.add(pos);
            if (previous != null && "FOUNDATION".equals(previous.writerType()) && previous.owner()!=null && y==-1) {
                var fb=ComponentFootprintUtil.bounds(previous.owner());
                var fs=slots.get(previous.owner().slotId()); var fa=fs==null?null:fs.anchor();
                int fy=fa==null?0:fa.y();
                if(fb!=null && fb.maxY()+fy==pos.getY()+1) result.add(pos);
            }
        }
        }
        Object stairBuilding = stair.params().get("building_id");
        if (stairBuilding == null) stairBuilding = stair.params().get("host_id");
        // Optional eaves outside another building's core yield to an explicit
        // flight. Its core roof and walls retain the existing collision rules.
        for (int sample=0;sample<=run+(landing>1?landing:0);sample++) {
            int stepY = number(from.get("y"))+(int)Math.round(rise*(Math.min(run,sample)/(double)Math.max(1,run)));
            for (int side=-width/2;side<width-width/2;side++) for (int y=-1;y<=clear;y++) {
                var pos = new BlockPos(offset.getX()+rp.x()+number(from.get("x"))+dx*sample+(dz!=0?side:0),
                        offset.getY()+rp.y()+stepY+y,
                        offset.getZ()+rp.z()+number(from.get("z"))+dz*sample+(dx!=0?side:0));
                var previous = occupancy.placeAt(pos);
                if (previous == null || !"ROOF".equals(previous.writerType()) || previous.owner() == null
                        || previous.owner().params() == null) continue;
                Object hostId = previous.owner().params().get("host_id");
                var host = components.stream().filter(c -> c.params()!=null && hostId!=null
                        && hostId.equals(c.params().get("component_id"))).findFirst();
                if (host.isEmpty()) continue;
                var core = ComponentFootprintUtil.bounds(host.get());
                var hostSlot = slots.get(host.get().slotId());
                var ha = hostSlot == null ? null : hostSlot.anchor();
                int hx=ha==null?0:ha.x(), hz=ha==null?0:ha.z();
                if (core!=null && (pos.getX()<core.minX()+hx || pos.getX()>=core.maxX()+hx
                        || pos.getZ()<core.minZ()+hz || pos.getZ()>=core.maxZ()+hz)) result.add(pos);
            }
        }
        // Declared destination doorway: foundation footing, portal cells, and a
        // bounded landing intrusion into the target host (not mid-run foreign mass).
        for (var door : components) {
            if (!"ENTRANCE".equals(door.componentType()) || door.params()==null
                    || !Objects.equals(stair.params().get("component_id"),door.params().get("stair_connection"))
                    || !Objects.equals(stair.params().get("target_host_id"),door.params().get("host_id"))) continue;
            var db = ComponentFootprintUtil.bounds(door);
            var ds = slots.get(door.slotId()); var da=ds==null?null:ds.anchor();
            int ox=da==null?0:da.x(), oy=da==null?0:da.y(), oz=da==null?0:da.z();
            if (db==null) continue;
            Object doorHost = door.params().get("host_id");
            int doorClear = Math.max(clear, Math.max(1, db.maxY() - db.minY()));
            for(int step=0;step<=(landing>1?landing:0);step++) for(int side=-width/2;side<width-width/2;side++) {
                for (int y = -1; y <= doorClear; y++) {
                    var pos = new BlockPos(offset.getX()+rp.x()+number(to.get("x"))+dx*step+(dz!=0?side:0),
                            offset.getY()+rp.y()+number(to.get("y"))+y,
                            offset.getZ()+rp.z()+number(to.get("z"))+dz*step+(dx!=0?side:0));
                    var previous=occupancy.placeAt(pos);
                    if (previous == null || previous.owner() == null || previous.owner().params() == null) continue;
                    boolean inPortalBand = pos.getX()>=db.minX()+ox-Math.abs(dx)*landing && pos.getX()<db.maxX()+ox+Math.abs(dx)*landing
                            && pos.getZ()>=db.minZ()+oz-Math.abs(dz)*landing && pos.getZ()<db.maxZ()+oz+Math.abs(dz)*landing;
                    boolean hostOwned = Objects.equals(doorHost, previous.owner().params().get("host_id"))
                            || Objects.equals(doorHost, previous.owner().params().get("component_id"));
                    if (!hostOwned) continue;
                    if (y == -1 && "FOUNDATION".equals(previous.writerType()) && inPortalBand) {
                        result.add(pos);
                        continue;
                    }
                    // Portal + short inward landing may meet the destination host shell.
                    if (inPortalBand || Set.of("MASS_MAIN", "MAIN_MASS", "TOWER", "MASS_SECONDARY",
                            "FOUNDATION", "ROOF", "ROOF_STRUCTURE").contains(previous.writerType())) {
                        if (pos.getY() >= db.minY()+oy-1 && pos.getY() < db.maxY()+oy+clear) result.add(pos);
                    }
                }
            }
        }
        for (Component connector : components) {
            if (connector.params() == null) continue;
            boolean enclosure = "DECOR_DETAIL".equals(connector.componentType())
                    && Boolean.TRUE.equals(connector.params().get("corridor_enclosure"));
            Object platformHost = connector.params().get("host_id");
            boolean platformRole = com.formacraft.common.generation.component.util.PlateRoleCatalog.TERRACE_TOKENS
                    .contains(String.valueOf(connector.params().get("plate_role")))
                    || "gallery".equals(connector.params().get("plate_role"))
                    || Set.of("TERRACE","BALCONY").contains(connector.componentType());
            boolean ownPlatform = platformRole && platformHost != null && (platformHost.equals(stairBuilding)
                    || platformHost.equals(stair.params().get("target_host_id")));
            if (!Set.of("MASS_SECONDARY","TERRACE","BALCONY").contains(connector.componentType()) && !enclosure) continue;
            if (enclosure) {
                boolean joinsBuilding = false;
                for (var bridge : components) {
                    if (bridge.params() == null || !"plate".equals(bridge.params().get("extrude_mode"))
                            || !com.formacraft.common.generation.component.util.HostedPlateOccupancy.isBridgePlate(bridge.params())) continue;
                    Object owner = connector.params().get("host_id");
                    if (owner != null && owner.equals(bridge.params().get("host_id"))
                            && stairBuilding != null && stairBuilding.equals(bridge.params().get("target_host_id"))) {
                        var a = ComponentPlanCompiler.inSlotFrame(connector, bridge.slotId(), slots);
                        var cb = ComponentFootprintUtil.bounds(a);
                        var bb = ComponentFootprintUtil.bounds(bridge);
                        if (cb != null && bb != null && cb.minX() >= bb.minX() && cb.maxX() <= bb.maxX()
                                && cb.minZ() >= bb.minZ() && cb.maxZ() <= bb.maxZ()) joinsBuilding = true;
                    }
                }
                if (!joinsBuilding) continue;
            }
            if (!ownPlatform && "plate".equals(connector.params().get("extrude_mode"))
                    && (!com.formacraft.common.generation.component.util.HostedPlateOccupancy.isBridgePlate(connector.params())
                        || stairBuilding == null || !(stairBuilding.equals(connector.params().get("host_id"))
                            || stairBuilding.equals(connector.params().get("target_host_id"))))) continue;
            var bounds = ComponentFootprintUtil.bounds(connector);
            if (bounds == null) continue;
            Slot slot = slots.get(connector.slotId());
            var anchor = slot == null ? null : slot.anchor();
            int ox = anchor == null ? 0 : anchor.x(), oy = anchor == null ? 0 : anchor.y(), oz = anchor == null ? 0 : anchor.z();
            for (int step = enclosure ? -clear : -1; step <= landing; step++) for (int side = -width/2; side < width-width/2; side++)
                for (int y = -1; y <= clear; y++) {
                    var pos = new BlockPos(offset.getX()+rp.x()+number(to.get("x"))+dx*step+(dz != 0 ? side : 0),
                            offset.getY()+rp.y()+number(to.get("y"))+y,
                            offset.getZ()+rp.z()+number(to.get("z"))+dz*step+(dx != 0 ? side : 0));
                    var previous = occupancy.placeAt(pos);
                    if (previous == null || previous.owner() == null) continue;
                    boolean inConnector = pos.getX() >= bounds.minX()+ox && pos.getX() < bounds.maxX()+ox
                            && pos.getY() >= bounds.minY()+oy-(ownPlatform ? 1 : 0)
                            && pos.getY() < bounds.maxY()+oy+(ownPlatform ? clear : 0)
                            && pos.getZ() >= bounds.minZ()+oz && pos.getZ() < bounds.maxZ()+oz;
                    boolean onOwnHost = ownPlatform && previous.owner().params() != null
                            && (platformHost.equals(previous.owner().params().get("component_id"))
                                || platformHost.equals(previous.owner().params().get("host_id")));
                    // Own-platform terminal neighborhood may also meet the host shell just
                    // past the plate (gallery/terrace landings), still limited to to±landing.
                    if ((inConnector && (previous.owner() == connector || onOwnHost
                            || (previous.owner().params() != null
                                && java.util.Objects.equals(connector.params().get("component_id"),
                                        previous.owner().params().get("host_id")))))
                            || (onOwnHost && !inConnector
                                && Set.of("MASS_MAIN", "MAIN_MASS", "TOWER", "MASS_SECONDARY",
                                    "FOUNDATION", "ROOF", "ROOF_STRUCTURE").contains(previous.writerType()))) {
                        result.add(pos);
                    }
                }
        }
        return result;
    }

    private static int number(Object value) { return new java.math.BigDecimal(String.valueOf(value)).intValueExact(); }

    private static boolean contains(BlockPos p, List<BuildingVolume> volumes) {
        return volumes.stream().anyMatch(v -> v.contains(p.getX(), p.getY(), p.getZ()));
    }
}
