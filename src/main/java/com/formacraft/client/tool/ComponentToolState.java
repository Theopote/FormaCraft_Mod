package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.component.socket.SocketContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentTool（v1）状态：面板字段 + anchor/facing。
 */
public class ComponentToolState {
    /** 捕获草案（用于拾取过程中的临时状态）。 */
    public final ComponentCaptureDraft captureDraft = new ComponentCaptureDraft();
    /** 捕获确认快照（用于取消/恢复）。 */
    public final ComponentCaptureDraft confirmedDraft = new ComponentCaptureDraft();
    /** 是否处于捕获态。 */
    public boolean captureActive = false;
    public ComponentCategory category = ComponentCategory.GENERIC;
    public String name = "New Component";
    public Set<String> tags = new HashSet<>();

    /** null = 自动推断 */
    public String culturalStyleOverride = null;
    /** null = 自动推断，{@link com.formacraft.common.component.archetype.GeometryArchetype} 名称 */
    public String geometryArchetypeOverride = null;

    /** 世界坐标 anchor（必须落在选区内）；null 则默认选区 min。 */
    public BlockPos anchorWorld = null;

    /** 捕获阶段的附着模式（与 UI 同步，用于生成 placementSpec）。 */
    public AttachmentType attachmentMode = AttachmentType.NONE;
    /** 是否要求内外方向（门/窗等）。 */
    public boolean hasInteriorExterior = false;
    /** 是否要求上下方向（楼梯等）。 */
    public boolean hasBottomTop = false;
    /** 宿主面（外墙表面）所在方块。 */
    public BlockPos hostFaceBlock = null;
    /** 宿主面法向（外法线）。 */
    public Direction hostFaceNormal = null;
    /** 内侧标记（世界坐标）。 */
    public BlockPos insideMarkWorld = null;
    /** 外侧标记（世界坐标）。 */
    public BlockPos outsideMarkWorld = null;
    /** 底端标记（世界坐标）。 */
    public BlockPos bottomMarkWorld = null;
    /** 顶端标记（世界坐标）。 */
    public BlockPos topMarkWorld = null;
    /** 允许锚点在选区外侧（用于“空气宿主面”）。 */
    public boolean allowAnchorOutsideSelection = false;
    
    /** 显式选择的方块集合（点选模式使用）。如果非空且 useExplicitSelection=true，buildCurrentComponentJson 将仅导出这些方块。 */
    public Set<BlockPos> explicitSelectedBlocks = null;
    /** 是否使用显式选区（true=点选模式）。 */
    public boolean useExplicitSelection = false;
    /** 构件正面朝向（用于后续旋转/匹配）。 */
    public Direction facing = Direction.SOUTH;
    /** 构件镜像模式（v1）。 */
    public Mirror mirror = Mirror.NONE;

    /** 材质模式：是否使用语义调色板进行“换皮”。 */
    public boolean semanticSkin = false;
    /** 语义部位（semanticSkin=true 时用于选取材质）；null 表示 AUTO（按方块类型/位置自动猜测）。 */
    public SemanticPart semanticPart = SemanticPart.WALL;
    /** SemanticStyleProfile id（semanticSkin=true 时用于材质规则）。 */
    public String semanticStyleId = "DEFAULT";

    /**
     * 保存时是否写入每个 block 的 semantic（AUTO 标注）。
     * - 打开：构件入库即具备“基因接口”，后续可任意风格换皮
     * - 关闭：仅在 semanticSkin=true 时才写 semantic（旧行为）
     */
    public boolean semanticTagOnSave = true;

    /** 放置来源：false=当前选区；true=从构件库加载的构件。 */
    public boolean useLibrary = false;
    /** 构件库中当前选中的构件 id（仅 useLibrary=true 有意义）。 */
    public String librarySelectedId = null;
    /** 构件库中当前选中的构件 name（仅用于 UI 展示）。 */
    public String librarySelectedName = null;
    /** 构件库搜索关键字（id/name/tags）。 */
    public String librarySearch = "";
    /** 构件库分页（从 0 开始）。 */
    public int libraryPage = 0;
    /** 构件库排序模式：RECENT / NAME / CATEGORY */
    public String librarySort = "RECENT";
    /** 构件库分类过滤：null 表示 ALL */
    public ComponentCategory libraryFilterCategory = null;

    /** UI 状态：正在选择 anchor */
    public boolean pickingAnchor = false;

