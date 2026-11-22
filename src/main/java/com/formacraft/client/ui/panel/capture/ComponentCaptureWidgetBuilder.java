package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.panel.ComponentCapturePanel;
import com.formacraft.client.ui.panel.ComponentSelectionMode;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

public final class ComponentCaptureWidgetBuilder {
    private ComponentCaptureWidgetBuilder() {}
    public static void ensureWidgets(ComponentCapturePanel p) {
        if (p.categoryButton != null) return;

        var st = ComponentTool.INSTANCE.getState();

        // 基础信息按钮
        p.categoryButton = ButtonWidget.builder(Text.literal("更改捕获类型"), b -> { p.typeSection.open(); p.scrollY = 0; })
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        构件分类
                        ━━━━━━━━━━━━
                        返回顶部选择预设或自定义类型
                        
                        分类用于：
                        • AI 智能放置时的语义识别
                        • 构件库的分类浏览
                        • 自动检测附着模式
                        
                        常见分类：门、窗、柱子、装饰等""")))
                .build();

        p.pickAnchorButton = ButtonWidget.builder(Text.literal("📍 点击选择锚点"), b -> ComponentTool.INSTANCE.startPickAnchor())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.clearAnchorButton = ButtonWidget.builder(Text.literal("清除锚点"), b -> ComponentTool.INSTANCE.clearAnchor())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清除锚点
                        ━━━━━━━━━━━━
                        移除当前设置的锚点
                        
                        清除后需要重新设置锚点
                        才能保存构件""")))
                .build();

        p.hostFaceButton = ButtonWidget.builder(Text.literal("选择宿主面"), b -> p.orientationController.startMarkingHostFace())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.anchorOutsideButton = ButtonWidget.builder(Text.literal("外侧锚点：关"), b -> {
                    st.captureDraft.anchor.allowOutsideSelection = !st.captureDraft.anchor.allowOutsideSelection;
                    st.syncDraftToState();
                })
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        允许外侧锚点（空气宿主面）
                        ━━━━━━━━━━━━
                        开启后可将锚点放在选区外侧的空气方块
                        
                        使用场景：
                        ? 完全外凸的窗/装饰
                        ? 需要以外墙面为零面的构件""")))
                .build();

        p.autoAnchorButton = ButtonWidget.builder(Text.literal("自动锚点（底部中心）"), b -> p.setAutoAnchor())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        自动设置锚点
                        ━━━━━━━━━━━━
                        将锚点放置在构件底部中心
                        
                        对偶数宽度构件更友好
                        适合门窗/柱子等构件""")))
                .build();

        p.facingButton = ButtonWidget.builder(Text.literal("朝向：SOUTH"), b -> p.cycleFacing())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.mirrorButton = ButtonWidget.builder(Text.literal("镜像：NONE"), b -> p.cycleMirror())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        镜像模式
                        ━━━━━━━━━━━━
                        控制构件的镜像行为
                        
                        选项：
                        • NONE：不镜像
                        • X：沿 X 轴镜像
                        • Z：沿 Z 轴镜像
                        
                        用于创建对称变体""")))
                .build();

        // 语义标注按钮
        p.semanticSkinButton = ButtonWidget.builder(Text.literal("材质：原样"), b -> ComponentTool.INSTANCE.toggleSemanticSkin())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.semanticTagOnSaveButton = ButtonWidget.builder(Text.literal("存语义：开"), b -> st.semanticTagOnSave = !st.semanticTagOnSave)
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.semanticStyleButton = ButtonWidget.builder(Text.literal("风格：DEFAULT"), b -> ComponentTool.INSTANCE.cycleSemanticStyle())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.semanticPartButton = ButtonWidget.builder(Text.literal("语义：AUTO"), b -> ComponentTool.INSTANCE.cycleSemanticPart())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        p.socketContextButton = ButtonWidget.builder(Text.literal("连接位上下文: WALL"), b -> ComponentTool.INSTANCE.cycleSocketType())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.socketPickOriginButton = ButtonWidget.builder(Text.literal("点选原点"), b -> ComponentTool.INSTANCE.startPickSocketOrigin())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.socketFacingButton = ButtonWidget.builder(Text.literal("朝向: SOUTH"), b -> ComponentTool.INSTANCE.cycleSocketFacing())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.socketAddButton = ButtonWidget.builder(Text.literal("添加连接位"), b -> ComponentTool.INSTANCE.addSocket())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        添加连接位（高级）
                        ━━━━━━━━━━━━
                        将当前配置的连接位添加到构件
                        
                        一个构件可以有多个连接位
                        例如：墙体可以有多个门窗位置
                        
                        添加后在列表中显示""")))
                .build();

        p.socketPreviewButton = ButtonWidget.builder(Text.literal("预览"), b -> ComponentTool.INSTANCE.toggleSocketPreview(p.client()))
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        预览连接位（高级）
                        ━━━━━━━━━━━━
                        在世界中显示连接位的：
                        • 位置标记
                        • 朝向箭头
                        • 大小区域
                        
                        帮助验证连接位配置正确""")))
                .build();

        p.socketClearButton = ButtonWidget.builder(Text.literal("清空连接位"), b -> ComponentTool.INSTANCE.clearSockets())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        清空所有连接位（高级）
                        ━━━━━━━━━━━━
                        移除所有已添加的连接位
                        
                        注意：此操作不可撤销
                        清空后需要重新配置""")))
                .build();

        // 自动修复按钮
        p.autoFixButton = ButtonWidget.builder(Text.literal("🤖 自动修复"), b -> p.healthCoordinator.runAutoFix(p.client()))
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.undoAutoFixButton = ButtonWidget.builder(Text.literal("↩ 撤销修复"), b -> p.healthCoordinator.undoAutoFix())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        撤销上一次自动修复
                        ━━━━━━━━━━━━
                        恢复到修复前的状态
                        """)))
                .build();

        // 底部按钮
        p.cancelButton = ButtonWidget.builder(Text.literal("重置"), b -> {
                    if (st.isCaptureSavePending()) { com.formacraft.client.component.ClientComponentImport.cancel(); return; }
                    st.pickingAnchor = false;
                    st.cancelCapture();
                    p.clearSelection();
                    p.advancedOptionsExpanded = false;
                    st.beginCapture();
                    p.syncLocalFromState();
                })
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        重置构件拾取
                        ━━━━━━━━━━━━
                        清空当前选区与配置
                        留在拾取面板重新开始
                        
                        已配置的内容将丢失""")))
                .build();

        p.saveButton = ButtonWidget.builder(Text.literal("✓ 保存到构件库"), b -> ComponentCaptureSubmitter.saveComponent(p))
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        p.boxSelectButton = ButtonWidget.builder(Text.literal("📦 框选"), b -> p.setSelectionMode(ComponentSelectionMode.BOX_SELECT))
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.pointSelectButton = ButtonWidget.builder(Text.literal("👆 点选"), b -> p.setSelectionMode(ComponentSelectionMode.POINT_SELECT))
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.clearSelectionButton = ButtonWidget.builder(Text.literal("🗑️ 清除"), b -> p.clearSelection())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        p.advancedOptionsButton = ButtonWidget.builder(Text.literal("▶ 高级选项"), b -> p.advancedOptionsExpanded = !p.advancedOptionsExpanded)
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("展开附着、方向性与语义标注等高级选项")))
                .build();

        p.attachmentModeButton = ButtonWidget.builder(
                Text.literal("附着: " + p.getAttachmentModeDisplay()), 
                b -> p.cycleAttachmentMode()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.directionalityButton = ButtonWidget.builder(
                Text.literal("方向: " + p.orientationController.getDirectionalityMode().getDisplayName()), 
                b -> p.cycleDirectionality()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.setInsideButton = ButtonWidget.builder(
                Text.literal("🏠 设内侧"), 
                b -> p.orientationController.startMarkingInside()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.setOutsideButton = ButtonWidget.builder(
                Text.literal("🌍 设外侧"), 
                b -> p.orientationController.startMarkingOutside()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.setBottomButton = ButtonWidget.builder(
                Text.literal("⬇️ 设底端"), 
                b -> p.orientationController.startMarkingBottom()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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
        
        p.setTopButton = ButtonWidget.builder(
                Text.literal("⬆️ 设顶端"), 
                b -> p.orientationController.startMarkingTop()
        )
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
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

        p.culturalStyleButton = ButtonWidget.builder(Text.literal("文化风格: 自动"), b -> p.semanticPreview.cycleCulturalStyle())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        文化风格（AI 检索用）
                        ━━━━━━━━━━━━
                        • 自动：根据标签与材质推断
                        • 手动：覆盖自动推断结果
                        
                        影响 AI 选择构件时的风格匹配""")))
                .build();

        p.geometryArchetypeButton = ButtonWidget.builder(Text.literal("几何原型: 自动"), b -> p.semanticPreview.cycleGeometryArchetype())
                .dimensions(0, 0, 0, ComponentCapturePanel.BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("""
                        几何原型（形态族）
                        ━━━━━━━━━━━━
                        ORNAMENT / FRAME / LINEAR 等
                        
                        帮助 AI 区分斗拱、窗套、栏杆等形态""")))
                .build();
        p.captureButtons = java.util.List.of(p.categoryButton, p.pickAnchorButton, p.clearAnchorButton, p.hostFaceButton,
                p.anchorOutsideButton, p.autoAnchorButton, p.facingButton, p.mirrorButton, p.semanticSkinButton,
                p.semanticTagOnSaveButton, p.semanticStyleButton, p.semanticPartButton, p.socketContextButton,
                p.socketPickOriginButton, p.socketFacingButton, p.socketAddButton, p.socketPreviewButton, p.socketClearButton,
                p.autoFixButton, p.undoAutoFixButton, p.cancelButton, p.saveButton,
                p.boxSelectButton, p.pointSelectButton, p.clearSelectionButton, p.advancedOptionsButton, p.attachmentModeButton,
                p.directionalityButton, p.culturalStyleButton, p.geometryArchetypeButton, p.setInsideButton, p.setOutsideButton,
                p.setBottomButton, p.setTopButton);
    }
}
