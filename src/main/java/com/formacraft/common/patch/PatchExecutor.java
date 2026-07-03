package com.formacraft.common.patch;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.world.WorldBuildBounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
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
 * <p>
 * 跳过未加载区块、世界高度越界与非法方块目标（计入 {@link ApplyResult}）。
 */
public final class PatchExecutor {
    private static final FcaLog LOG = FcaLog.of("PatchExecutor");

    private PatchExecutor() {}

    public record ApplyResult(
            int applied,
            int skippedWorldHeight,
            int skippedUnloaded,
            int skippedIllegal
    ) {
        public int skippedTotal() {
            return skippedWorldHeight + skippedUnloaded + skippedIllegal;
        }

        /** 玩家可读的应用结果摘要（中文）。 */
        public String summaryZh() {
            if (applied <= 0 && skippedTotal() <= 0) {
                return "未应用任何方块修改";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("已应用 ").append(applied).append(" 个方块");
            if (skippedWorldHeight > 0) {
                sb.append("，跳过 ").append(skippedWorldHeight).append(" 个越界方块");
            }
            if (skippedUnloaded > 0) {
                sb.append("，跳过 ").append(skippedUnloaded).append(" 个未加载区块方块");
            }
            if (skippedIllegal > 0) {
                sb.append("，跳过 ").append(skippedIllegal).append(" 个非法目标");
            }
            return sb.toString();
        }
    }

    public static ApplyResult apply(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || origin == null || patches == null || patches.isEmpty()) {
            return new ApplyResult(0, 0, 0, 0);
        }

        int applied = 0;
        int skippedHeight = 0;
        int skippedUnloaded = 0;
        int skippedIllegal = 0;

        for (BlockPatch p : patches) {
            if (p == null) {
                skippedIllegal++;
                continue;
            }
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());

            if (!WorldBuildBounds.isInsideWorldHeight(world, pos)) {
                skippedHeight++;
                continue;
            }
            if (!WorldBuildBounds.isChunkReady(world, pos)) {
                skippedUnloaded++;
                continue;
            }

            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                applied++;
                continue;
            }

            if (p.targetBlock() == null || p.targetBlock().isBlank()) {
                skippedIllegal++;
                continue;
            }

            BlockState target = parseBlockState(p.targetBlock());
            if (target.getBlock() == Blocks.AIR && !BlockPatch.REMOVE.equals(action)) {
                Identifier ident = Identifier.tryParse(stripProperties(p.targetBlock()));
                if (ident == null || !Registries.BLOCK.containsId(ident)) {
                    skippedIllegal++;
                    continue;
                }
            }

            world.setBlockState(pos, target, 3);
            applied++;
        }

        if (skippedHeight + skippedUnloaded + skippedIllegal > 0) {
            LOG.debug("patch apply skipped height={} unloaded={} illegal={} applied={}",
                    skippedHeight, skippedUnloaded, skippedIllegal, applied);
        }
        return new ApplyResult(applied, skippedHeight, skippedUnloaded, skippedIllegal);
    }

    private static String stripProperties(String raw) {
        if (raw == null) return "";
        int lb = raw.indexOf('[');
        return lb >= 0 ? raw.substring(0, lb).trim() : raw.trim();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState parseBlockState(String id) {
        if (id == null || id.isBlank()) return Blocks.AIR.getDefaultState();
        try {
            String raw = id.trim();
            String baseId = raw;
            String props = null;
            int lb = raw.indexOf('[');
            if (lb >= 0 && raw.endsWith("]")) {
                baseId = raw.substring(0, lb);
                props = raw.substring(lb + 1, raw.length() - 1);
            }

            Identifier ident = Identifier.tryParse(baseId.trim());
            if (ident == null || !Registries.BLOCK.containsId(ident)) {
                return Blocks.AIR.getDefaultState();
            }
            Block b = Registries.BLOCK.get(ident);
            BlockState state = b.getDefaultState();

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
                        if (!(v instanceof Comparable<?>)) continue;
                        state = state.with((Property) prop, (Comparable) v);
                    }
                }
            }

            return state;
        } catch (Throwable ex) {
            LOG.debug("resolve block state failed blockId={}", id, ex);
            return Blocks.AIR.getDefaultState();
        }
    }
}
