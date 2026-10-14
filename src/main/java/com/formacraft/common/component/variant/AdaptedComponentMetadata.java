package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.json.JsonUtil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Keep attachment points in the same coordinate system as resized geometry. */
public final class AdaptedComponentMetadata {
    private AdaptedComponentMetadata() {}

    public static ComponentDefinition remap(ComponentDefinition base, ComponentDefinition geometry,
            StructureTemplate template, ComponentPrototype.VariantRules.Scaling rules,
            PersistedComponentVariant.Params.Scale target) {
        ComponentDefinition out = JsonUtil.fromJson(JsonUtil.toJson(base), ComponentDefinition.class);
        out.blocks = geometry.blocks;
        out.size = geometry.size;
        var maps = new EnumMap<Voxel.Axis, SegmentScaler.Mapping>(Voxel.Axis.class);
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            var rule = rules.axes != null ? rules.axes.get(axis.name()) : null;
            int size = switch (axis) { case X -> target.x; case Y -> target.y; case Z -> target.z; };
            maps.put(axis, SegmentScaler.mapping(template.all(), axis, rule, size));
        }
        if (out.directionHints != null) {
            var h = out.directionHints;
            for (var mark : new ComponentDefinition.DirectionHints.Mark[]{h.inside, h.outside, h.bottom, h.top}) {
                if (mark == null) continue;
                mark.dx = map(maps, Voxel.Axis.X, mark.dx);
                mark.dy = map(maps, Voxel.Axis.Y, mark.dy);
                mark.dz = map(maps, Voxel.Axis.Z, mark.dz);
            }
            if (h.hostFace != null) {
                h.hostFace.dx = map(maps, Voxel.Axis.X, h.hostFace.dx);
                h.hostFace.dy = map(maps, Voxel.Axis.Y, h.hostFace.dy);
                h.hostFace.dz = map(maps, Voxel.Axis.Z, h.hostFace.dz);
            }
        }

        if (out.socketPlacements != null) {
            var expanded = new ArrayList<ComponentDefinition.SocketPlacement>();
            for (var p : out.socketPlacements) {
                if (p == null) continue;
                String mode = adaptMode(p);
                if ("FORBIDDEN".equals(mode) && inExpandingMiddle(maps, p)) {
                    throw new IllegalArgumentException(
                            "Socket '" + p.id + "' is FORBIDDEN in a repeating/trimming middle band");
                }
                if ("REPEAT".equals(mode)) {
                    expanded.addAll(expandRepeatPlacements(p, maps));
                } else {
                    var copy = copyPlacement(p);
                    copy.dx = map(maps, Voxel.Axis.X, p.dx);
                    copy.dy = map(maps, Voxel.Axis.Y, p.dy);
                    copy.dz = map(maps, Voxel.Axis.Z, p.dz);
                    expanded.add(copy);
                }
            }
            out.socketPlacements = expanded;
        }

        if (out.sockets != null) {
            var sockets = new ArrayList<ComponentSocket>();
            for (var socket : out.sockets) {
                if (socket == null) continue;
                List<ComponentDefinition.SocketPlacement> origins = out.socketPlacements == null ? List.of()
                        : out.socketPlacements.stream()
                        .filter(p -> p != null && p.id != null
                                && (p.id.equals(socket.id) || p.id.startsWith(socket.id + "#")))
                        .toList();
                if (origins.isEmpty()) {
                    sockets.add(socket);
                    continue;
                }
                for (var origin : origins) {
                    String facing = origin.facing != null ? origin.facing
                            : (out.anchor != null ? out.anchor.facing : null);
                    boolean alongZ = "EAST".equalsIgnoreCase(facing) || "WEST".equalsIgnoreCase(facing);
                    var widthAxis = alongZ ? Voxel.Axis.Z : Voxel.Axis.X;
                    int widthOrigin = alongZ ? origin.dz : origin.dx;
                    int heightOrigin = origin.dy;
                    int widthSign = "SOUTH".equalsIgnoreCase(facing) || "WEST".equalsIgnoreCase(facing) ? -1 : 1;
                    int[] min = resize(socket.size.min, maps, widthAxis, widthOrigin, heightOrigin, widthSign);
                    int[] max = resize(socket.size.max, maps, widthAxis, widthOrigin, heightOrigin, widthSign);
                    sockets.add(new ComponentSocket(origin.id, socket.role, socket.shape, socket.context,
                            socket.facingPolicy, new SizeConstraint(min, max), socket.tags));
                }
            }
            out.sockets = sockets;
        }

