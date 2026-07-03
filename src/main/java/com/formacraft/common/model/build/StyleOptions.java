package com.formacraft.common.model.build;

/**
 * 建筑风格选项
 * BuildingSpec 2.0 版本的标准风格参数
 * 统一管理所有风格/结构扩展参数
 */
public class StyleOptions {
    private String doorStyle = "single";       // single / double / arched / none
    private String roofType = "flat";          // flat / gable / cone / pyramid / hipped
    private String bridgeType = "flat";        // flat / arched / suspension / beam / rope
    private double windowRatio = 0.3;          // 0.0 ~ 1.0
    private String windowStyle = "pane";       // pane / fence / stained
    private String wallPattern = "uniform";    // uniform / striped / gradient / random

    public StyleOptions() {}

    public String getDoorStyle() {
        return doorStyle;
    }

    public void setDoorStyle(String doorStyle) {
        this.doorStyle = doorStyle;
    }

    public String getRoofType() {
        return roofType;
    }

    public void setRoofType(String roofType) {
        this.roofType = roofType;
    }

    public String getBridgeType() {
        return bridgeType;
    }

    public void setBridgeType(String bridgeType) {
        this.bridgeType = bridgeType;
    }

    public double getWindowRatio() {
        return windowRatio;
    }

    public void setWindowRatio(double windowRatio) {
        this.windowRatio = windowRatio;
    }

    public String getWindowStyle() {
        return windowStyle;
    }

    public void setWindowStyle(String windowStyle) {
        this.windowStyle = windowStyle;
    }

    public String getWallPattern() {
        return wallPattern;
    }

    public void setWallPattern(String wallPattern) {
        this.wallPattern = wallPattern;
    }
}

