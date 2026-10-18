package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.server.build.quality.BuildQualityGate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端持有的 Patch 预览票据：确认时仅接受 ticketId，客户端不能篡改 patch 内容。
 * <p>
 * {@code dimension} 绑定签发时的世界维度；{@code quality} 绑定签发时的质量摘要；
 * {@code planId}/{@code patchHash}/{@code contextHash} 绑定用户确认的冻结计划身份；
 * {@code expectedStates} 记录预览时受影响格的原状态，供 Apply 前冲突检测。
 */
public record PreviewTicket(
        UUID id,
        UUID playerId,
        Identifier dimension,
        BlockPos origin,
        List<BlockPatch> patches,
        long expiresAtMillis,
        BuildQualityGate.QualitySnapshot quality,
        String planId,
        String patchHash,
        String contextHash,
        Map<BlockPos, String> expectedStates
) {
    public PreviewTicket {
        patches = patches != null ? List.copyOf(patches) : List.of();
        planId = planId == null ? "" : planId;
        patchHash = patchHash == null ? "" : patchHash;
        contextHash = contextHash == null ? "" : contextHash;
        expectedStates = expectedStates == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(expectedStates));
    }

    /** Backward-compatible constructor (no plan identity / expected states). */
    public PreviewTicket(
            UUID id,
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            long expiresAtMillis,
            BuildQualityGate.QualitySnapshot quality
    ) {
        this(id, playerId, dimension, origin, patches, expiresAtMillis, quality,
                "", "", "", Map.of());
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }

    /** Recompute patch hash from frozen patches; must match {@link #patchHash()}. */
    public boolean patchIntegrityOk() {
        if (patchHash.isBlank()) {
            return true; // legacy tickets
        }
        return patchHash.equals(ConfirmedPlanIdentity.patchHash(patches));
    }

    public String expectedWorldFingerprint() {
        return ConfirmedPlanIdentity.worldStateFingerprint(expectedStates);
    }
}
