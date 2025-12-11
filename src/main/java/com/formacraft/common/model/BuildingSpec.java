package com.formacraft.common.model;

import java.util.Map;

/**
 * AI 响应的建筑规格数据结构（Python → Minecraft）
 * 与 Python 后端的 BuildingSpec 完全对齐
 */
public class BuildingSpec {
    public String type; // tower, house, bridge, castle, etc.
    public String style; // medieval, modern, rustic, etc.
    public int height;
    public int radius;
    public int width;
    public int depth;
    public Materials materials;
    public Features features;
    public String notes; // AI 生成的说明

    public BuildingSpec() {}

    public static class Materials {
        public String wall;
        public String roof;
        public String floor;
        public String foundation;

        public Materials() {}
    }

    public static class Features {
        public boolean hasWindows;
        public boolean hasStairs;
        public boolean hasDoor;
        public boolean hasRoof;
        public int windowCount;
        public int floorCount;

        public Features() {
            this.hasWindows = false;
            this.hasStairs = false;
            this.hasDoor = false;
            this.hasRoof = false;
            this.windowCount = 0;
            this.floorCount = 1;
        }
    }
}

