package com.formacraft.common.cluster.zoning;

import java.util.ArrayList;
import java.util.List;

/**
 * ZoningProfile（街区分区预设）
 * 
 * 一套完整的街区分区规则集合
 * 
 * 可以给不同 StyleProfile 或用户输入套不同 profile
 */
public final class ZoningProfile {

    public final String id;
    public final List<ZoneRule> rules = new ArrayList<>();

    public ZoningProfile(String id) {
        this.id = id != null ? id : "DEFAULT";
    }

    /**
     * 添加分区规则
     */
    public ZoningProfile add(ZoneRule rule) {
        if (rule != null) {
            this.rules.add(rule);
        }
        return this;
    }

    /**
     * 默认城镇街道分区预设
     * 
     * 典型布局：主街 + 尾端工业 + 中段广场节点
     * 
     * @param laneCount 建筑排数
     * @return ZoningProfile
     */
    public static ZoningProfile defaultTownStreet(int laneCount) {
        ZoningProfile z = new ZoningProfile("TOWN_STREET_DEFAULT");

        // 0~0.25 商业（内排优先）
        z.add(new ZoneRule("commercial_front", 0.00f, 0.25f,
                ZoneRule.bothSides(), 0, null,
                BuildingProgram.COMMERCIAL, 1.0f));

        // 0~0.70 住宅（外排）
        if (laneCount >= 2) {
            z.add(new ZoneRule("residential_outer", 0.00f, 0.70f,
                    ZoneRule.bothSides(), 1, null,
                    BuildingProgram.RESIDENTIAL, 0.9f));
        } else {
            z.add(new ZoneRule("residential_mid", 0.25f, 0.70f,
                    ZoneRule.bothSides(), null, null,
                    BuildingProgram.RESIDENTIAL, 0.8f));
        }

        // 0.35~0.45 广场（需要 label=plaza 才触发最好；无 label 也可弱触发）
        z.add(new ZoneRule("plaza_label", 0.35f, 0.45f,
                ZoneRule.bothSides(), null, "plaza",
                BuildingProgram.PLAZA, 2.0f));

        // 0.75~1.0 工业/仓储
        z.add(new ZoneRule("industrial_tail", 0.75f, 1.00f,
                ZoneRule.bothSides(), null, null,
                BuildingProgram.INDUSTRIAL, 0.7f));

        return z;
    }

    /**
     * 商业街分区预设
     */
    public static ZoningProfile commercialStreet(int laneCount) {
        ZoningProfile z = new ZoningProfile("COMMERCIAL_STREET");

        // 全程商业（内排）
        z.add(new ZoneRule("commercial_all", 0.00f, 1.00f,
                ZoneRule.bothSides(), 0, null,
                BuildingProgram.COMMERCIAL, 1.0f));

        // 外排住宅（如果有外排）
        if (laneCount >= 2) {
            z.add(new ZoneRule("residential_back", 0.00f, 1.00f,
                    ZoneRule.bothSides(), 1, null,
                    BuildingProgram.RESIDENTIAL, 0.8f));
        }

        return z;
    }

    /**
     * 防御性街道分区预设（城墙走廊）
     */
    public static ZoningProfile defensiveStreet() {
        ZoningProfile z = new ZoningProfile("DEFENSIVE_STREET");

        // 全程防御
        z.add(new ZoneRule("defensive_all", 0.00f, 1.00f,
                ZoneRule.bothSides(), null, null,
                BuildingProgram.DEFENSIVE, 1.0f));

        // 节点地标（需要 label=gate 或 label=tower）
        z.add(new ZoneRule("landmark_gate", 0.00f, 1.00f,
                ZoneRule.bothSides(), null, "gate",
                BuildingProgram.LANDMARK, 2.0f));

        z.add(new ZoneRule("landmark_tower", 0.00f, 1.00f,
                ZoneRule.bothSides(), null, "tower",
                BuildingProgram.DEFENSIVE, 2.0f));

        return z;
    }
}

