package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.preview.ComponentPreviewState;
import com.formacraft.client.tool.placement.PlacementAnalyzer;
import com.formacraft.client.tool.placement.PlacementContext;
import com.formacraft.client.tool.placement.PlacementResult;
import com.formacraft.client.tool.placement.PlacementValidator;
import com.formacraft.common.component.socket.*;
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
import com.formacraft.common.component.placement.ComponentPlacementAnalyzer;
import com.formacraft.common.component.placement.PlacementCaptureContext;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.client.preview.ComponentSocketPreviewState;
import com.formacraft.client.tool.socket.SocketHighlighter;
import com.formacraft.common.component.socket.match.SocketMatchResult;
import com.formacraft.common.component.variant.ComponentVariant;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

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

    // hover placement (library mode) status
    private volatile PlacementResult lastHoverPlacement = null;
    private volatile BlockPos lastHoverPos = null;
    private volatile Direction lastHoverFace = null;

    private final List<ComponentSocket> sockets = new ArrayList<>();
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

    /**
     * 获取当前加载的构件定义（用于验证和 AI 接口）
     */
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
        // 避免预览残留（v1：预览随工具生命周期）
        ComponentPreviewState.clear();
        ComponentSocketPreviewState.clear();
        state.pickingAnchor = false;
        state.pickingSocket = false;
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        // 右键：在“构件库放置模式”下尝试放置（否则不消费）
        if (button == 1) {
            return tryPlaceFromLibrary();
        }
        if (button != 0) return false;
        if (!state.pickingAnchor && !state.pickingSocket) return true; // 吃掉点击，避免误破坏

        if (!SelectionTool.INSTANCE.hasSelection()) return true;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;

        BlockPos pos = hit.getBlockPos();
        if (!state.useLibrary) {
            if (state.pickingAnchor && !isAnchorAllowed(pos)) return true;
            if (state.pickingSocket && !isInSelectionBlocks(pos)) return true;
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
            BlockPos anchor = effectiveDraft().anchor.worldPos;
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
        // 若正在预览，更新 anchor
        if (ComponentPreviewState.isActive()) {
            preview(net.minecraft.client.MinecraftClient.getInstance(), true);
        }
        return true;
    }

    @Override
    public void tick() {
        // v1：当 useLibrary=true 且已加载构件时，启用“悬停合法性检测 + 绿/红预览”
        tryUpdatePlacementPreview(net.minecraft.client.MinecraftClient.getInstance());
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        if (ctx == null) return;
        if (!state.useLibrary) return; // 只在库模式下高亮 Socket
        if (loadedComponent == null) return;

        // 获取焦点位置（鼠标 hit 或 anchor）
        Vec3d focus = getVec3d();

        if (focus == null) return;

        // 获取构件变体（如果有）
        ComponentVariant variant = null; // v1: 简化处理，暂时不使用变体

        // 使用 SocketHighlighter 获取合法的 Socket
        List<SocketMatchResult> results = SocketHighlighter.getValidSockets(
                loadedComponent, null, focus
        );

        // 渲染高亮
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

    /** 给 ToolPanel 用的 hover 反馈（不刷 toast）。 */
    public String getHoverPlacementHint() {
        PlacementResult pr = lastHoverPlacement;
        BlockPos p = lastHoverPos;
        Direction f = lastHoverFace;
        if (pr == null || p == null || f == null) return null;
        String s = switch (pr.status) {
            case VALID -> "OK 合法";
            case WARN -> "WARN 可能需要条件";
            case INVALID -> "ERR 非法";
        };
        String why = (pr.reason != null && !pr.reason.isBlank()) ? ("： " + pr.reason) : "";
        return "Hover@" + p.getX() + "," + p.getY() + "," + p.getZ() + " face=" + f.name() + "  " + s + why;
    }

    private void tryUpdatePlacementPreview(net.minecraft.client.MinecraftClient client) {
        if (client == null || client.world == null) return;
        if (!state.useLibrary) return;
        if (state.pickingAnchor || state.pickingSocket) return;
        ComponentDefinition def = loadedComponent;
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return;

        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getSide();
        if (hitPos == null || face == null) return;

        PlacementContext pc = PlacementAnalyzer.analyze(client, hitPos, face);
        PlacementResult pr = PlacementValidator.validate(def.placementSpec, pc, client);
        lastHoverPlacement = pr;
        lastHoverPos = hitPos.toImmutable();
        lastHoverFace = face;

        // anchor：把构件锚点放在被点击面的“外侧一格”
        BlockPos anchor = switch (face) {
            case UP -> hitPos.up();
            case DOWN -> hitPos.down();
            default -> hitPos.offset(face);
        };

        // build local blocks from def
        List<BlockPos> local = new ArrayList<>(def.blocks.size());
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            local.add(new BlockPos(be.dx, be.dy, be.dz));
        }

        // fromFacing：读取构件自身 anchor.facing（默认 SOUTH）
        Direction fromFacing = Direction.SOUTH;
        try {
            if (def.anchor != null && def.anchor.facing != null) {
                Direction d = parseDir(def.anchor.facing);
                if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
            }
        } catch (Throwable ignored) {}

        // preview transform：尽量不用“手动 facing”，让 policy/上下文决定
        Direction previewFacing = fromFacing;
        if (def.placementSpec != null && def.placementSpec.facingPolicy != null) {
            switch (def.placementSpec.facingPolicy) {
                case NONE -> previewFacing = fromFacing;
                case USER_DEFINED -> previewFacing = state.facing != null ? state.facing : fromFacing;
                case DERIVED_FROM_HOST, OUTWARD_NORMAL -> {
                    if (pc.outwardNormal != null && pc.outwardNormal.getAxis().isHorizontal()) {
                        previewFacing = pc.outwardNormal;
                    } else {
                        previewFacing = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
                case ALONG_EDGE -> {
                    if (pc.edgeDirection != null && pc.edgeDirection.getAxis().isHorizontal()) {
                        previewFacing = pc.edgeDirection;
                    } else {
                        previewFacing = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
            }
        }

        Mirror m = state.mirror != null ? state.mirror : Mirror.NONE;
        ComponentTransform t = new ComponentTransform(previewFacing, m);
        ComponentPreviewState.show(local, anchor, fromFacing, t);

        // color：绿/红/黄
        switch (pr.status) {
            case VALID -> ComponentPreviewState.setColor(0.20f, 0.95f, 0.25f, 0.75f);
            case WARN -> ComponentPreviewState.setColor(1.00f, 0.85f, 0.20f, 0.80f);
            case INVALID -> ComponentPreviewState.setColor(1.00f, 0.25f, 0.25f, 0.85f);
        }
    }

    private boolean tryPlaceFromLibrary() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.world == null) return false;
        if (!state.useLibrary) return false;
        if (state.pickingAnchor || state.pickingSocket) return false;

        ComponentDefinition def = loadedComponent;
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return false;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return false;
        BlockPos hitPos = hit.getBlockPos();
        Direction face = hit.getSide();
        if (hitPos == null || face == null) return false;

        PlacementContext pc = PlacementAnalyzer.analyze(client, hitPos, face);
        PlacementResult pr = PlacementValidator.validate(def.placementSpec, pc, client);
        if (pr.status == PlacementResult.Status.INVALID) {
            HudToast.show("放置失败：" + (pr.reason != null ? pr.reason : "非法位置"), true);
            return true;
        }

        // anchor：面外一格
        BlockPos anchor = switch (face) {
            case UP -> hitPos.up();
            case DOWN -> hitPos.down();
            default -> hitPos.offset(face);
        };

        // fromFacing
        Direction fromFacing = Direction.SOUTH;
        try {
            if (def.anchor != null && def.anchor.facing != null) {
                Direction d = parseDir(def.anchor.facing);
                if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
            }
        } catch (Throwable ignored) {}

        // transform 同预览逻辑
        Direction placeFacing = fromFacing;
        if (def.placementSpec != null && def.placementSpec.facingPolicy != null) {
            switch (def.placementSpec.facingPolicy) {
                case NONE -> placeFacing = fromFacing;
                case USER_DEFINED -> placeFacing = state.facing != null ? state.facing : fromFacing;
                case DERIVED_FROM_HOST, OUTWARD_NORMAL -> {
                    if (pc.outwardNormal != null && pc.outwardNormal.getAxis().isHorizontal()) {
                        placeFacing = pc.outwardNormal;
                    } else {
                        placeFacing = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
                case ALONG_EDGE -> {
                    if (pc.edgeDirection != null && pc.edgeDirection.getAxis().isHorizontal()) {
                        placeFacing = pc.edgeDirection;
                    } else {
                        placeFacing = client.player != null ? client.player.getHorizontalFacing() : fromFacing;
                    }
                }
            }
        }
        Mirror m = state.mirror != null ? state.mirror : Mirror.NONE;

        requestServerPatchPreview(anchor, def.id, placeFacing, m, true);
        HudToast.show(pr.status == PlacementResult.Status.WARN ? ("已放置构件（警告：" + pr.reason + "）") : "已放置构件");

        state.useLibrary = false;
        loadedComponent = null;
        ComponentPreviewState.clear();

        return true;
    }

    private void requestServerPatchPreview(
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
        FormaCraftNetworking.sendRequestPatchPreview(new FormaCraftNetworking.RequestPatchPreviewPayload(
                anchor,
                componentId,
                facing != null ? facing.name() : null,
                mirror != null ? mirror.name() : null,
                state.semanticSkin,
                state.semanticStyleId,
                restrict,
                selMin,
                selMax,
                ProtectedZoneTool.INSTANCE.getZones(),
                autoConfirm
        ));
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
            // 设置验证目标并自动验证
            state.setComponentForValidation(def);
            state.validateComponent();
            
            // 显示验证结果
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
            
            try { com.formacraft.client.component.ComponentLibraryUsage.markLoaded(def.id); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            HudToast.show("加载构件失败：JSON 解析失败", true);
        }
    }

    /**
     * 手动触发验证（用于 UI 按钮）
     */
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

    /**
     * 手动触发自动修复（用于 UI 按钮）
     */
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
            // 更新 loadedComponent 引用（因为 AutoFix 修改了对象）
            loadedComponent = state.getComponentForValidation();
        } else {
            HudToast.show("无需修复");
        }
        
        // 显示修复后的验证结果
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

    /**
     * 检查当前加载的构件是否有效（用于 AI 接口保护）
     */
    public boolean isLoadedComponentValid() {
        if (loadedComponent == null) return false;
        if (state.getComponentForValidation() != loadedComponent) {
            // 如果验证目标不是当前加载的构件，重新验证
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
        // 预设尺寸
        switch (state.socketContext) {
            case WALL -> { state.socketW = 2; state.socketH = 3; state.socketD = 1; } // 默认门尺寸
            case EDGE -> { state.socketW = 4; state.socketH = 1; state.socketD = 1; } // 栏杆
            case CORNER -> { state.socketW = 1; state.socketH = 3; state.socketD = 1; } // 角柱
            case ROOF -> { state.socketW = 2; state.socketH = 3; state.socketD = 1; } // 烟囱
            case GROUND -> { state.socketW = 3; state.socketH = 1; state.socketD = 3; } // 地砖
            case INTERIOR -> { state.socketW = 1; state.socketH = 1; state.socketD = 1; } // 装饰
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
            // normalize
            want = want.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
            if (want.isEmpty()) want = "socket_" + state.socketCount;
        }
        String id = uniqueSocketId(want);
        // v1 新版 Socket：使用 Builder 模式
        ComponentSocket s = ComponentSocket.builder(id)
                .role(SocketRole.CONSUMER) // 默认 CONSUMER（组件需要接口）
                .shape(SocketShape.RECT)   // 默认 RECT（门/窗）
                .context(SocketContext.WALL) // 默认 WALL
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
        Direction fromFacing = Direction.SOUTH; // capture local forward
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
        return isAnchorAllowed(effectiveDraft().anchor.worldPos, effectiveDraft());
    }

    public boolean canSave() {
        // v1：强制显式 Anchor（避免后续旋转/放置语义混乱）
        return hasValidSelection(effectiveDraft()) && isAnchorAllowed(effectiveDraft().anchor.worldPos, effectiveDraft());
    }

    private boolean isAnchorAllowed(BlockPos pos) {
        return isAnchorAllowed(pos, effectiveDraft());
    }

    private boolean isAnchorAllowed(BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        if (state.useLibrary) return true;
        if (!SelectionTool.INSTANCE.hasSelection() && !draft.hasExplicitSelection()
                && draft.selection.aabbMin == null && draft.selection.aabbMax == null) return false;

        if (draft.hasExplicitSelection()) {
            if (draft.selection.blocks.contains(pos)) return true;
            if (!draft.anchor.allowOutsideSelection) return false;
            return isAnchorAdjacentToExplicitSelection(pos, draft);
        }

        if (isInsideSelection(pos, draft)) return true;
        if (!draft.anchor.allowOutsideSelection) return false;
        return isInsideExpandedSelection(pos, 1, draft);
    }

    private boolean isInSelectionBlocks(BlockPos pos) {
        if (pos == null) return false;
        ComponentCaptureDraft draft = effectiveDraft();
        if (draft.hasExplicitSelection()) {
            return draft.selection.blocks.contains(pos);
        }
        return isInsideSelection(pos, draft);
    }

    private boolean isAnchorAdjacentToExplicitSelection(BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null || !draft.hasExplicitSelection()) return false;
        for (Direction d : Direction.values()) {
            if (draft.selection.blocks.contains(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideExpandedSelection(BlockPos pos, int pad, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            min = draft.selection.aabbMin;
            max = draft.selection.aabbMax;
        }
        if (min == null || max == null) return false;
        return pos.getX() >= (min.getX() - pad) && pos.getX() <= (max.getX() + pad)
                && pos.getY() >= (min.getY() - pad) && pos.getY() <= (max.getY() + pad)
                && pos.getZ() >= (min.getZ() - pad) && pos.getZ() <= (max.getZ() + pad);
    }

    public void markSavePending(String displayName) {
        this.awaitingSaveAck = true;
        this.awaitingSaveName = (displayName == null || displayName.isBlank()) ? null : displayName.trim();
    }

    /** 
     * 服务端保存确认：用于 toast、跳转与高亮；catalog 刷新不再承担此职责。
     */
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
                : makeId(state.category, displayName);
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
            try {
                if (def.anchor != null && def.anchor.facing != null) {
                    Direction d = parseDir(def.anchor.facing);
                    if (d != null && d.getAxis().isHorizontal()) fromFacing = d;
                }
            } catch (Throwable ignored) {}
            ComponentPreviewState.show(local, effectiveDraft().anchor.worldPos, fromFacing, currentTransform());
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
        requestServerPatchPreview(anchor, componentId, facing, mirror, false);
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

    private List<ComponentDefinition.BlockEntry> buildLocalBlockEntries(net.minecraft.client.MinecraftClient client) {
        List<ComponentDefinition.BlockEntry> out = new ArrayList<>();
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return out;

        BlockPos anchor = effectiveDraft().anchor.worldPos;
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
        ComponentCaptureDraft draft = effectiveDraft();
        Direction f = draft.orientation.facing != null ? draft.orientation.facing : Direction.SOUTH;
        Mirror m = draft.orientation.mirror != null ? draft.orientation.mirror : Mirror.NONE;
        return new ComponentTransform(f, m);
    }

    /** 构造 ComponentDefinition 并序列化为 JSON（供 C2S 发送）。 */
    /**
     * 检查是否有有效选区（AABB 或显式方块集合）
     */
    private boolean hasValidSelection() {
        return hasValidSelection(effectiveDraft());
    }

    private boolean hasValidSelection(ComponentCaptureDraft draft) {
        // 优先检查显式方块集合（点选模式）
        if (draft.hasExplicitSelection()) return true;
        // 回退到 AABB（框选模式）
        if (SelectionTool.INSTANCE.hasSelection()) return true;
        return draft.selection.aabbMin != null && draft.selection.aabbMax != null;
    }

    /**
     * 获取有效选区的方块数量
     */
    private int getValidSelectionBlockCount(net.minecraft.client.MinecraftClient client) {
        if (client == null || client.world == null) return 0;
        
        // 优先使用显式方块集合
        ComponentCaptureDraft draft = effectiveDraft();
        if (draft.hasExplicitSelection()) {
            int count = 0;
            for (BlockPos pos : draft.selection.blocks) {
                if (pos != null && !client.world.getBlockState(pos).isAir()) {
                    count++;
                }
            }
            return count;
        }
        
        // 回退到 AABB 扫描
        if (!SelectionTool.INSTANCE.hasSelection()) return 0;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return 0;
        
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

    public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client) {
        return buildCurrentComponentJson(client, effectiveDraft());
    }

    public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client, ComponentCaptureDraft draft) {
        if (client == null || client.world == null) return null;
        if (!hasValidSelection(draft)) return null;

        // v1：Anchor 必须显式选择
        BlockPos anchor = draft.anchor.worldPos;
        if (anchor == null) return null;
        if (!isAnchorAllowed(anchor, draft)) return null;

        // 计算边界（用于 size 计算）
        BlockPos min, max;
        if (draft.hasExplicitSelection()) {
            // 从显式方块集合计算边界
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : draft.selection.blocks) {
                if (pos == null) continue;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            min = new BlockPos(minX, minY, minZ);
            max = new BlockPos(maxX, maxY, maxZ);
        } else {
            min = SelectionTool.INSTANCE.getMin();
            max = SelectionTool.INSTANCE.getMax();
            if (min == null || max == null) {
                min = draft.selection.aabbMin;
                max = draft.selection.aabbMax;
            }
            if (min == null || max == null) return null;
        }

        int minX = min.getX();
        int minY = min.getY();
        int minZ = min.getZ();
        int maxX = max.getX();
        int maxY = max.getY();
        int maxZ = max.getZ();

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
        a.facing = (draft.orientation.facing != null ? draft.orientation.facing : Direction.SOUTH).name();
        def.anchor = a;

        // v1.1：锚点归一化提示（用于变体/拉伸时的语义稳定）
        if (anchor != null && size.w > 0 && size.h > 0 && size.d > 0) {
            ComponentDefinition.AnchorHint hint = new ComponentDefinition.AnchorHint();
            hint.u = (float) ((anchor.getX() - minX + 0.5) / (double) size.w);
            hint.v = (float) ((anchor.getY() - minY) / (double) size.h);
            hint.w = (float) ((anchor.getZ() - minZ + 0.5) / (double) size.d);
            def.anchorHint = hint;
        }

        // v1.1：放置提示在几何分析后写入（见 blocks 扫描之后）

        def.allowed_facing = java.util.Set.of("NORTH", "SOUTH", "EAST", "WEST");
        def.placement_rules = new ComponentDefinition.PlacementRules();
        if (!sockets.isEmpty()) {
            def.sockets = new ArrayList<>(sockets);
        }

        ComponentDefinition.DirectionHints hints = buildDirectionHints(anchor, draft);
        if (hints != null) {
            def.directionHints = hints;
        }

        def.blocks = new ArrayList<>();
        int minDy = Integer.MAX_VALUE;
        
        // 确定要扫描的方块集合
        java.util.Set<BlockPos> blocksToScan;
        if (draft.hasExplicitSelection()) {
            // 点选模式：仅扫描显式选择的方块
            blocksToScan = draft.selection.blocks;
        } else {
            // 框选模式：扫描整个 AABB
            blocksToScan = new java.util.HashSet<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        blocksToScan.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        
        // 第一遍：计算 minDy（用于语义标注）
        if (state.semanticTagOnSave || state.semanticSkin) {
            for (BlockPos p : blocksToScan) {
                if (p == null) continue;
                BlockState bs = client.world.getBlockState(p);
                if (bs == null || bs.isAir()) continue;
                minDy = Math.min(minDy, p.getY() - anchor.getY());
            }
            if (minDy == Integer.MAX_VALUE) minDy = 0;
        }
        
        // 第二遍：导出方块
        for (BlockPos p : blocksToScan) {
            if (p == null) continue;
            BlockState bs = client.world.getBlockState(p);
            if (bs == null || bs.isAir()) continue;

            ComponentDefinition.BlockEntry be = new ComponentDefinition.BlockEntry();
            be.dx = p.getX() - anchor.getX();
            be.dy = p.getY() - anchor.getY();
            be.dz = p.getZ() - anchor.getZ();
            be.block = serializeBlockState(bs);
            if (state.semanticTagOnSave) {
                be.semantic = guessSemanticPart(bs, be.dy, minDy);
            } else if (state.semanticSkin) {
                be.semantic = (state.semanticPart != null) ? state.semanticPart : guessSemanticPart(bs, be.dy, minDy);
            }
            def.blocks.add(be);
        }

        def.placementSpec = ComponentPlacementAnalyzer.analyze(def, toPlacementContext(draft));
        applyPlacementHints(def, draft);

        com.formacraft.common.component.semantic.ComponentSemanticInference.ensureSemanticFields(def);
        if (state.culturalStyleOverride != null && !state.culturalStyleOverride.isBlank()) {
            def.culturalStyle = state.culturalStyleOverride;
        }
        if (state.geometryArchetypeOverride != null && !state.geometryArchetypeOverride.isBlank()) {
            def.geometryArchetype = state.geometryArchetypeOverride;
        } else if (def.geometryArchetype == null || def.geometryArchetype.isBlank()) {
            def.geometryArchetype = com.formacraft.common.component.semantic.ComponentSemanticInference.inferGeometryArchetype(def);
        }
        return JsonUtil.toJson(def);
    }

    private static PlacementCaptureContext toPlacementContext(ComponentCaptureDraft draft) {
        PlacementCaptureContext ctx = PlacementCaptureContext.createDefault();
        if (draft == null) {
            return ctx;
        }
        ctx.userAttachment = draft.host.attachment != null ? draft.host.attachment : AttachmentType.NONE;
        ctx.userAttachmentManual = draft.host.manualAttachment || draft.host.confirmed;
        ctx.hasInteriorExterior = draft.orientation.hasInteriorExterior;
        ctx.hasBottomTop = draft.orientation.hasBottomTop;
        ctx.hasInsideOutsideMarks = draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null;
        ctx.hasBottomTopMarks = draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null;
        ctx.hasHostFace = draft.host.referenceBlock != null && draft.host.normal != null;
        return ctx;
    }

    private static void applyPlacementHints(ComponentDefinition def, ComponentCaptureDraft draft) {
        if (def == null || draft == null) {
            return;
        }
        ComponentDefinition.PlacementHints hints = new ComponentDefinition.PlacementHints();
        if (def.placementSpec != null) {
            hints.attachment = def.placementSpec.attachment.name();
            hints.needsHostFace = def.placementSpec.attachment == AttachmentType.WALL_OPENING
                    || def.placementSpec.attachment == AttachmentType.WALL_SURFACE
                    || def.placementSpec.attachment == AttachmentType.ROOF_SURFACE;
        } else if (draft.host.attachment != null) {
            hints.attachment = draft.host.attachment.name();
        }
        if (draft.orientation.hasBottomTop || draft.orientation.bottomMarkWorld != null) {
            hints.primaryAxis = "V";
        } else if (draft.orientation.hasInteriorExterior || draft.orientation.insideMarkWorld != null) {
            hints.primaryAxis = "W";
        }
        def.placementHints = hints;
    }

    private ComponentDefinition.DirectionHints buildDirectionHints(BlockPos anchor, ComponentCaptureDraft draft) {
        ComponentDefinition.DirectionHints hints = new ComponentDefinition.DirectionHints();
        boolean hasAny = false;

        if (draft.host.attachment != null) {
            hints.attachmentMode = draft.host.attachment.name();
            hasAny = true;
        }
        if (draft.orientation.hasInteriorExterior) {
            hints.hasInteriorExterior = true;
            hasAny = true;
        }
        if (draft.orientation.hasBottomTop) {
            hints.hasBottomTop = true;
            hasAny = true;
        }

        if (draft.orientation.insideMarkWorld != null && anchor != null) {
            hints.inside = toMark(draft.orientation.insideMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.outsideMarkWorld != null && anchor != null) {
            hints.outside = toMark(draft.orientation.outsideMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.bottomMarkWorld != null && anchor != null) {
            hints.bottom = toMark(draft.orientation.bottomMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.topMarkWorld != null && anchor != null) {
            hints.top = toMark(draft.orientation.topMarkWorld, anchor);
            hasAny = true;
        }

        if (draft.host.referenceBlock != null && draft.host.normal != null && anchor != null) {
            ComponentDefinition.DirectionHints.HostFace host = new ComponentDefinition.DirectionHints.HostFace();
            host.dx = draft.host.referenceBlock.getX() - anchor.getX();
            host.dy = draft.host.referenceBlock.getY() - anchor.getY();
            host.dz = draft.host.referenceBlock.getZ() - anchor.getZ();
            host.normal = draft.host.normal.name();
            host.allowAir = draft.anchor.allowOutsideSelection;
            hints.hostFace = host;
            hasAny = true;
        }

        return hasAny ? hints : null;
    }

    private static ComponentDefinition.DirectionHints.Mark toMark(BlockPos pos, BlockPos anchor) {
        ComponentDefinition.DirectionHints.Mark m = new ComponentDefinition.DirectionHints.Mark();
        m.dx = pos.getX() - anchor.getX();
        m.dy = pos.getY() - anchor.getY();
        m.dz = pos.getZ() - anchor.getZ();
        return m;
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
        return isInsideSelection(pos, effectiveDraft());
    }

    private boolean isInsideSelection(BlockPos pos, ComponentCaptureDraft draft) {
        if (pos == null) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            min = draft.selection.aabbMin;
            max = draft.selection.aabbMax;
        }
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

