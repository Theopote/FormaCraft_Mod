package com.formacraft.common.model.build;

/**
 * 材质结构
 */
public class Materials {
    private String wall = "minecraft:stone";
    private String roof = "minecraft:oak_planks";
    private String floor = "minecraft:oak_planks";
    private String window = "minecraft:glass_pane";
    private String foundation = "minecraft:stone";

    public Materials() {}

    public String getWall() {
        return wall;
    }

    public void setWall(String wall) {
        this.wall = wall;
    }

    public String getRoof() {
        return roof;
    }

    public void setRoof(String roof) {
        this.roof = roof;
    }

    public String getFloor() {
        return floor;
    }

    public void setFloor(String floor) {
        this.floor = floor;
    }

    public String getWindow() {
        return window;
    }

    public void setWindow(String window) {
        this.window = window;
    }

    public String getFoundation() {
        return foundation;
    }

    public void setFoundation(String foundation) {
        this.foundation = foundation;
    }
}