        if (out.anchorHint != null) {
            int minX = out.blocks.stream().mapToInt(b -> b.dx).min().orElse(0);
            int minY = out.blocks.stream().mapToInt(b -> b.dy).min().orElse(0);
            int minZ = out.blocks.stream().mapToInt(b -> b.dz).min().orElse(0);
            out.anchorHint.u = (-minX + 0.5f) / out.size.w;
            out.anchorHint.v = -minY / (float) out.size.h;
            out.anchorHint.w = (-minZ + 0.5f) / out.size.d;
        }
        return out;
    }

    private static String adaptMode(ComponentDefinition.SocketPlacement p) {
        if (p.adaptMode == null || p.adaptMode.isBlank()) return "STRETCH";
        return p.adaptMode.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean inExpandingMiddle(EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps,
                                            ComponentDefinition.SocketPlacement p) {
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            var map = maps.get(axis);
            if (map == null || map.originalSize() == map.targetSize()) continue;
            if (!"REPEAT".equalsIgnoreCase(map.mode()) && !"TRIM".equalsIgnoreCase(map.mode())) continue;
            int coord = switch (axis) { case X -> p.dx; case Y -> p.dy; case Z -> p.dz; };
            int local = coord - map.min();
            if (local >= map.start() && local < map.originalSize() - map.end()) return true;
        }
        return false;
    }

    private static List<ComponentDefinition.SocketPlacement> expandRepeatPlacements(
            ComponentDefinition.SocketPlacement p, EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps) {
        Voxel.Axis repeatAxis = null;
        int periods = 1;
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            var map = maps.get(axis);
            if (map == null || !"REPEAT".equalsIgnoreCase(map.mode())) continue;
            int mid = map.middle();
            int tMid = map.targetMiddle();
            if (mid <= 0 || tMid <= mid) continue;
            int pCount = tMid / mid;
            if (pCount > periods) {
                periods = pCount;
                repeatAxis = axis;
            }
        }
        var out = new ArrayList<ComponentDefinition.SocketPlacement>();
        if (repeatAxis == null || periods <= 1) {
            var copy = copyPlacement(p);
            copy.dx = map(maps, Voxel.Axis.X, p.dx);
            copy.dy = map(maps, Voxel.Axis.Y, p.dy);
            copy.dz = map(maps, Voxel.Axis.Z, p.dz);
            out.add(copy);
            return out;
        }
        var map = maps.get(repeatAxis);
        int coord = switch (repeatAxis) { case X -> p.dx; case Y -> p.dy; case Z -> p.dz; };
        int local = coord - map.min();
        int offsetInMiddle = local - map.start();
        for (int k = 0; k < periods; k++) {
            int destLocal = map.start() + offsetInMiddle + k * map.middle();
            if (destLocal >= map.targetSize() - map.end()) break;
            int anchored = (map.min() + destLocal) - map.position(0);
            var copy = copyPlacement(p);
            copy.id = k == 0 ? p.id : p.id + "#" + k;
            copy.dx = repeatAxis == Voxel.Axis.X ? anchored : map(maps, Voxel.Axis.X, p.dx);
            copy.dy = repeatAxis == Voxel.Axis.Y ? anchored : map(maps, Voxel.Axis.Y, p.dy);
            copy.dz = repeatAxis == Voxel.Axis.Z ? anchored : map(maps, Voxel.Axis.Z, p.dz);
            out.add(copy);
        }
        return out;
    }

    private static ComponentDefinition.SocketPlacement copyPlacement(ComponentDefinition.SocketPlacement p) {
        var c = new ComponentDefinition.SocketPlacement();
        c.id = p.id;
        c.dx = p.dx;
        c.dy = p.dy;
        c.dz = p.dz;
        c.facing = p.facing;
        c.adaptMode = p.adaptMode;
        return c;
    }

    private static int[] resize(int[] size, EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps,
            Voxel.Axis width, int x, int y, int widthSign) {
        if (size == null) return null;
        int[] result = size.clone();
        for (int i = 0; i < Math.min(2, result.length); i++) {
            if (result[i] <= 0 || result[i] > SegmentScaler.MAX_AXIS_SIZE) continue;
            Voxel.Axis axis = i == 0 ? width : Voxel.Axis.Y;
            int origin = i == 0 ? x : y;
            int sign = i == 0 ? widthSign : 1;
            result[i] = Math.max(1, Math.abs(map(maps, axis, origin + sign * (size[i] - 1))
                    - map(maps, axis, origin)) + 1);
        }
        return result;
    }

    private static int map(EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps, Voxel.Axis axis, int value) {
        return maps.get(axis).anchoredPosition(value);
    }
}
