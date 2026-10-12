package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.build.quality.BuildQualityGate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 服务端持有的 Patch 预览票据：确认时仅接受 ticketId，客户端不能篡改 patch 内容。
 * <p>
 * {@code dimension} 绑定签发时的世界维度；{@code quality} 绑定签发时的质量摘要，
 * 避免依赖玩家维度的 PreviewStorage 报告做门禁。
 */
public record PreviewTicket(
        UUID id,
        UUID playerId,
        Identifier dimension,
        BlockPos origin,
        List<BlockPatch> patches,
        long expiresAtMillis,
        BuildQualityGate.QualitySnapshot quality
) {
    public PreviewTicket {
        patches = patches != null ? List.copyOf(patches) : List.of();
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
