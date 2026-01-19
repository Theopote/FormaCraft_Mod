package com.formacraft.common.mass.integration;

import com.formacraft.common.mass.BuildingMass;
import com.formacraft.common.mass.RectMask;
import com.formacraft.common.mass.integration.BuildingMassPipeline.BuildingMassPipelineResult;
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
            // v1 简化：为每个体量生成一个简单的框架 BlockPatch
            // 这只是演示用途，实际应该通过 Socket → Component → Generator
            for (BuildingMass mass : result.composition.getMasses()) {
                // 生成体量的边界框（简单的框架表示）
                List<BlockPatch> massPatches = generateMassFramework(mass, origin);
                patches.addAll(massPatches);
            }

            // 未来：从 Socket 生成 Component，再生成 BlockPatch
            // List<Socket> sockets = result.getAllProcessedSockets();
            // for (Socket socket : sockets) {
            //     // 查询 Component
            //     // 生成 BlockPatch
            // }

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
     * 生成体量的框架 BlockPatch（v1 简化）
     * <p>
     * 只生成边界框，用于验证体量位置
     */
    private static List<BlockPatch> generateMassFramework(BuildingMass mass, BlockPos origin) {
        List<BlockPatch> patches = new ArrayList<>();

        // v1 简化：使用 RectMask 生成边界
        if (mass.footprint instanceof RectMask rectMask) {
            // 获取边界坐标
            int minX = rectMask.getMinX();
            int maxX = rectMask.getMaxX();
            int minZ = rectMask.getMinZ();
            int maxZ = rectMask.getMaxZ();
            
            // 生成底部边界
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // 只生成边界位置的方块
                    if (x == minX || x == maxX || z == minZ || z == maxZ) {
                        int dx = x - origin.getX();
                        int dy = mass.height.baseY - origin.getY();
                        int dz = z - origin.getZ();

                        // 生成垂直柱（体量的高度）
                        for (int y = 0; y < mass.height.height(); y++) {
                            patches.add(new BlockPatch(
                                    BlockPatch.PLACE,
                                    dx,
                                    dy + y,
                                    dz,
                                    "minecraft:stone" // v1 占位材质
                            ));
                        }
                    }
                }
            }
        }

        return patches;
    }
}
