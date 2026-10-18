package com.formacraft.server.patch;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.server.build.PatchHistoryManager;
import com.formacraft.server.build.quality.BuildQualityGate;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.build.quality.PatchQualityChecker;
import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.preview.ConfirmedPlanRegistry;
import com.formacraft.server.preview.GenerationRequestIds;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.preview.PreviewTicket;
import com.formacraft.server.preview.PreviewTicketStorage;
import com.formacraft.server.preview.PreviewWorldStateCapture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 签发 Patch 预览票据并在确认时应用（服务端权威）。
 * <p>
 * 已绑定宫殿计划的 Ticket（{@code requiresConfirmedPlan}）找不到计划时拒绝，
 * 不得降级为普通 Patch 确认；Confirm 同时校验地形快照与 expectedStates。
 */
public final class PatchPreviewService {
    private PatchPreviewService() {}

    private static final double MAX_APPLY_DISTANCE = 96.0;
    private static final long PLAN_TTL_MS = 5L * 60L * 1000L;

    public static PreviewTicket issuePreview(
            ServerPlayerEntity player,
            BlockPos origin,
            List<BlockPatch> rawPatches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax
    ) {
        return issuePreview(player, origin, rawPatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax, true, null);
    }

    public static PreviewTicket issuePreview(
            ServerPlayerEntity player,
            BlockPos origin,
            List<BlockPatch> rawPatches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax,
            boolean deliverToClient
    ) {
        return issuePreview(player, origin, rawPatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax, deliverToClient, null);
    }

    public static PreviewTicket issuePreview(
            ServerPlayerEntity player,
            BlockPos origin,
            List<BlockPatch> rawPatches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax,
            boolean deliverToClient,
            PalacePreviewPlan confirmedPlan
    ) {
        if (player == null || origin == null) {
            return null;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld sw)) {
            return null;
        }

        List<BlockPatch> sourcePatches = rawPatches;
        String ctx = "";
        String explicitPlanId = null;
        boolean requiresPlan = false;
        UUID requestId = GenerationRequestIds.current();
        String worldKey = PalacePreviewPlan.worldKeyOf(sw);

        if (confirmedPlan == null && requestId != null) {
            confirmedPlan = ConfirmedPlanRegistry.takePending(
                    requestId, player.getUuid(), worldKey, origin);
        }

