package com.formacraft.client.tool;

import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.client.tool.placement.PlacementAnalyzer;
import com.formacraft.client.tool.placement.PlacementResult;
import com.formacraft.client.tool.placement.PlacementValidator;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.placement.PlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 构件库模式：悬停预览与右键放置。
 */
final class ComponentToolLibraryPlacement {
    private ComponentToolLibraryPlacement() {}

    /** 悬停合法性检测输出（由 ComponentTool 持有）。 */
    static final class HoverSnapshot {
        PlacementResult placement;
        BlockPos pos;
        Direction face;
    }

    /** 右键放置结果：consumed=是否消费点击，placed=是否实际放置成功。 */
    static final class PlaceResult {
        final boolean consumed;
        final boolean placed;

        PlaceResult(boolean consumed, boolean placed) {
            this.consumed = consumed;
            this.placed = placed;
        }
    }

    static void tryUpdatePlacementPreview(
            MinecraftClient client,
            ComponentToolState state,
            ComponentDefinition loadedComponent,
            HoverSnapshot hoverOut
    ) {
        if (client == null || client.world == null) return;
        if (!state.useLibrary) return;
        if (state.pickingAnchor || state.pickingSocket) return;
        if (loadedComponent == null || loadedComponent.blocks == null || loadedComponent.blocks.isEmpty()) return;
        if (hoverOut == null) return;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return;

        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getSide();
        if (hitPos == null || face == null) return;

        PlacementContext pc = PlacementAnalyzer.analyze(client, hitPos, face);
        PlacementResult pr = PlacementValidator.validate(loadedComponent.placementSpec, pc, client);
        hoverOut.placement = pr;
        hoverOut.pos = hitPos.toImmutable();
        hoverOut.face = face;

        BlockPos anchor = anchorFromHit(hitPos, face);

        List<BlockPos> local = new ArrayList<>(loadedComponent.blocks.size());
        for (ComponentDefinition.BlockEntry be : loadedComponent.blocks) {
            if (be == null) continue;
            local.add(new BlockPos(be.dx, be.dy, be.dz));
        }

        Direction fromFacing = fromFacing(loadedComponent);
        Direction previewFacing = resolvePlacementFacing(loadedComponent, state, pc, client);
        Mirror m = state.mirror != null ? state.mirror : Mirror.NONE;
        ComponentTransform t = new ComponentTransform(previewFacing, m);
        ComponentPreviewState.show(local, anchor, fromFacing, t);

        switch (pr.status) {
            case VALID -> ComponentPreviewState.setColor(0.20f, 0.95f, 0.25f, 0.75f);
            case WARN -> ComponentPreviewState.setColor(1.00f, 0.85f, 0.20f, 0.80f);
            case INVALID -> ComponentPreviewState.setColor(1.00f, 0.25f, 0.25f, 0.85f);
        }
    }

    static PlaceResult tryPlaceFromLibrary(
            MinecraftClient client,
            ComponentToolState state,
            ComponentDefinition loadedComponent
    ) {
        if (client == null || client.world == null) return new PlaceResult(false, false);
        if (!state.useLibrary) return new PlaceResult(false, false);
        if (state.pickingAnchor || state.pickingSocket) return new PlaceResult(false, false);
        if (loadedComponent == null || loadedComponent.blocks == null || loadedComponent.blocks.isEmpty()) {
            return new PlaceResult(false, false);
        }

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return new PlaceResult(false, false);
        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getSide();
        if (hitPos == null || face == null) return new PlaceResult(false, false);

        PlacementContext pc = PlacementAnalyzer.analyze(client, hitPos, face);
        PlacementResult pr = PlacementValidator.validate(loadedComponent.placementSpec, pc, client);
        if (pr.status == PlacementResult.Status.INVALID) {
            HudToast.show("放置失败：" + (pr.reason != null ? pr.reason : "非法位置"), true);
            return new PlaceResult(true, false);
        }

        BlockPos anchor = anchorFromHit(hitPos, face);
        Direction placeFacing = resolvePlacementFacing(loadedComponent, state, pc, client);
        Mirror m = state.mirror != null ? state.mirror : Mirror.NONE;

        requestServerPatchPreview(state, anchor, loadedComponent.id, placeFacing, m, true);
        HudToast.show(pr.status == PlacementResult.Status.WARN ? ("已放置构件（警告：" + pr.reason + "）") : "已放置构件");

        state.useLibrary = false;
        ComponentPreviewState.clear();
        return new PlaceResult(true, true);
    }

    static void requestServerPatchPreview(
            ComponentToolState state,
            BlockPos anchor,
            String componentId,
            Direction facing,
            Mirror mirror,
            boolean autoConfirm
    ) {
        if (anchor == null) return;
        boolean restrict = PromptModeState.restrictToSelection();
        BlockPos selMin = null;
        BlockPos selMax = null;
        if ((componentId == null || componentId.isBlank()) && SelectionTool.INSTANCE.hasSelection()) {
            selMin = SelectionTool.INSTANCE.getMin();
            selMax = SelectionTool.INSTANCE.getMax();
        }
        FormaCraftClientNetworking.sendRequestPatchPreview(new FormaCraftNetworking.RequestPatchPreviewPayload(
                anchor,
                componentId,
                facing != null ? facing.name() : null,
                mirror != null ? mirror.name() : null,
                state.semanticSkin,
                state.semanticStyleId,
                restrict,
                selMin,
                selMax,
                List.of(),
                autoConfirm
        ));
        FormaCraftClientNetworking.sendProtectedZoneSync(ProtectedZoneTool.INSTANCE.getZones());
        FormaCraftClientNetworking.sendOutlineSync(BuildContextResolver.currentOutlineShape());
    }

    static Direction resolvePlacementFacing(
            ComponentDefinition def,
            ComponentToolState state,
            PlacementContext pc,
            MinecraftClient client
    ) {
        Direction fromFacing = fromFacing(def);
        Direction result = fromFacing;
        if (def.placementSpec != null && def.placementSpec.facingPolicy != null) {
            switch (def.placementSpec.facingPolicy) {
                case NONE -> result = fromFacing;
                case USER_DEFINED -> result = state.facing != null ? state.facing : fromFacing;
                case DERIVED_FROM_HOST, OUTWARD_NORMAL -> {
                    if (pc.outwardNormal != null && pc.outwardNormal.getAxis().isHorizontal()) {
                        result = pc.outwardNormal;
                    } else {
                        result = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
                case ALONG_EDGE -> {
                    if (pc.edgeDirection != null && pc.edgeDirection.getAxis().isHorizontal()) {
                        result = pc.edgeDirection;
                    } else {
                        result = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
            }
        }
        return result;
    }

    static BlockPos anchorFromHit(BlockPos hitPos, Direction face) {
        return switch (face) {
            case UP -> hitPos.up();
            case DOWN -> hitPos.down();
            default -> hitPos.offset(face);
        };
    }

    private static Direction fromFacing(ComponentDefinition def) {
        Direction fromFacing = Direction.SOUTH;
        if (def.anchor != null && def.anchor.facing != null) {
            Direction d = ComponentToolJsonBuilder.parseDir(def.anchor.facing);
            if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
        }
        return fromFacing;
    }
}
