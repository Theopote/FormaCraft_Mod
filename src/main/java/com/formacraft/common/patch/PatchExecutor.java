package com.formacraft.common.patch;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Patch 执行器：在服务端世界应用增量修改。
 * <p>
 * 约定：
 * - place/replace：setBlockState(target)
 * - remove：setBlockState(AIR)
 */
public final class PatchExecutor {
    private PatchExecutor() {}

    public static void apply(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || origin == null || patches == null || patches.isEmpty()) return;

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());

            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                continue;
            }

            // place/replace：目标方块
            BlockState target = parseBlockState(p.targetBlock());
            world.setBlockState(pos, target, 3);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState parseBlockState(String id) {
        if (id == null || id.isBlank()) return Blocks.AIR.getDefaultState();
        try {
            String raw = id.trim();
            // 支持 v1 blockstate string：minecraft:oak_stairs[facing=east,half=bottom]
            String baseId = raw;
            String props = null;
            int lb = raw.indexOf('[');
            if (lb >= 0 && raw.endsWith("]")) {
                baseId = raw.substring(0, lb);
                props = raw.substring(lb + 1, raw.length() - 1);
            }

            Identifier ident = Identifier.tryParse(baseId.trim());
            if (ident == null) return Blocks.AIR.getDefaultState();
            Block b = Registries.BLOCK.get(ident);
            BlockState state = b.getDefaultState();

            // 应用属性（尽力而为：解析失败则忽略该键值）
            if (props != null && !props.isBlank()) {
                StateManager<Block, BlockState> sm = b.getStateManager();
                if (sm != null) {
                    String[] kvs = props.split(",");
                    for (String kv : kvs) {
                        if (kv == null) continue;
                        String t = kv.trim();
                        if (t.isEmpty()) continue;
                        int eq = t.indexOf('=');
                        if (eq <= 0 || eq >= t.length() - 1) continue;
                        String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String val = t.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                        if (key.isEmpty() || val.isEmpty()) continue;

                        Property<?> prop = sm.getProperty(key);
                        if (prop == null) continue;
                        Optional<?> parsed = prop.parse(val);
                        if (parsed.isEmpty()) continue;
                        Object v = parsed.get();
                        if (!(v instanceof Comparable<?> c)) continue;
                        // 类型擦除下的通用属性写入：我们只在 parse() 成功后写入
                        state = state.with((Property) prop, (Comparable) c);
                    }
                }
            }

            return state;
        } catch (Throwable ignored) {
            return Blocks.AIR.getDefaultState();
        }
    }
}

