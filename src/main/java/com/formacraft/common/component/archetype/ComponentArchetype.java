package com.formacraft.common.component.archetype;

import java.util.List;

/**
 * ComponentArchetype（构件原型）：一个"可被生成系统调用的、受规则约束的建筑器官模板"。
 * <p>
 * 核心思想：
 * - 构件不是"模型"，而是"可变的建筑器官（Architectural Organs）"
 * - 它回答 5 个问题：
 *   1. 它是什么？（语义）
 *   2. 它能贴在哪？（上下文 / 附着）
 *   3. 它能怎么变？（变体规则）
 *   4. 它和谁对接？（Socket）
 *   5. 它不能做什么？（约束）
 * <p>
 * ⚠️ 注意：这里完全不涉及具体方块
 * → Archetype 是"语义 + 规则"，不是模型
 */
public class ComponentArchetype {
    /**
     * Schema 版本
     */
    public String schema = "formacraft.archetype.v1";

    /**
     * 唯一标识符（例如：door.basic, window.gothic）
     */
    public String id;

    /**
     * 显示名称（例如：门、哥特窗）
     */
    public String displayName;

    /**
     * 分类（例如：OPENING, SUPPORT, DECORATION）
     */
    public String category;

    /**
     * 语义标签（例如：["door", "entry", "opening"]）
     */
    public List<String> semanticTags;

    /**
     * 附着与上下文规则
     */
    public AttachmentSpec attachment;

    /**
     * 允许的变形
     */
    public VariationSpec variation;

    /**
     * 对接规则
     */
    public SocketSpec socket;

    /**
     * 给生成器 / AI 的形态提示
     */
    public GeometryHint geometryHint;

    /**
     * 禁止规则
     */
    public ValidationSpec validation;

    /**
     * 创建基础门的原型
     */
    public static ComponentArchetype createBasicDoor() {
        ComponentArchetype archetype = new ComponentArchetype();
        archetype.id = "door.basic";
        archetype.displayName = "基础门";
        archetype.category = "OPENING";
        archetype.semanticTags = List.of("door", "entry", "opening");
        archetype.attachment = AttachmentSpec.forDoor();
        archetype.variation = VariationSpec.forDoor();
        archetype.socket = SocketSpec.forDoor();
        archetype.geometryHint = GeometryHint.forDoor();
        archetype.validation = ValidationSpec.forDoor();
        return archetype;
    }

    /**
     * 创建基础窗的原型
     */
    public static ComponentArchetype createBasicWindow() {
        ComponentArchetype archetype = new ComponentArchetype();
        archetype.id = "window.basic";
        archetype.displayName = "基础窗";
        archetype.category = "OPENING";
        archetype.semanticTags = List.of("window", "opening");
        archetype.attachment = AttachmentSpec.forWindow();
        archetype.variation = VariationSpec.forWindow();
        archetype.socket = SocketSpec.forWindow();
        archetype.geometryHint = GeometryHint.forWindow();
        archetype.validation = ValidationSpec.createDefault();
        return archetype;
    }

    /**
     * 创建栏杆的原型
     */
    public static ComponentArchetype createRailing() {
        ComponentArchetype archetype = new ComponentArchetype();
        archetype.id = "railing.basic";
        archetype.displayName = "基础栏杆";
        archetype.category = "DECORATION";
        archetype.semanticTags = List.of("railing", "edge", "guard");
        archetype.attachment = AttachmentSpec.forRailing();
        archetype.variation = VariationSpec.forRailing();
        archetype.socket = SocketSpec.forRailing();
        archetype.geometryHint = GeometryHint.forRailing();
        archetype.validation = ValidationSpec.createDefault();
        return archetype;
    }
}