        if (confirmedPlan != null) {
            if (!confirmedPlan.executable()) {
                if (deliverToClient) {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                            "预览计划不可执行（示意预览或生成失败），请重新预览。"
                    ));
                }
                return null;
            }
            sourcePatches = confirmedPlan.patches();
            ctx = confirmedPlan.contextHash();
            explicitPlanId = confirmedPlan.planId();
            requiresPlan = true;
            if (requestId == null) {
                requestId = GenerationRequestIds.currentOrCreate();
            }
        }
        if (sourcePatches == null || sourcePatches.isEmpty()) {
            return null;
        }
        if (!isPlayerNear(player, origin)) {
            return null;
        }

        BuildQualityReport quality = PatchQualityChecker.check(sw, origin, sourcePatches);
        quality.logIssues("patch-preview");

        if (!quality.allowPreview()) {
            if (deliverToClient) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(quality.summaryZh()));
            }
            PreviewStorage.storeQualityReport(player, quality);
            return null;
        }

        PatchFilterResult filtered = ServerPatchFilter.filter(
                origin, sourcePatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax);

        if (filtered.rejected != null && !filtered.rejected.isEmpty()) {
            quality.stats().rejectedPatches = filtered.rejected.size();
            quality.add(BuildQualitySeverity.INFO, "PATCH_FILTERED",
                    "过滤 " + filtered.rejected.size() + " 个越界 Patch 操作");
        }

        if (filtered.accepted.isEmpty()) {
            quality.add(BuildQualitySeverity.FATAL, "PATCH_ALL_REJECTED",
                    "所有 Patch 均被约束过滤，无法预览");
            if (deliverToClient) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(quality.summaryZh()));
            }
            PreviewStorage.storeQualityReport(player, quality);
            return null;
        }

        List<BlockPatch> acceptedFinal = ConfirmedPlanIdentity.toFinalPatches(filtered.accepted);
        if (confirmedPlan != null) {
            String acceptedHash = ConfirmedPlanIdentity.patchHash(acceptedFinal);
            if (!acceptedHash.equals(confirmedPlan.patchHash())) {
                quality.add(BuildQualitySeverity.FATAL, "PLAN_PATCH_FILTERED",
                        "确认计划的方块列表被约束过滤改变，拒绝签发预览");
                if (deliverToClient) {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                            "预览计划与过滤结果不一致，请缩小约束范围后重试。"
                    ));
                }
                PreviewStorage.storeQualityReport(player, quality);
                return null;
            }
        }

        Identifier dimension = sw.getRegistryKey().getValue();
        BuildQualityGate.QualitySnapshot qualitySnap = BuildQualityGate.QualitySnapshot.from(quality);
        Map<BlockPos, String> expected = PreviewWorldStateCapture.capture(sw, origin, acceptedFinal);
        PreviewTicket ticket = PreviewTicketStorage.create(
                player.getUuid(), dimension, origin, acceptedFinal, qualitySnap,
                PLAN_TTL_MS, explicitPlanId, ctx, expected, requiresPlan, requestId);
        if (ticket == null) {
            return null;
        }

        if (confirmedPlan != null) {
            ConfirmedPlanRegistry.put(
                    player.getUuid(), requestId, worldKey, origin, confirmedPlan, PLAN_TTL_MS);
            quality.add(BuildQualitySeverity.INFO, "CONFIRMED_PLAN",
                    "planId=" + confirmedPlan.planId() + " requestId=" + requestId);
        }

        PreviewStorage.storeQualityReport(player, quality);

        if (deliverToClient) {
            FormaCraftServerNetworking.sendPatchPreview(
                    player,
                    ticket.id(),
                    origin,
                    acceptedFinal,
                    filtered.rejected
            );
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(quality.summaryZh()));
        }
        return ticket;
    }

    public static boolean confirm(ServerPlayerEntity player, UUID ticketId) {
        return confirm(player, ticketId, false);
    }

    public static boolean confirm(ServerPlayerEntity player, UUID ticketId, boolean force) {
        if (player == null || ticketId == null) return false;
        if (!(player.getEntityWorld() instanceof ServerWorld sw)) return false;

        PreviewTicket pending = PreviewTicketStorage.get(ticketId);
        if (pending == null || !player.getUuid().equals(pending.playerId())) {
            FormacraftMod.LOGGER.warn("Player {} tried to confirm unknown/expired/foreign patch ticket {}",
                    player.getName().getString(), ticketId);
            return false;
        }

        Identifier currentDim = sw.getRegistryKey().getValue();
        if (pending.dimension() != null && !pending.dimension().equals(currentDim)) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "预览维度与当前世界不一致，请回到原维度后确认，或重新预览。"
            ));
            return false;
        }

        if (!isPlayerNear(player, pending.origin())) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "距离预览原点过远，请靠近后再确认（票据仍有效）。"
            ));
            return false;
        }

        if (!pending.patchIntegrityOk()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "预览计划方块列表完整性校验失败，请重新预览。"
            ));
            return false;
        }

        PalacePreviewPlan bound = ConfirmedPlanRegistry.get(pending.planId(), player.getUuid());
        if (pending.requiresConfirmedPlan()) {
            if (bound == null) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "确认计划已丢失或过期（PLAN_MISSING），请重新预览。不得降级执行。"
                ));
                return false;
            }
            if (!bound.patchHash().equals(pending.patchHash())) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "确认计划与预览票据的方块列表不一致，请重新预览。"
                ));
                return false;
            }
            if (!bound.contextHash().equals(pending.contextHash())) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "建造上下文已变化（参数/原点/材质），请重新预览后再确认。"
                ));
                return false;
            }
            // Terrain + build-context validation (not replaceable by expectedStates).
            PalacePreviewPlan.ApplyValidation terrain = bound.validateLiveTerrainAndContext(sw);
            if (!terrain.ok()) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "地形或建造上下文校验失败：" + terrain.reason() + " " + terrain.detail()
                ));
                return false;
            }
        } else if (bound != null) {
            // Soft bind (legacy): still verify hashes if a plan happens to be present.
            if (!bound.patchHash().equals(pending.patchHash())
                    || !bound.contextHash().equals(pending.contextHash())) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "预览计划与票据不一致，请重新预览。"
                ));
                return false;
            }
        }

        BlockMutationAccess access = BlockMutationAccess.forWorld(sw);
        String conflict = PreviewWorldStateCapture.conflictDetail(access, pending.expectedStates());
        if (conflict != null) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "建造区域已被修改，预览已过期，请重新预览。(" + conflict + ")"
            ));
            return false;
        }

        BuildQualityGate.Decision decision = BuildQualityGate.decide(pending.quality(), force);
        if (decision == BuildQualityGate.Decision.DENY_FATAL) {
            String msg = pending.quality() != null ? pending.quality().summaryZh() : "质量检查失败";
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(msg));
            return false;
        }
        if (decision == BuildQualityGate.Decision.REQUIRE_FORCE) {
            String msg = pending.quality() != null ? pending.quality().summaryZh() : "存在质量错误";
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "质量错误，需强制确认后才能应用：" + msg
            ));
            return false;
        }
        if (force && pending.quality() != null && pending.quality().hasError()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "【强制确认】" + pending.quality().summaryZh()
            ));
        }

        PreviewTicket ticket = PreviewTicketStorage.consume(ticketId, player.getUuid());
        if (ticket == null) {
            return false;
        }

        conflict = PreviewWorldStateCapture.conflictDetail(access, ticket.expectedStates());
        if (conflict != null) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "建造区域在确认瞬间被修改，已拒绝写入。请重新预览。"
            ));
            return false;
        }

        // Re-validate terrain after consume for require-bound tickets (TOCTOU).
        if (ticket.requiresConfirmedPlan() && bound != null) {
            PalacePreviewPlan.ApplyValidation terrain = bound.validateLiveTerrainAndContext(sw);
            if (!terrain.ok()) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                        "确认瞬间地形校验失败：" + terrain.reason() + " " + terrain.detail()
                ));
                return false;
            }
        }

        List<BlockPatch> patches = ticket.patches();
        if (patches == null || patches.isEmpty()) return false;

        if (bound != null) {
            ConfirmedPlanRegistry.remove(bound.planId(), player.getUuid());
        }

        var applyResult = PatchHistoryManager.applyWithHistory(
                sw, player.getUuid(), ticket.origin(), patches);
        if (applyResult != null) {
            FormaCraftServerNetworking.sendPatchApplyResult(player, applyResult);
            if (applyResult.status() != BuildExecutionStatus.SUCCESS) {
                return false;
            }
        }
        PreviewStorage.clearQualityReport(player);
        return true;
    }

    private static boolean isPlayerNear(ServerPlayerEntity player, BlockPos origin) {
        if (player == null || origin == null) return false;
        double d2 = player.squaredDistanceTo(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
        return d2 <= MAX_APPLY_DISTANCE * MAX_APPLY_DISTANCE;
    }

    public static String verifyExpectedStates(BlockMutationAccess access, PreviewTicket ticket) {
        if (ticket == null) return "ticket_null";
        if (!ticket.patchIntegrityOk()) return "patch_integrity";
        return PreviewWorldStateCapture.conflictDetail(access, ticket.expectedStates());
    }
}
