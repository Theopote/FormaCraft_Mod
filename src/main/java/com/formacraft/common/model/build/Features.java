package com.formacraft.common.model.build;

/**
 * 建筑功能特性
 */
public class Features {
    private boolean hasWindows = true;
    private boolean hasStairs = true;
    private boolean hasDoor = true;
    private boolean hasBalcony = false;
    private boolean hasRoof = true;
    private boolean hasRoofDecoration = false;
    private int windowCount = 0;
    private int floorCount = 1;

    public Features() {}

    public boolean hasWindows() {
        return hasWindows;
    }

    public void setHasWindows(boolean hasWindows) {
        this.hasWindows = hasWindows;
    }

    public boolean hasStairs() {
        return hasStairs;
    }

    public void setHasStairs(boolean hasStairs) {
        this.hasStairs = hasStairs;
    }

    public boolean hasDoor() {
        return hasDoor;
    }

    public void setHasDoor(boolean hasDoor) {
        this.hasDoor = hasDoor;
    }

    public boolean hasBalcony() {
        return hasBalcony;
    }

    public void setHasBalcony(boolean hasBalcony) {
        this.hasBalcony = hasBalcony;
    }

    public boolean hasRoof() {
        return hasRoof;
    }

    public void setHasRoof(boolean hasRoof) {
        this.hasRoof = hasRoof;
    }

    public boolean hasRoofDecoration() {
        return hasRoofDecoration;
    }

    public void setHasRoofDecoration(boolean hasRoofDecoration) {
        this.hasRoofDecoration = hasRoofDecoration;
    }

    public int getWindowCount() {
        return windowCount;
    }

    public void setWindowCount(int windowCount) {
        this.windowCount = windowCount;
    }

    public int getFloorCount() {
        return floorCount;
    }

    public void setFloorCount(int floorCount) {
        this.floorCount = floorCount;
    }
}

