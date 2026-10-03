package com.formacraft.common.patch;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.world.BlockMutationAccess;
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
            int skippedIllegal,
            int skippedSameState,
            int failedWrites
    ) {
        public ApplyResult(int applied, int skippedWorldHeight, int skippedUnloaded, int skippedIllegal) {
            this(applied, skippedWorldHeight, skippedUnloaded, skippedIllegal, 0, 0);
        }
        public int skippedTotal() {
            return skippedWorldHeight + skippedUnloaded + skippedIllegal + skippedSameState;
        }

        /** 玩家可读的应用结果摘要（中文）。 */
        public String summaryZh() {
            if (applied <= 0 && skippedTotal() <= 0 && failedWrites <= 0) {
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
            if (skippedSameState > 0) sb.append("，同状态跳过 ").append(skippedSameState).append(" 个");
            if (failedWrites > 0) sb.append("，写入失败 ").append(failedWrites).append(" 个");
            return sb.toString();
        }
    }

    public static ApplyResult apply(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || origin == null || patches == null || patches.isEmpty()) {
            return new ApplyResult(0, 0, 0, 0);
        }

        return applyToAccess(BlockMutationAccess.forWorld(world), origin, patches);
    }

    public static ApplyResult applyToAccess(BlockMutationAccess access, BlockPos origin, List<BlockPatch> patches) {
        if (access == null || origin == null || patches == null || patches.isEmpty()) {
            return new ApplyResult(0, 0, 0, 0);
        }
        int applied = 0;
        int skippedHeight = 0;
        int skippedUnloaded = 0;
        int skippedIllegal = 0;
        int sameState = 0;
        int failedWrites = 0;

        for (BlockPatch p : patches) {
            if (p == null) {
                skippedIllegal++;
                continue;
            }
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());

            if (!access.isInsideHeight(pos)) {
                skippedHeight++;
                continue;
            }
            if (!access.isChunkReady(pos)) {
                skippedUnloaded++;
                continue;
            }

            String action = p.action() == null ? "" : p.action().trim().toLowerCase(Locale.ROOT);
            BlockState target;
            if (BlockPatch.REMOVE.equals(action)) {
                target = Blocks.AIR.getDefaultState();
            } else if (BlockPatch.PLACE.equals(action) || BlockPatch.REPLACE.equals(action)) {
                target = parseBlockState(p.targetBlock());
            } else {
                target = null;
            }
            if (target == null) {
                skippedIllegal++;
            } else if (access.getState(pos).equals(target)) {
                sameState++;
            } else if (access.setState(pos, target)) {
                applied++;
            } else {
                failedWrites++;
            }
        }

        if (skippedHeight + skippedUnloaded + skippedIllegal > 0) {
            LOG.debug("patch apply skipped height={} unloaded={} illegal={} applied={}",
                    skippedHeight, skippedUnloaded, skippedIllegal, applied);
        }
        return new ApplyResult(applied, skippedHeight, skippedUnloaded, skippedIllegal, sameState, failedWrites);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState parseBlockState(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            String raw = id.trim();
            String baseId = raw;
            String props = null;
            int lb = raw.indexOf('[');
            if (lb >= 0 && (!raw.endsWith("]") || raw.indexOf('[', lb + 1) >= 0)) return null;
            if (lb < 0 && raw.contains("]")) return null;
            if (lb >= 0) {
                baseId = raw.substring(0, lb);
                props = raw.substring(lb + 1, raw.length() - 1);
            }

            Identifier ident = Identifier.tryParse(baseId.trim());
            if (ident == null || !Registries.BLOCK.containsId(ident)) {
                return null;
            }
            Block b = Registries.BLOCK.get(ident);
            BlockState state = b.getDefaultState();

            if (props != null && !props.isBlank()) {
                StateManager<Block, BlockState> sm = b.getStateManager();
                if (sm != null) {
                    String[] kvs = props.split(",", -1);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (String kv : kvs) {
                        if (kv == null) continue;
                        String t = kv.trim();
                        if (t.isEmpty()) return null;
                        int eq = t.indexOf('=');
                        if (eq <= 0 || eq >= t.length() - 1) return null;
                        String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String val = t.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                        if (key.isEmpty() || val.isEmpty() || !seen.add(key)) return null;

                        Property<?> prop = sm.getProperty(key);
                        if (prop == null) return null;
                        Optional<?> parsed = prop.parse(val);
                        if (parsed.isEmpty()) return null;
                        Object v = parsed.get();
                        if (!(v instanceof Comparable<?>)) return null;
                        state = state.with((Property) prop, (Comparable) v);
                    }
                }
            }

            return state;
        } catch (RuntimeException ex) {
            LOG.debug("resolve block state failed blockId={}", id, ex);
            return null;
        }
    }
}