    // ===== Socket 编辑（v1）=====
    /** 已添加 socket 数量（仅用于 UI 反馈/自动命名）。 */
    public int socketCount = 0;
    /** 当前 socket 上下文（循环切换）。 */
    public SocketContext socketContext = SocketContext.WALL;
    /** 当前 socket 朝向（循环切换）。 */
    public Direction socketFacing = Direction.SOUTH;
    /** 当前 socket 尺寸（w/h/d）。 */
    public int socketW = 2, socketH = 3, socketD = 1;
    /** 当前 socket 原点（相对 anchor 的局部坐标），null 表示未设置。 */
    public BlockPos socketOriginLocal = null;
    /** 当前 socket id（用于保存到 ComponentDefinition.sockets）。 */
    public String socketIdDraft = "main_door";
    /** UI 状态：正在点选 socket 原点 */
    public boolean pickingSocket = false;

    // ===== 验证和修复状态（新增）=====
    /** 当前加载的构件定义（用于验证和修复） */
    private transient com.formacraft.common.component.ComponentDefinition componentForValidation = null;
    /** 验证结果 */
    private transient com.formacraft.common.component.validate.ValidationResult validationResult = null;
    /** 自动修复报告 */
    private transient com.formacraft.common.component.autofix.AutoFixReport autoFixReport = null;
    /** 是否已被 AutoFix 修改过 */
    private transient boolean componentDirty = false;
    /** 是否已运行验证 */
    private transient boolean validated = false;
    /** 是否已运行 AutoFix */
    private transient boolean autoFixed = false;

    /**
     * 设置要验证的构件（重置验证状态）
     */
    public void setComponentForValidation(com.formacraft.common.component.ComponentDefinition component) {
        this.componentForValidation = component;
        this.validationResult = null;
        this.autoFixReport = null;
        this.componentDirty = false;
        this.validated = false;
        this.autoFixed = false;
    }

    /**
     * 获取当前验证的构件
     */
    public com.formacraft.common.component.ComponentDefinition getComponentForValidation() {
        return componentForValidation;
    }

    /**
     * 获取验证结果
     */
    public com.formacraft.common.component.validate.ValidationResult getValidationResult() {
        return validationResult;
    }

    /**
     * 获取自动修复报告
     */
    public com.formacraft.common.component.autofix.AutoFixReport getAutoFixReport() {
        return autoFixReport;
    }

    /**
     * 检查构件是否有效（无错误）
     */
    public boolean isComponentValid() {
        return validationResult != null && validationResult.ok();
    }

    /**
     * 检查构件是否已被修改
     */
    public boolean isComponentDirty() {
        return componentDirty;
    }

    /**
     * 检查是否已运行验证
     */
    public boolean isValidated() {
        return validated;
    }

    /**
     * 检查是否已运行 AutoFix
     */
    public boolean isAutoFixed() {
        return autoFixed;
    }

    /**
     * 运行验证
     */
    public void validateComponent() {
        if (componentForValidation == null) {
            validationResult = null;
            validated = false;
            return;
        }
        validationResult = com.formacraft.common.component.validate.ComponentValidator.validate(componentForValidation);
        validated = true;
    }

    /**
     * 运行自动修复（修复后自动重新验证）
     */
    public void autoFixComponent() {
        if (componentForValidation == null) {
            autoFixReport = null;
            autoFixed = false;
            return;
        }
        autoFixReport = com.formacraft.common.component.autofix.ComponentAutoFix.apply(componentForValidation);
        autoFixed = true;
        componentDirty = !autoFixReport.empty();
        // 修复后自动重新验证
        validateComponent();
    }

    /**
     * 进入捕获态：初始化草案与快照。
     */
    public void beginCapture() {
        if (captureActive) return;
        confirmedDraft.loadFrom(this);
        captureDraft.loadFrom(this);
        captureDraft.updatePhase();
        captureActive = true;
    }

    /**
     * 将草案同步到当前状态（用于实时预览）。
     */
    public void syncDraftToState() {
        if (!captureActive) return;
        captureDraft.updatePhase();
        captureDraft.applyTo(this);
    }

    /**
     * 提交草案为最终状态。
     */
    public void commitCapture() {
        if (!captureActive) return;
        captureDraft.updatePhase();
        captureDraft.applyTo(this);
        confirmedDraft.loadFrom(this);
        captureActive = false;
    }

    /**
     * 取消捕获，恢复到进入捕获态前的快照。
     */
    public void cancelCapture() {
        if (!captureActive) return;
        confirmedDraft.applyTo(this);
        captureActive = false;
    }
}

