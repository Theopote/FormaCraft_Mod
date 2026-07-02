package com.formacraft.common.component;

import com.formacraft.common.component.semantic.BlockStatePropertyUtil;
import com.formacraft.common.component.semantic.SemanticBlockStatePicker;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 将现有的 ComponentDefinition(v1) 编译为 BlockPatch 列表（最小可用编译器）。
 * <p>
 * 这是 Prototype/Variant/Instance 体系落地前的“兼容编译路径”：
 * - Prototype 的 structure 若暂时仍引用 v1 ComponentDefinition JSON，可直接复用该编译器
 * - 后续接入 NBT structure / 分段缩放（repeat/trim）时，可替换上层实现，不必改调用方
 */
public final class ComponentDefinitionCompiler {
    private ComponentDefinitionCompiler() {}

    private static final FcaLog LOG = FcaLog.of("ComponentDefinitionCompiler");

    public static List<BlockPatch> compile(ComponentDefinition def,
                                          int baseX, int baseY, int baseZ,
                                          Direction targetFacing,
                                          com.formacraft.common.component.transform.Mirror mirror,
                                          boolean semanticSkin,
                                          String semanticStyleId,
                                          long seedBase) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return List.of();

        Direction fromFacing = parseFacing(def.anchor != null ? def.anchor.facing : null);
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;
        if (mirror == null) mirror = com.formacraft.common.component.transform.Mirror.NONE;

        ComponentTransform t = new ComponentTransform(targetFacing, mirror);
        List<BlockPatch> out = new ArrayList<>(def.blocks.size());

        int minDy = Integer.MAX_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minDy = Math.min(minDy, be.dy);
        }
        if (minDy == Integer.MAX_VALUE) minDy = 0;

        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;

            BlockPos local = new BlockPos(be.dx, be.dy, be.dz);
            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, t);

            int dx = baseX + off.getX();
            int dy = baseY + off.getY();
            int dz = baseZ + off.getZ();

            String block;
            if (semanticSkin) {
                com.formacraft.common.semantic.SemanticPart part = be.semantic != null
                        ? be.semantic
                        : guessSemanticPartFromString(be.block, be.dy, minDy);
                long seed = mixSeed(seedBase, dx, dy, dz, part.ordinal());
                BlockState picked = SemanticBlockStatePicker.pick(semanticStyleId, part, seed);

                Direction capturedFacing = BlockStateStringUtil.extractFacing(be.block);
                if (capturedFacing != null) {
                    Direction tf = FacingTransformUtil.transformFacing(capturedFacing, fromFacing, t);
                    picked = BlockStatePropertyUtil.applyFacing(picked, tf);
                }
                block = BlockStateStringUtil.fromState(picked);
            } else {
                if (be.block == null || be.block.isBlank()) continue;
                block = BlockStateStringUtil.withTransformedFacing(be.block, fromFacing, t);
            }
            out.add(new BlockPatch(BlockPatch.PLACE, dx, dy, dz, block));
        }
        return out;
    }

    private static Direction parseFacing(String s) {
        if (s == null) return Direction.SOUTH;
        try {
            Direction d = Direction.valueOf(s.trim().toUpperCase());
            return d.getAxis().isHorizontal() ? d : Direction.SOUTH;
        } catch (Throwable t) {
            LOG.debug("parse facing failed value={}", s, t);
            return Direction.SOUTH;
        }
    }

    // --------- 以下是“兼容旧构件”的最小启发式（与 PlayerComponentExpander 行为一致） ---------

    private static com.formacraft.common.semantic.SemanticPart guessSemanticPartFromString(String block, int dy, int minDy) {
        // v1 简化：尽量不做复杂分类，优先保底为 GENERIC。
        // 后续如果要保持完全一致，可把 PlayerComponentExpander 的启发式迁移到这里。
        if (block == null) return com.formacraft.common.semantic.SemanticPart.GENERIC;
        String b = block.toLowerCase();
        if (b.contains("glass")) return com.formacraft.common.semantic.SemanticPart.WINDOW;
        if (b.contains("door")) return com.formacraft.common.semantic.SemanticPart.DOOR;
        if (dy == minDy) return com.formacraft.common.semantic.SemanticPart.FOUNDATION;
        return com.formacraft.common.semantic.SemanticPart.GENERIC;
    }

    private static long mixSeed(long base, int x, int y, int z, int salt) {
        long h = base;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) z * 0x165667B19E3779F9L;
        h ^= (long) salt * 0x27D4EB2F165667C5L;
        return h;
    }
}

