package com.formacraft.server.preview;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.dsl.FbdSourcePlan;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预览存储
 * 临时存储玩家最后一次生成的建筑结构，用于预览和确认。
 * <p>
 * 确认契约：维度 + origin + 结构 digest；确认后一次性消费，防止重放。
 */
public class PreviewStorage {
    private static final Map<UUID, GeneratedStructure> lastStructures = new HashMap<>();
    private static final Map<UUID, Boolean> hasPreview = new HashMap<>();
    private static final Map<UUID, Object> skeletonLayouts = new HashMap<>();
    private static final Map<UUID, BuildQualityReport> qualityReports = new HashMap<>();
    private static final Map<UUID, PreviewMeta> metas = new HashMap<>();
    /** Sanitized FBD source plan JSON pending confirm (cleared with structure). */
    private static final Map<UUID, String> sourcePlans = new HashMap<>();

    public record PreviewMeta(
            Identifier dimension,
            BlockPos origin,
            String structureDigest,
            long createdAtMs
    ) {}

    /**
     * 存储玩家的建筑结构（记录维度与 digest）。
     */
    public static void storeStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            lastStructures.put(player.getUuid(), structure);
            hasPreview.put(player.getUuid(), false);
            skeletonLayouts.remove(player.getUuid());
            metas.put(player.getUuid(), buildMeta(player, structure));
            FormacraftMod.LOGGER.debug("Stored preview structure for player {}: {} blocks at {}",
                    player.getName().getString(),
                    structure.getBlocks() != null ? structure.getBlocks().size() : 0,
                    structure.getOrigin());
        }
    }

    /** Store sanitize FBD source plan for confirm → Memory. Non-FBD plans clear any prior plan. */
    public static void storeSourcePlan(ServerPlayerEntity player, LlmPlan plan) {
        if (player == null) return;
        String json = FbdSourcePlan.sanitizeToJson(plan);
        if (json == null) {
            sourcePlans.remove(player.getUuid());
        } else {
            sourcePlans.put(player.getUuid(), json);
        }
    }

    public static String peekSourcePlan(ServerPlayerEntity player) {
        if (player == null) return null;
        return sourcePlans.get(player.getUuid());
    }

    /** Take and clear the pending FBD source plan (may be null). */
    public static String consumeSourcePlan(ServerPlayerEntity player) {
        if (player == null) return null;
        return sourcePlans.remove(player.getUuid());
    }

    /**
     * 更新玩家的预览结构（不改变 preview active 状态；刷新 digest/维度）。
     */
    public static void updateStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            lastStructures.put(player.getUuid(), structure);
            metas.put(player.getUuid(), buildMeta(player, structure));
        }
    }

    /**
     * 验证预览结构是否仍然有效（用于确认建造前检查）。
     */
    public static boolean validatePreview(ServerPlayerEntity player) {
        if (player == null) return false;
        UUID uuid = player.getUuid();
        GeneratedStructure structure = lastStructures.get(uuid);
        if (structure == null) {
            FormacraftMod.LOGGER.warn("Player {} has no preview structure stored", player.getName().getString());
            return false;
        }
        if (structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            FormacraftMod.LOGGER.warn("Player {} preview structure has no blocks", player.getName().getString());
            return false;
        }
        if (structure.getOrigin() == null) {
            FormacraftMod.LOGGER.warn("Player {} preview structure has no origin", player.getName().getString());
            return false;
        }

        PreviewMeta meta = metas.get(uuid);
        if (meta == null) {
            FormacraftMod.LOGGER.warn(
                    "Player {} preview missing PreviewMeta; reject confirm (no auto-backfill)",
                    player.getName().getString());
            return false;
        }

        if (player.getEntityWorld() instanceof ServerWorld sw) {
            Identifier currentDim = sw.getRegistryKey().getValue();
            if (meta.dimension() != null && !meta.dimension().equals(currentDim)) {
                FormacraftMod.LOGGER.warn("Player {} preview dimension mismatch: stored={} current={}",
                        player.getName().getString(), meta.dimension(), currentDim);
                return false;
            }
        }

        String currentDigest = digestStructure(structure);
        if (meta.structureDigest() != null && !meta.structureDigest().equals(currentDigest)) {
            FormacraftMod.LOGGER.warn("Player {} preview digest mismatch", player.getName().getString());
            return false;
        }
        if (meta.origin() != null && structure.getOrigin() != null && !meta.origin().equals(structure.getOrigin())) {
            FormacraftMod.LOGGER.warn("Player {} preview origin mismatch", player.getName().getString());
            return false;
        }
        return true;
    }

    /**
     * 取出并清除预览结构（一次性确认消费）。
     *
     * @return 结构，无效或不存在时返回 null
     */
    public static GeneratedStructure consumeStructure(ServerPlayerEntity player) {
        if (!validatePreview(player)) {
            return null;
        }
        UUID uuid = player.getUuid();
        GeneratedStructure structure = lastStructures.remove(uuid);
        hasPreview.put(uuid, false);
        skeletonLayouts.remove(uuid);
        metas.remove(uuid);
        // sourcePlans retained until explicit consumeSourcePlan so confirm can attach it to the build task.
        return structure;
    }

    public static PreviewMeta getMeta(ServerPlayerEntity player) {
        if (player == null) return null;
        return metas.get(player.getUuid());
    }

    public static GeneratedStructure getStructure(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        return lastStructures.get(player.getUuid());
    }

    public static void clear(ServerPlayerEntity player) {
        if (player != null) {
            UUID uuid = player.getUuid();
            lastStructures.remove(uuid);
            hasPreview.remove(uuid);
            skeletonLayouts.remove(uuid);
            qualityReports.remove(uuid);
            metas.remove(uuid);
            sourcePlans.remove(uuid);
        }
    }

    public static boolean hasPreview(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return hasPreview.getOrDefault(player.getUuid(), false);
    }

    public static void setPreview(ServerPlayerEntity player, boolean active) {
        if (player != null) {
            hasPreview.put(player.getUuid(), active);
        }
    }

    public static void setSkeletonLayout(ServerPlayerEntity player, Object layout) {
        if (player == null) return;
        UUID uuid = player.getUuid();
        if (layout == null) {
            skeletonLayouts.remove(uuid);
        } else {
            skeletonLayouts.put(uuid, layout);
        }
    }

    public static Object getSkeletonLayout(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        return skeletonLayouts.get(player.getUuid());
    }

    public static void storeQualityReport(ServerPlayerEntity player, BuildQualityReport report) {
        if (player != null && report != null) {
            qualityReports.put(player.getUuid(), report);
        }
    }

    public static BuildQualityReport getQualityReport(ServerPlayerEntity player) {
        if (player == null) return null;
        return qualityReports.get(player.getUuid());
    }

    public static void clearQualityReport(ServerPlayerEntity player) {
        if (player != null) {
            qualityReports.remove(player.getUuid());
        }
    }

    private static PreviewMeta buildMeta(ServerPlayerEntity player, GeneratedStructure structure) {
        Identifier dim = null;
        if (player.getEntityWorld() instanceof ServerWorld sw) {
            dim = sw.getRegistryKey().getValue();
        }
        return new PreviewMeta(
                dim,
                structure.getOrigin(),
                digestStructure(structure),
                System.currentTimeMillis()
        );
    }

    /** 方块坐标与目标状态摘要（确认前后一致性）。 */
    public static String digestStructure(GeneratedStructure structure) {
        if (structure == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            BlockPos origin = structure.getOrigin();
            if (origin != null) {
                md.update(("o:" + origin.getX() + "," + origin.getY() + "," + origin.getZ()).getBytes(StandardCharsets.UTF_8));
            }
            List<PlannedBlock> blocks = structure.getBlocks();
            if (blocks != null) {
                md.update(("n:" + blocks.size()).getBytes(StandardCharsets.UTF_8));
                for (PlannedBlock b : blocks) {
                    if (b == null) continue;
                    BlockPos p = b.getPos();
                    String state = b.getTargetState() != null ? b.getTargetState().toString() : "null";
                    String line = (p != null ? p.getX() + "," + p.getY() + "," + p.getZ() : "null") + "|" + state;
                    md.update(line.getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "digest-error:" + (structure.getBlocks() != null ? structure.getBlocks().size() : 0);
        }
    }
}
