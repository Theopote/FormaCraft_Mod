package com.formacraft.client.ui.panel;

import com.formacraft.client.component.ComponentThumbnailGenerator;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ToolRenderUtil;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.toast.HudToast;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 构件拾取面板（独立面板）
 * <p>
 * 职责：
 * - 显示当前选区预览
 * - 配置构件的所有参数（基础信息、锚点朝向、语义标注、连接位）
 * - 智能分析构件特征
 * - 保存构件到库
 * <p>
 * 工作流：
 * 1. 用户在世界中用 SelectionTool 框选
 * 2. 切换到此面板
 * 3. 配置参数（或点击智能分析）
 * 4. 点击"保存到构件库"
 * 5. 自动跳转到构件库面板并高亮新构件
 */
public class ComponentCapturePanel extends BasePanel {
    private static final int CONTENT_PADDING = 10;
    private static final int LABEL_OFFSET = 18;
    private static final int BUTTON_HEIGHT = 16;
    private static final int THUMBNAIL_SIZE = 120; // 增大显示尺寸以更好地展示构件细节
    private static final int FIELD_SPACING = 28;

    // 输入框
    private final HudTextInput nameInput = new HudTextInput();
    private final HudTextInput tagsInput = new HudTextInput();
    private final HudTextInput socketIdInput = new HudTextInput();

    // 基础信息按钮
    private ButtonWidget categoryButton;
    private ButtonWidget pickAnchorButton;
    private ButtonWidget clearAnchorButton;
    private ButtonWidget hostFaceButton;
    private ButtonWidget anchorOutsideButton;
    private ButtonWidget autoAnchorButton;
    private ButtonWidget facingButton;
    private ButtonWidget mirrorButton;

    // 语义标注按钮
    private ButtonWidget semanticSkinButton;
    private ButtonWidget semanticTagOnSaveButton;
    private ButtonWidget semanticStyleButton;
    private ButtonWidget semanticPartButton;

    // 连接位配置按钮
    private ButtonWidget socketContextButton;
    private ButtonWidget socketPickOriginButton;
    private ButtonWidget socketFacingButton;
    private ButtonWidget socketAddButton;
    private ButtonWidget socketPreviewButton;
    private ButtonWidget socketClearButton;

    // 智能分析按钮
    private ButtonWidget autoAnalyzeButton;
    private ButtonWidget autoDetectSocketsButton;
    private ButtonWidget autoFixButton;

    // 底部按钮
    private ButtonWidget cancelButton;
    private ButtonWidget saveButton;
    
    // 选择工具按钮
    private ButtonWidget boxSelectButton;
    private ButtonWidget pointSelectButton;
    private ButtonWidget clearSelectionButton;
    
    // Phase 3: 语义配置按钮
    private ButtonWidget attachmentModeButton;
    private ButtonWidget directionalityButton;
    private ButtonWidget setInsideButton;
    private ButtonWidget setOutsideButton;
    private ButtonWidget setBottomButton;
    private ButtonWidget setTopButton;

    // 输入框边界（用于点击检测）
    private int nameInputX, nameInputY, nameInputW, nameInputH;
    private int tagsInputX, tagsInputY, tagsInputW, tagsInputH;
    private int socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH;
    private boolean nameInputBoundsValid = false;
    private boolean tagsInputBoundsValid = false;
    private boolean socketIdInputBoundsValid = false;

    // 缩略图缓存
    private BufferedImage cachedThumbnail = null;
    private BlockPos lastSelectionMin = null;
    private BlockPos lastSelectionMax = null;
    private volatile boolean isGeneratingThumbnail = false;

    // 滚动
    private int scrollY = 0;
    private int maxScrollY = 0;
    
    // 选择工具状态
    private ComponentSelectionMode selectionMode = ComponentSelectionMode.BOX_SELECT;
    private java.util.Set<BlockPos> selectedBlocks = new java.util.HashSet<>();
    private boolean isDragging = false;
    
    // Phase 3: 语义配置状态
    private com.formacraft.common.component.placement.AttachmentType attachmentMode = 
        com.formacraft.common.component.placement.AttachmentType.NONE;
    private DirectionalityMode directionalityMode = DirectionalityMode.NONE;
    private DirectionMarkingMode markingMode = DirectionMarkingMode.NONE;
    private BlockPos insideMark = null;
    private BlockPos outsideMark = null;
    private BlockPos bottomMark = null;
    private BlockPos topMark = null;
    
    // 阶段感知状态
    private CapturePhase currentPhase = CapturePhase.SELECTION;
    private boolean[] phaseCollapsed = new boolean[CapturePhase.getTotalPhases()]; // 默认都展开
    
    // 健康检查抽屉状态
    private boolean healthDrawerExpanded = false; // 健康检查抽屉是否展开
    private long lastHealthCheckTime = 0; // 上次健康检查时间（用于防抖）
    private static final long HEALTH_CHECK_DEBOUNCE_MS = 200; // 健康检查防抖时间
    private int healthSummaryStartY = -1; // 健康摘要行的起始Y坐标（用于点击检测）
    private int healthSummaryEndY = -1; // 健康摘要行的结束Y坐标（用于点击检测）
    
    // 调试开关
    private static final boolean DEBUG_CAPTURE = false; // 设置为 true 启用调试日志

    public ComponentCapturePanel() {
        nameInput.setMaxLength(64);
        nameInput.setPlaceholder("输入构件名称（如：橡木门）");
        
        tagsInput.setMaxLength(256);
        tagsInput.setPlaceholder("输入标签，用逗号分隔（如：wood, modern）");
        
        socketIdInput.setMaxLength(64);
        socketIdInput.setPlaceholder("输入连接位 ID（如：door_frame）");
    }

