package com.formacraft.common.style;

import java.util.Objects;

/**
 * StyleGenome：风格“基因”定义（数据驱动）
 * - Palette：材质基因（主材/屋顶/地面/窗/地基/线脚/柱）
 * - Params：形态参数（屋顶类型、开窗比例等）
 *
 * 该结构用于把“风格”从生成器代码中抽离，避免未来风格增多后到处写 switch。
 */
public class StyleGenome {
    /** 唯一 ID（文件名/引用名） */
    public String id;
    /** 展示用名称（可选） */
    public String name;
    /** 绑定的枚举风格（可选：ASIAN/MODERN/...） */
    public String style;

    public Palette palette = new Palette();
    public Params params = new Params();

    public static class Palette {
        /** Minecraft 方块 ID（如 minecraft:red_terracotta） */
        public String wall;
        public String roof;
        public String floor;
        public String window;
        public String foundation;
        public String trim;
        public String pillar;
    }

    public static class Params {
        /** flat / gable / hipped / pyramid / cone */
        public String roofType;
        /** 0.0~1.0 */
        public Double windowRatio;
        /** pane / fence / stained */
        public String windowStyle;
        /** uniform / striped / gradient / random */
        public String wallPattern;
        /** single / double / arched / none */
        public String doorStyle;
    }

    public boolean hasPalette() {
        return palette != null && (palette.wall != null || palette.roof != null || palette.floor != null || palette.window != null);
    }

    @Override
    public String toString() {
        return "StyleGenome{" +
                "id='" + id + '\'' +
                ", style='" + style + '\'' +
                ", palette=" + (palette == null ? "null" : "Palette") +
                ", params=" + (params == null ? "null" : "Params") +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StyleGenome that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}


