package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.semantic.BlockStatePropertyUtil;
import com.formacraft.common.component.semantic.SemanticBlockStatePicker;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.placement.PlacementConstraints;
import com.formacraft.common.component.placement.SpatialContext;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.client.ui.panel.BuildConfirmPanel;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.client.preview.ComponentSocketPreviewState;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
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
 * <p>
 * v1：不做旋转放置、不做预览展开，仅保存到服务端构件库。
 */
public final class ComponentTool implements FormacraftTool {
    public static final ComponentTool INSTANCE = new ComponentTool();

    private final ComponentToolState state = new ComponentToolState();
    private volatile boolean awaitingSaveAck = false;
    private volatile String awaitingSaveName = null;

    private volatile boolean awaitingComponentLoad = false;
    private volatile String awaitingComponentId = null;
    private volatile ComponentDefinition loadedComponent = null;

    private final List<ComponentSocket> sockets = new ArrayList<>();

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
        ComponentSocketPreviewState.clear();
        state.pickingAnchor = false;
        state.pickingSocket = false;
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0) return false;
        if (!state.pickingAnchor && !state.pickingSocket) return true; // 吃掉点击，避免误破坏

        if (!SelectionTool.INSTANCE.hasSelection()) return true;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        BlockPos pos = hit.getBlockPos();
        if (!state.useLibrary) {
            if (!isInsideSelection(pos)) return true;
        }

        if (state.pickingAnchor) {
            state.anchorWorld = pos.toImmutable();
            state.pickingAnchor = false;
        } else if (state.pickingSocket) {
            BlockPos anchor = state.anchorWorld;
            if (anchor == null) {
                HudToast.show("请先选择 Anchor（再点选 Socket 原点）", true);
                return true;
            }
            state.socketOriginLocal = new BlockPos(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
            state.pickingSocket = false;
            HudToast.show("已设置 Socket 原点（local=" + state.socketOriginLocal.getX() + "," + state.socketOriginLocal.getY() + "," + state.socketOriginLocal.getZ() + "）");
            if (ComponentSocketPreviewState.isActive()) {
                showSocketPreview(true);
            }
        }
        // 若正在预览，更新 anchor
        if (ComponentPreviewState.isActive()) {
            preview(net.minecraft.client.MinecraftClient.getInstance(), true);
        }
        return true;
    }

    public void cycleFacing() {
        state.facing = switch (state.facing) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
        if (ComponentPreviewState.isActive()) {
            ComponentPreviewState.setTransform(currentTransform());
        }
    }

    public void cycleMirror() {
        state.mirror = switch (state.mirror) {
            case NONE -> Mirror.X;
            case X -> Mirror.Z;
            case Z -> Mirror.NONE;
        };
        if (ComponentPreviewState.isActive()) {
            ComponentPreviewState.setTransform(currentTransform());
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

    public void toggleSemanticSkin() {
        state.semanticSkin = !state.semanticSkin;
    }

    public void cycleSemanticPart() {
        // 允许一个“AUTO”档位：semanticPart == null
        if (state.semanticPart == null) {
            state.semanticPart = SemanticPart.values()[0];
            return;
        }
        SemanticPart[] v = SemanticPart.values();
        int idx = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == state.semanticPart) {
                idx = i;
                break;
            }
        }
        if (idx == v.length - 1) {
            state.semanticPart = null; // AUTO
        } else {
            state.semanticPart = v[idx + 1];
        }
    }

    public void cycleSemanticStyle() {
        List<String> ids = SemanticStyleProfileRegistry.ids();
        if (ids.isEmpty()) {
            state.semanticStyleId = "DEFAULT";
            return;
        }
        String cur = (state.semanticStyleId == null || state.semanticStyleId.isBlank()) ? "DEFAULT" : state.semanticStyleId.trim();
        int idx = ids.indexOf(cur);
        if (idx < 0) idx = 0;
        state.semanticStyleId = ids.get((idx + 1) % ids.size());
    }

    public void toggleSource() {
        state.useLibrary = !state.useLibrary;
        // 切换来源时关闭预览，避免状态错乱
        ComponentPreviewState.clear();
    }

    public void cycleLibraryComponent() {
        ComponentCatalog cat = com.formacraft.client.component.ClientComponentCatalogState.getCatalog();
        if (cat == null || cat.components == null || cat.components.isEmpty()) {
            HudToast.show("构件库为空：请先保存构件或等待 catalog 同步", true);
            state.librarySelectedId = null;
            state.librarySelectedName = null;
            return;
        }
        int n = cat.components.size();
        int idx = 0;
        if (state.librarySelectedId != null) {
            for (int i = 0; i < n; i++) {
                var e = cat.components.get(i);
                if (e != null && state.librarySelectedId.equals(e.id)) {
                    idx = i;
                    break;
                }
            }
            idx = (idx + 1) % n;
        }
        var e = cat.components.get(idx);
        if (e == null) return;
        state.librarySelectedId = e.id;
        state.librarySelectedName = (e.name != null && !e.name.isBlank()) ? e.name : e.id;
    }

    public void requestLoadSelectedComponent() {
        if (state.librarySelectedId == null || state.librarySelectedId.isBlank()) {
            HudToast.show("请先选择一个构件（构件库）", true);
            return;
        }
        awaitingComponentLoad = true;
        awaitingComponentId = state.librarySelectedId;
        loadedComponent = null;
        HudToast.show("正在加载构件：「" + (state.librarySelectedName != null ? state.librarySelectedName : state.librarySelectedId) + "」…");
        FormaCraftNetworking.sendComponentGetRequest(state.librarySelectedId);
    }

    public void onComponentDefinitionFromServer(String json) {
        if (!awaitingComponentLoad) return;
        awaitingComponentLoad = false;
        String id = awaitingComponentId;
        awaitingComponentId = null;

        if (json == null || json.isBlank()) {
            HudToast.show("加载构件失败：服务端未找到该 id（" + (id != null ? id : "?") + "）", true);
            return;
        }
        try {
            ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def == null || def.blocks == null || def.blocks.isEmpty()) {
                HudToast.show("加载构件失败：数据为空", true);
                return;
            }
            loadedComponent = def;
            HudToast.show("已加载构件：「" + (def.name != null ? def.name : def.id) + "」 blocks=" + def.blocks.size());
        } catch (Throwable t) {
            HudToast.show("加载构件失败：JSON 解析失败", true);
        }
    }

    public void startPickAnchor() {
        state.pickingAnchor = true;
    }

    public void startPickSocketOrigin() {
        state.pickingSocket = true;
    }

    public void cycleSocketType() {
        SocketType[] v = SocketType.values();
        int idx = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == state.socketType) {
                idx = i;
                break;
            }
        }
        state.socketType = v[(idx + 1) % v.length];
        // 预设尺寸
        switch (state.socketType) {
            case DOOR -> { state.socketW = 2; state.socketH = 3; state.socketD = 1; }
            case WINDOW -> { state.socketW = 2; state.socketH = 2; state.socketD = 1; }
            case BALCONY -> { state.socketW = 3; state.socketH = 2; state.socketD = 1; }
            case ROOF_ATTACHMENT -> { state.socketW = 3; state.socketH = 1; state.socketD = 1; }
            case WALL -> { state.socketW = 1; state.socketH = 5; state.socketD = 3; }
            case DECORATION -> { state.socketW = 1; state.socketH = 1; state.socketD = 1; }
        }
    }

    public void cycleSocketFacing() {
        state.socketFacing = switch (state.socketFacing) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
    }

    public void addSocket() {
        if (state.socketOriginLocal == null) {
            HudToast.show("请先点选 Socket 原点", true);
            return;
        }
        state.socketCount++;
        String want = (state.socketIdDraft == null) ? "" : state.socketIdDraft.trim();
        if (want.isEmpty()) {
            want = "socket_" + state.socketCount;
        } else {
            // normalize
            want = want.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
            if (want.isEmpty()) want = "socket_" + state.socketCount;
        }
        String id = uniqueSocketId(want);
        ComponentSocket s = new ComponentSocket(
                id,
                state.socketType != null ? state.socketType : SocketType.DECORATION,
                state.socketOriginLocal.getX(),
                state.socketOriginLocal.getY(),
                state.socketOriginLocal.getZ(),
                (state.socketFacing != null ? state.socketFacing : Direction.SOUTH).name(),
                Math.max(1, state.socketW),
                Math.max(1, state.socketH),
                Math.max(1, state.socketD)
        );
        sockets.add(s);
        HudToast.show("已添加 Socket：" + id + " (" + s.type() + ") " + s.width() + "x" + s.height() + "x" + s.depth());
    }

    public void toggleSocketPreview(net.minecraft.client.MinecraftClient client) {
        if (ComponentSocketPreviewState.isActive()) {
            ComponentSocketPreviewState.clear();
            HudToast.show("已关闭 Socket 预览");
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("Socket 预览失败：世界未就绪", true);
            return;
        }
        if (state.anchorWorld == null) {
            HudToast.show("Socket 预览失败：请先选择 Anchor", true);
            return;
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            HudToast.show("Socket 预览失败：请先完成选区", true);
            return;
        }
        if (state.socketOriginLocal == null) {
            HudToast.show("Socket 预览失败：请先点选 Socket 原点", true);
            return;
        }
        showSocketPreview(false);
    }

    private void showSocketPreview(boolean silent) {
        Direction fromFacing = Direction.SOUTH; // capture local forward
        ComponentSocketPreviewState.show(
                state.anchorWorld,
                state.socketOriginLocal,
                Math.max(1, state.socketW),
                Math.max(1, state.socketH),
                Math.max(1, state.socketD),
                state.socketFacing != null ? state.socketFacing : Direction.SOUTH,
                fromFacing,
                currentTransform()
        );
        if (!silent) {
            HudToast.show("已开启 Socket 预览（" + (state.socketType != null ? state.socketType.name() : "SOCKET") + "）");
        }
    }

    private String uniqueSocketId(String base) {
        if (base == null || base.isBlank()) base = "socket";
        String id = base;
        int n = 2;
        while (hasSocketId(id)) {
            id = base + "_" + n;
            n++;
            if (n > 999) break;
        }
        return id;
    }

    private boolean hasSocketId(String id) {
        if (id == null || id.isBlank()) return false;
        for (ComponentSocket s : sockets) {
            if (s == null || s.id() == null) continue;
            if (id.equals(s.id())) return true;
        }
        return false;
    }

    public void clearSockets() {
        sockets.clear();
        state.socketCount = 0;
        HudToast.show("已清空 Sockets");
    }

    public void clearAnchor() {
        state.anchorWorld = null;
        state.pickingAnchor = false;
        ComponentPreviewState.clear();
    }

    private boolean isAnchorValid() {
        if (state.anchorWorld == null) return false;
        if (state.useLibrary) return true;
        return isInsideSelection(state.anchorWorld);
    }

    public boolean canSave() {
        // v1：强制显式 Anchor（避免后续旋转/放置语义混乱）
        return SelectionTool.INSTANCE.hasSelection() && isInsideSelection(state.anchorWorld);
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
        if (!isAnchorValid()) {
            HudToast.show("预览失败：请先选择 Anchor", true);
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("预览失败：世界未就绪", true);
            return;
        }

        if (!state.useLibrary) {
            if (!SelectionTool.INSTANCE.hasSelection()) {
                HudToast.show("预览失败：请先完成选区", true);
                return;
            }
            List<BlockPos> local = buildLocalBlocks(client);
            if (local.isEmpty()) {
                HudToast.show("预览失败：选区内没有非空气方块", true);
                return;
            }
            // v1：选区捕获的局部坐标以 SOUTH 为“前方”
            ComponentPreviewState.show(local, state.anchorWorld, Direction.SOUTH, currentTransform());
        } else {
            ComponentDefinition def = loadedComponent;
            if (def == null || def.blocks == null || def.blocks.isEmpty()) {
                HudToast.show("预览失败：请先从构件库加载一个构件", true);
                return;
            }
            List<BlockPos> local = new ArrayList<>(def.blocks.size());
            for (ComponentDefinition.BlockEntry be : def.blocks) {
                if (be == null) continue;
                local.add(new BlockPos(be.dx, be.dy, be.dz));
            }
            Direction fromFacing = Direction.SOUTH;
            try {
                if (def.anchor != null && def.anchor.facing != null) {
                    Direction d = parseDir(def.anchor.facing);
                    if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
                }
            } catch (Throwable ignored) {}
            ComponentPreviewState.show(local, state.anchorWorld, fromFacing, currentTransform());
        }
        if (!force) {
            HudToast.show("已开启构件预览");
        }
    }

    public void preview(net.minecraft.client.MinecraftClient client) {
        preview(client, false);
    }

    /**
     * 将当前选区作为构件“放置测试”：走 PatchPreview -> Apply（Undo/Redo）。
     * - 不直接 setBlock
     * - 会正确应用 mirror/rotate 到坐标
     * - 会尽力修正 blockstate 中的 facing=...
     */
    public void applyPatchPreview(net.minecraft.client.MinecraftClient client) {
        if (!isAnchorValid()) {
            HudToast.show("放置失败：请先选择 Anchor", true);
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("放置失败：世界未就绪", true);
            return;
        }

        List<ComponentDefinition.BlockEntry> entries;
        Direction fromFacing = Direction.SOUTH;
        if (!state.useLibrary) {
            if (!SelectionTool.INSTANCE.hasSelection()) {
                HudToast.show("放置失败：请先完成选区", true);
                return;
            }
            entries = buildLocalBlockEntries(client);
            if (entries.isEmpty()) {
                HudToast.show("放置失败：选区内没有非空气方块", true);
                return;
            }
        } else {
            ComponentDefinition def = loadedComponent;
            if (def == null || def.blocks == null || def.blocks.isEmpty()) {
                HudToast.show("放置失败：请先从构件库加载一个构件", true);
                return;
            }
            entries = def.blocks;
            try {
                if (def.anchor != null && def.anchor.facing != null) {
                    Direction d = parseDir(def.anchor.facing);
                    if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
                }
            } catch (Throwable ignored) {}
        }

        ComponentTransform t = currentTransform();

        List<BlockPatch> patches = new ArrayList<>(entries.size());
        int minDy = Integer.MAX_VALUE;
        if (state.semanticSkin && state.semanticPart == null) {
            for (ComponentDefinition.BlockEntry be : entries) {
                if (be == null) continue;
                minDy = Math.min(minDy, be.dy);
            }
            if (minDy == Integer.MAX_VALUE) minDy = 0;
        }
        for (ComponentDefinition.BlockEntry be : entries) {
            if (be == null) continue;
            BlockPos local = new BlockPos(be.dx, be.dy, be.dz);
            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, t);

            String block;
            if (state.semanticSkin) {
                SemanticPart part;
                if (state.semanticPart != null) {
                    part = state.semanticPart;
                } else if (be.semantic != null) {
                    part = be.semantic;
                } else {
                    part = guessSemanticPartFromString(be.block, be.dy, minDy);
                }
                // 语义换皮：shape(坐标/朝向) 来自构件，material 来自 SemanticStyleProfile
                long seed = mixSeed(state.anchorWorld, off, part);
                BlockState picked = SemanticBlockStatePicker.pick(state.semanticStyleId, part, seed);

                // 若原始 blockstate 带有 facing，则尽力把 facing 传递到“换皮后”的方块上
                Direction capturedFacing = BlockStateStringUtil.extractFacing(be.block);
                if (capturedFacing != null) {
                    Direction tf = FacingTransformUtil.transformFacing(capturedFacing, fromFacing, t);
                    picked = BlockStatePropertyUtil.applyFacing(picked, tf);
                }
                block = BlockStateStringUtil.fromState(picked);
            } else {
                block = be.block;
                // 尽力修正 facing/horizontal_facing
                block = BlockStateStringUtil.withTransformedFacing(block, fromFacing, t);
            }

            patches.add(new BlockPatch(BlockPatch.PLACE, off.getX(), off.getY(), off.getZ(), block));
        }

        // 避免 overlay 叠太多：进入 patch preview 时关闭 component 预览
        ComponentPreviewState.clear();

        BuildConfirmPanel.INSTANCE.showPatchPreview(state.anchorWorld, patches);
        HudToast.show("已进入 Patch 预览（可 Apply / Undo / Redo）");
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
                    BlockState bs = null;
                    if (client.world != null) {
                        bs = client.world.getBlockState(p);
                    }
                    if (bs == null || bs.isAir()) continue;
                    out.add(new BlockPos(x - anchor.getX(), y - anchor.getY(), z - anchor.getZ()));
                }
            }
        }
        return out;
    }

    private List<ComponentDefinition.BlockEntry> buildLocalBlockEntries(net.minecraft.client.MinecraftClient client) {
        List<ComponentDefinition.BlockEntry> out = new ArrayList<>();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return out;

        BlockPos anchor = state.anchorWorld;
        if (anchor == null) return out;

        int minDy = Integer.MAX_VALUE;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = null;
                    if (client.world != null) {
                        bs = client.world.getBlockState(p);
                    }
                    if (bs == null || bs.isAir()) continue;
                    minDy = Math.min(minDy, y - anchor.getY());
                }
            }
        }
        if (minDy == Integer.MAX_VALUE) minDy = 0;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = null;
                    if (client.world != null) {
                        bs = client.world.getBlockState(p);
                    }
                    if (bs == null || bs.isAir()) continue;

                    ComponentDefinition.BlockEntry be = new ComponentDefinition.BlockEntry();
                    be.dx = x - anchor.getX();
                    be.dy = y - anchor.getY();
                    be.dz = z - anchor.getZ();
                    be.block = serializeBlockState(bs);
                    if (state.semanticSkin) {
                        if (state.semanticPart != null) {
                            be.semantic = state.semanticPart;
                        } else {
                            be.semantic = guessSemanticPart(bs, be.dy, minDy);
                        }
                    }
                    out.add(be);
                }
            }
        }
        return out;
    }

    private ComponentTransform currentTransform() {
        Direction f = state.facing != null ? state.facing : Direction.SOUTH;
        Mirror m = state.mirror != null ? state.mirror : Mirror.NONE;
        return new ComponentTransform(f, m);
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
        if (!isInsideSelection(anchor)) return null;

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
        if (!sockets.isEmpty()) {
            def.sockets = new ArrayList<>(sockets);
        }
        // v1：自动生成语义放置规格（Attachment / Context / FacingPolicy）
        def.placementSpec = defaultPlacementSpec(def.category, def.tags);

        def.blocks = new ArrayList<>();
        int minDy = Integer.MAX_VALUE;
        if (state.semanticTagOnSave || state.semanticSkin) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState bs = client.world.getBlockState(p);
                        if (bs == null || bs.isAir()) continue;
                        minDy = Math.min(minDy, y - anchor.getY());
                    }
                }
            }
            if (minDy == Integer.MAX_VALUE) minDy = 0;
        }
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
                    if (state.semanticTagOnSave) {
                        be.semantic = guessSemanticPart(bs, be.dy, minDy);
                    } else if (state.semanticSkin) {
                        be.semantic = (state.semanticPart != null) ? state.semanticPart : guessSemanticPart(bs, be.dy, minDy);
                    }
                    def.blocks.add(be);
                }
            }
        }

        return JsonUtil.toJson(def);
    }

    private static ComponentPlacementSpec defaultPlacementSpec(ComponentCategory category, java.util.List<String> tags) {
        ComponentCategory c = (category != null) ? category : ComponentCategory.GENERIC;
        ComponentPlacementSpec spec = new ComponentPlacementSpec();

        // defaults
        spec.attachment = AttachmentType.NONE;
        spec.spatialContext = SpatialContext.ANY;
        spec.facingPolicy = FacingPolicy.NONE;
        spec.constraints = new PlacementConstraints();

        switch (c) {
            case DOOR -> {
                spec.attachment = AttachmentType.WALL_OPENING;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                spec.hasInteriorExterior = true;
                spec.constraints.requiresAttachment = true;
                spec.constraints.minAttachments = 1;
                spec.constraints.maxAttachments = 1;
                spec.semanticTags.add("entry");
                spec.semanticTags.add("circulation");
                spec.aiHint = "WALL_OPENING: derive facing from host wall; keep inside/outside semantics.";
            }
            case WINDOW -> {
                spec.attachment = AttachmentType.WALL_OPENING;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                spec.hasInteriorExterior = true;
                spec.constraints.requiresAttachment = true;
                spec.constraints.minAttachments = 1;
                spec.constraints.maxAttachments = 1;
                spec.semanticTags.add("light");
                spec.semanticTags.add("ventilation");
                spec.aiHint = "WALL_OPENING: derive facing from host wall.";
            }
            case COLUMN -> {
                spec.attachment = AttachmentType.FLOOR;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.NONE;
                spec.constraints.requiresSupportBelow = true;
                spec.semanticTags.add("structure");
                spec.semanticTags.add("support");
                spec.aiHint = "Structural support: no facing; requires support below.";
            }
            case BRACKET -> {
                // 斗拱：通常位于柱顶/梁下，面向不重要
                spec.attachment = AttachmentType.NONE;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.NONE;
                spec.constraints.requiresSupportBelow = true;
                spec.semanticTags.add("structure");
                spec.semanticTags.add("transition");
                spec.aiHint = "Bracket: no facing; attach near structural joints.";
            }
            case ROOF_DETAIL -> {
                spec.attachment = AttachmentType.ROOF_EDGE;
                spec.spatialContext = SpatialContext.EXTERIOR;
                spec.facingPolicy = FacingPolicy.ALONG_EDGE;
                spec.constraints.requiresAttachment = true;
                spec.constraints.requiresEdge = true;
                spec.semanticTags.add("roof");
                spec.semanticTags.add("detail");
                spec.aiHint = "Roof detail: attach to roof edge; align along edge.";
            }
            case ORNAMENT -> {
                spec.attachment = AttachmentType.WALL_SURFACE;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                spec.constraints.requiresAttachment = true;
                spec.semanticTags.add("ornament");
                spec.aiHint = "Ornament: attach to surfaces; facing derived from host.";
            }
            case ARCH -> {
                spec.attachment = AttachmentType.WALL_OPENING;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                spec.constraints.requiresAttachment = true;
                spec.semanticTags.add("arch");
                spec.aiHint = "Arch: treat as opening; derive facing from host.";
            }
            case STAIRS -> {
                spec.attachment = AttachmentType.FLOOR;
                spec.spatialContext = SpatialContext.ANY;
                spec.facingPolicy = FacingPolicy.USER_DEFINED;
                spec.constraints.requiresAttachment = true;
                spec.semanticTags.add("circulation");
                spec.aiHint = "Stairs: direction may be user-defined; ensure continuity.";
            }
            case GENERIC -> {
                spec.aiHint = "Generic component: placement is unconstrained unless tags imply otherwise.";
            }
        }

        // tag hints（best-effort，避免引入新 UI）
        if (tags != null) {
            for (String t : tags) {
                if (t == null) continue;
                String u = t.trim().toLowerCase(Locale.ROOT);
                if (u.contains("balcony") || u.contains("terrace") || u.contains("awning") || u.contains("canopy")) {
                    spec.attachment = AttachmentType.WALL_SURFACE;
                    spec.spatialContext = SpatialContext.EXTERIOR;
                    spec.facingPolicy = FacingPolicy.OUTWARD_NORMAL;
                    spec.constraints.requiresAttachment = true;
                    spec.constraints.minAttachments = 1;
                    spec.constraints.maxAttachments = 2;
                    spec.constraints.forbidInterior = true;
                    spec.semanticTags.add("outdoor");
                }
                if (u.contains("railing") || u.contains("guard") || u.contains("balustrade")) {
                    spec.attachment = AttachmentType.EDGE;
                    spec.facingPolicy = FacingPolicy.ALONG_EDGE;
                    spec.constraints.requiresEdge = true;
                    spec.constraints.prefersContinuity = true;
                    spec.semanticTags.add("safety");
                }
                if (u.contains("chimney")) {
                    spec.attachment = AttachmentType.ROOF_SURFACE;
                    spec.spatialContext = SpatialContext.EXTERIOR;
                    spec.facingPolicy = FacingPolicy.NONE;
                    spec.constraints.requiresAttachment = true;
                    spec.semanticTags.add("roof");
                    spec.semanticTags.add("ornament");
                }
                if (u.contains("dormer")) {
                    spec.attachment = AttachmentType.ROOF_SURFACE;
                    spec.spatialContext = SpatialContext.EXTERIOR;
                    spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                    spec.constraints.requiresAttachment = true;
                    spec.semanticTags.add("roof");
                }
            }
        }
        return spec;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SemanticPart guessSemanticPart(BlockState bs, int dy, int minDy) {
        if (bs == null) return SemanticPart.WALL;

        // 低层优先视为 FOUNDATION（用于“地基换皮”）
        if (dy == minDy) {
            return SemanticPart.FOUNDATION;
        }

        var b = bs.getBlock();
        // 门/窗/栏杆/光源/楼梯
        if (b instanceof net.minecraft.block.DoorBlock || b instanceof net.minecraft.block.TrapdoorBlock) {
            return SemanticPart.DOORWAY;
        }
        String id = null;
        try {
            var bid = Registries.BLOCK.getId(b);
            id = bid.toString();
        } catch (Throwable ignored) {
        }
        if ((id != null && id.contains("glass_pane")) || b == net.minecraft.block.Blocks.GLASS) {
            return SemanticPart.WINDOW;
        }
        if (b instanceof net.minecraft.block.FenceBlock || b instanceof net.minecraft.block.FenceGateBlock || b == net.minecraft.block.Blocks.IRON_BARS) {
            return SemanticPart.RAILING;
        }
        if (b instanceof net.minecraft.block.LanternBlock || b instanceof net.minecraft.block.TorchBlock) {
            return SemanticPart.LIGHT;
        }
        if (b instanceof net.minecraft.block.StairsBlock) {
            return SemanticPart.STAIR_STEP;
        }
        if (b instanceof net.minecraft.block.SlabBlock) {
            return SemanticPart.FLOOR;
        }

        // 柱/梁：按 axis 属性猜（避免依赖具体方块类名/映射）
        try {
            for (Property<?> p : bs.getProperties()) {
                if (p == null) continue;
                if (!"axis".equalsIgnoreCase(p.getName())) continue;
                Object v = bs.get((Property) p);
                if (v instanceof net.minecraft.util.math.Direction.Axis axis) {
                    return axis == net.minecraft.util.math.Direction.Axis.Y ? SemanticPart.PILLAR : SemanticPart.BEAM;
                }
            }
        } catch (Throwable ignored) {
        }

        return SemanticPart.WALL;
    }

    private static SemanticPart guessSemanticPartFromString(String blockStateString, int dy, int minDy) {
        if (dy == minDy) return SemanticPart.FOUNDATION;
        if (blockStateString == null) return SemanticPart.WALL;
        String s = blockStateString.toLowerCase(Locale.ROOT);
        if (s.contains("door") || s.contains("trapdoor")) return SemanticPart.DOORWAY;
        if (s.contains("glass_pane") || s.contains("stained_glass_pane") || s.contains(":glass")) return SemanticPart.WINDOW;
        if (s.contains("fence") || s.contains("iron_bars") || s.contains("bars")) return SemanticPart.RAILING;
        if (s.contains("lantern") || s.contains("torch")) return SemanticPart.LIGHT;
        if (s.contains("stairs")) return SemanticPart.STAIR_STEP;
        if (s.contains("slab")) return SemanticPart.FLOOR;
        if (s.contains("log") || s.contains("stem")) return SemanticPart.PILLAR;
        return SemanticPart.WALL;
    }

    private static long mixSeed(BlockPos anchor, BlockPos off, SemanticPart part) {
        long ax = anchor != null ? anchor.getX() : 0;
        long ay = anchor != null ? anchor.getY() : 0;
        long az = anchor != null ? anchor.getZ() : 0;
        long x = off != null ? off.getX() : 0;
        long y = off != null ? off.getY() : 0;
        long z = off != null ? off.getZ() : 0;
        long p = part != null ? part.ordinal() : 0;

        long h = 1469598103934665603L;
        h ^= ax; h *= 1099511628211L;
        h ^= ay; h *= 1099511628211L;
        h ^= az; h *= 1099511628211L;
        h ^= x;  h *= 1099511628211L;
        h ^= y;  h *= 1099511628211L;
        h ^= z;  h *= 1099511628211L;
        h ^= p;  h *= 1099511628211L;
        return h;
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

    private static Direction parseDir(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Direction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String makeId(ComponentCategory cat, String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (n.isBlank()) n = "component";
        // Group/Socket 系统需要“可被引用”的稳定 id：
        // - 默认使用 name 的规范化形式作为 id（例如 "tower_shell"）
        // - 允许用户通过“同名覆盖保存”来更新构件（saveComponent 会覆盖同 id 文件）
        return n;
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
            sb.append(e.getKey().getName()).append("=").append(e.getValue());
        }
        sb.append("]");
        return sb.toString();
    }
}