    private void ensureWidgets() {
        if (categoryButton != null) return;

        var st = ComponentTool.INSTANCE.getState();

        // 基础信息按钮
        categoryButton = ButtonWidget.builder(Text.literal("分类：GENERIC"), b -> cycleCategory())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        构件分类
                        ━━━━━━━━━━━━
                        点击循环切换构件的类型分类
                        
                        分类用于：
                        • AI 智能放置时的语义识别
                        • 构件库的分类浏览
                        • 自动检测附着模式
                        
                        常见分类：门、窗、柱子、装饰等""")))
                .build();

        pickAnchorButton = ButtonWidget.builder(Text.literal("📍 点击选择锚点"), b -> ComponentTool.INSTANCE.startPickAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置锚点（原点）
                        ━━━━━━━━━━━━
                        锚点是构件的参考原点
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中右键点击方块
                        3. 该方块将成为锚点
                        
                        建议位置：
                        • 门窗：底部中心
                        • 柱子：底部中心
                        • 装饰：附着点
                        • 家具：前侧底部中心""")))
                .build();

        clearAnchorButton = ButtonWidget.builder(Text.literal("清除锚点"), b -> ComponentTool.INSTANCE.clearAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清除锚点
                        ━━━━━━━━━━━━
                        移除当前设置的锚点
                        
                        清除后需要重新设置锚点
                        才能保存构件""")))
                .build();

        hostFaceButton = ButtonWidget.builder(Text.literal("选择宿主面"), b -> startMarkingHostFace())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        选择宿主面（外墙表面）
                        ━━━━━━━━━━━━
                        宿主面是构件与墙体对齐的参考面
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中点击外墙表面
                        3. 系统自动记录外法线
                        
                        用于：窗、门、雨棚、阳台等需要对齐墙面的构件""")))
                .build();

        anchorOutsideButton = ButtonWidget.builder(Text.literal("外侧锚点：关"), b -> st.allowAnchorOutsideSelection = !st.allowAnchorOutsideSelection)
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        允许外侧锚点（空气宿主面）
                        ━━━━━━━━━━━━
                        开启后可将锚点放在选区外侧的空气方块
                        
                        使用场景：
                        ? 完全外凸的窗/装饰
                        ? 需要以外墙面为零面的构件""")))
                .build();

        autoAnchorButton = ButtonWidget.builder(Text.literal("自动锚点（底部中心）"), b -> setAutoAnchor())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        自动设置锚点
                        ━━━━━━━━━━━━
                        将锚点放置在构件底部中心
                        
                        对偶数宽度构件更友好
                        适合门窗/柱子等构件""")))
                .build();

        facingButton = ButtonWidget.builder(Text.literal("朝向：SOUTH"), b -> cycleFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        构件朝向
                        ━━━━━━━━━━━━
                        定义构件的"正面"朝向
                        
                        用途：
                        • AI 放置时自动旋转
                        • 确保门窗朝向正确
                        • 对称构件的镜像参考
                        
                        点击循环切换：北/东/南/西""")))
                .build();

        mirrorButton = ButtonWidget.builder(Text.literal("镜像：NONE"), b -> cycleMirror())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        镜像模式
                        ━━━━━━━━━━━━
                        控制构件的镜像行为
                        
                        选项：
                        • NONE：不镜像
                        • LEFT_RIGHT：左右镜像
                        • FRONT_BACK：前后镜像
                        
                        用于创建对称变体""")))
                .build();

        // 语义标注按钮
        semanticSkinButton = ButtonWidget.builder(Text.literal("材质：原样"), b -> ComponentTool.INSTANCE.toggleSemanticSkin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        材质模式（高级）
                        ━━━━━━━━━━━━
                        控制方块材质的处理方式
                        
                        原样方块：
                        • 保留精确的方块类型
                        • 适合特定设计的构件
                        
                        语义换皮：
                        • AI 可根据风格替换材质
                        • 适合可变风格的构件""")))
                .build();

        semanticTagOnSaveButton = ButtonWidget.builder(Text.literal("存语义：开"), b -> st.semanticTagOnSave = !st.semanticTagOnSave)
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        保存语义标注（高级）
                        ━━━━━━━━━━━━
                        保存时为每个方块添加语义信息
                        
                        开启后：
                        • 自动标注方块的功能部位
                        • AI 可更智能地调整构件
                        • 支持风格驱动的材质替换
                        
                        推荐：通用构件开启""")))
                .build();

        semanticStyleButton = ButtonWidget.builder(Text.literal("风格：DEFAULT"), b -> ComponentTool.INSTANCE.cycleSemanticStyle())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        建筑风格（高级）
                        ━━━━━━━━━━━━
                        定义构件的建筑风格
                        
                        风格类型：
                        • DEFAULT：默认/混合
                        • MEDIEVAL：中世纪
                        • MODERN：现代
                        • ASIAN：亚洲风格
                        
                        用于 AI 风格匹配""")))
                .build();

        semanticPartButton = ButtonWidget.builder(Text.literal("语义：AUTO"), b -> ComponentTool.INSTANCE.cycleSemanticPart())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        语义部位（高级）
                        ━━━━━━━━━━━━
                        标注构件的功能部位
                        
                        AUTO：自动检测
                        手动选项：
                        • FRAME：框架/边框
                        • FILL：填充/主体
                        • DETAIL：装饰细节
                        
                        用于智能材质替换""")))
                .build();

        // 连接位配置按钮
        socketContextButton = ButtonWidget.builder(Text.literal("连接位上下文: WALL"), b -> ComponentTool.INSTANCE.cycleSocketType())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        连接位上下文（高级）
                        ━━━━━━━━━━━━
                        定义连接位的附着类型
                        
                        类型：
                        • WALL：墙面（门窗）
                        • FLOOR：地板（家具）
                        • CEILING：天花板（吊灯）
                        • EDGE：边缘（栏杆）
                        
                        AI 将根据上下文智能放置""")))
                .build();

        socketPickOriginButton = ButtonWidget.builder(Text.literal("点选原点"), b -> ComponentTool.INSTANCE.startPickSocketOrigin())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置连接位原点（高级）
                        ━━━━━━━━━━━━
                        连接位是构件的嵌入位置
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中右键点击
                        3. 设置连接位的参考点
                        
                        例如：门框的底部中心""")))
                .build();

        socketFacingButton = ButtonWidget.builder(Text.literal("朝向: SOUTH"), b -> ComponentTool.INSTANCE.cycleSocketFacing())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        连接位朝向（高级）
                        ━━━━━━━━━━━━
                        定义连接位的放置方向
                        
                        确保：
                        • 门窗朝向正确
                        • 附着面对齐
                        • 多个连接位协调一致
                        
                        点击循环切换方向""")))
                .build();

        socketAddButton = ButtonWidget.builder(Text.literal("添加连接位"), b -> ComponentTool.INSTANCE.addSocket())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        添加连接位（高级）
                        ━━━━━━━━━━━━
                        将当前配置的连接位添加到构件
                        
                        一个构件可以有多个连接位
                        例如：墙体可以有多个门窗位置
                        
                        添加后在列表中显示""")))
                .build();

        socketPreviewButton = ButtonWidget.builder(Text.literal("预览"), b -> ComponentTool.INSTANCE.toggleSocketPreview(client))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        预览连接位（高级）
                        ━━━━━━━━━━━━
                        在世界中显示连接位的：
                        • 位置标记
                        • 朝向箭头
                        • 大小区域
                        
                        帮助验证连接位配置正确""")))
                .build();

        socketClearButton = ButtonWidget.builder(Text.literal("清空连接位"), b -> ComponentTool.INSTANCE.clearSockets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清空所有连接位（高级）
                        ━━━━━━━━━━━━
                        移除所有已添加的连接位
                        
                        注意：此操作不可撤销
                        清空后需要重新配置""")))
                .build();

        // 智能分析按钮
        autoAnalyzeButton = ButtonWidget.builder(Text.literal("🔍 智能分析"), b -> runAutoAnalysis())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        智能分析构件（推荐）
                        ━━━━━━━━━━━━
                        自动分析构件特征并设置参数
                        
                        分析内容：
                        • 构件类型（门/窗/柱子等）
                        • 附着模式（底面/竖边等）
                        • 建筑风格（现代/中世纪等）
                        • 建筑原型（民居/宫殿等）
                        
                        节省手动配置时间！""")))
                .build();
        
        // 自动修复按钮
        autoFixButton = ButtonWidget.builder(Text.literal("🤖 自动修复"), b -> runAutoFix())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        自动修复构件问题
                        ━━━━━━━━━━━━
                        根据健康检查结果自动修复可修复的问题
                        
                        修复内容：
                        • 自动设置锚点（如果缺失）
                        • 调整锚点位置（如果不合理）
                        • 其他可自动修复的问题
                        
                        注意：只修复标记为"可自动修复"的问题""")))
                .build();

        autoDetectSocketsButton = ButtonWidget.builder(Text.literal("自动检测"), b -> autoDetectSockets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        自动检测连接位（高级）
                        ━━━━━━━━━━━━
                        智能识别构件中的嵌入位置
                        
                        可检测：
                        • 门框开口
                        • 窗户位置
                        • 栏杆连接点
                        • 装饰附着点
                        
                        自动创建连接位列表""")))
                .build();

        // 底部按钮
        cancelButton = ButtonWidget.builder(Text.literal("取消"), b -> {
                    ComponentTool.INSTANCE.getState().pickingAnchor = false;
                    FormaCraftHudOverlay.activePanel = PanelType.TOOLS;
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        取消构件拾取
                        ━━━━━━━━━━━━
                        放弃当前构件配置
                        返回工具面板
                        
                        已配置的内容将丢失""")))
                .build();

        saveButton = ButtonWidget.builder(Text.literal("✓ 保存到构件库"), b -> saveComponent())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        保存构件到库
                        ━━━━━━━━━━━━
                        将构件保存到全局构件库
                        
                        保存内容：
                        • 方块数据和结构
                        • 锚点和朝向
                        • 分类和标签
                        • 缩略图预览
                        • 连接位配置（如果有）
                        
                        保存后自动跳转到构件库""")))
                .build();
        
        // 选择工具按钮
        boxSelectButton = ButtonWidget.builder(Text.literal("📦 框选"), b -> setSelectionMode(ComponentSelectionMode.BOX_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        框选模式
                        ━━━━━━━━━━━━
                        拖拽框选区域（有实时预览）
                        
                        使用方法：
                        1. 激活此模式
                        2. 在世界中左键拖拽
                        3. 看到蓝色预览框
                        4. 释放鼠标完成选择
                        
                        提示：适合快速框选完整结构""")))
                .build();
        
        pointSelectButton = ButtonWidget.builder(Text.literal("👆 点选"), b -> setSelectionMode(ComponentSelectionMode.POINT_SELECT))
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        点选模式
                        ━━━━━━━━━━━━
                        单个方块精确选择
                        
                        使用方法：
                        • 点击未选方块 → 加选
                        • 点击已选方块 → 减选
                        • Ctrl+点击 → 强制加选
                        
                        提示：适合精细调整和不规则选区""")))
                .build();
        
        clearSelectionButton = ButtonWidget.builder(Text.literal("🗑️ 清除"), b -> clearSelection())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清除选区
                        ━━━━━━━━━━━━
                        一键清空当前选区
                        
                        使用场景：
                        • 重新开始选择
                        • 清除错误的选区
                        • 快速重置
                        
                        提示：清除后需要重新选择""")))
                .build();
        
        // Phase 3: 语义配置按钮
        attachmentModeButton = ButtonWidget.builder(
                Text.literal("附着: " + getAttachmentModeDisplay()), 
                b -> cycleAttachmentMode()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        附着模式
                        ━━━━━━━━━━━━
                        定义构件如何附着到建筑上
                        
                        模式：
                        • 无附着 - 独立构件（柱子、雕塑）
                        • 地面 - 地板装饰
                        • 墙面 - 贴墙装饰、壁龛
                        • 墙体 - 门、窗（自动开洞）
                        • 屋面 - 老虎窗
                        • 屋檐 - 飞檐
                        • 屋脊 - 脊兽
                        • 边缘 - 栏杆、护栏
                        • 转角 - 阳台、塔角装饰
                        
                        点击循环切换""")))
                .build();
        
        directionalityButton = ButtonWidget.builder(
                Text.literal("方向: " + directionalityMode.getDisplayName()), 
                b -> cycleDirectionality()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        方向性
                        ━━━━━━━━━━━━
                        定义构件是否有方向性
                        
                        模式：
                        • 无方向 - 任意旋转
                        • 内外 - 有内外侧（门、窗）
                        • 上下 - 有上下端（楼梯）
                        • 双向 - 同时有内外和上下
                        
                        点击循环切换""")))
                .build();
        
        setInsideButton = ButtonWidget.builder(
                Text.literal("🏠 设内侧"), 
                b -> startMarkingInside()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置内侧标记
                        ━━━━━━━━━━━━
                        标记构件的内侧方向
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中点击内侧方块
                        3. 系统自动计算朝向
                        
                        用于：门、窗等有内外之分的构件""")))
                .build();
        
        setOutsideButton = ButtonWidget.builder(
                Text.literal("🌍 设外侧"), 
                b -> startMarkingOutside()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置外侧标记
                        ━━━━━━━━━━━━
                        标记构件的外侧方向
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中点击外侧方块
                        3. 系统自动计算朝向
                        
                        用于：门、窗等有内外之分的构件""")))
                .build();
        
        setBottomButton = ButtonWidget.builder(
                Text.literal("⬇️ 设底端"), 
                b -> startMarkingBottom()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置底端标记
                        ━━━━━━━━━━━━
                        标记构件的底端位置
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中点击底端方块
                        3. 系统自动计算上下朝向
                        
                        用于：楼梯、梯子等有上下之分的构件""")))
                .build();
        
        setTopButton = ButtonWidget.builder(
                Text.literal("⬆️ 设顶端"), 
                b -> startMarkingTop()
        )
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        设置顶端标记
                        ━━━━━━━━━━━━
                        标记构件的顶端位置
                        
                        使用方法：
                        1. 点击此按钮
                        2. 在世界中点击顶端方块
                        3. 系统自动计算上下朝向
                        
                        用于：楼梯、梯子等有上下之分的构件""")))
                .build();
    }
    
    /**
     * 获取分类的显示名称
     */
    private String getCategoryDisplayName(ComponentCategory category) {
        return switch (category) {
            case DOOR -> "门";
            case WINDOW -> "窗";
            case COLUMN -> "柱子";
            case STAIRS -> "楼梯";
            case BRACKET -> "斗拱";
            case ORNAMENT -> "装饰";
            case ARCH -> "拱券";
            case ROOF_DETAIL -> "屋顶细节";
            default -> "通用构件";
        };
    }
    
    /**
     * 获取附着模式的显示名称
     */
    private String getAttachmentModeDisplay() {
        return switch (attachmentMode) {
            case NONE -> "无附着";
            case FLOOR -> "地面";
            case WALL_SURFACE -> "墙面";
            case WALL_OPENING -> "墙体"; // 门、窗等会在墙体上开洞的构件
            case ROOF_SURFACE -> "屋面";
            case ROOF_EDGE -> "屋檐";
            case ROOF_RIDGE -> "屋脊";
            case EDGE -> "边缘";
            case CORNER -> "转角";
        };
    }
    
    /**
     * 循环切换附着模式
     */
    private void cycleAttachmentMode() {
        com.formacraft.common.component.placement.AttachmentType[] values = 
            com.formacraft.common.component.placement.AttachmentType.values();
        int index = attachmentMode.ordinal();
        attachmentMode = values[(index + 1) % values.length];
        syncPlacementHintsToState();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 切换附着模式: {}", attachmentMode);
        }
    }
    
    /**
     * 循环切换方向性模式
     */
    private void cycleDirectionality() {
        directionalityMode = directionalityMode.next();
        syncPlacementHintsToState();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 切换方向性: {}", directionalityMode.getDisplayName());
        }
    }

    /**
     * 同步 UI 的附着/方向性提示到 ComponentToolState
     */
    private void syncPlacementHintsToState() {
        var st = ComponentTool.INSTANCE.getState();
        st.attachmentMode = attachmentMode;
        st.hasInteriorExterior = directionalityMode == DirectionalityMode.INSIDE_OUTSIDE
                || directionalityMode == DirectionalityMode.BOTH;
        st.hasBottomTop = directionalityMode == DirectionalityMode.BOTTOM_TOP
                || directionalityMode == DirectionalityMode.BOTH;
    }
    
    /**
     * 开始标记内侧
     */
    private void startMarkingInside() {
        markingMode = DirectionMarkingMode.MARKING_INSIDE;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 进入内侧标记模式");
        }
    }
    
    /**
     * 开始标记外侧
     */
    private void startMarkingOutside() {
        markingMode = DirectionMarkingMode.MARKING_OUTSIDE;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 进入外侧标记模式");
        }
    }
    
    /**
     * 开始标记底端
     */
    private void startMarkingBottom() {
        markingMode = DirectionMarkingMode.MARKING_BOTTOM;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 进入底端标记模式");
        }
    }
    
    /**
     * 开始标记顶端
     */
    private void startMarkingTop() {
        markingMode = DirectionMarkingMode.MARKING_TOP;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 进入顶端标记模式");
        }
    }

    /**
     * 开始标记宿主面
     */
    private void startMarkingHostFace() {
        markingMode = DirectionMarkingMode.MARKING_HOST_FACE;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 进入宿主面标记模式");
        }
    }
    
    /**
     * 处理方向标记（在世界点击时调用）
     */
    private void handleDirectionMarking(BlockHitResult hit) {
        if (hit == null) return;
        BlockPos pos = hit.getBlockPos();
        switch (markingMode) {
            case MARKING_INSIDE:
                insideMark = pos.toImmutable();
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 内侧标记: {}", insideMark);
                }
                markingMode = DirectionMarkingMode.NONE;
                applyFacingFromMarks();
                break;
                
            case MARKING_OUTSIDE:
                outsideMark = pos.toImmutable();
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 外侧标记: {}", outsideMark);
                }
                markingMode = DirectionMarkingMode.NONE;
                applyFacingFromMarks();
                break;
                
            case MARKING_BOTTOM:
                bottomMark = pos.toImmutable();
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 底端标记: {}", bottomMark);
                }
                markingMode = DirectionMarkingMode.NONE;
                break;
                
            case MARKING_TOP:
                topMark = pos.toImmutable();
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 顶端标记: {}", topMark);
                }
                markingMode = DirectionMarkingMode.NONE;
                break;

            case MARKING_HOST_FACE:
                setHostFace(hit);
                markingMode = DirectionMarkingMode.NONE;
                break;
                
            default:
                break;
        }
    }
    
    /**
     * 设置选择模式
     */
    private void setSelectionMode(ComponentSelectionMode mode) {
        this.selectionMode = mode;
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 切换选择模式: {}", mode.getDisplayName());
        }
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();

        int x = panelX + CONTENT_PADDING;
        int w = panelWidth - CONTENT_PADDING * 2;
        int y = getContentY() + CONTENT_PADDING - scrollY;

        // 重置边界
        nameInputBoundsValid = false;
        tagsInputBoundsValid = false;
        socketIdInputBoundsValid = false;

        // 背景
        ctx.fill(panelX + 1, getContentY(), panelX + panelWidth - 1, panelY + panelHeight - 1, 0x80101010);

        // 启用裁剪
        ctx.enableScissor(panelX, getContentY(), panelX + panelWidth, panelY + panelHeight);

        // 标题
        y = drawWrappedText(ctx, Text.literal("🎯 构件拾取  Component Capture"), x, y, w, 0xFFFFFFFF);
        y += 4;
        
        // 阶段提示（顶部弱引导）
        currentPhase = computeCurrentPhase();
        String phaseText = String.format("🟢 步骤 %d / %d  ── %s", 
            currentPhase.getPhaseNumber(), 
            CapturePhase.getTotalPhases(),
            currentPhase.getDescription());
        y = drawWrappedText(ctx, Text.literal(phaseText), x, y, w, 0xFF88FF88);
        y += 6;
        
        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;
        
        // ============ 阶段 1：选区定义 ============
        boolean phase1Collapsed = phaseCollapsed[0];
        boolean isPhase1Active = currentPhase == CapturePhase.SELECTION;
        boolean isPhase1Complete = isPhaseComplete(CapturePhase.SELECTION);
        
        String phase1Title = (phase1Collapsed ? "▶ " : "▼ ") + 
            "① 选区定义" + (isPhase1Complete ? "（已完成 ✓）" : (isPhase1Active ? "（当前步骤 ★）" : "（未开始）"));
        int phase1TitleColor = isPhase1Active ? 0xFFFFFF00 : (isPhase1Complete ? 0xFF88FF88 : 0xFF888888);
        y = drawWrappedText(ctx, Text.literal(phase1Title), x, y, w, phase1TitleColor);
        y += 2;
        
        if (!phase1Collapsed) {
        
        // 选择模式按钮组
        int buttonW = (w - 8) / 3; // 3个按钮平分宽度
        
        // 框选按钮
        boxSelectButton.setMessage(Text.literal(selectionMode == ComponentSelectionMode.BOX_SELECT ? "📦 [框选]" : "📦 框选"));
        boxSelectButton.setPosition(x, y);
        boxSelectButton.setWidth(buttonW);
        boxSelectButton.visible = true;
        boxSelectButton.active = true;
        boxSelectButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        // 点选按钮
        pointSelectButton.setMessage(Text.literal(selectionMode == ComponentSelectionMode.POINT_SELECT ? "👆 [点选]" : "👆 点选"));
        pointSelectButton.setPosition(x + buttonW + 4, y);
        pointSelectButton.setWidth(buttonW);
        pointSelectButton.visible = true;
        pointSelectButton.active = true;
        pointSelectButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        // 清除按钮
        clearSelectionButton.setMessage(Text.literal("🗑️ 清除"));
        clearSelectionButton.setPosition(x + buttonW * 2 + 8, y);
        clearSelectionButton.setWidth(buttonW);
        clearSelectionButton.visible = true;
        clearSelectionButton.active = !selectedBlocks.isEmpty() || SelectionTool.INSTANCE.hasSelection();
        clearSelectionButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        
        y += LABEL_OFFSET;
        
        // 当前模式提示
        String modeText = "当前模式: " + selectionMode.getDisplayName() + " - " + selectionMode.getHint();
        y = drawWrappedText(ctx, Text.literal(modeText), x, y, w, 0xFF88CCFF);
        y += 4;
        
        // 快捷键说明
        String hint = selectionMode == ComponentSelectionMode.POINT_SELECT 
            ? "提示: 点击方块切换选择 | Ctrl+点击强制加选 | 右键设锚点"
            : "提示: 拖拽可见预览框 | 右键设锚点";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(hint), x, y, 0xFF666666);
        y += client.textRenderer.fontHeight + 4;
        
        // 分隔线
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 4;

            // 检查是否有选区
            if (!SelectionTool.INSTANCE.hasSelection() && selectedBlocks.isEmpty()) {
                y = drawWrappedText(ctx, Text.literal("⚠ 尚未选择任何方块"), x, y, w, 0xFFFFAA00);
            } else {

        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();

        // 选区预览
        y = drawWrappedText(ctx, Text.literal("当前选区"), x, y, w, 0xFFFFFFFF);
        y += 2;

        int sizeX = 0;
        if (max != null) {
            if (min != null) {
                sizeX = max.getX() - min.getX() + 1;
            }
        }
        int sizeY = 0;
        if (max != null) {
            if (min != null) {
                sizeY = max.getY() - min.getY() + 1;
            }
        }
        int sizeZ = 0;
        if (max != null) {
            if (min != null) {
                sizeZ = max.getZ() - min.getZ() + 1;
            }
        }
        int blockCount = countBlocksInSelection();

        y = drawWrappedText(ctx, Text.literal("尺寸: " + sizeX + "×" + sizeY + "×" + sizeZ), x, y, w, 0xFFAAAAAA);
        y += client.textRenderer.fontHeight;
        y = drawWrappedText(ctx, Text.literal("方块数: " + blockCount), x, y, w, 0xFFAAAAAA);
        y += 6;
        
        // 状态指示器
        y = drawStatusIndicator(ctx, x, y, w);
        y += 4;

        // 缩略图预览
        drawThumbnailPreview(ctx, x + (w - THUMBNAIL_SIZE) / 2, y);
        y += THUMBNAIL_SIZE + 8;

                // 分隔线
                ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            }
            y += 4;
        }
        
        // 获取状态（所有阶段都需要）
        var st = ComponentTool.INSTANCE.getState();
        syncPlacementHintsToState();
        
        // ============ 阶段 2：锚点与朝向 ============
        boolean phase2Collapsed = phaseCollapsed[1];
        boolean isPhase2Active = currentPhase == CapturePhase.ANCHOR_ORIENTATION;
        boolean isPhase2Complete = isPhaseComplete(CapturePhase.ANCHOR_ORIENTATION);
        
        // 如果阶段1未完成，阶段2应该折叠
        if (!isPhase1Complete) {
            phase2Collapsed = true;
        }
        
        String phase2Title = (phase2Collapsed ? "▶ " : "▼ ") + 
            "② 锚点 & 朝向" + (isPhase2Complete ? "（已完成 ✓）" : (isPhase2Active ? "（当前步骤 ★）" : "（未开始）"));
        int phase2TitleColor = isPhase2Active ? 0xFFFFFF00 : (isPhase2Complete ? 0xFF88FF88 : 0xFF888888);
        y = drawWrappedText(ctx, Text.literal(phase2Title), x, y, w, phase2TitleColor);
        y += 2;
        
        if (!phase2Collapsed) {
            // 锚点与朝向（阶段2内容）
            String anchorText = st.anchorWorld != null
                ? "锚点: (" + st.anchorWorld.getX() + ", " + st.anchorWorld.getY() + ", " + st.anchorWorld.getZ() + ")"
                : "锚点: (未设置，默认为选区最小角)";
            y = drawWrappedText(ctx, Text.literal(anchorText), x, y, w, 
                st.anchorWorld != null ? 0xFF66FF66 : 0xFFFFAA00);
            y += 2;

            int half = (w - 4) / 2;

            pickAnchorButton.setMessage(Text.literal(st.pickingAnchor ? "⏹ 取消选择" : "📍 点击选择"));
            pickAnchorButton.setPosition(x, y);
            pickAnchorButton.setWidth(half);
            pickAnchorButton.visible = true;
            pickAnchorButton.active = true;
            pickAnchorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            clearAnchorButton.setPosition(x + half + 4, y);
            clearAnchorButton.setWidth(w - half - 4);
            clearAnchorButton.visible = true;
            clearAnchorButton.active = st.anchorWorld != null || st.pickingAnchor;
            clearAnchorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            String hostFaceText = (st.hostFaceBlock != null && st.hostFaceNormal != null)
                    ? ("宿主面: (" + st.hostFaceBlock.getX() + ", " + st.hostFaceBlock.getY() + ", " + st.hostFaceBlock.getZ() + ") " + st.hostFaceNormal.name())
                    : "宿主面: (未设置)";
            y = drawWrappedText(ctx, Text.literal(hostFaceText), x, y, w,
                    st.hostFaceNormal != null ? 0xFF66CCFF : 0xFF888888);
            y += 2;

            hostFaceButton.setMessage(Text.literal(st.hostFaceNormal != null ? "宿主面：重选" : "选择宿主面"));
            hostFaceButton.setPosition(x, y);
            hostFaceButton.setWidth(half);
            hostFaceButton.visible = true;
            hostFaceButton.active = hasValidSelection();
            hostFaceButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            anchorOutsideButton.setMessage(Text.literal(st.allowAnchorOutsideSelection ? "外侧锚点：开" : "外侧锚点：关"));
            anchorOutsideButton.setPosition(x + half + 4, y);
            anchorOutsideButton.setWidth(w - half - 4);
            anchorOutsideButton.visible = true;
            anchorOutsideButton.active = true;
            anchorOutsideButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            autoAnchorButton.setPosition(x, y);
            autoAnchorButton.setWidth(w);
            autoAnchorButton.visible = true;
            autoAnchorButton.active = hasValidSelection();
            autoAnchorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            facingButton.setMessage(Text.literal("朝向：" + st.facing.name()));
            facingButton.setPosition(x, y);
            facingButton.setWidth(half);
            facingButton.visible = true;
            facingButton.active = true;
            facingButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            mirrorButton.setMessage(Text.literal("镜像：" + st.mirror.name()));
            mirrorButton.setPosition(x + half + 4, y);
            mirrorButton.setWidth(w - half - 4);
            mirrorButton.visible = true;
            mirrorButton.active = true;
            mirrorButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;
            
            // 如果构件需要方向性（门/窗/阳台），显示提示
            if (st.category == ComponentCategory.DOOR || st.category == ComponentCategory.WINDOW) {
                y = drawWrappedText(ctx, Text.literal("⚠ 该构件需要\"内 / 外\"方向"), x, y, w, 0xFFFFAA00);
                y += 2;
                y = drawWrappedText(ctx, Text.literal("请分别标记："), x, y, w, 0xFFAAAAAA);
                y += 2;
            }

            // 分隔线
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }
        
        // ============ 阶段 3：构件语义确认 ============
        boolean phase3Collapsed = phaseCollapsed[2];
        boolean isPhase3Active = currentPhase == CapturePhase.SEMANTIC;
        boolean isPhase3Complete = isPhaseComplete(CapturePhase.SEMANTIC);
        
        // 如果阶段2未完成，阶段3应该折叠
        if (!isPhase2Complete) {
            phase3Collapsed = true;
        }
        
        String phase3Title = (phase3Collapsed ? "▶ " : "▼ ") + 
            "③ 构件语义" + (isPhase3Complete ? "（已完成 ✓）" : (isPhase3Active ? "（当前步骤 ★）" : "（自动 + 可调整）"));
        int phase3TitleColor = isPhase3Active ? 0xFFFFFF00 : (isPhase3Complete ? 0xFF88FF88 : 0xFF888888);
        y = drawWrappedText(ctx, Text.literal(phase3Title), x, y, w, phase3TitleColor);
        y += 2;
        
        if (!phase3Collapsed) {
            // 基础信息
            y = drawWrappedText(ctx, Text.literal("📝 基础信息"), x, y, w, 0xFFFFFFFF);
            y += 2;
            
            // 名称输入
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("名称:"), x, y, 0xFFAAAAAA);
            int inputY = y + LABEL_OFFSET - 2;
            if (!nameInput.isFocused() && !st.name.equals(nameInput.getText())) {
                nameInput.setText(st.name);
            }
            nameInput.render(ctx, x, inputY, w, 14);
            nameInputX = x; nameInputY = inputY; nameInputW = w; nameInputH = 14;
            nameInputBoundsValid = true;
            st.name = nameInput.getText() != null ? nameInput.getText() : "New Component";
            y += FIELD_SPACING;

            // 分类按钮（语义声明按钮）
            String categoryEmoji = switch (st.category) {
                case DOOR -> "🚪";
                case WINDOW -> "🪟";
                case COLUMN -> "🏛️";
                case STAIRS -> "🪜";
                case BRACKET -> "🏗️";
                case ORNAMENT -> "🧱";
                case ARCH -> "⛩️";
                case ROOF_DETAIL -> "🏠";
                default -> "📦";
            };
            categoryButton.setMessage(Text.literal("你正在定义的是：" + categoryEmoji + " " + getCategoryDisplayName(st.category)));
            categoryButton.setPosition(x, y);
            categoryButton.setWidth(w);
            categoryButton.visible = true;
            categoryButton.active = true;
            categoryButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;
            
            // 附着方式解释（自动）
            String attachmentExplanation = switch (attachmentMode) {
                case WALL_OPENING -> "📌 这个构件会被\"嵌入到墙体中\"";
                case WALL_SURFACE -> "📌 这个构件会\"附着在墙面上\"";
                case FLOOR -> "📌 这个构件会\"放置在地面上\"";
                case ROOF_SURFACE -> "📌 这个构件会\"附着在屋面上\"";
                case ROOF_EDGE -> "📌 这个构件会\"附着在屋檐边缘\"";
                case ROOF_RIDGE -> "📌 这个构件会\"附着在屋脊上\"";
                case EDGE -> "📌 这个构件会\"沿边缘放置\"";
                case CORNER -> "📌 这个构件会\"放置在转角\"";
                default -> "📌 这个构件是\"独立放置\"";
            };
            y = drawWrappedText(ctx, Text.literal(attachmentExplanation + "（自动）"), x, y, w, 0xFFAAAAAA);
            y += 4;
            
            // 方向语义解释（自动）
            if (directionalityMode == DirectionalityMode.INSIDE_OUTSIDE) {
                y = drawWrappedText(ctx, Text.literal("方向语义：内 → 外（自动）"), x, y, w, 0xFFAAAAAA);
            } else if (directionalityMode == DirectionalityMode.BOTTOM_TOP) {
                y = drawWrappedText(ctx, Text.literal("方向语义：下 → 上（自动）"), x, y, w, 0xFFAAAAAA);
            } else if (directionalityMode == DirectionalityMode.BOTH) {
                y = drawWrappedText(ctx, Text.literal("方向语义：内 → 外，下 → 上（自动）"), x, y, w, 0xFFAAAAAA);
            }
            y += 4;

            // 标签输入
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("标签 (逗号分隔):"), x, y, 0xFFAAAAAA);
            inputY = y + LABEL_OFFSET - 2;
            String currentTags = String.join(", ", st.tags);
            if (!tagsInput.isFocused() && !currentTags.equals(tagsInput.getText())) {
                tagsInput.setText(currentTags);
            }
            tagsInput.render(ctx, x, inputY, w, 14);
            tagsInputX = x; tagsInputY = inputY; tagsInputW = w; tagsInputH = 14;
            tagsInputBoundsValid = true;
            updateTagsFromInput();
            y += FIELD_SPACING;
            
            // 附着模式和方向性（可调整）
            y = drawWrappedText(ctx, Text.literal("🔧 附着与方向性（可调整）"), x, y, w, 0xFFFFFFFF);
            y += 2;
            
            int halfW = (w - 4) / 2;
            
            attachmentModeButton.setMessage(Text.literal("附着: " + getAttachmentModeDisplay()));
            attachmentModeButton.setPosition(x, y);
            attachmentModeButton.setWidth(halfW);
            attachmentModeButton.visible = true;
            attachmentModeButton.active = true;
            attachmentModeButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            
            directionalityButton.setMessage(Text.literal("方向: " + directionalityMode.getDisplayName()));
            directionalityButton.setPosition(x + halfW + 4, y);
            directionalityButton.setWidth(w - halfW - 4);
            directionalityButton.visible = true;
            directionalityButton.active = true;
            directionalityButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;
            
            // 方向标记按钮（根据方向性模式显示）
            if (directionalityMode.needsInsideOutside()) {
                setInsideButton.setMessage(Text.literal(insideMark != null ? "🏠✓ 内侧" : "🏠 设内侧"));
                setInsideButton.setPosition(x, y);
                setInsideButton.setWidth(halfW);
                setInsideButton.visible = true;
                setInsideButton.active = true;
                setInsideButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
                
                setOutsideButton.setMessage(Text.literal(outsideMark != null ? "🌍✓ 外侧" : "🌍 设外侧"));
                setOutsideButton.setPosition(x + halfW + 4, y);
                setOutsideButton.setWidth(w - halfW - 4);
                setOutsideButton.visible = true;
                setOutsideButton.active = true;
                setOutsideButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
                y += LABEL_OFFSET;
            }
            
            if (directionalityMode.needsBottomTop()) {
                setBottomButton.setMessage(Text.literal(bottomMark != null ? "⬇️✓ 底端" : "⬇️ 设底端"));
                setBottomButton.setPosition(x, y);
                setBottomButton.setWidth(halfW);
                setBottomButton.visible = true;
                setBottomButton.active = true;
                setBottomButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
                
                setTopButton.setMessage(Text.literal(topMark != null ? "⬆️✓ 顶端" : "⬆️ 设顶端"));
                setTopButton.setPosition(x + halfW + 4, y);
                setTopButton.setWidth(w - halfW - 4);
                setTopButton.visible = true;
                setTopButton.active = true;
                setTopButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
                y += LABEL_OFFSET;
            }
            
            // 标记模式提示
            if (markingMode != DirectionMarkingMode.NONE) {
                y = drawWrappedText(ctx, Text.literal("⚡ " + markingMode.getHint()), x, y, w, 0xFFFFFF00);
                y += 4;
            }
            
            // 分隔线
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            
            // 语义标注（高级，可折叠）
            y = drawWrappedText(ctx, Text.literal("🎨 语义标注（高级）"), x, y, w, 0xFF888888);
            y += 2;
            
            int half = (w - 4) / 2;
            semanticSkinButton.setMessage(Text.literal(st.semanticSkin ? "材质：语义" : "材质：原样"));
            semanticSkinButton.setPosition(x, y);
            semanticSkinButton.setWidth(half);
            semanticSkinButton.visible = true;
            semanticSkinButton.active = true;
            semanticSkinButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            semanticTagOnSaveButton.setMessage(Text.literal(st.semanticTagOnSave ? "存语义：开" : "存语义：关"));
            semanticTagOnSaveButton.setPosition(x + half + 4, y);
            semanticTagOnSaveButton.setWidth(w - half - 4);
            semanticTagOnSaveButton.visible = true;
            semanticTagOnSaveButton.active = true;
            semanticTagOnSaveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            semanticStyleButton.setMessage(Text.literal("风格：" + (st.semanticStyleId != null ? st.semanticStyleId : "DEFAULT")));
            semanticStyleButton.setPosition(x, y);
            semanticStyleButton.setWidth(w);
            semanticStyleButton.visible = true;
            semanticStyleButton.active = st.semanticSkin;
            semanticStyleButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            semanticPartButton.setMessage(Text.literal("语义：" + (st.semanticPart != null ? st.semanticPart.name() : "AUTO")));
            semanticPartButton.setPosition(x, y);
            semanticPartButton.setWidth(w);
            semanticPartButton.visible = true;
            semanticPartButton.active = st.semanticSkin;
            semanticPartButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            // 分隔线
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }
        
        // ============ 阶段 4：AI 使用保障 ============
        boolean phase4Collapsed = phaseCollapsed[3];
        boolean isPhase4Active = currentPhase == CapturePhase.AI_GUARANTEE;
        boolean isPhase4Complete = isPhaseComplete(CapturePhase.AI_GUARANTEE);
        
        // 如果阶段3未完成，阶段4应该折叠
        if (!isPhase3Complete) {
            phase4Collapsed = true;
        }
        
        String phase4Title = (phase4Collapsed ? "▶ " : "▼ ") + 
            "④ AI 使用保障" + (isPhase4Complete ? "（已完成 ✓）" : (isPhase4Active ? "（当前步骤 ★）" : "（高级，可折叠）"));
        int phase4TitleColor = isPhase4Active ? 0xFFFFFF00 : (isPhase4Complete ? 0xFF88FF88 : 0xFF888888);
        y = drawWrappedText(ctx, Text.literal(phase4Title), x, y, w, phase4TitleColor);
        y += 2;
        
        if (!phase4Collapsed) {
            // 连接位配置
            y = drawWrappedText(ctx, Text.literal("连接位配置"), x, y, w, 0xFFFFFFFF);
            y += 2;
            
            String so = st.socketOriginLocal != null
                    ? ("原点(local)=" + st.socketOriginLocal.getX() + "," + st.socketOriginLocal.getY() + "," + st.socketOriginLocal.getZ())
                    : "原点(local)=未设置";
            String ss = "尺寸=" + st.socketW + "×" + st.socketH + "×" + st.socketD;
            y = drawWrappedText(ctx, Text.literal("已添加: " + st.socketCount + " 个  " + so + "  " + ss), x, y, w, 0xFFAAAAAA);
            y += 2;

            autoDetectSocketsButton.setPosition(x, y);
            autoDetectSocketsButton.setWidth(w);
            autoDetectSocketsButton.visible = true;
            autoDetectSocketsButton.active = true;
            autoDetectSocketsButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            socketContextButton.setMessage(Text.literal("连接位上下文: " + (st.socketContext != null ? st.socketContext.name() : "WALL")));
            socketContextButton.setPosition(x, y);
            socketContextButton.setWidth(w);
            socketContextButton.visible = true;
            socketContextButton.active = SelectionTool.INSTANCE.hasSelection();
            socketContextButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            // 连接位 ID 输入
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("连接位 ID:"), x, y, 0xFFAAAAAA);
            int inputY = y + LABEL_OFFSET - 2;
            socketIdInput.render(ctx, x, inputY, w, 14);
            socketIdInputX = x; socketIdInputY = inputY; socketIdInputW = w; socketIdInputH = 14;
            socketIdInputBoundsValid = true;
            String sid = socketIdInput.getText();
            if (sid != null && !sid.isBlank()) {
                st.socketIdDraft = sid.trim();
            }
            y += FIELD_SPACING;

            int half = (w - 4) / 2;
            socketPickOriginButton.setPosition(x, y);
            socketPickOriginButton.setWidth(half);
            socketPickOriginButton.visible = true;
            socketPickOriginButton.active = SelectionTool.INSTANCE.hasSelection() && st.anchorWorld != null;
            socketPickOriginButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            socketFacingButton.setMessage(Text.literal("朝向: " + (st.socketFacing != null ? st.socketFacing.name() : "SOUTH")));
            socketFacingButton.setPosition(x + half + 4, y);
            socketFacingButton.setWidth(w - half - 4);
            socketFacingButton.visible = true;
            socketFacingButton.active = true;
            socketFacingButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            socketAddButton.setPosition(x, y);
            socketAddButton.setWidth(half);
            socketAddButton.visible = true;
            socketAddButton.active = SelectionTool.INSTANCE.hasSelection() && st.socketOriginLocal != null;
            socketAddButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

            socketPreviewButton.setPosition(x + half + 4, y);
            socketPreviewButton.setWidth(w - half - 4);
            socketPreviewButton.visible = true;
            socketPreviewButton.active = st.anchorWorld != null && st.socketOriginLocal != null;
            socketPreviewButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            socketClearButton.setPosition(x, y);
            socketClearButton.setWidth(w);
            socketClearButton.visible = true;
            socketClearButton.active = st.socketCount > 0;
            socketClearButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;

            // 分隔线
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            
            // 智能分析
            y = drawWrappedText(ctx, Text.literal("🧠 智能分析"), x, y, w, 0xFFFFFFFF);
            y += 2;
            
            autoAnalyzeButton.setPosition(x, y);
            autoAnalyzeButton.setWidth(w);
            autoAnalyzeButton.visible = true;
            autoAnalyzeButton.active = st.anchorWorld != null;
            autoAnalyzeButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
            y += LABEL_OFFSET;
            
            // 分隔线
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }
        
        // ============ 构件健康状态检查（新设计：健康条 + 抽屉）============
        if (isPhase2Complete) {
            // 实时检查（防抖）
            long now = System.currentTimeMillis();
            if (now - lastHealthCheckTime > HEALTH_CHECK_DEBOUNCE_MS) {
                // 触发检查（不显示，只更新状态）
                lastHealthCheckTime = now;
            }
            
            var healthResult = checkComponentHealth();
            var items = healthResult.getItems();
            
            // 统计
            int okCount = 0, warnCount = 0, errorCount = 0;
            for (var item : items) {
                switch (item.level) {
                    case OK: okCount++; break;
                    case WARN: warnCount++; break;
                    case ERROR: errorCount++; break;
                }
            }
            
            // A. 1行摘要（永远可见，放在底部按钮上方）
            y += 4;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
            
            // 构建摘要文本
            String summaryText = "健康状态：";
            if (okCount > 0) summaryText += "✅ " + okCount + "  ";
            if (warnCount > 0) summaryText += "⚠ " + warnCount + "  ";
            if (errorCount > 0) summaryText += "⛔ " + errorCount + "  ";
            if (okCount == 0 && warnCount == 0 && errorCount == 0) {
                summaryText += "✅ 全部通过";
            }
            summaryText += "  （点击查看）";
            
            // 摘要行颜色（有ERROR变红）
            int summaryColor = errorCount > 0 ? 0xFFFF5555 : (warnCount > 0 ? 0xFFFFAA00 : 0xFF55FF55);
            
            // 可点击的摘要行（点击展开/折叠抽屉）
            // 记录摘要行的Y坐标范围，用于点击检测
            int summaryStartY = y;
            y = drawWrappedText(ctx, Text.literal(summaryText), x, y, w, summaryColor);
            int summaryEndY = y;
            
            // 存储摘要行位置（用于mouseClicked检测）
            healthSummaryStartY = summaryStartY + scrollY;
            healthSummaryEndY = summaryEndY + scrollY;
            
            // 如果有ERROR，摘要行轻量抖动提示（这里用颜色闪烁代替）
            if (errorCount > 0) {
                // 在摘要行下方画一条红色提示线
                ctx.fill(x, y, x + w, y + 1, 0x44FF5555);
                y += 2;
            }
            
            // B. Chips（折叠态显示最重要的1-3条）
            if (!healthDrawerExpanded) {
                // 只显示ERROR和最重要的WARN（最多3条）
                int chipCount = 0;
                for (var item : items) {
                    if (chipCount >= 3) break;
                    if (item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR ||
                        (item.level == com.formacraft.common.component.health.HealthCheckResult.Level.WARN && chipCount < 2)) {
                        String chipIcon = item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR ? "⛔" : "⚠";
                        int chipColor = item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR ? 0xFFFF5555 : 0xFFFFAA00;
                        String chipText = chipIcon + " " + item.title;
                        y = drawWrappedText(ctx, Text.literal(chipText), x, y, w, chipColor);
                        chipCount++;
                    }
                }
            } else {
                // C. Detail 抽屉（展开态的可操作清单）
                y += 2;
                
                // 快捷动作区
                if (healthResult.hasAutoFixable() || errorCount > 0) {
                    int buttonW = (w - 4) / 3;
                    if (healthResult.hasAutoFixable()) {
                        autoFixButton.setPosition(x, y);
                        autoFixButton.setWidth(buttonW);
                        autoFixButton.visible = true;
                        autoFixButton.active = true;
                        autoFixButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
                    }
                    y += LABEL_OFFSET;
                }
                
                // 详细列表
                for (var item : items) {
                    // 跳过OK项（只显示问题和警告）
                    if (item.level == com.formacraft.common.component.health.HealthCheckResult.Level.OK) {
                        continue;
                    }
                    
                    // 图标和颜色（按建议规范）
                    String icon;
                    int color = switch (item.level) {
                        case WARN -> {
                            icon = "⚠";
                            yield 0xFFFFAA00;
                        }
                        case ERROR -> {
                            icon = "⛔";
                            yield 0xFFFF5555;
                        }
                        default -> {
                            icon = "ℹ";
                            yield 0xFF55FFFF;
                        }
                    };

                    // 自动修复标记
                    if (item.fixAction == com.formacraft.common.component.health.HealthCheckResult.FixAction.AUTO) {
                        icon += "✨";
                    }
                    
                    // 需要世界交互标记
                    if (item.ruleId.startsWith("H2-") && item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR) {
                        icon += "🎯";
                    }
                    
                    // 标题行（可点击跳转）
                    y = drawWrappedText(ctx, Text.literal("[" + icon + "] " + item.title), x, y, w, color);
                    
                    // 影响说明
                    if (!item.impact.isEmpty()) {
                        y = drawWrappedText(ctx, Text.literal("  影响：" + item.impact), x, y, w, 0xFFFFAA00);
                    }
                    
                    // 建议说明
                    if (!item.fixSuggestion.isEmpty()) {
                        String suggestionIcon = item.fixAction == com.formacraft.common.component.health.HealthCheckResult.FixAction.AUTO 
                            ? "🤖" : "💡";
                        y = drawWrappedText(ctx, Text.literal("  建议：" + suggestionIcon + " " + item.fixSuggestion), x, y, w, 0xFF88CCFF);
                    }
                    
                    y += 2; // 项之间间距
                }
            }
            
            y += 4;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }
        
        // ============ AI 视角解释区 ============
        if (isPhase3Complete) {
            y = drawWrappedText(ctx, Text.literal("🤖 AI 将如何理解这个构件："), x, y, w, 0xFF88CCFF);
            y += 2;
            
            var explanations = getAIViewExplanation();
            for (String exp : explanations) {
                y = drawWrappedText(ctx, Text.literal("- " + exp), x, y, w, 0xFFAAAAAA);
            }
            
            y += 4;
            ctx.fill(x, y, x + w, y + 1, 0xFF444444);
            y += 4;
        }
        
        // ============ 底部按钮 ============
        y += 4;
        
        int half = (w - 4) / 2;
        cancelButton.setPosition(x, y);
        cancelButton.setWidth(half);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);

        // Save按钮策略（按建议：ERROR阻断，WARN提示）
        var healthResult = checkComponentHealth();
        boolean hasErrors = healthResult.hasErrors();
        boolean hasWarnings = healthResult.hasWarnings();
        boolean canSaveNow = canSave() && !hasErrors; // ERROR阻断保存
        
        saveButton.setPosition(x + half + 4, y);
        saveButton.setWidth(w - half - 4);
        saveButton.visible = true;
        saveButton.active = canSaveNow;
        
        // 更新按钮文本和Tooltip（根据健康状态）
        long warnCount = healthResult.getItems().stream()
            .filter(item -> item.level == com.formacraft.common.component.health.HealthCheckResult.Level.WARN)
            .count();
        long errorCount = healthResult.getItems().stream()
            .filter(item -> item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR)
            .count();
        
        if (hasErrors) {
            saveButton.setMessage(Text.literal("💾 保存构件（需先解决 " + errorCount + " 个阻断项）"));
            saveButton.setTooltip(Tooltip.of(Text.literal("存在阻断项（⛔），请先修复后才能保存")));
        } else if (hasWarnings && canSaveNow) {
            saveButton.setMessage(Text.literal("💾 保存构件（建议先修复 " + warnCount + " 个风险项）"));
            saveButton.setTooltip(Tooltip.of(Text.literal("构件有 " + warnCount + " 个风险项，建议先修复但可以保存")));
        } else {
            saveButton.setMessage(Text.literal("💾 保存构件"));
            saveButton.setTooltip(Tooltip.of(Text.literal("保存构件到库\n━━━━━━━━━━━━\n将构件保存到全局构件库\n\n保存内容：\n• 方块数据和结构\n• 锚点和朝向\n• 分类和标签\n• 缩略图预览\n• 连接位配置（如果有）\n\n保存后自动跳转到构件库")));
        }
        
        saveButton.render(ctx, getScaledMouseX(), getScaledMouseY(), 0f);
        y += LABEL_OFFSET;

        // 计算最大滚动
        // y 已经减去了 scrollY，所以需要加回来计算总内容高度
        int contentStartY = getContentY() + CONTENT_PADDING;
        int totalContentHeight = (y + scrollY) - contentStartY;
        int visibleHeight = panelHeight - (contentStartY - panelY) - CONTENT_PADDING;
        maxScrollY = Math.max(0, totalContentHeight - visibleHeight);
        
        // 限制当前滚动位置
        if (scrollY > maxScrollY) scrollY = maxScrollY;
        if (scrollY < 0) scrollY = 0;

        ctx.disableScissor();
    }

    private void drawThumbnailPreview(DrawContext ctx, int x, int y) {
        // 绘制边框
        ctx.fill(x - 1, y - 1, x + THUMBNAIL_SIZE + 1, y + THUMBNAIL_SIZE + 1, 0xFF444444);
        ctx.fill(x, y, x + THUMBNAIL_SIZE, y + THUMBNAIL_SIZE, 0xFF1A1A1A);

        // 检查是否需要重新生成缩略图
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();

        // 防止重复触发生成（防抖）
        if (max != null && min != null && 
            (cachedThumbnail == null || !min.equals(lastSelectionMin) || !max.equals(lastSelectionMax))) {
            // 只有在没有正在生成时才触发新的生成
            if (!isGeneratingThumbnail) {
                regenerateThumbnail();
                lastSelectionMin = min;
                lastSelectionMax = max;
            }
        }

        // 绘制缩略图
        if (cachedThumbnail != null) {
            com.formacraft.client.ui.render.ImageRenderer.renderCentered(ctx, cachedThumbnail, x, y, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("生成中..."), 
                x + THUMBNAIL_SIZE / 2 - 20, y + THUMBNAIL_SIZE / 2, 0xFFAAAAAA);
        }
    }

    private void regenerateThumbnail() {
        // 防止重复生成
        if (isGeneratingThumbnail) {
            if (DEBUG_CAPTURE) {
                com.formacraft.FormacraftMod.LOGGER.debug("[缩略图] 已有生成任务在进行，跳过");
            }
            return;
        }
        
        // 清空旧缩略图
        cachedThumbnail = null;
        isGeneratingThumbnail = true;
        
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[缩略图] 开始生成...");
        }
        
        // 异步生成缩略图
        new Thread(() -> {
            try {
                // 检查前置条件
                if (client == null || client.world == null) {
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] client 或 client.world 为 null");
                    }
                    return;
                }
                
                if (!SelectionTool.INSTANCE.hasSelection()) {
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] 没有选区");
                    }
                    return;
                }
                
                BlockPos min = SelectionTool.INSTANCE.getMin();
                BlockPos max = SelectionTool.INSTANCE.getMax();
                if (min == null || max == null) {
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] 选区 min 或 max 为 null");
                    }
                    return;
                }
                
                // 检查锚点
                var st = ComponentTool.INSTANCE.getState();
                if (st.anchorWorld == null) {
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] 锚点未设置！请先设置锚点（右键点击方块）");
                    }
                    return;
                }
                
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[缩略图] 选区: {} -> {}, 锚点: {}", min, max, st.anchorWorld);
                }
                
                String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
                if (json != null) {
                    ComponentDefinition def = JsonUtil.fromJson(json, ComponentDefinition.class);
                    if (def != null) {
                        java.awt.image.BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
                        if (thumb != null) {
                            if (DEBUG_CAPTURE) {
                                com.formacraft.FormacraftMod.LOGGER.debug("[缩略图] 生成成功: {}x{}", thumb.getWidth(), thumb.getHeight());
                            }
                            cachedThumbnail = thumb;
                        } else {
                            if (DEBUG_CAPTURE) {
                                com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] 生成失败: generateThumbnail 返回 null");
                            }
                        }
                    } else {
                        if (DEBUG_CAPTURE) {
                            com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] 无法解析 ComponentDefinition");
                        }
                    }
                } else {
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.warn("[缩略图] buildCurrentComponentJson 返回 null（可能是锚点问题）");
                    }
                }
            } catch (Exception e) {
                com.formacraft.FormacraftMod.LOGGER.error("[缩略图] 生成缩略图时出错", e);
            } finally {
                isGeneratingThumbnail = false;
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[缩略图] 生成任务结束");
                }
            }
        }, "ThumbnailGenerator").start();
    }

    /**
     * 统一判断是否有有效选区（AABB 或显式方块集合）
     */
    private boolean hasValidSelection() {
        // 优先检查显式方块集合（点选模式）
        if (!selectedBlocks.isEmpty()) {
            return true;
        }
        // 回退到 AABB（框选模式）
        return SelectionTool.INSTANCE.hasSelection();
    }

    private int countBlocksInSelection() {
        if (client == null || client.world == null) return 0;
        
        // 优先使用显式方块集合（点选模式）
        if (!selectedBlocks.isEmpty()) {
            int count = 0;
            for (BlockPos pos : selectedBlocks) {
                if (pos != null && !client.world.getBlockState(pos).isAir()) {
                    count++;
                }
            }
            return count;
        }
        
        // 回退到 AABB 扫描（框选模式）
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

    private void cycleCategory() {
        var st = ComponentTool.INSTANCE.getState();
        ComponentCategory[] values = ComponentCategory.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.category) {
                idx = i;
                break;
            }
        }
        st.category = values[(idx + 1) % values.length];
        
        // 分类驱动：根据分类自动设置附着模式和方向性
        applyCategoryDefaults(st.category);
    }
    
    /**
     * 根据分类自动设置附着模式和方向性
     */
    private void applyCategoryDefaults(ComponentCategory category) {
        switch (category) {
            case DOOR, WINDOW:
                attachmentMode = com.formacraft.common.component.placement.AttachmentType.WALL_OPENING;
                directionalityMode = DirectionalityMode.INSIDE_OUTSIDE;
                break;
            case COLUMN:
                attachmentMode = com.formacraft.common.component.placement.AttachmentType.FLOOR;
                directionalityMode = DirectionalityMode.NONE;
                break;
            case STAIRS:
                attachmentMode = com.formacraft.common.component.placement.AttachmentType.FLOOR;
                directionalityMode = DirectionalityMode.BOTTOM_TOP;
                break;
            case BRACKET:
            case ORNAMENT:
            case ARCH:
            case ROOF_DETAIL:
                attachmentMode = com.formacraft.common.component.placement.AttachmentType.WALL_SURFACE;
                directionalityMode = DirectionalityMode.NONE;
                break;
            default:
                // GENERIC 保持当前设置
                break;
        }
        syncPlacementHintsToState();
    }
    
    /**
     * 计算当前应该处于的阶段
     */
    private CapturePhase computeCurrentPhase() {
        var st = ComponentTool.INSTANCE.getState();
        boolean hasSelection = SelectionTool.INSTANCE.hasSelection() || !selectedBlocks.isEmpty();
        boolean hasAnchor = st.anchorWorld != null;
        boolean hasName = st.name != null && !st.name.isBlank() && !st.name.equals("New Component");
        boolean hasCategory = st.category != ComponentCategory.GENERIC;
        
        if (!hasSelection) {
            return CapturePhase.SELECTION;
        }
        if (!hasAnchor) {
            return CapturePhase.ANCHOR_ORIENTATION;
        }
        if (!hasName || !hasCategory) {
            return CapturePhase.SEMANTIC;
        }
        // 阶段4是可选的，但如果有问题会提示
        return CapturePhase.AI_GUARANTEE;
    }
    
    /**
     * 检查阶段是否完成
     */
    private boolean isPhaseComplete(CapturePhase phase) {
        var st = ComponentTool.INSTANCE.getState();
        switch (phase) {
            case SELECTION:
                return SelectionTool.INSTANCE.hasSelection() || !selectedBlocks.isEmpty();
            case ANCHOR_ORIENTATION:
                return st.anchorWorld != null;
            case SEMANTIC:
                boolean hasName = st.name != null && !st.name.isBlank() && !st.name.equals("New Component");
                boolean hasCategory = st.category != ComponentCategory.GENERIC;
                return hasName && hasCategory;
            case AI_GUARANTEE:
                // 阶段4是可选的，总是返回true
                return true;
            default:
                return false;
        }
    }

    private void cycleFacing() {
        var st = ComponentTool.INSTANCE.getState();
        st.facing = st.facing.rotateYClockwise();
    }

    private void cycleMirror() {
        var st = ComponentTool.INSTANCE.getState();
        var values = com.formacraft.common.component.transform.Mirror.values();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == st.mirror) {
                idx = i;
                break;
            }
        }
        st.mirror = values[(idx + 1) % values.length];
    }

    private void updateTagsFromInput() {
        var st = ComponentTool.INSTANCE.getState();
        String text = tagsInput.getText();
        if (text == null || text.isBlank()) {
            st.tags.clear();
            return;
        }

        st.tags.clear();
        for (String tag : text.split("[,，]")) {
            tag = tag.trim();
            if (!tag.isEmpty()) {
                st.tags.add(tag);
            }
        }
    }

    private void runAutoAnalysis() {
        HudToast.show("正在智能分析构件特征...");
        // TODO: 调用智能分析逻辑
        // ComponentTool.INSTANCE.runAutoAnalysis();
        HudToast.show("智能分析完成！（功能待实现）");
    }

    private void autoDetectSockets() {
        HudToast.show("正在自动检测连接位...");
        // TODO: 调用自动检测逻辑
        // ComponentTool.INSTANCE.autoDetectSockets();
        HudToast.show("连接位自动检测完成！（功能待实现）");
    }
    
    /**
     * 执行自动修复
     */
    private void runAutoFix() {
        if (client == null || client.world == null) {
            HudToast.show("无法修复：世界未加载", true);
            return;
        }
        
        // 构建当前构件定义
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
        if (json == null || json.isBlank()) {
            HudToast.show("无法修复：请先选择构件方块", true);
            return;
        }
        
        try {
            // 解析构件定义
            var def = com.formacraft.common.json.JsonUtil.fromJson(json, com.formacraft.common.component.ComponentDefinition.class);
            if (def == null) {
                HudToast.show("无法修复：构件定义无效", true);
                return;
            }
            
            // 执行健康检查
            var healthResult = com.formacraft.common.component.health.ComponentHealthChecker.check(def);
            
            // 执行自动修复
            var fixReport = com.formacraft.common.component.health.ComponentHealthAutoFix.apply(def, healthResult);
            
            if (fixReport.isEmpty()) {
                HudToast.show("没有可自动修复的问题");
                return;
            }
            
            // 应用修复到 ComponentToolState
            applyFixToState(def, fixReport);
            
            // 显示修复结果
            String message = "已自动修复 " + fixReport.size() + " 个问题";
            for (String fix : fixReport.getFixes()) {
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[自动修复] {}", fix);
                }
            }
            HudToast.show("完成: " + message);
            
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.error("[ComponentCapturePanel] 自动修复失败", t);
            HudToast.show("自动修复失败: " + t.getMessage(), true);
        }
    }
    
    /**
     * 将修复后的 ComponentDefinition 应用到 ComponentToolState
     */
    private void applyFixToState(com.formacraft.common.component.ComponentDefinition def, 
                                 com.formacraft.common.component.health.ComponentHealthAutoFix.FixReport fixReport) {
        var st = ComponentTool.INSTANCE.getState();
        
        // 应用锚点修复（H2-1, H2-2）
        if (def.anchor != null && st.anchorWorld != null) {
            // 计算修复后的锚点世界坐标
            // 锚点在 ComponentDefinition 中是相对坐标，需要转换为世界坐标
            net.minecraft.util.math.BlockPos min = SelectionTool.INSTANCE.getMin();
            if (min != null) {
                // 锚点相对坐标 + 选区最小点 = 世界坐标
                int worldX = min.getX() + def.anchor.dx;
                int worldY = min.getY() + def.anchor.dy;
                int worldZ = min.getZ() + def.anchor.dz;
                st.anchorWorld = new net.minecraft.util.math.BlockPos(worldX, worldY, worldZ);
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[自动修复] 更新锚点: {}", st.anchorWorld);
                }
            }
        } else if (def.anchor != null) {
            // 如果之前没有锚点，现在创建了
            net.minecraft.util.math.BlockPos min = SelectionTool.INSTANCE.getMin();
            if (min != null) {
                int worldX = min.getX() + def.anchor.dx;
                int worldY = min.getY() + def.anchor.dy;
                int worldZ = min.getZ() + def.anchor.dz;
                st.anchorWorld = new net.minecraft.util.math.BlockPos(worldX, worldY, worldZ);
                if (DEBUG_CAPTURE) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[自动修复] 创建锚点: {}", st.anchorWorld);
                }
            }
        }
        
        // 其他修复可以在这里添加
    }

    private boolean canSave() {
        var st = ComponentTool.INSTANCE.getState();
        // 检查基本条件
        if (!hasValidSelection()) {
            return false;
        }
        if (st.name == null || st.name.isBlank()) {
            return false;
        }
        // 检查锚点（buildCurrentComponentJson 需要锚点）
        if (st.anchorWorld == null) {
            return false;
        }
        // 检查锚点是否在有效选区内（或允许外侧锚点）
        BlockPos anchor = st.anchorWorld;
        return isAnchorLocationAllowed(anchor);
    }

    private void saveComponent() {
        if (!canSave()) {
            HudToast.show("保存失败：请检查选区和名称", true);
            return;
        }

        var st = ComponentTool.INSTANCE.getState();
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
        if (json == null || json.isBlank()) {
            HudToast.show("保存失败：请检查选区", true);
            return;
        }
        
        // 解析、健康检查和自动修复（保存前）
        ComponentDefinition def = null;
        try {
            def = JsonUtil.fromJson(json, ComponentDefinition.class);
            if (def != null) {
                // 使用新的健康检查系统
                var healthResult = com.formacraft.common.component.health.ComponentHealthChecker.check(def);
                
                // 如果有 ERROR，阻止保存
                if (healthResult.hasErrors()) {
                    int errorCount = healthResult.getItems().stream()
                        .filter(item -> item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR)
                        .mapToInt(item -> 1).sum();
                    HudToast.show("保存失败：存在 " + errorCount + " 个阻断项，请先修复", true);
                    return;
                }
                
                // 执行自动修复（基于健康检查结果）
                var fixReport = com.formacraft.common.component.health.ComponentHealthAutoFix.apply(def, healthResult);
                if (!fixReport.isEmpty()) {
                    com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 保存前自动修复: {} 项", fixReport.size());
                    for (var fix : fixReport.getFixes()) {
                        com.formacraft.FormacraftMod.LOGGER.debug("  {}", fix);
                    }
                    // 重新生成 JSON（修复后）
                    json = JsonUtil.toJson(def);
                    // 重新检查健康状态（修复后）
                    healthResult = com.formacraft.common.component.health.ComponentHealthChecker.check(def);
                }
                
                // 如果有 WARN，提示但不阻止
                if (healthResult.hasWarnings()) {
                    int warnCount = healthResult.getItems().stream()
                        .filter(item -> item.level == com.formacraft.common.component.health.HealthCheckResult.Level.WARN)
                        .mapToInt(item -> 1).sum();
                    HudToast.show("警告：构件有 " + warnCount + " 个风险项，但仍将保存");
                }
                
                // 作为补充，运行结构性验证（ComponentValidator）
                var validationResult = com.formacraft.common.component.validate.ComponentValidator.validate(def);
                if (validationResult.hasErrors()) {
                    com.formacraft.FormacraftMod.LOGGER.warn("[ComponentCapturePanel] 保存前结构性验证发现错误: {}", validationResult.errors().size());
                }
            }
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.error("[ComponentCapturePanel] 保存前健康检查/修复失败", t);
            // 继续保存，不阻止（容错）
        }

        // 生成缩略图（使用修复后的 def，如果 def 为 null 则重新解析）
        byte[] thumbnailPng = null;
        try {
            if (def == null) {
                def = JsonUtil.fromJson(json, ComponentDefinition.class);
            }
            if (def != null) {
                BufferedImage thumb = ComponentThumbnailGenerator.generateThumbnail(def);
                if (thumb != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(thumb, "PNG", baos);
                    thumbnailPng = baos.toByteArray();
                }
            }
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to generate thumbnail", t);
        }

        // 记录新构件 ID，用于跳转后高亮（已移至 ComponentTool.onCatalogUpdatedFromServer）
        String componentName = st.name.trim();
        
        // 标记保存待确认（等待服务端 catalog 更新回调）
        ComponentTool.INSTANCE.markSavePending(componentName);
        HudToast.show("正在保存构件「" + componentName + "」…");
        FormaCraftNetworking.sendSaveComponent(json, thumbnailPng);
        
        // 注意：跳转逻辑已移至 ComponentTool.onCatalogUpdatedFromServer()
        // 当服务端保存成功并推送 catalog 更新时，会自动触发跳转
        // 这样避免了 sleep(500) 的竞态问题
    }

    private String makeId(ComponentCategory cat, String name) {
        String n = (name == null ? "" : name).toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_");
        if (n.isBlank()) n = "component";
        return n;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (!isMouseOver(mouseX, mouseY)) return false;

        ensureWidgets();
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        // 健康检查摘要行点击（展开/折叠抽屉）
        if (healthSummaryStartY > 0 && healthSummaryEndY > 0) {
            int panelY = getPanelY();
            int actualSummaryStartY = panelY + healthSummaryStartY - scrollY;
            int actualSummaryEndY = panelY + healthSummaryEndY - scrollY;
            if (mouseY >= actualSummaryStartY && mouseY <= actualSummaryEndY) {
                healthDrawerExpanded = !healthDrawerExpanded;
                return true;
            }
        }
        
        // 选择工具按钮点击
        if (boxSelectButton != null && boxSelectButton.visible && boxSelectButton.mouseClicked(click, false)) return true;
        if (pointSelectButton != null && pointSelectButton.visible && pointSelectButton.mouseClicked(click, false)) return true;
        if (clearSelectionButton != null && clearSelectionButton.visible && clearSelectionButton.mouseClicked(click, false)) return true;
        
        // Phase 3: 语义配置按钮点击
        if (attachmentModeButton != null && attachmentModeButton.visible && attachmentModeButton.mouseClicked(click, false)) return true;
        if (directionalityButton != null && directionalityButton.visible && directionalityButton.mouseClicked(click, false)) return true;
        if (setInsideButton != null && setInsideButton.visible && setInsideButton.mouseClicked(click, false)) return true;
        if (setOutsideButton != null && setOutsideButton.visible && setOutsideButton.mouseClicked(click, false)) return true;
        if (setBottomButton != null && setBottomButton.visible && setBottomButton.mouseClicked(click, false)) return true;
        if (setTopButton != null && setTopButton.visible && setTopButton.mouseClicked(click, false)) return true;
        
        // 按钮点击
        if (categoryButton != null && categoryButton.visible && categoryButton.mouseClicked(click, false)) return true;
        if (pickAnchorButton != null && pickAnchorButton.visible && pickAnchorButton.mouseClicked(click, false)) return true;
        if (clearAnchorButton != null && clearAnchorButton.visible && clearAnchorButton.mouseClicked(click, false)) return true;
        if (hostFaceButton != null && hostFaceButton.visible && hostFaceButton.mouseClicked(click, false)) return true;
        if (anchorOutsideButton != null && anchorOutsideButton.visible && anchorOutsideButton.mouseClicked(click, false)) return true;
        if (autoAnchorButton != null && autoAnchorButton.visible && autoAnchorButton.mouseClicked(click, false)) return true;
        if (facingButton != null && facingButton.visible && facingButton.mouseClicked(click, false)) return true;
        if (mirrorButton != null && mirrorButton.visible && mirrorButton.mouseClicked(click, false)) return true;
        
        if (semanticSkinButton != null && semanticSkinButton.visible && semanticSkinButton.mouseClicked(click, false)) return true;
        if (semanticTagOnSaveButton != null && semanticTagOnSaveButton.visible && semanticTagOnSaveButton.mouseClicked(click, false)) return true;
        if (semanticStyleButton != null && semanticStyleButton.visible && semanticStyleButton.mouseClicked(click, false)) return true;
        if (semanticPartButton != null && semanticPartButton.visible && semanticPartButton.mouseClicked(click, false)) return true;
        
        if (socketContextButton != null && socketContextButton.visible && socketContextButton.mouseClicked(click, false)) return true;
        if (socketPickOriginButton != null && socketPickOriginButton.visible && socketPickOriginButton.mouseClicked(click, false)) return true;
        if (socketFacingButton != null && socketFacingButton.visible && socketFacingButton.mouseClicked(click, false)) return true;
        if (socketAddButton != null && socketAddButton.visible && socketAddButton.mouseClicked(click, false)) return true;
        if (socketPreviewButton != null && socketPreviewButton.visible && socketPreviewButton.mouseClicked(click, false)) return true;
        if (socketClearButton != null && socketClearButton.visible && socketClearButton.mouseClicked(click, false)) return true;
        
        if (autoAnalyzeButton != null && autoAnalyzeButton.visible && autoAnalyzeButton.mouseClicked(click, false)) return true;
        if (autoDetectSocketsButton != null && autoDetectSocketsButton.visible && autoDetectSocketsButton.mouseClicked(click, false)) return true;
        if (autoFixButton != null && autoFixButton.visible && autoFixButton.mouseClicked(click, false)) return true;
        
        if (cancelButton != null && cancelButton.visible && cancelButton.mouseClicked(click, false)) return true;
        if (saveButton != null && saveButton.visible && saveButton.mouseClicked(click, false)) return true;

        // 输入框点击
        if (nameInputBoundsValid) {
            if (nameInput.mouseClicked(mouseX, mouseY, nameInputX, nameInputY, nameInputW, nameInputH)) {
                tagsInput.setFocused(false);
                socketIdInput.setFocused(false);
                return true;
            }
        }

        if (tagsInputBoundsValid) {
            if (tagsInput.mouseClicked(mouseX, mouseY, tagsInputX, tagsInputY, tagsInputW, tagsInputH)) {
                nameInput.setFocused(false);
                socketIdInput.setFocused(false);
                return true;
            }
        }

        if (socketIdInputBoundsValid) {
            if (socketIdInput.mouseClicked(mouseX, mouseY, socketIdInputX, socketIdInputY, socketIdInputW, socketIdInputH)) {
                nameInput.setFocused(false);
                tagsInput.setFocused(false);
                return true;
            }
        }

        return true; // 消费所有面板内点击
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isMouseOver(mouseX, mouseY)) return;
        
        // 滚动步长：每次滚轮滚动 12 像素（与 ToolPanel 和 SettingsPanel 一致）
        int step = 12;
        scrollY = (int) Math.round(scrollY - amount * step);
        
        // 限制滚动范围
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureWidgets();
        nameInput.keyPressed(keyCode, modifiers);
        tagsInput.keyPressed(keyCode, modifiers);
        socketIdInput.keyPressed(keyCode, modifiers);
    }

    @Override
    public void charTyped(char chr) {
        ensureWidgets();
        nameInput.charTyped(chr);
        tagsInput.charTyped(chr);
        socketIdInput.charTyped(chr);
    }

    @Override
    public boolean wantsKeyboardInput() {
        return nameInput.isFocused() || tagsInput.isFocused() || socketIdInput.isFocused();
    }

    private int drawWrappedText(DrawContext ctx, Text text, int x, int y, int maxWidth, int color) {
        if (text == null) return y;
        int lineHeight = client.textRenderer.fontHeight;
        for (var line : client.textRenderer.wrapLines(text, maxWidth)) {
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, color);
            y += lineHeight;
        }
        return y;
    }

    private int getScaledMouseX() {
        return (int) (client.mouse.getX() / client.getWindow().getScaleFactor());
    }

    private int getScaledMouseY() {
        return (int) (client.mouse.getY() / client.getWindow().getScaleFactor());
    }
    
    /**
     * 检查构件健康状态（使用新的健康检查系统）
     * 包括 ComponentDefinition 层面的检查和 UI 层面的检查（如方向标记）
     */
    private com.formacraft.common.component.health.HealthCheckResult checkComponentHealth() {
        // 尝试构建 ComponentDefinition 进行检查
        String json = ComponentTool.INSTANCE.buildCurrentComponentJson(client);
        var result = new com.formacraft.common.component.health.HealthCheckResult();
        
        if (json == null || json.isBlank()) {
            // 如果无法构建，返回基础检查结果
            var st = ComponentTool.INSTANCE.getState();
            boolean hasSelection = SelectionTool.INSTANCE.hasSelection() || !selectedBlocks.isEmpty();
            if (!hasSelection) {
                result.add(com.formacraft.common.component.health.HealthCheckResult.CheckItem.error(
                    "H1-1", "未选择有效方块", "请先选择构件方块", "无法保存",
                    com.formacraft.common.component.health.HealthCheckResult.FixAction.NONE, ""));
            }
            if (st.anchorWorld == null) {
                result.add(com.formacraft.common.component.health.HealthCheckResult.CheckItem.error(
                    "H2-1", "未设置构件锚点", "请右键点击方块设置锚点", "构件无法稳定放置",
                    com.formacraft.common.component.health.HealthCheckResult.FixAction.AUTO, "自动推荐锚点（底部中心）"));
            }
            return result;
        }
        
        try {
            var def = com.formacraft.common.json.JsonUtil.fromJson(json, com.formacraft.common.component.ComponentDefinition.class);
            if (def != null) {
                // ComponentDefinition 层面的检查
                result = com.formacraft.common.component.health.ComponentHealthChecker.check(def);
                
                // UI 层面的额外检查：方向标记（H2-3 增强版）
                var st = ComponentTool.INSTANCE.getState();
                ComponentCategory cat = st.category != null ? st.category : ComponentCategory.GENERIC;
                
                // 检查门/窗是否需要内外标记
                if ((cat == ComponentCategory.DOOR || cat == ComponentCategory.WINDOW) && 
                    directionalityMode == DirectionalityMode.INSIDE_OUTSIDE) {
                    if (insideMark == null || outsideMark == null) {
                        // 移除原有的 H2-3 OK 结果（如果有）
                        result.getItems().removeIf(item -> "H2-3".equals(item.ruleId) && 
                            item.level == com.formacraft.common.component.health.HealthCheckResult.Level.OK);
                        // 添加 UI 层面的警告
                        result.add(com.formacraft.common.component.health.HealthCheckResult.CheckItem.warn(
                            "H2-3", "需要设置内外方向标记",
                            (cat == ComponentCategory.DOOR ? "门" : "窗") + "需要标记内侧和外侧位置",
                            "AI 可能反向放置（非常常见错误）",
                            com.formacraft.common.component.health.HealthCheckResult.FixAction.SUGGEST,
                            "点击「标记内侧」和「标记外侧」按钮在世界中标记"));
                    }
                }

                // 检查宿主面（墙面类构件建议设置）
                if ((attachmentMode == com.formacraft.common.component.placement.AttachmentType.WALL_OPENING
                        || attachmentMode == com.formacraft.common.component.placement.AttachmentType.WALL_SURFACE)
                        && st.hostFaceNormal == null) {
                    result.add(com.formacraft.common.component.health.HealthCheckResult.CheckItem.warn(
                            "H2-4", "建议设置宿主面",
                            "墙面类构件建议选择外墙表面作为参考面",
                            "可能导致内外方向不稳定",
                            com.formacraft.common.component.health.HealthCheckResult.FixAction.SUGGEST,
                            "点击「选择宿主面」并在世界中选一个外墙面"));
                }
                
                // 检查楼梯是否需要上下标记
                if (cat == ComponentCategory.STAIRS && 
                    directionalityMode == DirectionalityMode.BOTTOM_TOP) {
                    if (bottomMark == null || topMark == null) {
                        // 移除原有的 H2-3 OK 结果（如果有）
                        result.getItems().removeIf(item -> "H2-3".equals(item.ruleId) && 
                            item.level == com.formacraft.common.component.health.HealthCheckResult.Level.OK);
                        // 添加 UI 层面的警告
                        result.add(com.formacraft.common.component.health.HealthCheckResult.CheckItem.warn(
                            "H2-3", "需要设置上下方向标记",
                            "楼梯需要标记底端和顶端位置",
                            "AI 可能反向放置",
                            com.formacraft.common.component.health.HealthCheckResult.FixAction.SUGGEST,
                            "点击「标记底端」和「标记顶端」按钮在世界中标记"));
                    }
                }
            }
        } catch (Throwable t) {
            com.formacraft.FormacraftMod.LOGGER.error("[ComponentCapturePanel] 健康检查失败", t);
        }
        
        return result;
    }
    
    /**
     * 获取AI视角解释
     */
    private java.util.List<String> getAIViewExplanation() {
        var explanations = new java.util.ArrayList<String>();
        var st = ComponentTool.INSTANCE.getState();
        
        // 类型
        String categoryName = switch (st.category) {
            case DOOR -> "门（Door）";
            case WINDOW -> "窗（Window）";
            case COLUMN -> "柱子（Column）";
            case STAIRS -> "楼梯（Stairs）";
            case BRACKET -> "斗拱（Bracket）";
            case ORNAMENT -> "装饰（Ornament）";
            case ARCH -> "拱券（Arch）";
            case ROOF_DETAIL -> "屋顶细节（Roof Detail）";
            default -> "通用构件（Generic）";
        };
        explanations.add("类型：" + categoryName);
        
        // 使用场景
        String usage = switch (attachmentMode) {
            case WALL_OPENING -> "墙体开口";
            case WALL_SURFACE -> "墙面附着";
            case FLOOR -> "地面放置";
            case ROOF_SURFACE -> "屋面附着";
            case ROOF_EDGE -> "屋檐边缘";
            case ROOF_RIDGE -> "屋脊";
            case EDGE -> "边缘放置";
            case CORNER -> "转角放置";
            default -> "独立放置";
        };
        explanations.add("使用场景：" + usage);
        
        // 朝向规则
        if (directionalityMode == DirectionalityMode.INSIDE_OUTSIDE) {
            explanations.add("朝向规则：内 → 外");
        } else if (directionalityMode == DirectionalityMode.BOTTOM_TOP) {
            explanations.add("朝向规则：下 → 上");
        } else if (directionalityMode == DirectionalityMode.BOTH) {
            explanations.add("朝向规则：内 → 外，下 → 上");
        } else {
            explanations.add("朝向规则：任意方向");
        }
        
        // 推荐使用高度
        if (st.category == ComponentCategory.DOOR || st.category == ComponentCategory.WINDOW) {
            explanations.add("推荐使用高度：首层");
        }
        
        // 可重复性
        if (st.category == ComponentCategory.COLUMN || st.category == ComponentCategory.ORNAMENT) {
            explanations.add("可重复：是");
        } else {
            explanations.add("可重复：否");
        }
        
        return explanations;
    }
    
    /**
     * 绘制状态指示器
     */
    private int drawStatusIndicator(DrawContext ctx, int x, int y, int w) {
        var st = ComponentTool.INSTANCE.getState();
        
        // 边框
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;
        
        // 选区状态
        boolean hasSelection = SelectionTool.INSTANCE.hasSelection();
        String selectionText = hasSelection ? "OK 选区已设置" : "WARN 选区未设置";
        int selectionColor = hasSelection ? 0xFF00FF00 : 0xFFFFAA00;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(selectionText), x, y, selectionColor);
        y += client.textRenderer.fontHeight + 2;
        
        // 锚点状态
        boolean hasAnchor = st.anchorWorld != null;
        int anchorColor = hasAnchor ? 0xFF00FF00 : 0xFFFFAA00;
        String anchorText = hasAnchor
                ? String.format("OK 锚点: (%d, %d, %d)", st.anchorWorld.getX(), st.anchorWorld.getY(), st.anchorWorld.getZ())
                : "WARN 锚点: 未设置";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(anchorText), x, y, anchorColor);
        y += client.textRenderer.fontHeight + 2;

        String hostText = (st.hostFaceBlock != null && st.hostFaceNormal != null)
                ? ("INFO 宿主面: " + st.hostFaceNormal.name() + " @ " + st.hostFaceBlock.toShortString())
                : "INFO 宿主面: 未设置";
        int hostColor = st.hostFaceNormal != null ? 0xFF66CCFF : 0xFF888888;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(hostText), x, y, hostColor);
        y += client.textRenderer.fontHeight + 2;

        if (st.allowAnchorOutsideSelection) {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("INFO 外侧锚点: 开启"), x, y, 0xFF88CCFF);
            y += client.textRenderer.fontHeight + 2;
        }
        
        // 朝向状态
        String facingText = "INFO 朝向: " + st.facing.name();
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(facingText), x, y, 0xFF88CCFF);
        y += client.textRenderer.fontHeight + 2;

        // 名称状态
        boolean hasName = st.name != null && !st.name.isEmpty() && !st.name.equals("New Component");
        int nameColor = hasName ? 0xFF00FF00 : 0xFFFFAA00;
        String nameText = hasName ? "OK 名称已填写" : "WARN 名称: 请填写";
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(nameText), x, y, nameColor);
        y += client.textRenderer.fontHeight + 2;
        
        // 健康检查状态（使用新的 HealthCheck 系统）
        var healthResult = checkComponentHealth();
        int okCount = 0, warnCount = 0, errorCount = 0;
        for (var item : healthResult.getItems()) {
            switch (item.level) {
                case OK: okCount++; break;
                case WARN: warnCount++; break;
                case ERROR: errorCount++; break;
            }
        }
        
        // 显示健康状态摘要
        String healthText;
        int healthColor;
        if (errorCount > 0) {
            healthText = String.format("ERR 健康: %d 个阻断项", errorCount);
            healthColor = 0xFFFF5555;
        } else if (warnCount > 0) {
            healthText = String.format("WARN 健康: %d 个风险项", warnCount);
            healthColor = 0xFFFFAA00;
        } else {
            healthText = "OK 健康检查通过";
            healthColor = 0xFF55FF55;
        }
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(healthText), x, y, healthColor);
        y += client.textRenderer.fontHeight + 2;
        
        // 显示最关键的问题（最多2条：优先 ERROR，其次 WARN）
        int shownCount = 0;
        for (var item : healthResult.getItems()) {
            if (shownCount >= 2) break;
            if (item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR || 
                item.level == com.formacraft.common.component.health.HealthCheckResult.Level.WARN) {
                String icon = item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR ? "ERR" : "WARN";
                int color = item.level == com.formacraft.common.component.health.HealthCheckResult.Level.ERROR ? 0xFFFF5555 : 0xFFFFAA00;
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("  " + icon + " " + item.title), x + 6, y, color);
                y += client.textRenderer.fontHeight + 1;
                shownCount++;
            }
        }
        
        ctx.fill(x, y, x + w, y + 1, 0xFF444444);
        y += 2;
        
        return y;
    }
    
    // ============ 世界交互方法 ============
    
    /**
     * 处理世界点击（从 InputRouter 调用）
     * @return true 如果事件被处理
     */
    public boolean handleWorldClick(BlockHitResult hit, int button) {
        if (hit == null) return false;
        BlockPos pos = hit.getBlockPos();
        if (pos == null) return false;
        
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 世界点击: {}, 按钮: {}, 模式: {}, 标记模式: {}", 
                pos, button, selectionMode, markingMode);
        }
        
        // Phase 3: 优先处理方向标记模式
        if (markingMode != DirectionMarkingMode.NONE && button == 0) {
            handleDirectionMarking(hit);
            return true;
        }
        
        // 右键设置锚点
        if (button == 1) {
            setAnchor(pos);
            return true;
        }
        
        // 左键根据模式处理
        if (button == 0) {
            switch (selectionMode) {
                case BOX_SELECT:
                    // 框选模式：直接使用 SelectionTool 进行框选
                    // SelectionTool 会处理拖拽和渲染预览
                    // 注意：SelectionTool.onMouseClick 需要屏幕坐标，但实际它内部会自己计算鼠标射线
                    // 这里传入的坐标会被忽略，SelectionTool 会从 client.mouse 获取实际坐标
                    double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
                    double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
                    SelectionTool.INSTANCE.onMouseClick(mouseX, mouseY, button);
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 框选: 交给 SelectionTool 处理");
                    }
                    return true;
                    
                case POINT_SELECT:
                    // 点选模式：切换方块选择状态
                    boolean isCtrlDown = org.lwjgl.glfw.GLFW.glfwGetKey(
                        client.getWindow().getHandle(), 
                        org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
                    ) == org.lwjgl.glfw.GLFW.GLFW_PRESS || org.lwjgl.glfw.GLFW.glfwGetKey(
                        client.getWindow().getHandle(), 
                        org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL
                    ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    
                    if (isCtrlDown) {
                        // Ctrl+点击：强制加选
                        addBlockToSelection(pos);
                        if (DEBUG_CAPTURE) {
                            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] Ctrl+点击 强制加选: {}", pos);
                        }
                    } else {
                        // 普通点击：切换状态
                        if (selectedBlocks.contains(pos.toImmutable())) {
                            // 已选中 → 减选
                            removeBlockFromSelection(pos);
                            if (DEBUG_CAPTURE) {
                                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 点击减选: {}, 总数: {}", pos, selectedBlocks.size());
                            }
                        } else {
                            // 未选中 → 加选
                            addBlockToSelection(pos);
                            if (DEBUG_CAPTURE) {
                                com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 点击加选: {}, 总数: {}", pos, selectedBlocks.size());
                            }
                        }
                    }
                    return true;
            }
        }
        
        return false;
    }
    
    /**
     * 处理世界拖拽（从 tick 调用）
     * 框选模式下由 SelectionTool 自动处理
     */
    public void handleWorldDrag(net.minecraft.util.math.BlockPos currentPos) {
        // 框选模式：SelectionTool 自动更新
        // 点选模式：无需拖拽处理
    }
    
    /**
     * 处理鼠标释放（从 InputRouter 调用）
     */
    public void handleWorldRelease(int button) {
        if (button == 0 && selectionMode == ComponentSelectionMode.BOX_SELECT) {
            // 框选模式完成：从 SelectionTool 同步选区
            if (SelectionTool.INSTANCE.hasSelection()) {
                net.minecraft.util.math.BlockPos min = SelectionTool.INSTANCE.getMin();
                net.minecraft.util.math.BlockPos max = SelectionTool.INSTANCE.getMax();
                if (min != null && max != null) {
                    setBoxSelection(min, max);
                    if (DEBUG_CAPTURE) {
                        com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 从 SelectionTool 同步选区: {} -> {}, 方块数: {}", min, max, selectedBlocks.size());
                    }
                }
            }
        }
    }
    
    /**
     * 添加单个方块到选区
     */
    private void addBlockToSelection(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        selectedBlocks.add(pos.toImmutable());
        updateSelectionToolFromBlocks();
    }
    
    /**
     * 从选区移除单个方块
     */
    private void removeBlockFromSelection(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        selectedBlocks.remove(pos.toImmutable());
        updateSelectionToolFromBlocks();
    }
    
    /**
     * 设置框选区域
     */
    private void setBoxSelection(net.minecraft.util.math.BlockPos start, net.minecraft.util.math.BlockPos end) {
        if (start == null || end == null) return;
        
        var st = ComponentTool.INSTANCE.getState();
        
        // 清空现有选区
        selectedBlocks.clear();
        st.explicitSelectedBlocks = null; // 框选模式下不使用显式集合
        
        // 计算边界
        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());
        
        // 框选模式下，selectedBlocks 仅用于显示，实际导出时使用 AABB
        // 但为了保持兼容，我们仍然填充 selectedBlocks（用于 countBlocksInSelection）
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    selectedBlocks.add(new net.minecraft.util.math.BlockPos(x, y, z));
                }
            }
        }
        
        // 同步到 SelectionTool（保持兼容）
        SelectionTool.INSTANCE.setSelection(
            new net.minecraft.util.math.BlockPos(minX, minY, minZ),
            new net.minecraft.util.math.BlockPos(maxX, maxY, maxZ)
        );
    }
    
    /**
     * 更新 SelectionTool 以匹配 selectedBlocks，并同步到 ComponentToolState
     */
    private void updateSelectionToolFromBlocks() {
        var st = ComponentTool.INSTANCE.getState();
        
        if (selectedBlocks.isEmpty()) {
            SelectionTool.INSTANCE.clearSelection();
            st.explicitSelectedBlocks = null; // 清空显式方块集合
            return;
        }
        
        // 计算边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (net.minecraft.util.math.BlockPos pos : selectedBlocks) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        
        // 更新 SelectionTool（用于显示包围盒）
        SelectionTool.INSTANCE.setSelection(
            new net.minecraft.util.math.BlockPos(minX, minY, minZ),
            new net.minecraft.util.math.BlockPos(maxX, maxY, maxZ)
        );
        
        // 同步到 ComponentToolState（用于 buildCurrentComponentJson）
        // 点选模式：使用显式集合；框选模式：清空显式集合（使用 AABB）
        if (selectionMode == ComponentSelectionMode.POINT_SELECT) {
            st.explicitSelectedBlocks = new java.util.HashSet<>(selectedBlocks);
        } else {
            st.explicitSelectedBlocks = null; // 框选模式使用 AABB
        }
    }
    
    /**
     * 设置锚点
     */
    private void setAnchor(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return;
        
        var st = ComponentTool.INSTANCE.getState();
        if (!isAnchorLocationAllowed(pos)) {
            HudToast.show("锚点需落在选区内或紧邻选区", true);
            return;
        }
        st.anchorWorld = pos.toImmutable();
        st.pickingAnchor = false;
        
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 设置锚点: {}", pos);
        }
        com.formacraft.client.ui.toast.HudToast.show("锚点已设置: " + pos.toShortString());
    }
    
    private void setHostFace(BlockHitResult hit) {
        if (hit == null) return;
        var st = ComponentTool.INSTANCE.getState();
        BlockPos base = hit.getBlockPos().toImmutable();
        Direction normal = hit.getSide();

        st.hostFaceBlock = base;
        st.hostFaceNormal = normal;

        BlockPos anchor = st.allowAnchorOutsideSelection ? base.offset(normal) : base;
        if (!isAnchorLocationAllowed(anchor)) {
            HudToast.show("宿主面已记录，但锚点不在选区附近", true);
        } else {
            st.anchorWorld = anchor;
        }
        st.facing = normal;
        com.formacraft.client.ui.toast.HudToast.show("已设置宿主面: " + normal.name());
    }

    private void setAutoAnchor() {
        if (!hasValidSelection()) {
            HudToast.show("请先选择构件方块", true);
            return;
        }
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) {
            HudToast.show("选区无效，无法自动设置锚点", true);
            return;
        }
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        BlockPos anchor = new BlockPos(cx, min.getY(), cz);
        setAnchor(anchor);
    }

    private void applyFacingFromMarks() {
        var st = ComponentTool.INSTANCE.getState();
        Direction derived = deriveHorizontalFacing(insideMark, outsideMark);
        if (derived != null) {
            st.facing = derived;
        }
    }

    private Direction deriveHorizontalFacing(BlockPos inside, BlockPos outside) {
        if (inside == null || outside == null) return null;
        int dx = outside.getX() - inside.getX();
        int dz = outside.getZ() - inside.getZ();
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isAnchorLocationAllowed(BlockPos pos) {
        if (pos == null) return false;
        if (!selectedBlocks.isEmpty()) {
            if (selectedBlocks.contains(pos)) return true;
            if (!ComponentTool.INSTANCE.getState().allowAnchorOutsideSelection) return false;
            return isAnchorAdjacentToSelection(pos);
        }
        if (!SelectionTool.INSTANCE.hasSelection()) return false;
        BlockPos min = SelectionTool.INSTANCE.getMin();
        BlockPos max = SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return false;
        boolean inside = pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        if (inside) return true;
        if (!ComponentTool.INSTANCE.getState().allowAnchorOutsideSelection) return false;
        return pos.getX() >= (min.getX() - 1) && pos.getX() <= (max.getX() + 1)
                && pos.getY() >= (min.getY() - 1) && pos.getY() <= (max.getY() + 1)
                && pos.getZ() >= (min.getZ() - 1) && pos.getZ() <= (max.getZ() + 1);
    }

    private boolean isAnchorAdjacentToSelection(BlockPos pos) {
        if (pos == null || selectedBlocks.isEmpty()) return false;
        for (Direction d : Direction.values()) {
            if (selectedBlocks.contains(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清除选区
     */
    public void clearSelection() {
        selectedBlocks.clear();
        isDragging = false;
        SelectionTool.INSTANCE.clearSelection();
        if (DEBUG_CAPTURE) {
            com.formacraft.FormacraftMod.LOGGER.debug("[ComponentCapturePanel] 清除选区");
        }
    }
    
    /**
     * 是否正在选择
     */
    public boolean isSelecting() {
        return isDragging || selectionMode != ComponentSelectionMode.BOX_SELECT;
    }
    
    /**
     * Tick 方法 - 更新 SelectionTool
     */
    public void tick() {
        // 框选模式：让 SelectionTool 处理拖拽
        if (selectionMode == ComponentSelectionMode.BOX_SELECT && SelectionTool.INSTANCE.isSelecting()) {
            SelectionTool.INSTANCE.tick();
        }
    }
    
    /**
     * 渲染世界中的选区预览
     * 从 SelectionBoxRenderMixin 调用
     */
    public void renderWorldSelection(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        // 渲染 SelectionTool 的选区（框选和已完成的选区）
        SelectionTool.INSTANCE.renderWorld(ctx);
        
        // 渲染点选模式下的单个方块高亮（性能优化：超过阈值时只渲染采样点）
        if (selectionMode == ComponentSelectionMode.POINT_SELECT && !selectedBlocks.isEmpty()) {
            int blockCount = selectedBlocks.size();
            int renderThreshold = 400; // 超过 400 个方块时启用采样渲染
            int sampleRate = blockCount > renderThreshold ? Math.max(1, blockCount / 200) : 1; // 采样率
            
            int rendered = 0;
            for (net.minecraft.util.math.BlockPos pos : selectedBlocks) {
                if (rendered % sampleRate == 0) {
                    renderBlockHighlight(ctx, pos, 0.0f, 1.0f, 0.0f, 0.3f); // 绿色高亮
                }
                rendered++;
            }
        }
        
        renderHostFace(ctx);

        // Phase 3: 渲染方向标记
        renderDirectionMarkers(ctx);
    }
    
    /**
     * 渲染方向标记（内外、上下）
     * 使用彩色方块高亮来标识不同的方向标记
     */
    private void renderDirectionMarkers(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        // 渲染内侧标记（蓝色）
        if (insideMark != null) {
            renderBlockHighlight(ctx, insideMark, 0.2f, 0.5f, 1.0f, 0.6f); // 蓝色
        }
        
        // 渲染外侧标记（橙色）
        if (outsideMark != null) {
            renderBlockHighlight(ctx, outsideMark, 1.0f, 0.5f, 0.0f, 0.6f); // 橙色
        }
        
        // 渲染底端标记（绿色）
        if (bottomMark != null) {
            renderBlockHighlight(ctx, bottomMark, 0.0f, 1.0f, 0.3f, 0.6f); // 绿色
        }
        
        // 渲染顶端标记（紫色）
        if (topMark != null) {
            renderBlockHighlight(ctx, topMark, 0.8f, 0.2f, 1.0f, 0.6f); // 紫色
        }

        // 内外方向线
        if (insideMark != null && outsideMark != null) {
            renderDirectionLine(ctx, insideMark, outsideMark, 255, 170, 0, 220);
        }

        // 上下方向线
        if (bottomMark != null && topMark != null) {
            renderDirectionLine(ctx, bottomMark, topMark, 120, 220, 120, 220);
        }
        
        // TODO: 方向箭头渲染（需要更复杂的顶点格式处理）
        // 当前彩色方块高亮已经足够清晰地标识不同的方向标记
    }

    /**
     * 渲染宿主面（外墙表面）的高亮
     */
    private void renderHostFace(com.formacraft.client.tool.ToolWorldRenderContext ctx) {
        var st = ComponentTool.INSTANCE.getState();
        if (st.hostFaceBlock == null || st.hostFaceNormal == null) return;

        double x0 = st.hostFaceBlock.getX();
        double y0 = st.hostFaceBlock.getY();
        double z0 = st.hostFaceBlock.getZ();
        double x1 = x0 + 1;
        double y1 = y0 + 1;
        double z1 = z0 + 1;
        double t = 0.02;

        net.minecraft.util.math.Box world = switch (st.hostFaceNormal) {
            case NORTH -> new net.minecraft.util.math.Box(x0, y0, z0 - t, x1, y1, z0 + t);
            case SOUTH -> new net.minecraft.util.math.Box(x0, y0, z1 - t, x1, y1, z1 + t);
            case WEST -> new net.minecraft.util.math.Box(x0 - t, y0, z0, x0 + t, y1, z1);
            case EAST -> new net.minecraft.util.math.Box(x1 - t, y0, z0, x1 + t, y1, z1);
            case UP -> new net.minecraft.util.math.Box(x0, y1 - t, z0, x1, y1 + t, z1);
            case DOWN -> new net.minecraft.util.math.Box(x0, y0 - t, z0, x1, y0 + t, z1);
        };

        net.minecraft.util.math.Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ).expand(0.001);
        net.minecraft.client.render.VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.25f, 0.7f, 1.0f, 0.7f);

        double cx = x0 + 0.5 + st.hostFaceNormal.getOffsetX() * 0.5;
        double cy = y0 + 0.5 + st.hostFaceNormal.getOffsetY() * 0.5;
        double cz = z0 + 0.5 + st.hostFaceNormal.getOffsetZ() * 0.5;
        double ex = cx + st.hostFaceNormal.getOffsetX() * 0.6;
        double ey = cy + st.hostFaceNormal.getOffsetY() * 0.6;
        double ez = cz + st.hostFaceNormal.getOffsetZ() * 0.6;
        ToolRenderUtil.line(ctx, cx, cy, cz, ex, ey, ez, 80, 200, 255, 220);
    }

    private void renderDirectionLine(com.formacraft.client.tool.ToolWorldRenderContext ctx,
                                     BlockPos from, BlockPos to,
                                     int r, int g, int b, int a) {
        if (from == null || to == null) return;
        double x1 = from.getX() + 0.5;
        double y1 = from.getY() + 0.5;
        double z1 = from.getZ() + 0.5;
        double x2 = to.getX() + 0.5;
        double y2 = to.getY() + 0.5;
        double z2 = to.getZ() + 0.5;
        ToolRenderUtil.line(ctx, x1, y1, z1, x2, y2, z2, r, g, b, a);
    }
    
    /**
     * 渲染单个方块的高亮边框
     */
    private void renderBlockHighlight(com.formacraft.client.tool.ToolWorldRenderContext ctx, 
                                      net.minecraft.util.math.BlockPos pos,
                                      float r, float g, float b, float a) {
        net.minecraft.util.math.Box worldBox = new net.minecraft.util.math.Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
        ).expand(0.01);
        
        net.minecraft.util.math.Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        net.minecraft.client.render.VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
    }
}
