package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.tool.ComponentCaptureDraft;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.client.ui.panel.ComponentSelectionMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 构件捕获面板的选区控制：框选 / 点选、与 {@link SelectionTool} 同步。
 */
public final class ComponentCaptureSelectionController {
    private static final boolean DEBUG = false;
    private static final int POINT_SELECT_RENDER_THRESHOLD = 400;

    private ComponentSelectionMode mode = ComponentSelectionMode.BOX_SELECT;
    private final Set<BlockPos> selectedBlocks = new HashSet<>();
    private boolean isDragging = false;
    private Runnable onSelectionChanged;

    public ComponentSelectionMode getMode() {
        return mode;
    }

    public void setMode(ComponentSelectionMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
        if (DEBUG) {
            FormacraftMod.LOGGER.debug("[SelectionController] mode={}", this.mode);
        }
    }

    public Set<BlockPos> getSelectedBlocks() {
        return Collections.unmodifiableSet(selectedBlocks);
    }

    public void setOnSelectionChanged(Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    public boolean hasAnySelection() {
        return !selectedBlocks.isEmpty() || SelectionTool.INSTANCE.hasSelection();
    }

    public boolean hasValidSelection() {
        if (!selectedBlocks.isEmpty()) {
            return true;
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            return false;
        }
        return true;
    }

    public boolean hasValidSelection(MinecraftClient client) {
        return countBlocks(client) > 0;
    }

    public BlockPos getSelectionMin() {
        var st = ComponentTool.INSTANCE.getState();
        if (st.captureDraft.hasExplicitSelection() && st.captureDraft.selection.blocks != null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (BlockPos pos : st.captureDraft.selection.blocks) {
                if (pos == null) {
                    continue;
                }
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
            }
            if (minX != Integer.MAX_VALUE) {
                return new BlockPos(minX, minY, minZ);
            }
        }
        return SelectionTool.INSTANCE.getMin();
    }

    public int countBlocks(MinecraftClient client) {
        if (client == null || client.world == null) {
            return 0;
        }

        var st = ComponentTool.INSTANCE.getState();
        if (st.captureDraft.selection.explicit
                && st.captureDraft.selection.blocks != null
                && !st.captureDraft.selection.blocks.isEmpty()) {
            int count = 0;
            for (BlockPos pos : st.captureDraft.selection.blocks) {
                if (pos != null && !client.world.getBlockState(pos).isAir()) {
                    count++;
                }
            }
            return count;
        }

        if (!SelectionTool.INSTANCE.hasSelection()) {
            return 0;
        }
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            return 0;
        }

        int count = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (!client.world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public boolean isSelecting() {
        return isDragging || mode != ComponentSelectionMode.BOX_SELECT;
    }

    public void loadFromDraft(ComponentCaptureDraft draft, MinecraftClient client) {
        selectedBlocks.clear();
        if (draft != null && draft.selection.explicit && draft.selection.blocks != null) {
            for (BlockPos pos : draft.selection.blocks) {
                if (pos != null && client != null && client.world != null
                        && !client.world.getBlockState(pos).isAir()) {
                    selectedBlocks.add(pos.toImmutable());
                }
            }
            mode = ComponentSelectionMode.POINT_SELECT;
            syncSelectionToDraft();
        } else {
            mode = ComponentSelectionMode.BOX_SELECT;
        }
    }

    public void clearSelection() {
        selectedBlocks.clear();
        isDragging = false;
        SelectionTool.INSTANCE.clearSelection();

        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;
        draft.selection.blocks = null;
        draft.selection.explicit = false;
        draft.selection.aabbMin = null;
        draft.selection.aabbMax = null;
        st.syncDraftToState();
        notifySelectionChanged();
    }

    public boolean handleWorldClick(MinecraftClient client, BlockHitResult hit, int button) {
        if (hit == null || button != 0) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos == null) {
            return false;
        }

        if (mode == ComponentSelectionMode.BOX_SELECT) {
            double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
            double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
            SelectionTool.INSTANCE.onMouseClick(mouseX, mouseY, button);
            return true;
        }

        boolean ctrl = GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        if (client.world != null && client.world.getBlockState(pos).isAir()) {
            return true;
        }

        if (ctrl) {
            addBlock(pos);
        } else if (selectedBlocks.contains(pos.toImmutable())) {
            removeBlock(pos);
        } else {
            addBlock(pos);
        }
        return true;
    }

    public void handleWorldRelease(MinecraftClient client, int button) {
        if (button == 0 && mode == ComponentSelectionMode.BOX_SELECT && SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            if (min != null && max != null) {
                setBoxSelection(client, min, max);
            }
        }
    }

    public void tick(MinecraftClient client) {
        if (mode == ComponentSelectionMode.BOX_SELECT && SelectionTool.INSTANCE.isSelecting()) {
            SelectionTool.INSTANCE.tick();
        }
    }

    public boolean isAnchorLocationAllowed(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (!selectedBlocks.isEmpty()) {
            if (selectedBlocks.contains(pos)) {
                return true;
            }
            if (!ComponentTool.INSTANCE.getState().captureDraft.anchor.allowOutsideSelection) {
                return false;
            }
            return isAnchorAdjacentToSelection(pos);
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            return false;
        }
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            return false;
        }
        boolean inside = pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        if (inside) {
            return true;
        }
        if (!ComponentTool.INSTANCE.getState().captureDraft.anchor.allowOutsideSelection) {
            return false;
        }
        return pos.getX() >= (min.getX() - 1) && pos.getX() <= (max.getX() + 1)
                && pos.getY() >= (min.getY() - 1) && pos.getY() <= (max.getY() + 1)
                && pos.getZ() >= (min.getZ() - 1) && pos.getZ() <= (max.getZ() + 1);
    }

