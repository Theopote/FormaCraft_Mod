package com.formacraft.common.mass.integration;

import com.formacraft.common.mass.AreaMask;
import com.formacraft.common.mass.BuildingMass;
import com.formacraft.common.mass.MassType;
import com.formacraft.common.mass.RectMask;
import com.formacraft.common.mass.integration.BuildingMassPipeline.BuildingMassPipelineResult;
import com.formacraft.common.generation.component.util.ComponentFacadeStyler;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * BuildingMassToBlockPatchConverter（建筑体量到方块补丁转换器）
 * <p>
 * 🎯 核心职责：
 * 将 BuildingMass 流程结果转换为 BlockPatch 列表
 * <p>
 * 转换逻辑：
 * - Socket → Component → BlockPatch
 * - 使用现有的 Component 和 Generator 系统
 */
public final class BuildingMassToBlockPatchConverter {

    private BuildingMassToBlockPatchConverter() {}

    /**
     * 从 BuildingMassPipelineResult 生成 BlockPatch 列表
     * <p>
     * v1 简化：目前只生成占位 BlockPatch（表示体量范围）
     * 未来：通过 Socket → Component → Generator 生成实际的 BlockPatch
     *
     * @param result 流程结果
     * @param origin 世界原点
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> convertToBlockPatches(
            BuildingMassPipelineResult result,
            BlockPos origin
    ) {
        if (result == null || result.composition == null) {
            return List.of();
        }

        List<BlockPatch> patches = new ArrayList<>();

        try {
            // A3：把每个体量落地为"有基座/墙体/立面节奏/窗/屋顶"的真实方块，
            // 而不是早期的纯石头边界笼子。材质暂用风格中性默认，后续在默认路径接入
            // StyleProfile/Palette 后可替换（见蓝图轨道A A3）。
            for (BuildingMass mass : result.composition.getMasses()) {
                List<BlockPatch> massPatches = generateMassFramework(mass, origin);
                patches.addAll(massPatches);
            }

            FormacraftMod.LOGGER.info(
                    "BuildingMassToBlockPatchConverter: generated {} patches from {} masses",
                    patches.size(),
                    result.composition.getMasses().size()
            );

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("BuildingMassToBlockPatchConverter: conversion failed", e);
        }

        return patches;
    }

    /**
     * A3：把一个体量落地为真实建筑方块。
     * <p>
     * 按 {@link MassType} 决定填充方式（SOLID 实心 / HOLLOW 只留外墙 / SLAB 只顶底 / FRAME 结构柱），
     * 外墙套用 {@link ComponentFacadeStyler} 的立面节奏（基座带 + 楼层线脚），并在墙面按韵律开窗，
     * 顶层封屋盖。footprint 支持任意 {@link AreaMask}（用矩形包围盒扫描 + contains 判定成员）。
     */
    private static List<BlockPatch> generateMassFramework(BuildingMass mass, BlockPos origin) {
        List<BlockPatch> patches = new ArrayList<>();

        if (!(mass.footprint instanceof RectMask rectMask)) {
            return patches;
        }
        final AreaMask footprint = mass.footprint;
        final int minX = rectMask.getMinX();
        final int maxX = rectMask.getMaxX();
        final int minZ = rectMask.getMinZ();
        final int maxZ = rectMask.getMaxZ();
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        final int totalHeight = Math.max(1, mass.height.height());
        final int baseY = mass.height.baseY;

        // Style-neutral default palette (A3). Replaceable once a StyleProfile/Palette is threaded here.
        final String wallId = "minecraft:stone_bricks";
        final String baseId = "minecraft:polished_andesite";
        final String trimId = "minecraft:chiseled_stone_bricks";
        final String roofId = "minecraft:deepslate_tiles";
        final String windowId = "minecraft:glass_pane";
        final int floorHeight = 4;
        final String facadeProfile = "base_plinth";
        final String wallPattern = "gradient";

        final MassType type = mass.type != null ? mass.type : MassType.SOLID;

        for (int y = 0; y < totalHeight; y++) {
            boolean isRoofLayer = (y == totalHeight - 1) && totalHeight >= 2;
            boolean isFloorLayer = (y == 0);

            // SLAB：只在顶/底薄层放方块。
            if (type == MassType.SLAB && !isFloorLayer && !isRoofLayer) {
                continue;
            }

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!footprint.contains(x, z)) {
                        continue;
                    }
                    boolean edge = isPerimeter(footprint, x, z);
                    int lx = x - minX;
                    int lz = z - minZ;

                    // FRAME：只在周边结构柱（每 3 格）+ 顶/底放方块。
                    if (type == MassType.FRAME) {
                        boolean column = edge && ((lx % 3 == 0) || (lz % 3 == 0));
                        if (!column && !isRoofLayer && !isFloorLayer) {
                            continue;
                        }
                    }

                    // HOLLOW：只留外墙 + 顶 + 底，内部中空。
                    if (type == MassType.HOLLOW && !edge && !isFloorLayer && !isRoofLayer) {
                        continue;
                    }

                    String block;
                    if (isRoofLayer) {
                        block = roofId;
                    } else if (isFloorLayer) {
                        block = baseId;
                    } else if (edge) {
                        boolean bandRow = (floorHeight > 0) && (y % floorHeight == 0);
                        boolean corner = isCorner(footprint, x, z);
                        boolean window = !bandRow && !corner && (((lx + lz) % 3) == 1);
                        if (window) {
                            block = windowId;
                        } else {
                            boolean isEdgeZ = (lz == 0 || lz == depth - 1);
                            String styled = ComponentFacadeStyler.applyWallPattern(
                                    wallId, trimId, baseId, wallPattern, y, totalHeight);
                            styled = ComponentFacadeStyler.applyFacadeProfile(
                                    styled, wallId, trimId, baseId, facadeProfile,
                                    true, isEdgeZ, lx, y, lz, width, depth, floorHeight);
                            block = (styled != null && !styled.isEmpty()) ? styled : wallId;
                        }
                    } else {
                        block = wallId;
                    }

                    patches.add(new BlockPatch(
                            BlockPatch.PLACE,
                            x - origin.getX(),
                            baseY + y - origin.getY(),
                            z - origin.getZ(),
                            block
                    ));
                }
            }
        }

        return patches;
    }

    /** 是否处于 footprint 的外围（四邻中有一个不在 mask 内）。 */
    private static boolean isPerimeter(AreaMask mask, int x, int z) {
        if (!mask.contains(x, z)) {
            return false;
        }
        return !mask.contains(x + 1, z) || !mask.contains(x - 1, z)
                || !mask.contains(x, z + 1) || !mask.contains(x, z - 1);
    }

    /** 是否为拐角（外围且至少两个正交方向在 mask 外）。 */
    private static boolean isCorner(AreaMask mask, int x, int z) {
        if (!isPerimeter(mask, x, z)) {
            return false;
        }
        int outside = 0;
        if (!mask.contains(x + 1, z)) outside++;
        if (!mask.contains(x - 1, z)) outside++;
        if (!mask.contains(x, z + 1)) outside++;
        if (!mask.contains(x, z - 1)) outside++;
        return outside >= 2;
    }
}
