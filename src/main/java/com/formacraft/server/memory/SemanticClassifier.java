package com.formacraft.server.memory;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

/**
 * 语义分类器：将方块修改分类为语义部位
 * 
 * 轻量实现：基于位置和方块类型进行简单推断
 */
public final class SemanticClassifier {
    private SemanticClassifier() {}
    
    /**
     * 根据位置和 Patch 信息分类语义部位
     * 
     * @param pos 世界坐标
     * @param patch Patch 信息
     * @return 语义部位
     */
    public static SemanticPart classify(BlockPos pos, BlockPatch patch) {
        if (patch == null) {
            return SemanticPart.UNKNOWN;
        }
        
        // 移除操作无法准确分类
        if (BlockPatch.REMOVE.equals(patch.action())) {
            return SemanticPart.UNKNOWN;
        }
        
        String targetBlock = patch.targetBlock();
        if (targetBlock == null || targetBlock.isEmpty()) {
            return SemanticPart.UNKNOWN;
        }
        
        String blockLower = targetBlock.toLowerCase();
        
        // 高度判断（简单规则）
        int y = pos.getY();
        if (y < 2) {
            return SemanticPart.FOUNDATION;
        }
        
        // 方块类型判断
        if (blockLower.contains("stairs") || blockLower.contains("slab")) {
            // 楼梯和台阶通常用于屋顶或路径
            if (y > 10) {
                return SemanticPart.ROOF;
            } else {
                return SemanticPart.PATH;
            }
        }
        
        if (blockLower.contains("glass") || blockLower.contains("pane")) {
            return SemanticPart.WINDOW;
        }
        
        if (blockLower.contains("door") || blockLower.contains("gate")) {
            return SemanticPart.DOOR;
        }
        
        if (blockLower.contains("path") || blockLower.contains("road") || 
            blockLower.contains("planks") && (blockLower.contains("path") || blockLower.contains("slab"))) {
            return SemanticPart.PATH;
        }
        
        // 高处的方块更可能是屋顶
        if (y > 10) {
            return SemanticPart.ROOF;
        }
        
        // 装饰性方块（花、旗帜、灯笼等）
        if (blockLower.contains("flower") || blockLower.contains("banner") || 
            blockLower.contains("lantern") || blockLower.contains("torch") ||
            blockLower.contains("candle")) {
            return SemanticPart.DECORATION;
        }
        
        // 默认归类为墙体
        return SemanticPart.WALL;
    }
}

