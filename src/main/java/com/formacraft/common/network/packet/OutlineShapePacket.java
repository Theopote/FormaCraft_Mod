package com.formacraft.common.network.packet;

import com.formacraft.common.buildcontext.OutlineShape;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** OutlineShape 网络编解码（轮廓同步与 Patch 过滤共用）。 */
public final class OutlineShapePacket {
    private OutlineShapePacket() {}

    public static void writeOutline(PacketByteBuf buf, OutlineShape shape) {
        if (shape == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        String type = shape.shapeType() == null ? "polygon" : shape.shapeType();
        buf.writeString(type);
        buf.writeVarInt(shape.minY());
        buf.writeVarInt(shape.maxY());
        if ("circle".equalsIgnoreCase(type)) {
            buf.writeBlockPos(shape.center() != null ? shape.center() : BlockPos.ORIGIN);
            buf.writeVarInt(Math.max(0, shape.radius()));
        } else {
            List<BlockPos> vertices = shape.vertices();
            int n = vertices != null ? vertices.size() : 0;
            buf.writeVarInt(n);
            if (vertices != null) {
                for (BlockPos p : vertices) {
                    buf.writeBlockPos(p != null ? p : BlockPos.ORIGIN);
                }
            }
        }
    }

    public static OutlineShape readOutline(PacketByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        String type = buf.readString();
        int minY = buf.readVarInt();
        int maxY = buf.readVarInt();
        if ("circle".equalsIgnoreCase(type)) {
            BlockPos center = buf.readBlockPos();
            int radius = buf.readVarInt();
            return new OutlineShape("circle", List.of(), center, radius, minY, maxY);
        }
        int n = buf.readVarInt();
        List<BlockPos> vertices = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            vertices.add(buf.readBlockPos());
        }
        return new OutlineShape("polygon", vertices, null, 0, minY, maxY);
    }
}
