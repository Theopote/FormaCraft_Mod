package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.archetype.ComponentArchetype;
import com.formacraft.common.component.archetype.ComponentArchetypeStorage;

import java.util.Random;

/**
 * VariantGenerator（变体生成器）：核心逻辑。
 * <p>
 * 核心思想：
 * - Variant ≠ Random
 * - Variant = 受控变形
 * - 保持"看起来是同一个建筑师设计的东西"
 * <p>
 * 触发时机：
 * - Prompt → ComponentQuery
 * - Rank → Archetype
 * - VariantGenerator 自动触发
 * - 用户不需要知道它发生过
 */
public final class VariantGenerator {
    private VariantGenerator() {}

    /**
     * 生成变体
     * 
     * @param base 基础构件定义（Archetype）
     * @param query 查询条件（用于指导变体生成）
     * @param random 随机数生成器
     * @return 生成的变体
     */
    public static ComponentVariant generate(
            ComponentDefinition base,
            ComponentQuery query,
            Random random
    ) {
        if (base == null) {
            return null;
        }

        // 获取 Archetype（内存 / 磁盘 / 自动生成）
        ComponentArchetype archetype = ComponentArchetypeStorage.resolve(base);

        // 创建变体规格
        ComponentVariantSpec spec;
        if (archetype != null && archetype.variation != null) {
            spec = ComponentVariantSpec.fromArchetype(archetype.variation);
        } else {
            spec = ComponentVariantSpec.createDefault();
        }

        // 创建变体
        ComponentVariant variant = new ComponentVariant(base);

        // 1️⃣ 尺寸变体
        if (spec.allowScaling && query.geometry != null) {
            // 根据查询的几何要求调整缩放
            float scale = lerp(spec.scaleMin, spec.scaleMax, random.nextFloat());
            
            // 如果查询指定了 openingWidth/Height，尝试匹配
            if (query.geometry.openingWidth != null && query.geometry.openingHeight != null && base.size != null) {
                float targetWidth = (float) query.geometry.openingWidth / base.size.w;
                float targetHeight = (float) query.geometry.openingHeight / base.size.h;
                
                // 在允许范围内调整放大
                if (targetWidth >= spec.scaleMin && targetWidth <= spec.scaleMax) {
                    scale = Math.max(scale, targetWidth);
                }
                if (targetHeight >= spec.scaleMin && targetHeight <= spec.scaleMax) {
                    scale = Math.max(scale, targetHeight);
                }
            }
            
            variant.applyScale(scale, spec.scalePolicy);
        }

        // 1.5️⃣ 洞口/尺寸适配：目标小于原构件时用裁剪（缩小），不依赖整数放大
        applyOpeningFitTrim(base, query, spec, variant);

        // 2️⃣ 分段重复（栏杆 / 窗组）
        if (spec.allowSegmentRepeat && query.usageHint != null) {
            String frequency = query.usageHint.frequency;
            if ("secondary".equals(frequency) || "decorative".equals(frequency)) {
                int repeatCount = randomRepeatCount(random, spec.repeatUnit);
                variant.applyRepeat(spec.repeatAxis, repeatCount);
            }
        }

        // 3️⃣ 裁剪（适配 opening / edge）
        if (query.geometry != null && query.geometry.openingWidth != null && query.geometry.openingHeight != null) {
            if (spec.allowTrim || query.geometry.requiresOpening || needsShrinkTrim(base, query)) {
                int width = query.geometry.openingWidth;
                int height = query.geometry.openingHeight;
                int depth = base.size != null ? base.size.d : 1;
                variant.applyTrim(width, height, depth);
            }
        }

        // 4️⃣ 材质语义替换
        if (spec.materialPolicy != MaterialVariantPolicy.NONE && query.style != null) {
            // 根据风格推断材质语义（简化处理）
            String semantic = inferMaterialSemantic(query, base);
            if (semantic != null) {
                variant.applyMaterialSemantic(semantic);
            }
        }

        // 5️⃣ 镜像（如果 Archetype 允许）
        if (archetype != null && archetype.variation != null) {
            if (archetype.variation.allowMirror && random.nextFloat() < 0.3f) {
                // 30% 概率镜像（由展开器 reqMap.mirror 实际应用）
                variant.applyMirror(ComponentVariantSpec.Axis.X);
            }
        }

        return variant;
    }

    /**
     * 线性插值
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void applyOpeningFitTrim(
            ComponentDefinition base,
            ComponentQuery query,
            ComponentVariantSpec spec,
            ComponentVariant variant
    ) {
        if (query == null || query.geometry == null || base == null || base.size == null || variant == null) {
            return;
        }
        Integer openW = query.geometry.openingWidth;
        Integer openH = query.geometry.openingHeight;
        if (openW == null || openH == null || openW <= 0 || openH <= 0) {
            return;
        }
        if (openW >= base.size.w && openH >= base.size.h) {
            return;
        }
        if (!spec.allowTrim && !query.geometry.requiresOpening && !needsShrinkTrim(base, query)) {
            return;
        }
        int depth = base.size.d > 0 ? base.size.d : 1;
        variant.applyTrim(openW, openH, depth);
    }

    private static boolean needsShrinkTrim(ComponentDefinition base, ComponentQuery query) {
        if (base == null || base.size == null || query == null || query.geometry == null) {
            return false;
        }
        Integer openW = query.geometry.openingWidth;
        Integer openH = query.geometry.openingHeight;
        if (openW == null || openH == null) {
            return false;
        }
        return openW < base.size.w || openH < base.size.h;
    }

    /**
     * 随机重复次数
     */
    private static int randomRepeatCount(Random random, int minUnit) {
        // 1-5 个重复单元
        return minUnit + random.nextInt(5);
    }

    /**
     * 推断材质语义
     */
    private static String inferMaterialSemantic(ComponentQuery query, ComponentDefinition base) {
        // 简化处理：根据查询的风格和构件的角色推断材质语义
        if (query.style == null || query.style.styleProfile == null) {
            return null;
        }

        // 根据构件的分类推断材质语义
        if (base.category != null) {
            String category = base.category.name().toLowerCase();
            if (category.contains("door") || category.contains("window")) {
                return "FRAME";
            } else if (category.contains("wall")) {
                return "WALL_PRIMARY";
            } else if (category.contains("column") || category.contains("support")) {
                return "SUPPORT";
            } else if (category.contains("roof")) {
                return "ROOF";
            }
        }

        return "WALL_PRIMARY"; // 默认
    }
}
