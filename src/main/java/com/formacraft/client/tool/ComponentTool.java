package com.formacraft.client.tool;

import com.formacraft.FormacraftMod;
import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.client.tool.placement.PlacementResult;
import com.formacraft.common.component.socket.*;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.client.preview.ComponentSocketPreviewState;
import com.formacraft.client.tool.socket.SocketHighlighter;
import com.formacraft.common.component.socket.match.SocketMatchResult;
import com.formacraft.common.component.variant.ComponentVariant;
import net.minecraft.block.BlockState;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private final ComponentToolLibraryPlacement.HoverSnapshot hoverSnapshot = new ComponentToolLibraryPlacement.HoverSnapshot();

    private final List<ComponentSocket> sockets = new ArrayList<>();
    /** 与 sockets 一一对应的局部原点（保存时写入 socketPlacements）。 */
    private final List<ComponentDefinition.SocketPlacement> pendingSocketPlacements = new ArrayList<>();
    private final ComponentCaptureDraft scratchDraft = new ComponentCaptureDraft();

    private ComponentTool() {}

    private ComponentCaptureDraft effectiveDraft() {
        if (state.captureActive) return state.captureDraft;
        scratchDraft.loadFrom(state);
        scratchDraft.updatePhase();
        return scratchDraft;
    }

    public ComponentToolState getState() {
        return state;
    }

    public ComponentDefinition getLoadedComponent() {
        return loadedComponent;
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
        ComponentPreviewState.clear();
        ComponentSocketPreviewState.clear();
        state.pickingAnchor = false;
        state.pickingSocket = false;
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button == 1) {
            ComponentToolLibraryPlacement.PlaceResult placeResult = ComponentToolLibraryPlacement.tryPlaceFromLibrary(
                    net.minecraft.client.MinecraftClient.getInstance(), state, loadedComponent);
            if (placeResult.placed) {
                loadedComponent = null;
            }
            return placeResult.consumed;
        }
        if (button != 0) return false;
        if (!state.pickingAnchor && !state.pickingSocket) return true;

        if (!SelectionTool.INSTANCE.hasSelection()) return true;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        BlockPos pos = hit.getBlockPos();
        ComponentCaptureDraft draft = effectiveDraft();
        if (!state.useLibrary) {
            if (state.pickingAnchor && !ComponentToolCaptureSupport.isAnchorAllowed(state, pos, draft)) return true;
            if (state.pickingSocket && !ComponentToolCaptureSupport.isInSelectionBlocks(state, draft, pos)) return true;
        }

        if (state.pickingAnchor) {
            if (state.captureActive) {
                state.captureDraft.anchor.worldPos = pos.toImmutable();
                state.syncDraftToState();
            } else {
                state.anchorWorld = pos.toImmutable();
            }
            state.pickingAnchor = false;
        } else if (state.pickingSocket) {
            BlockPos anchor = draft.anchor.worldPos;
            if (anchor == null) {
                HudToast.show("请先选择 Anchor（再点选连接位原点）", true);
                return true;
            }
            state.socketOriginLocal = new BlockPos(pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ());
            state.pickingSocket = false;
            HudToast.show("已设置连接位原点（local=" + state.socketOriginLocal.getX() + "," + state.socketOriginLocal.getY() + "," + state.socketOriginLocal.getZ() + "）");
            if (ComponentSocketPreviewState.isActive()) {
                showSocketPreview(true);
            }
        }
        if (ComponentPreviewState.isActive()) {
            preview(net.minecraft.client.MinecraftClient.getInstance(), true);
        }
        return true;
    }

    @Override
    public void tick() {
        ComponentToolLibraryPlacement.tryUpdatePlacementPreview(
                net.minecraft.client.MinecraftClient.getInstance(), state, loadedComponent, hoverSnapshot);
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        if (ctx == null) return;
        if (!state.useLibrary) return;
        if (loadedComponent == null) return;

        Vec3d focus = getVec3d();
        if (focus == null) return;

        ComponentVariant variant = null;
        List<SocketMatchResult> results = SocketHighlighter.getValidSockets(
                loadedComponent, variant, focus
        );

        if (!results.isEmpty()) {
            SocketHighlighter.renderHighlights(ctx, results);
        }
    }

    private @Nullable Vec3d getVec3d() {
        Vec3d focus = null;
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.crosshairTarget instanceof BlockHitResult hit) {
            focus = hit.getPos();
        } else if (effectiveDraft().anchor.worldPos != null) {
            BlockPos anchor = effectiveDraft().anchor.worldPos;
            focus = new Vec3d(
                    anchor.getX() + 0.5,
                    anchor.getY() + 0.5,
                    anchor.getZ() + 0.5
            );
        }
        return focus;
    }

    public String getHoverPlacementHint() {
        PlacementResult pr = hoverSnapshot.placement;
        BlockPos p = hoverSnapshot.pos;
        Direction f = hoverSnapshot.face;
        if (pr == null || p == null || f == null) return null;
        String s = switch (pr.status) {
            case VALID -> "OK 合法";
            case WARN -> "WARN 可能需要条件";
            case INVALID -> "ERR 非法";
        };
        String why = (pr.reason != null && !pr.reason.isBlank()) ? ("： " + pr.reason) : "";
        return "Hover@" + p.getX() + "," + p.getY() + "," + p.getZ() + " face=" + f.name() + "  " + s + why;
    }

    public void cycleFacing() {
        Direction base = state.captureActive ? state.captureDraft.orientation.facing : state.facing;
        if (base == null) base = Direction.SOUTH;
        Direction next = switch (base) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
        if (state.captureActive) {
            state.captureDraft.orientation.facing = next;
            state.syncDraftToState();
        } else {
            state.facing = next;
        }
        if (ComponentPreviewState.isActive()) {
            ComponentPreviewState.setTransform(currentTransform());
        }
    }

    public void cycleMirror() {
        Mirror base = state.captureActive ? state.captureDraft.orientation.mirror : state.mirror;
        if (base == null) base = Mirror.NONE;
        Mirror next = switch (base) {
            case NONE -> Mirror.X;
            case X -> Mirror.Z;
            case Z -> Mirror.NONE;
        };
        if (state.captureActive) {
            state.captureDraft.orientation.mirror = next;
            state.syncDraftToState();
        } else {
            state.mirror = next;
        }
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
            state.semanticPart = null;
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
        FormaCraftClientNetworking.sendComponentGetRequest(state.librarySelectedId);
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
            state.setComponentForValidation(def);
            state.validateComponent();

            var validationResult = state.getValidationResult();
            if (validationResult != null) {
                if (validationResult.hasErrors()) {
                    HudToast.show("已加载构件，但有 " + validationResult.errors().size() + " 个错误", true);
                } else if (validationResult.hasWarnings()) {
                    HudToast.show("已加载构件，有 " + validationResult.warnings().size() + " 个警告");
                } else {
                    HudToast.show("已加载构件（验证通过）");
                }
            } else {
                HudToast.show("已加载构件：「" + (def.name != null ? def.name : def.id) + "」 blocks=" + def.blocks.size());
            }

            try {
                com.formacraft.client.component.ComponentLibraryUsage.markLoaded(def.id);
            } catch (Throwable t) {
                FormacraftMod.LOGGER.debug("[ComponentTool] markLoaded failed componentId={}", def.id, t);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("[ComponentTool] Failed to load component from server json", t);
            HudToast.show("加载构件失败：JSON 解析失败", true);
        }
    }

    public void validateLoadedComponent() {
        if (loadedComponent == null) {
            HudToast.show("请先加载一个构件", true);
            return;
        }
        state.setComponentForValidation(loadedComponent);
        state.validateComponent();
        var result = state.getValidationResult();
        if (result != null) {
            if (result.hasErrors()) {
                HudToast.show("验证失败：发现 " + result.errors().size() + " 个错误", true);
            } else if (result.hasWarnings()) {
                HudToast.show("验证通过，但有 " + result.warnings().size() + " 个警告");
            } else {
                HudToast.show("验证通过 ✓");
            }
        }
    }

    public void autoFixLoadedComponent() {
        if (loadedComponent == null) {
            HudToast.show("请先加载一个构件", true);
            return;
        }
        state.setComponentForValidation(loadedComponent);
        state.autoFixComponent();
        var report = state.getAutoFixReport();
        if (report != null && !report.empty()) {
            HudToast.show("已应用 " + report.size() + " 个自动修复");
            loadedComponent = state.getComponentForValidation();
        } else {
            HudToast.show("无需修复");
        }

        var result = state.getValidationResult();
        if (result != null) {
            if (result.hasErrors()) {
                HudToast.show("修复后仍有 " + result.errors().size() + " 个错误", true);
            } else if (result.hasWarnings()) {
                HudToast.show("修复后仍有 " + result.warnings().size() + " 个警告");
            } else {
                HudToast.show("修复完成，验证通过 ✓");
            }
        }
    }

    public boolean isLoadedComponentValid() {
        if (loadedComponent == null) return false;
        if (state.getComponentForValidation() != loadedComponent) {
            state.setComponentForValidation(loadedComponent);
            state.validateComponent();
        }
        return state.isComponentValid();
    }

    public void startPickAnchor() {
        state.pickingAnchor = true;
    }

    public void startPickSocketOrigin() {
        state.pickingSocket = true;
    }

    public void cycleSocketType() {
        SocketContext[] v = SocketContext.values();
        int idx = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == state.socketContext) {
                idx = i;
                break;
            }
        }
        state.socketContext = v[(idx + 1) % v.length];
        switch (state.socketContext) {
            case WALL -> { state.socketW = 2; state.socketH = 3; state.socketD = 1; }
            case EDGE -> { state.socketW = 4; state.socketH = 1; state.socketD = 1; }
            case CORNER -> { state.socketW = 1; state.socketH = 3; state.socketD = 1; }
            case ROOF -> { state.socketW = 2; state.socketH = 3; state.socketD = 1; }
            case GROUND -> { state.socketW = 3; state.socketH = 1; state.socketD = 3; }
            case INTERIOR -> { state.socketW = 1; state.socketH = 1; state.socketD = 1; }
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
            HudToast.show("请先点选连接位原点", true);
            return;
        }
        state.socketCount++;
        String want = (state.socketIdDraft == null) ? "" : state.socketIdDraft.trim();
        if (want.isEmpty()) {
            want = "socket_" + state.socketCount;
        } else {
            want = want.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
            if (want.isEmpty()) want = "socket_" + state.socketCount;
        }
        String id = uniqueSocketId(want);
        ComponentSocket s = ComponentSocket.builder(id)
                .role(SocketRole.CONSUMER)
                .shape(SocketShape.RECT)
                .context(SocketContext.WALL)
                .facingPolicy(SocketFacingPolicy.IN_OUT)
                .size(SizeConstraint.rect(
                        Math.max(1, state.socketW),
                        Math.max(1, state.socketH),
                        Math.max(1, state.socketW),
                        Math.max(1, state.socketH)
                ))
                .tag("opening")
                .build();
        sockets.add(s);
        ComponentDefinition.SocketPlacement placement = new ComponentDefinition.SocketPlacement();
        placement.id = id;
        placement.dx = state.socketOriginLocal.getX();
        placement.dy = state.socketOriginLocal.getY();
        placement.dz = state.socketOriginLocal.getZ();
        placement.facing = (state.socketFacing != null ? state.socketFacing : Direction.SOUTH).name();
        pendingSocketPlacements.add(placement);
        int w = s.size.min.length > 0 ? s.size.min[0] : 0;
        int h = s.size.min.length > 1 ? s.size.min[1] : 0;
        HudToast.show("已添加连接位：" + id + " (" + s.context + ") " + w + "x" + h);
    }

    public void toggleSocketPreview(net.minecraft.client.MinecraftClient client) {
        if (ComponentSocketPreviewState.isActive()) {
            ComponentSocketPreviewState.clear();
            HudToast.show("已关闭连接位预览");
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("连接位预览失败：世界未就绪", true);
            return;
        }
        if (effectiveDraft().anchor.worldPos == null) {
            HudToast.show("连接位预览失败：请先选择 Anchor", true);
            return;
        }
        if (!SelectionTool.INSTANCE.hasSelection()) {
            HudToast.show("连接位预览失败：请先完成选区", true);
            return;
        }
        if (state.socketOriginLocal == null) {
            HudToast.show("连接位预览失败：请先点选连接位原点", true);
            return;
        }
        showSocketPreview(false);
    }

    private void showSocketPreview(boolean silent) {
        Direction fromFacing = Direction.SOUTH;
        ComponentSocketPreviewState.show(
                effectiveDraft().anchor.worldPos,
                state.socketOriginLocal,
                Math.max(1, state.socketW),
                Math.max(1, state.socketH),
                Math.max(1, state.socketD),
                state.socketFacing != null ? state.socketFacing : Direction.SOUTH,
                fromFacing,
                currentTransform()
        );
        if (!silent) {
            HudToast.show("已开启连接位预览（" + (state.socketContext != null ? state.socketContext.name() : "连接位") + "）");
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
            if (s == null) continue;
            if (id.equals(s.id)) return true;
        }
        return false;
    }

    public void clearSockets() {
        sockets.clear();
        pendingSocketPlacements.clear();
        state.socketCount = 0;
        HudToast.show("已清空连接位");
    }

    public void clearAnchor() {
        if (state.captureActive) {
            state.captureDraft.anchor.worldPos = null;
            state.syncDraftToState();
        } else {
            state.anchorWorld = null;
        }
        state.pickingAnchor = false;
        ComponentPreviewState.clear();
    }

    private boolean isAnchorValid() {
        ComponentCaptureDraft draft = effectiveDraft();
        return ComponentToolCaptureSupport.isAnchorAllowed(state, draft.anchor.worldPos, draft);
    }

    public boolean canSave() {
        ComponentCaptureDraft draft = effectiveDraft();
        return ComponentToolCaptureSupport.hasValidSelection(draft)
                && ComponentToolCaptureSupport.isAnchorAllowed(state, draft.anchor.worldPos, draft);
    }

    public void markSavePending(String displayName) {
        this.awaitingSaveAck = true;
        this.awaitingSaveName = (displayName == null || displayName.isBlank()) ? null : displayName.trim();
    }

    public void onSaveAckFromServer(String id, String name, boolean success, String message) {
        if (!awaitingSaveAck) {
            return;
        }
        awaitingSaveAck = false;
        String pendingName = awaitingSaveName;
        awaitingSaveName = null;

        if (!success) {
            String error = (message == null || message.isBlank()) ? "保存构件失败" : message;
            HudToast.show(error, true);
            return;
        }

        String displayName = (name != null && !name.isBlank())
                ? name
                : ((pendingName == null || pendingName.isBlank()) ? "（未命名）" : pendingName);
        String toast = (message == null || message.isBlank())
                ? ("✓ 构件「" + displayName + "」已保存到库")
                : message;
        HudToast.show(toast);

        String componentId = (id != null && !id.isBlank())
                ? id
                : ComponentToolJsonBuilder.makeId(state.category, displayName);
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            com.formacraft.client.ui.FormaCraftHudOverlay.activePanel =
                    com.formacraft.client.ui.panel.PanelType.COMPONENT_LIBRARY;
            state.librarySelectedId = componentId;
            state.librarySelectedName = displayName;
        });
    }

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
            ComponentPreviewState.show(local, effectiveDraft().anchor.worldPos, Direction.SOUTH, currentTransform());
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
            if (def.anchor != null && def.anchor.facing != null) {
                Direction d = ComponentToolJsonBuilder.parseDir(def.anchor.facing);
                if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
            }
            ComponentPreviewState.show(local, effectiveDraft().anchor.worldPos, fromFacing, currentTransform());
        }
        if (!force) {
            HudToast.show("已开启构件预览");
        }
    }

    public void preview(net.minecraft.client.MinecraftClient client) {
        preview(client, false);
    }

    public void applyPatchPreview(net.minecraft.client.MinecraftClient client) {
        if (!isAnchorValid()) {
            HudToast.show("放置失败：请先选择 Anchor", true);
            return;
        }
        if (client == null || client.world == null) {
            HudToast.show("放置失败：世界未就绪", true);
            return;
        }

        BlockPos anchor = effectiveDraft().anchor.worldPos;
        String componentId = null;
        ComponentTransform t = currentTransform();
        Direction facing = t.facing();
        Mirror mirror = t.mirror();

        if (state.useLibrary) {
            ComponentDefinition def = loadedComponent;
            if (def == null || def.blocks == null || def.blocks.isEmpty()) {
                HudToast.show("放置失败：请先从构件库加载一个构件", true);
                return;
            }
            componentId = def.id;
        } else if (!SelectionTool.INSTANCE.hasSelection()) {
            HudToast.show("放置失败：请先完成选区", true);
            return;
        }

        ComponentPreviewState.clear();
        ComponentToolLibraryPlacement.requestServerPatchPreview(state, anchor, componentId, facing, mirror, false);
        HudToast.show("正在请求 Patch 预览…");
    }

    private List<BlockPos> buildLocalBlocks(net.minecraft.client.MinecraftClient client) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return out;

        BlockPos anchor = effectiveDraft().anchor.worldPos;
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

    private ComponentTransform currentTransform() {
        ComponentCaptureDraft draft = effectiveDraft();
        Direction f = draft.orientation.facing != null ? draft.orientation.facing : Direction.SOUTH;
        Mirror m = draft.orientation.mirror != null ? draft.orientation.mirror : Mirror.NONE;
        return new ComponentTransform(f, m);
    }

    public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client) {
        return buildCurrentComponentJson(client, effectiveDraft());
    }

    public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client, ComponentCaptureDraft draft) {
        return ComponentToolJsonBuilder.build(client, state, sockets, pendingSocketPlacements, draft);
    }
}
