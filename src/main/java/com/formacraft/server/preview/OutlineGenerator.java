package com.formacraft.server.preview;

import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * 预览线框生成器
 * 将 PlannedBlock 列表转换为 OutlineBlock 列表
 */
public class OutlineGenerator {
    /**
     * 从 PlannedBlock 列表生成 OutlineBlock 列表
     * @param blocks PlannedBlock 列表
     * @return OutlineBlock 列表（用于预览）
     */
    public static List<OutlineBlock> fromPlannedBlocks(List<PlannedBlock> blocks) {
        List<OutlineBlock> out = new ArrayList<>();
        
        if (blocks == null) {
            return out;
        }

        // 先收集所有“非空气方块”位置，便于判断外表面（减少预览密度）
        Set<Long> solid = new HashSet<>(Math.max(16, blocks.size() * 2));
        for (PlannedBlock pb : blocks) {
            if (pb != null && pb.getPos() != null) {
                // 只添加非空气方块的位置（避免预览过于密集）
                if (!pb.getTargetState().isAir()) {
                    solid.add(pb.getPos().asLong());
                }
            }
        }

        // 只输出“外表面”方块：如果六邻域里存在空气/缺失，则认为在表面
        // 这样客户端不会画出密密麻麻的内部方块框。
        for (Long packed : solid) {
            BlockPos p = BlockPos.fromLong(packed);
            boolean surface =
                    !solid.contains(p.add( 1, 0, 0).asLong()) ||
                    !solid.contains(p.add(-1, 0, 0).asLong()) ||
                    !solid.contains(p.add( 0, 1, 0).asLong()) ||
                    !solid.contains(p.add( 0,-1, 0).asLong()) ||
                    !solid.contains(p.add( 0, 0, 1).asLong()) ||
                    !solid.contains(p.add( 0, 0,-1).asLong());
            if (surface) out.add(new OutlineBlock(p));
        }

        return out;
    }
}

