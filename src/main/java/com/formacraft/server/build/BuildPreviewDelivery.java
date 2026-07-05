package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.preview.OutlineBlock;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.preview.OutlineGenerator;
import com.formacraft.server.preview.PreviewStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers a structure preview after graded quality pipeline.
 */
public final class BuildPreviewDelivery {
    private BuildPreviewDelivery() {}

    public static boolean deliver(
            ServerPlayerEntity player,
            FormaRequest req,
            GeneratedStructure generated,
            com.formacraft.common.model.build.BuildingSpec spec,
            ServerWorld serverWorld,
            Optional<BuildingStyle> style,
            AtomicBoolean hbAlive,
            Text readyMessage,
            String statusTail,
            SkeletonSender skeletonSender
    ) {
        BuildPreviewPipeline.Result pipeline = BuildPreviewPipeline.prepare(
                player, serverWorld, generated, spec, req, style
        );
        BuildQualityReport report = pipeline.report();

        if (!pipeline.delivered() || pipeline.structure() == null) {
            String msg = report.summaryZh();
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    msg.isBlank() ? "预览失败：质量检查未通过" : msg
            ));
            if (hbAlive != null) hbAlive.set(false);
            return false;
        }

        GeneratedStructure structure = pipeline.structure();
        PreviewStorage.storeStructure(player, structure);

        List<OutlineBlock> outline = OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
        FormaCraftServerNetworking.sendPreviewOutline(player, outline);

        if (skeletonSender != null) {
            try {
                skeletonSender.send(player, structure.getOrigin());
            } catch (Throwable t) {
                com.formacraft.FormacraftMod.LOGGER.debug("skeleton preview send failed", t);
            }
        }

        PreviewStorage.setPreview(player, true);

        if (readyMessage != null) {
            player.sendMessage(readyMessage, false);
        }

        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(report.summaryZh()));
        if (statusTail != null && !statusTail.isBlank()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(statusTail));
        }

        int planned = structure.getBlocks() != null ? structure.getBlocks().size() : 0;
        if (planned > 0) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "预览含 " + planned + " 个目标方块；确认建造后，与现有地形相同的方块不会重复放置"
            ));
        }

        if (hbAlive != null) hbAlive.set(false);
        return true;
    }

    @FunctionalInterface
    public interface SkeletonSender {
        void send(ServerPlayerEntity player, BlockPos origin) throws Exception;
    }
}
