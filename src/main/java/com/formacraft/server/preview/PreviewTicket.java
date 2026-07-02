package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 服务端持有的 Patch 预览票据：确认时仅接受 ticketId，客户端不能篡改 patch 内容。
 */
public record PreviewTicket(
        UUID id,
        UUID playerId,
        BlockPos origin,
        List<BlockPatch> patches,
        long expiresAtMillis
) {
    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
