package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.tool.ComponentCaptureDraft;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.DirectionMarkingMode;
import com.formacraft.client.ui.panel.DirectionalityMode;
import com.formacraft.client.ui.toast.HudToast;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Predicate;

/**
 * 构件捕获面板的方向与宿主面标记：内外/上下/宿主面点击标记及方向性模式。
 */
public final class ComponentCaptureOrientationController {
    private static final boolean DEBUG = false;

    private DirectionalityMode directionalityMode = DirectionalityMode.NONE;
    private DirectionMarkingMode markingMode = DirectionMarkingMode.NONE;

    public DirectionalityMode getDirectionalityMode() {
        return directionalityMode;
    }

    public void setDirectionalityMode(DirectionalityMode mode) {
        if (mode != null) {
            this.directionalityMode = mode;
        }
    }

    public DirectionMarkingMode getMarkingMode() {
        return markingMode;
    }

    public boolean isMarkingActive() {
        return markingMode != DirectionMarkingMode.NONE;
    }

    public void loadFromDraft(ComponentCaptureDraft draft) {
        if (draft == null) {
            return;
        }
        directionalityMode = draft.orientation.mode != null ? draft.orientation.mode : DirectionalityMode.NONE;
    }

    public void cycleDirectionality() {
        directionalityMode = directionalityMode.next();
        applyDirectionalityToDraft();
        if (DEBUG) {
            FormacraftMod.LOGGER.debug("[OrientationController] directionality={}", directionalityMode.getDisplayName());
        }
    }

    public void applyDirectionalityToDraft() {
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        draft.orientation.mode = directionalityMode;
        draft.orientation.hasInteriorExterior = directionalityMode.needsInsideOutside();
        draft.orientation.hasBottomTop = directionalityMode.needsBottomTop();
        st.syncDraftToState();
    }

    public void startMarkingInside() {
        markingMode = DirectionMarkingMode.MARKING_INSIDE;
    }

    public void startMarkingOutside() {
        markingMode = DirectionMarkingMode.MARKING_OUTSIDE;
    }

    public void startMarkingBottom() {
        markingMode = DirectionMarkingMode.MARKING_BOTTOM;
    }

    public void startMarkingTop() {
        markingMode = DirectionMarkingMode.MARKING_TOP;
    }

    public void startMarkingHostFace() {
        markingMode = DirectionMarkingMode.MARKING_HOST_FACE;
    }

    /**
     * @param anchorAllowed 锚点位置合法性检查（由选区控制器提供）
     * @return true 若点击被标记模式消费
     */
    public boolean handleMarkingClick(BlockHitResult hit, Predicate<BlockPos> anchorAllowed) {
        if (hit == null || markingMode == DirectionMarkingMode.NONE) {
            return false;
        }

        BlockPos pos = hit.getBlockPos();
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;

        switch (markingMode) {
            case MARKING_INSIDE -> {
                draft.orientation.insideMarkWorld = pos.toImmutable();
                st.syncDraftToState();
                markingMode = DirectionMarkingMode.NONE;
                applyFacingFromMarks();
            }
            case MARKING_OUTSIDE -> {
                draft.orientation.outsideMarkWorld = pos.toImmutable();
                st.syncDraftToState();
                markingMode = DirectionMarkingMode.NONE;
                applyFacingFromMarks();
            }
            case MARKING_BOTTOM -> {
                draft.orientation.bottomMarkWorld = pos.toImmutable();
                st.syncDraftToState();
                markingMode = DirectionMarkingMode.NONE;
            }
            case MARKING_TOP -> {
                draft.orientation.topMarkWorld = pos.toImmutable();
                st.syncDraftToState();
                markingMode = DirectionMarkingMode.NONE;
            }
            case MARKING_HOST_FACE -> {
                setHostFace(hit, anchorAllowed);
                markingMode = DirectionMarkingMode.NONE;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public void clearMarks() {
        markingMode = DirectionMarkingMode.NONE;
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        draft.orientation.insideMarkWorld = null;
        draft.orientation.outsideMarkWorld = null;
        draft.orientation.bottomMarkWorld = null;
        draft.orientation.topMarkWorld = null;
        draft.host.referenceBlock = null;
        draft.host.normal = null;
        st.syncDraftToState();
    }

    private void setHostFace(BlockHitResult hit, Predicate<BlockPos> anchorAllowed) {
        if (hit == null) {
            return;
        }
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        BlockPos base = hit.getBlockPos().toImmutable();
        Direction normal = hit.getSide();

        draft.host.referenceBlock = base;
        draft.host.normal = normal;
        draft.host.confirmed = true;

        BlockPos anchor = draft.anchor.allowOutsideSelection ? base.offset(normal) : base;
        if (anchorAllowed != null && !anchorAllowed.test(anchor)) {
            HudToast.show("宿主面已记录，但锚点不在选区附近", true);
        } else {
            draft.anchor.worldPos = anchor;
        }
        draft.orientation.facing = normal;
        st.syncDraftToState();
        HudToast.show("已设置宿主面: " + normal.name());
    }

    private void applyFacingFromMarks() {
        var st = ComponentTool.INSTANCE.getState();
        Direction derived = deriveHorizontalFacing(
                st.captureDraft.orientation.insideMarkWorld,
                st.captureDraft.orientation.outsideMarkWorld
        );
        if (derived != null) {
            st.captureDraft.orientation.facing = derived;
            st.syncDraftToState();
        }
    }

    private static Direction deriveHorizontalFacing(BlockPos inside, BlockPos outside) {
        if (inside == null || outside == null) {
            return null;
        }
        int dx = outside.getX() - inside.getX();
        int dz = outside.getZ() - inside.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
