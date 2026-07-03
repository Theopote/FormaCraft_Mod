package com.formacraft.common.network.packet;

import com.formacraft.common.preview.OutlineBlock;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览线框数据包
 * 服务端 → 客户端
 */
public class PreviewOutlinePacket {
    /**
     * 写入数据包
     */
    public static void write(PacketByteBuf buf, List<OutlineBlock> blocks) {
        if (blocks == null) {
            buf.writeInt(0);
            return;
        }
        
        buf.writeInt(blocks.size());
        for (OutlineBlock block : blocks) {
            if (block != null && block.pos != null) {
                buf.writeBlockPos(block.pos);
            }
        }
    }

    /**
     * 读取数据包
     */
    public static List<OutlineBlock> read(PacketByteBuf buf) {
        int count = buf.readInt();
        List<OutlineBlock> list = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            list.add(new OutlineBlock(pos));
        }
        
        return list;
    }
}

