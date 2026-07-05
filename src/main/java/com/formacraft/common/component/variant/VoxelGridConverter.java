package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将 {@link VoxelGrid} 转回 {@link ComponentDefinition} 的 blocks 列表（保留 base 元数据）。
 */
public final class VoxelGridConverter {
    private VoxelGridConverter() {}

    public static ComponentDefinition toComponentDefinition(ComponentDefinition base, VoxelGrid grid) {
        if (base == null || grid == null || grid.size() == 0) {
            return null;
        }

        List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>(grid.size());
        for (Voxel v : grid.all()) {
            if (v == null) continue;
            ComponentDefinition.BlockEntry be = new ComponentDefinition.BlockEntry();
            be.dx = v.x();
            be.dy = v.y();
            be.dz = v.z();
            be.block = v.blockState();
            be.semantic = parseSemantic(v);
            blocks.add(be);
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return ComponentVariantApplier.cloneWithBlocksPublic(base, blocks);
    }

    private static SemanticPart parseSemantic(Voxel v) {
        if (v == null) {
            return null;
        }
        for (String tag : v.semanticTags()) {
            if (tag == null || tag.isBlank()) continue;
            try {
                return SemanticPart.valueOf(tag.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}
