package com.formacraft.server.preview;

import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.server.build.PlannedBlock;

import java.util.ArrayList;
import java.util.List;

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

        for (PlannedBlock pb : blocks) {
            if (pb != null && pb.getPos() != null) {
                // 只添加非空气方块的位置（避免预览过于密集）
                if (!pb.getTargetState().isAir()) {
                    out.add(new OutlineBlock(pb.getPos()));
                }
            }
        }
        
        return out;
    }
}

