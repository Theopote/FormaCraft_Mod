package com.formacraft.server.preview;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.build.BuildingSpec;
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
    /** Monotonic preview generation per player — bumps on store/update. */
    private static final Map<UUID, Long> previewGenerations = new HashMap<>();
    /** Normalized BuildingSpec used for the preview's quality checks (may be null for FBD). */
    private static final Map<UUID, BuildingSpec> buildingSpecs = new HashMap<>();
    /** Sanitized FBD source plan JSON pending confirm (cleared with structure). */
    private static final Map<UUID, String> sourcePlans = new HashMap<>();

    public record PreviewMeta(
            Identifier dimension,
            BlockPos origin,
            String structureDigest,
            long createdAtMs,
            long generation
    ) {}

    /**
     * Immutable confirm-time binding: enqueue this structure, consume only if storage still matches.
     */
    public record PreviewSnapshot(
            GeneratedStructure structure,
            String digest,
            BlockPos origin,
            long generation,
            String fbdSourcePlanJson,
            BuildingSpec buildingSpec
    ) {
        public boolean matchesMeta(PreviewMeta meta) {
            if (meta == null || structure == null || digest == null || digest.isEmpty()) return false;
            if (meta.generation() != generation) return false;
            if (meta.structureDigest() != null && !meta.structureDigest().equals(digest)) return false;
            if (meta.origin() != null && origin != null && !meta.origin().equals(origin)) return false;
            return true;
        }
    }

    /**
     * 存储玩家的建筑结构（记录维度与 digest）。
     * Clears any prior BuildingSpec — callers that have a spec must {@link #storeBuildingSpec} after.
     */
    public static void storeStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            UUID uuid = player.getUuid();
            lastStructures.put(uuid, structure);
            hasPreview.put(uuid, false);
            skeletonLayouts.remove(uuid);
            buildingSpecs.remove(uuid);
            metas.put(uuid, buildMeta(player, structure, nextGeneration(uuid)));
            FormacraftMod.LOGGER.debug("Stored preview structure for player {}: {} blocks at {}",
                    player.getName().getString(),
                    structure.getBlocks() != null ? structure.getBlocks().size() : 0,
                    structure.getOrigin());
        }
    }

    /** Retain the BuildingSpec that participated in preview quality (null clears). */
    public static void storeBuildingSpec(ServerPlayerEntity player, BuildingSpec spec) {
        if (player == null) return;
        if (spec == null) {
            buildingSpecs.remove(player.getUuid());
        } else {
            buildingSpecs.put(player.getUuid(), spec);
        }
    }

    public static BuildingSpec getBuildingSpec(ServerPlayerEntity player) {
        if (player == null) return null;
        return buildingSpecs.get(player.getUuid());
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
     * 更新玩家的预览结构（不改变 preview active 状态；刷新 digest/维度/代次）。
     */
    public static void updateStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            UUID uuid = player.getUuid();
            lastStructures.put(uuid, structure);
            metas.put(uuid, buildMeta(player, structure, nextGeneration(uuid)));
        }
    }

    /**
     * Capture an immutable confirm snapshot (structure copy + digest + generation + FBD/spec).
     * Returns null when there is no usable preview structure.
     */
    public static PreviewSnapshot captureSnapshot(ServerPlayerEntity player) {
        if (player == null) return null;
        GeneratedStructure live = getStructure(player);
        if (live == null) return null;
        PreviewMeta meta = getMeta(player);
        GeneratedStructure copy = copyStructure(live);
        String digest = digestStructure(copy);
        BlockPos origin = copy.getOrigin() != null ? copy.getOrigin().toImmutable() : null;
        long generation = meta != null ? meta.generation() : previewGenerations.getOrDefault(player.getUuid(), 0L);
        return new PreviewSnapshot(
                copy,
                digest,
                origin,
                generation,
                peekSourcePlan(player),
                getBuildingSpec(player)
        );
    }

    /** Restore a confirm snapshot after a failed post-enqueue consume (keeps preview usable). */
    public static void restoreSnapshot(ServerPlayerEntity player, PreviewSnapshot snapshot, BuildQualityReport quality) {
        if (player == null || snapshot == null || snapshot.structure() == null) return;
        UUID uuid = player.getUuid();
        lastStructures.put(uuid, snapshot.structure());
        hasPreview.put(uuid, true);
        metas.put(uuid, buildMeta(player, snapshot.structure(), snapshot.generation()));
        previewGenerations.put(uuid, snapshot.generation());
        if (snapshot.buildingSpec() != null) {
            buildingSpecs.put(uuid, snapshot.buildingSpec());
        } else {
            buildingSpecs.remove(uuid);
        }
        if (snapshot.fbdSourcePlanJson() != null && !snapshot.fbdSourcePlanJson().isBlank()) {
            sourcePlans.put(uuid, snapshot.fbdSourcePlanJson());
        }
        if (quality != null) {
            qualityReports.put(uuid, quality);
        }
    }

    public static GeneratedStructure copyStructure(GeneratedStructure source) {
        if (source == null) return null;
        List<PlannedBlock> blocks = source.getBlocks() == null
                ? List.of()
                : List.copyOf(source.getBlocks());
        BlockPos origin = source.getOrigin() == null ? null : source.getOrigin().toImmutable();
        return new GeneratedStructure(source.getOwner(), origin, source.getDescription(), blocks);
    }

    private static long nextGeneration(UUID uuid) {
        long next = previewGenerations.getOrDefault(uuid, 0L) + 1L;
        previewGenerations.put(uuid, next);
        return next;
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
        buildingSpecs.remove(uuid);
        previewGenerations.remove(uuid);
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
            buildingSpecs.remove(uuid);
            previewGenerations.remove(uuid);
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

    private static PreviewMeta buildMeta(ServerPlayerEntity player, GeneratedStructure structure, long generation) {
        Identifier dim = null;
        if (player.getEntityWorld() instanceof ServerWorld sw) {
            dim = sw.getRegistryKey().getValue();
        }
        BlockPos origin = structure.getOrigin() == null ? null : structure.getOrigin().toImmutable();
        return new PreviewMeta(
                dim,
                origin,
                digestStructure(structure),
                System.currentTimeMillis(),
                generation
        );
    }

    /**
     * Digest of the <b>final planned world state</b> for confirm integrity.
     * <p>
     * Per-cell last-write-wins (chronological list order), then positions sorted.
     * Same final layout ⇒ same digest regardless of distinct-cell list order —
     * aligned with {@link com.formacraft.common.patch.ConfirmedPlanIdentity} /
     * build execution-hash semantics so preview confirm cannot false-fail on
     * harmless reorderings or collide when intermediate overwrites differ only in log order.
     */
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
                java.util.TreeMap<String, String> finalByPos = new java.util.TreeMap<>();
                for (PlannedBlock b : blocks) {
                    if (b == null || b.getPos() == null) continue;
                    BlockPos p = b.getPos();
                    String key = p.getX() + "," + p.getY() + "," + p.getZ();
                    String state = b.getTargetState() != null ? b.getTargetState().toString() : "null";
                    finalByPos.put(key, state);
                }
                md.update(("n:" + finalByPos.size()).getBytes(StandardCharsets.UTF_8));
                for (var entry : finalByPos.entrySet()) {
                    md.update((entry.getKey() + "|" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "digest-error:" + (structure.getBlocks() != null ? structure.getBlocks().size() : 0);
        }
    }
}
