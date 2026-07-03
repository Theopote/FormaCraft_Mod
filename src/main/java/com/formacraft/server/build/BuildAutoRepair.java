package com.formacraft.server.build;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.StyleGenomeRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BuildAutoRepair (H-layer MVP):
 * Runs after generation but before preview/apply to prevent obvious "generation accidents".
 *
 * v1 scope:
 * - Floating support: for bottom-most columns near base, if unsupported, add support pillars downwards.
 *
 * Notes:
 * - Does NOT change skeleton/topology. Only inserts supporting blocks.
 * - Respects BuildConstraintContext.allow(...) (selection/outline/protected zones).
 */
public final class BuildAutoRepair {
    private BuildAutoRepair() {}

    public record Result(List<PlannedBlock> blocks,
                         int columnsFixed,
                         int supportBlocksAdded,
                         int doorsFixed,
                         int entranceClears,
                         String summary) {}

    public static Result apply(ServerWorld world, Optional<BuildingStyle> style, List<PlannedBlock> original) {
        if (world == null || original == null || original.isEmpty()) {
            return new Result(original == null ? List.of() : deduplicateBlocks(original), 0, 0, 0, 0, "");
        }

        // Final state per position (later planned blocks override earlier ones).
        Map<BlockPos, BlockState> finalState = new HashMap<>(Math.max(1024, original.size() * 2));
        int overallMinY = Integer.MAX_VALUE;
        for (PlannedBlock pb : original) {
            if (pb == null) continue;
            BlockPos p = pb.getPos();
            BlockState s = pb.getTargetState();
            if (p == null || s == null) continue;
            finalState.put(p, s);
            if (s.getBlock() != Blocks.AIR) {
                overallMinY = Math.min(overallMinY, p.getY());
            }
        }
        if (overallMinY == Integer.MAX_VALUE) {
            return new Result(deduplicateBlocks(original), 0, 0, 0, 0, "");
        }

        // Only consider columns whose bottom-most block is near the base band (avoid fixing roof/cap-only overhang columns).
        int baseBandMaxY = overallMinY + 2;

        // Compute bottom-most Y per (x,z).
        Map<Long, Integer> minYByXZ = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : finalState.entrySet()) {
            BlockPos p = e.getKey();
            BlockState s = e.getValue();
            if (s == null || s.getBlock() == Blocks.AIR) continue;
            long key = xzKey(p.getX(), p.getZ());
            int y = p.getY();
            Integer cur = minYByXZ.get(key);
            if (cur == null || y < cur) minYByXZ.put(key, y);
        }

        BlockState support = resolveSupportBlock(world, style);

        final int maxDepth = 24;
        final int maxColumnsToFix = 256;

        int fixedColumns = 0;
        int added = 0;
        int doorsFixed = 0;
        int entranceClears = 0;

        List<PlannedBlock> extras = new ArrayList<>();

        for (Map.Entry<Long, Integer> col : minYByXZ.entrySet()) {
            if (fixedColumns >= maxColumnsToFix) break;

            long key = col.getKey();
            int x = (int) (key >> 32);
            int z = (int) key;
            int yMin = col.getValue();

            if (yMin > baseBandMaxY) continue;

            // Skip if already supported directly below.
            BlockPos below = new BlockPos(x, yMin - 1, z);
            BlockState plannedBelow = finalState.get(below);
            if (plannedBelow != null && plannedBelow.getBlock() != Blocks.AIR) continue;
            if (!world.getBlockState(below).isAir()) continue;

            int bottomLimit = Math.max(world.getBottomY(), yMin - maxDepth);

            // Find a support point (planned or world) within maxDepth.
            int supportY = Integer.MIN_VALUE;
            for (int y = yMin - 1; y >= bottomLimit; y--) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState ps = finalState.get(p);
                if (ps != null && ps.getBlock() != Blocks.AIR) {
                    supportY = y;
                    break;
                }
                if (!world.getBlockState(p).isAir()) {
                    supportY = y;
                    break;
                }
            }
            if (supportY == Integer.MIN_VALUE) {
                // Too deep / void / ocean: skip v1 (we don't want to spam pillars to bedrock).
                continue;
            }

