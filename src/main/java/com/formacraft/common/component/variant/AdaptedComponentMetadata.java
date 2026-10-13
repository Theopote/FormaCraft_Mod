package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.json.JsonUtil;

import java.util.ArrayList;
import java.util.EnumMap;

/** Keep attachment points in the same coordinate system as resized geometry. */
public final class AdaptedComponentMetadata {
    private AdaptedComponentMetadata() {}

    public static ComponentDefinition remap(ComponentDefinition base, ComponentDefinition geometry,
            StructureTemplate template, ComponentPrototype.VariantRules.Scaling rules,
            PersistedComponentVariant.Params.Scale target) {
        // Metadata must be independently owned: the library's original remains immutable.
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
        if (out.sockets != null) {
            var sockets = new ArrayList<ComponentSocket>();
            for (var socket : out.sockets) {
                if (socket == null) continue;
                var origin = out.socketPlacements == null ? null : out.socketPlacements.stream()
                        .filter(p -> socket.id.equals(p.id)).findFirst().orElse(null);
                String facing = origin != null ? origin.facing : (out.anchor != null ? out.anchor.facing : null);
                boolean alongZ = "EAST".equalsIgnoreCase(facing) || "WEST".equalsIgnoreCase(facing);
                var widthAxis = alongZ ? Voxel.Axis.Z : Voxel.Axis.X;
                int widthOrigin = origin == null ? 0 : alongZ ? origin.dz : origin.dx;
                int heightOrigin = origin == null ? 0 : origin.dy;
                // Socket constraints describe an opening in local positive width/height coordinates.
                int[] min = resize(socket.size.min, maps, widthAxis, widthOrigin, heightOrigin);
                int[] max = resize(socket.size.max, maps, widthAxis, widthOrigin, heightOrigin);
                sockets.add(new ComponentSocket(socket.id, socket.role, socket.shape, socket.context,
                        socket.facingPolicy, new SizeConstraint(min, max), socket.tags));
            }
            out.sockets = sockets;
        }
        if (out.socketPlacements != null) for (var p : out.socketPlacements) {
            p.dx = map(maps, Voxel.Axis.X, p.dx);
            p.dy = map(maps, Voxel.Axis.Y, p.dy);
            p.dz = map(maps, Voxel.Axis.Z, p.dz);
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

    private static int[] resize(int[] size, EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps,
            Voxel.Axis width, int x, int y) {
        if (size == null) return null;
        int[] result = size.clone();
        for (int i = 0; i < Math.min(2, result.length); i++) {
            if (result[i] <= 0 || result[i] > SegmentScaler.MAX_AXIS_SIZE) continue;
            Voxel.Axis axis = i == 0 ? width : Voxel.Axis.Y;
            int origin = i == 0 ? x : y;
            result[i] = Math.max(1, map(maps, axis, origin + size[i] - 1) - map(maps, axis, origin) + 1);
        }
        return result;
    }

    private static int map(EnumMap<Voxel.Axis, SegmentScaler.Mapping> maps, Voxel.Axis axis, int value) {
        return maps.get(axis).anchoredPosition(value);
    }
}
