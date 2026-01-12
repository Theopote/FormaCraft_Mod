package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构件捕获工具（v1）：
 * - 依赖 SelectionTool 提供 AABB
 * - 可在选区内选择 anchor
 * - 保存时：读取方块 -> 相对 anchor 坐标 -> ComponentDefinition JSON
 *
 * v1：不做旋转放置、不做预览展开，仅保存到服务端构件库。
 */
public final class ComponentTool implements FormacraftTool {
    public static final ComponentTool INSTANCE = new ComponentTool();

    private final ComponentToolState state = new ComponentToolState();
    private volatile boolean awaitingSaveAck = false;
    private volatile String awaitingSaveName = null;

    private ComponentTool() {}

    public ComponentToolState getState() {
        return state;
    }

    @Override
    public String getId() {
        return "component";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("构件工具");
    }

    @Override
    public void onDeactivate() {
        // 避免预览残留（v1：预览随工具生命周期）
        ComponentPreviewState.clear();
        state.pickingAnchor = false;
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0) return false;
        if (!state.pickingAnchor) return true; // 吃掉点击，避免误破坏

        if (!SelectionTool.INSTANCE.hasSelection()) return true;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        BlockPos pos = hit.getBlockPos();
        if (!isInsideSelection(pos)) return true;

        state.anchorWorld = pos.toImmutable();
        state.pickingAnchor = false;
        // 若正在预览，更新 anchor
        if (ComponentPreviewState.isActive()) {
            preview(net.minecraft.client.MinecraftClient.getInstance(), true);
        }
        return true;
    }

    public void cycleFacing() {
        state.facing = switch (state.facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
        if (ComponentPreviewState.isActive()) {
            ComponentPreviewState.setFacing(state.facing);
        }
    }

    public void cycleCategory() {
        ComponentCategory[] v = ComponentCategory.values();
        int idx = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == state.category) {
                idx = i;
                break;
            }
        }
        state.category = v[(idx + 1) % v.length];
    }

    public void startPickAnchor() {
        state.pickingAnchor = true;
    }

    public void clearAnchor() {
        state.anchorWorld = null;
        state.pickingAnchor = false;
        ComponentPreviewState.clear();
    }

    public boolean canSave() {
        // v1：强制显式 Anchor（避免后续旋转/放置语义混乱）
        return SelectionTool.INSTANCE.hasSelection() && state.anchorWorld != null && isInsideSelection(state.anchorWorld);
    }

    public void markSavePending(String displayName) {
        this.awaitingSaveAck = true;
        this.awaitingSaveName = (displayName == null || displayName.isBlank()) ? null : displayName.trim();
    }

    /** 服务端回推 catalog 后调用：用于给 ToolPanel toast 强反馈。 */
    public void onCatalogUpdatedFromServer() {
        if (!awaitingSaveAck) return;
        awaitingSaveAck = false;
        String n = (awaitingSaveName == null || awaitingSaveName.isBlank()) ? "（未命名）" : awaitingSaveName;
        awaitingSaveName = null;
        HudToast.show("构件「" + n + "」已保存");
    }

    /**
     * 预览放置（纯客户端）：在 anchorWorld 处渲染构件线框。
     * - force=true：强制刷新当前预览（用于 anchor 改变时）
     */
    public void preview(net.minecraft.client.MinecraftClient client, boolean force) {
        if (!force && ComponentPreviewState.isActive()) {
            ComponentPreviewState.clear();
            HudToast.show("已关闭构件预览");
            return;
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            HudToast.show("预览失败：请先完成选区", true);
            return;
        }
        if (state.anchorWorld == null || !isInsideSelection(state.anchorWorld)) {
            HudToast.show("预览失败：请先选择 Anchor", true);
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("预览失败：世界未就绪", true);
            return;
        }

        List<BlockPos> local = buildLocalBlocks(client);
        if (local.isEmpty()) {
            HudToast.show("预览失败：选区内没有非空气方块", true);
            return;
        }

        ComponentPreviewState.show(local, state.anchorWorld, state.facing);
        if (!force) {
            HudToast.show("已开启构件预览（" + local.size() + " blocks）");
        }
    }

    public void preview(net.minecraft.client.MinecraftClient client) {
        preview(client, false);
    }

    private List<BlockPos> buildLocalBlocks(net.minecraft.client.MinecraftClient client) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return out;

        BlockPos anchor = state.anchorWorld;
        if (anchor == null) return out;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = client.world.getBlockState(p);
                    if (bs == null || bs.isAir()) continue;
                    out.add(new BlockPos(x - anchor.getX(), y - anchor.getY(), z - anchor.getZ()));
                }
            }
        }
        return out;
    }

    /** 构造 ComponentDefinition 并序列化为 JSON（供 C2S 发送）。 */
    public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client) {
        if (client == null || client.world == null) return null;
        if (!SelectionTool.INSTANCE.hasSelection()) return null;

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return null;

        int minX = min.getX();
        int minY = min.getY();
        int minZ = min.getZ();
        int maxX = max.getX();
        int maxY = max.getY();
        int maxZ = max.getZ();

        // v1：Anchor 必须显式选择（不再默认选区 min）
        BlockPos anchor = state.anchorWorld;
        if (anchor == null || !isInsideSelection(anchor)) return null;

        ComponentDefinition def = new ComponentDefinition();
        def.id = makeId(state.category, state.name);
        def.name = state.name;
        def.category = state.category != null ? state.category : ComponentCategory.GENERIC;
        def.tags = state.tags != null ? new ArrayList<>(state.tags) : new ArrayList<>();

        ComponentDefinition.Size size = new ComponentDefinition.Size();
        size.w = (maxX - minX + 1);
        size.h = (maxY - minY + 1);
        size.d = (maxZ - minZ + 1);
        def.size = size;

        ComponentDefinition.Anchor a = new ComponentDefinition.Anchor();
        a.dx = 0;
        a.dy = 0;
        a.dz = 0;
        a.facing = (state.facing != null ? state.facing : Direction.SOUTH).name();
        def.anchor = a;

        def.allowed_facing = java.util.Set.of("NORTH", "SOUTH", "EAST", "WEST");
        def.placement_rules = new ComponentDefinition.PlacementRules();

        def.blocks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = client.world.getBlockState(p);
                    if (bs == null || bs.isAir()) continue;

                    ComponentDefinition.BlockEntry be = new ComponentDefinition.BlockEntry();
                    be.dx = x - anchor.getX();
                    be.dy = y - anchor.getY();
                    be.dz = z - anchor.getZ();
                    be.block = serializeBlockState(bs);
                    def.blocks.add(be);
                }
            }
        }

        return JsonUtil.toJson(def);
    }

    private boolean isInsideSelection(BlockPos pos) {
        if (pos == null) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return false;
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private static String makeId(ComponentCategory cat, String name) {
        String c = (cat != null ? cat.name() : "GENERIC").toLowerCase(Locale.ROOT);
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (n.isBlank()) n = "component";
        return c + "_" + n + "_" + System.currentTimeMillis();
    }

    /**
     * v1：序列化为 blockId + [prop=val,...]（稳定排序，便于 diff）。
     */
    private static String serializeBlockState(BlockState state) {
        String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.getEntries().isEmpty()) return id;

        List<Map.Entry<Property<?>, Comparable<?>>> entries = new ArrayList<>(state.getEntries().entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().getName()));

        StringBuilder sb = new StringBuilder(id);
        sb.append("[");
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> e : entries) {
            if (!first) sb.append(",");
            first = false;
            sb.append(e.getKey().getName()).append("=").append(String.valueOf(e.getValue()));
        }
        sb.append("]");
        return sb.toString();
    }
}