    public void renderPointSelectHighlights(ToolWorldRenderContext ctx) {
        if (mode != ComponentSelectionMode.POINT_SELECT) {
            return;
        }
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        Set<BlockPos> highlightBlocks = selectedBlocks;
        if (draft.selection.explicit && draft.selection.blocks != null && !draft.selection.blocks.isEmpty()) {
            highlightBlocks = draft.selection.blocks;
        }
        if (highlightBlocks == null || highlightBlocks.isEmpty()) {
            return;
        }

        int blockCount = highlightBlocks.size();
        int sampleRate = blockCount > POINT_SELECT_RENDER_THRESHOLD ? Math.max(1, blockCount / 200) : 1;
        int rendered = 0;
        for (BlockPos pos : highlightBlocks) {
            if (rendered % sampleRate == 0) {
                renderBlockHighlight(ctx, pos, 0.0f, 1.0f, 0.0f, 0.3f);
            }
            rendered++;
        }
    }

    private void addBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        selectedBlocks.add(pos.toImmutable());
        syncSelectionToDraft();
        notifySelectionChanged();
    }

    private void removeBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        selectedBlocks.remove(pos.toImmutable());
        syncSelectionToDraft();
        notifySelectionChanged();
    }

    private void setBoxSelection(MinecraftClient client, BlockPos start, BlockPos end) {
        if (start == null || end == null) {
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        selectedBlocks.clear();
        st.captureDraft.selection.blocks = null;
        st.captureDraft.selection.explicit = false;
        st.captureDraft.selection.aabbMin = null;
        st.captureDraft.selection.aabbMax = null;

        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (client != null && client.world != null && client.world.getBlockState(pos).isAir()) {
                        continue;
                    }
                    selectedBlocks.add(pos);
                }
            }
        }

        if (selectedBlocks.isEmpty()) {
            SelectionTool.INSTANCE.clearSelection();
            st.syncDraftToState();
            notifySelectionChanged();
            return;
        }

        SelectionTool.INSTANCE.setSelection(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        st.captureDraft.selection.aabbMin = new BlockPos(minX, minY, minZ);
        st.captureDraft.selection.aabbMax = new BlockPos(maxX, maxY, maxZ);
        st.syncDraftToState();
        notifySelectionChanged();
    }

    private void syncSelectionToDraft() {
        var st = ComponentTool.INSTANCE.getState();
        var draft = st.captureDraft;

        if (selectedBlocks.isEmpty()) {
            SelectionTool.INSTANCE.clearSelection();
            draft.selection.blocks = null;
            draft.selection.explicit = false;
            draft.selection.aabbMin = null;
            draft.selection.aabbMax = null;
            st.syncDraftToState();
            return;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos blockPos : selectedBlocks) {
            minX = Math.min(minX, blockPos.getX());
            minY = Math.min(minY, blockPos.getY());
            minZ = Math.min(minZ, blockPos.getZ());
            maxX = Math.max(maxX, blockPos.getX());
            maxY = Math.max(maxY, blockPos.getY());
            maxZ = Math.max(maxZ, blockPos.getZ());
        }

        SelectionTool.INSTANCE.setSelection(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        draft.selection.aabbMin = new BlockPos(minX, minY, minZ);
        draft.selection.aabbMax = new BlockPos(maxX, maxY, maxZ);

        if (mode == ComponentSelectionMode.POINT_SELECT) {
            draft.selection.blocks = new HashSet<>(selectedBlocks);
            draft.selection.explicit = true;
        } else {
            draft.selection.blocks = null;
            draft.selection.explicit = false;
        }
        st.syncDraftToState();
    }

    private boolean isAnchorAdjacentToSelection(BlockPos pos) {
        if (pos == null || selectedBlocks.isEmpty()) {
            return false;
        }
        for (Direction d : Direction.values()) {
            if (selectedBlocks.contains(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private void notifySelectionChanged() {
        if (onSelectionChanged != null) {
            onSelectionChanged.run();
        }
    }

    private static void renderBlockHighlight(
            ToolWorldRenderContext ctx,
            BlockPos pos,
            float r, float g, float b, float a
    ) {
        Box worldBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1)
                .expand(0.01);
        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
    }
}
