package com.formacraft.common.model.build;

/**
 * 建筑占地结构
 */
public class Footprint {
    private String shape = "rectangle";  // rectangle, circle, polygon
    private int width;
    private int depth;
    private int radius; // 用于圆形
    private ShapeSpec shapeSpec;

    public Footprint() {}

    public Footprint(int width, int depth) {
        this.width = width;
        this.depth = depth;
        this.shape = "rectangle";
    }

    public Footprint(int radius) {
        this.radius = radius;
        this.shape = "circle";
    }

    public String getShape() {
        return shape;
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public ShapeSpec getShapeSpec() {
        return shapeSpec;
    }

    public void setShapeSpec(ShapeSpec shapeSpec) {
        this.shapeSpec = shapeSpec;
    }
}

