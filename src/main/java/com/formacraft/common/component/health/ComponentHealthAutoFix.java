package com.formacraft.common.component.health;

import com.formacraft.common.component.ComponentDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于健康检查结果的自动修复器
 * 
 * 根据 HealthCheckResult 中的 FixAction.AUTO 项执行自动修复
 */
public final class ComponentHealthAutoFix {
    private ComponentHealthAutoFix() {}
    
    /**
     * 修复报告
     */
    public static class FixReport {
        private final List<String> fixes = new ArrayList<>();
        
        public void add(String ruleId, String description) {
            fixes.add("[" + ruleId + "] " + description);
        }
        
        public List<String> getFixes() {
            return new ArrayList<>(fixes);
        }
        
        public boolean isEmpty() {
            return fixes.isEmpty();
        }
        
        public int size() {
            return fixes.size();
        }
    }
    
    /**
     * 根据健康检查结果执行自动修复
     * 
     * @param def 要修复的构件定义（会被修改）
     * @param healthResult 健康检查结果
     * @return 修复报告
     */
    public static FixReport apply(ComponentDefinition def, HealthCheckResult healthResult) {
        FixReport report = new FixReport();
        
        if (def == null || healthResult == null) {
            return report;
        }
        
        // 获取所有可自动修复的项
        List<HealthCheckResult.CheckItem> autoFixableItems = healthResult.getAutoFixableItems();
        
        for (var item : autoFixableItems) {
            applyFix(def, item, report);
        }
        
        return report;
    }
    
    /**
     * 应用单个修复项
     */
    private static void applyFix(ComponentDefinition def, HealthCheckResult.CheckItem item, FixReport report) {
        String ruleId = item.ruleId;
        
        switch (ruleId) {
            case "H2-1":
                // 自动推荐锚点（底部中心）
                fixAnchor(def, report);
                break;
                
            case "H2-2":
                // 移动锚点到底部中心
                fixAnchorPosition(def, report);
                break;
                
            // 其他规则可以在这里添加
            default:
                // 未知规则，跳过
                break;
        }
    }
    
    /**
     * H2-1: 修复锚点未设置
     */
    private static void fixAnchor(ComponentDefinition def, FixReport report) {
        if (def.anchor != null) {
            return; // 已有锚点，无需修复
        }
        
        // 计算底部中心
        if (def.blocks == null || def.blocks.isEmpty()) {
            // 如果没有方块，创建默认锚点
            def.anchor = new ComponentDefinition.Anchor();
            def.anchor.dx = 0;
            def.anchor.dy = 0;
            def.anchor.dz = 0;
            def.anchor.facing = "SOUTH";
            report.add("H2-1", "创建默认锚点 (0,0,0)");
            return;
        }
        
        // 计算边界
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (var block : def.blocks) {
            minX = Math.min(minX, block.dx);
            minY = Math.min(minY, block.dy);
            minZ = Math.min(minZ, block.dz);
            maxX = Math.max(maxX, block.dx);
            maxY = Math.max(maxY, block.dy);
            maxZ = Math.max(maxZ, block.dz);
        }
        
        // 创建锚点在底部中心
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.dx = (minX + maxX) / 2;
        def.anchor.dy = minY; // 底部
        def.anchor.dz = (minZ + maxZ) / 2;
        def.anchor.facing = "SOUTH";
        
        report.add("H2-1", String.format("自动设置锚点到底部中心 (%d,%d,%d)", 
            def.anchor.dx, def.anchor.dy, def.anchor.dz));
    }
    
    /**
     * H2-2: 修复锚点位置（移动到底部中心）
     */
    private static void fixAnchorPosition(ComponentDefinition def, FixReport report) {
        if (def.anchor == null || def.blocks == null || def.blocks.isEmpty()) {
            return;
        }
        
        // 计算最低点
        int minY = Integer.MAX_VALUE;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        
        for (var block : def.blocks) {
            minY = Math.min(minY, block.dy);
            minX = Math.min(minX, block.dx);
            maxX = Math.max(maxX, block.dx);
            minZ = Math.min(minZ, block.dz);
            maxZ = Math.max(maxZ, block.dz);
        }
        
        // 移动锚点到底部中心
        int oldY = def.anchor.dy;
        def.anchor.dy = minY;
        def.anchor.dx = (minX + maxX) / 2;
        def.anchor.dz = (minZ + maxZ) / 2;
        
        report.add("H2-2", String.format("移动锚点从 Y=%d 到底部 Y=%d，中心 (%d,%d,%d)", 
            oldY, def.anchor.dy, def.anchor.dx, def.anchor.dy, def.anchor.dz));
    }
}
