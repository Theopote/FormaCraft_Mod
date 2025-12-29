package com.formacraft.common.model.path;

/**
 * 路径规格
 * 用于定义连接建筑之间的道路
 */
public class PathSpec {
    
    /**
     * 三维坐标点
     */
    public static class Point {
        public int x;
        public int y;
        public int z;

        public Point() {}

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private String id;                 // 唯一标识符，如 "main_road", "path_1"
    private Point from;
    private Point to;
    private int width = 3;  // 默认宽度 3 格
    private String material = "minecraft:gravel";  // 默认材质
    private String style = "default";  // 道路样式
    private java.util.Map<String, Object> extra;   // 可选：扩展参数（paletteId/styleProfileId/roadLamps 等）

    public PathSpec() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Point getFrom() {
        return from;
    }

    public void setFrom(Point from) {
        this.from = from;
    }

    public Point getTo() {
        return to;
    }

    public void setTo(Point to) {
        this.to = to;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public java.util.Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(java.util.Map<String, Object> extra) {
        this.extra = extra;
    }
}

