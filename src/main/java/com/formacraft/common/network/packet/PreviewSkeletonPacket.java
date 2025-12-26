package com.formacraft.common.network.packet;

import net.minecraft.network.PacketByteBuf;

/**
 * PreviewSkeletonPacket:
 * Server -> Client, carries a small JSON string for skeleton layout preview.
 */
public final class PreviewSkeletonPacket {
    private PreviewSkeletonPacket() {}

    public static void write(PacketByteBuf buf, String json) {
        buf.writeString(json != null ? json : "");
    }

    public static String read(PacketByteBuf buf) {
        String s = buf.readString();
        return s != null ? s : "";
    }
}