            boolean anyPlaced = false;
            for (int y = supportY + 1; y <= yMin - 1; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (!BuildConstraintContext.allow(p)) continue;
                BlockState ps = finalState.get(p);
                if (ps != null && ps.getBlock() != Blocks.AIR) continue;

                extras.add(new PlannedBlock(p, support));
                finalState.put(p, support);
                added++;
                anyPlaced = true;
            }
            if (anyPlaced) fixedColumns++;
        }

        // Door completeness fix (Spatial MVP):
        // Ensure a planned door has both halves. This prevents "broken doors" caused by overrides/clipping.
        for (Map.Entry<BlockPos, BlockState> e : new ArrayList<>(finalState.entrySet())) {
            BlockPos p = e.getKey();
            BlockState s = e.getValue();
            if (p == null || s == null) continue;
            if (!(s.getBlock() instanceof DoorBlock)) continue;
            if (!s.contains(Properties.DOUBLE_BLOCK_HALF)) continue;
            if (s.get(Properties.DOUBLE_BLOCK_HALF) != net.minecraft.block.enums.DoubleBlockHalf.LOWER) continue;

            BlockPos up = p.up();
            BlockState plannedUp = finalState.get(up);
            boolean ok =
                    plannedUp != null
                            && (plannedUp.getBlock() instanceof DoorBlock)
                            && plannedUp.contains(Properties.DOUBLE_BLOCK_HALF)
                            && plannedUp.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER;
            if (ok) continue;

            if (!BuildConstraintContext.allow(up)) continue;

            // Only place if the world is empty and no non-air planned block already occupies it.
            if (plannedUp != null && plannedUp.getBlock() != Blocks.AIR) continue;
            if (!world.getBlockState(up).isAir()) continue;

            BlockState upper = s.with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER);
            extras.add(new PlannedBlock(up, upper));
            finalState.put(up, upper);
            doorsFixed++;
        }

        // Entrance clearance fix (Spatial MVP v2):
        // If the block(s) directly in front of / behind a door are obstructed by world blocks, carve a 2-high path.
        // This does NOT override planned non-air blocks (generator intent wins).
        for (Map.Entry<BlockPos, BlockState> e : new ArrayList<>(finalState.entrySet())) {
            BlockPos p = e.getKey();
            BlockState s = e.getValue();
            if (p == null || s == null) continue;
            if (!(s.getBlock() instanceof DoorBlock)) continue;
            if (!s.contains(Properties.DOUBLE_BLOCK_HALF)) continue;
            if (s.get(Properties.DOUBLE_BLOCK_HALF) != net.minecraft.block.enums.DoubleBlockHalf.LOWER) continue;
            if (!s.contains(Properties.HORIZONTAL_FACING)) continue;

            Direction facing = s.get(Properties.HORIZONTAL_FACING);
            if (facing == null) continue;

            // outside (in facing direction)
            BlockPos frontLower = p.offset(facing);
            BlockPos frontUpper = frontLower.up();

            entranceClears += tryCarveAir(world, finalState, extras, frontLower);
            entranceClears += tryCarveAir(world, finalState, extras, frontUpper);

            // inside (opposite direction)
            Direction insideDir = facing.getOpposite();
            BlockPos backLower = p.offset(insideDir);
            BlockPos backUpper = backLower.up();
            entranceClears += tryCarveAir(world, finalState, extras, backLower);
            entranceClears += tryCarveAir(world, finalState, extras, backUpper);
        }

        if (extras.isEmpty()) {
            return new Result(deduplicateBlocks(original), 0, 0, 0, 0, "");
        }

        List<PlannedBlock> merged = new ArrayList<>(original.size() + extras.size());
        merged.addAll(original);
        merged.addAll(extras); // append so supports "win" over any earlier AIR-carves

        // 去重：移除重复位置的方块（保留最后一个）
        List<PlannedBlock> deduplicated = deduplicateBlocks(merged);

        String summary = String.format("已自动修复：支撑柱 %d 处（+%d）｜补齐门 %d 处｜入口净空 %d 格",
                fixedColumns, added, doorsFixed, entranceClears);
        return new Result(deduplicated, fixedColumns, added, doorsFixed, entranceClears, summary);
    }

    /**
     * 去重：移除重复位置的方块（保留最后一个）
     */
    private static List<PlannedBlock> deduplicateBlocks(List<PlannedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return blocks == null ? List.of() : blocks;
        
        // 使用 Map 去重：相同位置的方块，后面的覆盖前面的
        Map<BlockPos, BlockState> finalStates = new HashMap<>(blocks.size());
        for (PlannedBlock block : blocks) {
            if (block == null) continue;
            BlockPos pos = block.getPos();
            BlockState state = block.getTargetState();
            if (pos != null && state != null) {
                finalStates.put(pos, state);
            }
        }
        
        // 转换回 PlannedBlock 列表
        List<PlannedBlock> deduplicated = new ArrayList<>(finalStates.size());
        for (Map.Entry<BlockPos, BlockState> entry : finalStates.entrySet()) {
            deduplicated.add(new PlannedBlock(entry.getKey(), entry.getValue()));
        }
        
        return deduplicated;
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static BlockState resolveSupportBlock(ServerWorld world, Optional<BuildingStyle> style) {
        try {
            if (style != null && style.isPresent()) {
                StyleGenome g = StyleGenomeRegistry.forStyle(style.get());
                if (g != null && g.palette != null && g.palette.foundation != null && !g.palette.foundation.isBlank()) {
                    BlockState s = parseBlockState(world, g.palette.foundation);
                    if (s != null) return s;
                }
            }
        } catch (Throwable ignored) {}
        return Blocks.COBBLESTONE.getDefaultState();
    }

    private static BlockState parseBlockState(ServerWorld world, String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return null;
            return Registries.BLOCK.get(ident).getDefaultState();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int tryCarveAir(ServerWorld world,
                                  Map<BlockPos, BlockState> finalState,
                                  List<PlannedBlock> extras,
                                  BlockPos p) {
        if (p == null) return 0;
        if (!BuildConstraintContext.allow(p)) return 0;
        BlockState planned = finalState.get(p);
        if (planned != null && planned.getBlock() != Blocks.AIR) return 0; // don't override generator intent
        if (world.getBlockState(p).isAir()) return 0;
        extras.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
        finalState.put(p, Blocks.AIR.getDefaultState());
        return 1;
    }
}


