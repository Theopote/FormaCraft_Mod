package com.formacraft.common.network.packet;

import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** BlockPatch / ProtectedZone 列表的网络编解码（Patch 预览与确认共用）。 */
public final class BlockPatchPacket {
    private BlockPatchPacket() {}

    public static void writePatches(PacketByteBuf buf, List<BlockPatch> patches) {
        List<BlockPatch> ps = patches != null ? patches : List.of();
        buf.writeVarInt(ps.size());
        for (BlockPatch p : ps) {
            if (p == null) {
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeString("");
                buf.writeString("");
                continue;
            }
            buf.writeVarInt(p.dx());
            buf.writeVarInt(p.dy());
            buf.writeVarInt(p.dz());
            buf.writeString(p.action() == null ? "" : p.action());
            buf.writeString(p.targetBlock() == null ? "" : p.targetBlock());
        }
    }

    public static List<BlockPatch> readPatches(PacketByteBuf buf) {
        int n = buf.readVarInt();
        List<BlockPatch> ps = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            int dx = buf.readVarInt();
            int dy = buf.readVarInt();
            int dz = buf.readVarInt();
            String action = buf.readString();
            String target = buf.readString();
            if (target != null && target.isEmpty()) target = null;
            ps.add(new BlockPatch(action, dx, dy, dz, target));
        }
        return ps;
    }

    public static void writeProtectedZones(PacketByteBuf buf, List<ProtectedZone> zones) {
        List<ProtectedZone> zs = zones != null ? zones : List.of();
        buf.writeVarInt(zs.size());
        for (ProtectedZone z : zs) {
            if (z == null || z.min() == null || z.max() == null) {
                buf.writeBoolean(false);
                continue;
            }
            buf.writeBoolean(true);
            ProtectedZone n = z.normalized();
            buf.writeBlockPos(n.min());
            buf.writeBlockPos(n.max());
        }
    }

    public static List<ProtectedZone> readProtectedZones(PacketByteBuf buf) {
        int zn = buf.readVarInt();
        List<ProtectedZone> zs = new ArrayList<>(Math.max(0, zn));
        for (int i = 0; i < zn; i++) {
            boolean present = buf.readBoolean();
            if (!present) continue;
            BlockPos min = buf.readBlockPos();
            BlockPos max = buf.readBlockPos();
            zs.add(new ProtectedZone(min, max).normalized());
        }
        return zs;
    }
}
