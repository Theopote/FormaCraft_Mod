package com.formacraft.client.preview;

import com.formacraft.client.ui.panel.BuildConfirmPanel;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 客户端 Patch 预览状态：接收服务端签发的 PreviewTicket 并驱动 UI / 确认。
 */
public final class PatchPreviewClientState {
    private PatchPreviewClientState() {}

    public static void onPatchPreviewFromServer(
            UUID ticketId,
            BlockPos origin,
            List<BlockPatch> accepted,
            List<BlockPatch> rejected
    ) {
        if (ticketId == null || origin == null || accepted == null || accepted.isEmpty()) {
            return;
        }

        BuildConfirmPanel.INSTANCE.showPatchPreviewFromServer(ticketId, origin, accepted, rejected);
    }
}
