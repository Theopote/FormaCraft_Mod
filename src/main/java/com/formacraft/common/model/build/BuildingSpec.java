package com.formacraft.common.model.build;

import java.util.Map;

/**
 * 建筑规格数据结构（Python → Minecraft）
 * FormaCraft 所有建筑生成器共用的基础结构
 */
public class BuildingSpec {
    private BuildingType type;
    private BuildingStyle style;
    private Footprint footprint;
    private int height = 10;     // 默认高度
    private int floors = 1;      // 层数（可选）
    private Materials materials;
    private Features features;
    private String notes; // AI 生成的说明
    private StyleOptions styleOptions = new StyleOptions(); // 风格选项（BuildingSpec 2.0）
    
    // 额外参数（AI 可以动态增减）
    // 保留用于向后兼容和未来扩展
    private Map<String, Object> extra;

    public BuildingSpec() {}

    public BuildingType getType() {
        return type;
    }

    public void setType(BuildingType type) {
        this.type = type;
    }

    public BuildingStyle getStyle() {
        return style;
    }

    public void setStyle(BuildingStyle style) {
        this.style = style;
    }

    public Footprint getFootprint() {
        return footprint;
    }

    public void setFootprint(Footprint footprint) {
        this.footprint = footprint;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getFloors() {
        return floors;
    }

    public void setFloors(int floors) {
        this.floors = floors;
    }

    public Materials getMaterials() {
        return materials;
    }

    public void setMaterials(Materials materials) {
        this.materials = materials;
    }

    public Features getFeatures() {
        return features;
    }

    public void setFeatures(Features features) {
        this.features = features;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public StyleOptions getStyleOptions() {
        return styleOptions;
    }

    public void setStyleOptions(StyleOptions styleOptions) {
        this.styleOptions = styleOptions != null ? styleOptions : new StyleOptions();
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    // --- 强类型化门面（Phase 4）---
    // extra 仍是生成器库的可扩展键值袋（Phase 5 的 LLM 可调用模块库会继续依赖它做 bespoke 参数），
    // 但对"已知的一等公民字段"提供强类型访问器，避免每个调用点各自 instanceof/强转/拼字符串。

    /** 后端返回的调试告警（LLM 纠错/回退信息）。始终返回非 null 列表。 */
    public java.util.List<String> getDebugWarnings() {
        Object raw = (extra != null) ? extra.get("debugWarnings") : null;
        if (raw == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else {
            String s = String.valueOf(raw).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 是否携带 J 层骨架布局锚点（spec.extra.skeletonLayout）。 */
    public boolean hasSkeletonLayout() {
        return extra != null && extra.get("skeletonLayout") != null;
    }

    /** J 层骨架布局原始对象（预览 / 城市链路解析用）。可能为 null。 */
    public Object getSkeletonLayout() {
        return (extra != null) ? extra.get("skeletonLayout") : null;
    }

    // 便捷方法：获取宽度（从 footprint 或直接）
    public int getWidth() {
        if (footprint != null && footprint.getShape().equals("rectangle")) {
            return footprint.getWidth();
        }
        return 0;
    }

    // 便捷方法：获取深度（从 footprint 或直接）
    public int getDepth() {
        if (footprint != null && footprint.getShape().equals("rectangle")) {
            return footprint.getDepth();
        }
        return 0;
    }

    // 便捷方法：获取半径（从 footprint 或直接）
    public int getRadius() {
        if (footprint != null && footprint.getShape().equals("circle")) {
            return footprint.getRadius();
        }
        return 0;
    }
}

