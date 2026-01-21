package com.formacraft.common.component.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 构件健康检查结果
 * <p>
 * 这是 Formacraft 信任系统的核心：不是"修复构件"，而是"告诉用户这个构件将来会不会被 AI 用错"
 */
public final class HealthCheckResult {
    
    /**
     * 健康级别
     */
    public enum Level {
        /** 正常，无需关注 */
        OK,
        /** 警告，可能影响AI使用 */
        WARN,
        /** 错误，必须修复 */
        ERROR
    }
    
    /**
     * 修复动作类型
     */
    public enum FixAction {
        /** 可自动修复 */
        AUTO,
        /** 建议修复（需要用户确认） */
        SUGGEST,
        /** 无自动修复（必须用户操作） */
        NONE
    }
    
    /**
     * 单个检查项
     */
    public static class CheckItem {
        /** 规则ID（如 "H1-1", "H2-2"） */
        public final String ruleId;
        
        /** 健康级别 */
        public final Level level;
        
        /** 标题（用户可见） */
        public final String title;
        
        /** 详细描述 */
        public final String description;
        
        /** 影响说明（告诉用户后果） */
        public final String impact;
        
        /** 修复动作 */
        public final FixAction fixAction;
        
        /** 修复建议文本（如果 fixAction != NONE） */
        public final String fixSuggestion;
        
        /** 所属阶段（用于 UI 跳转，如 "SELECTION", "ANCHOR_ORIENTATION", "SEMANTIC", "AI_GUARANTEE"） */
        public final String phase;
        
        /** UI 目标ID（用于跳转到对应控件，如 "section.anchor", "button.setInside"） */
        public final String uiTargetId;
        
        /** 是否需要世界交互（🎯 标记，如锚点设置、方向标记） */
        public final boolean requiresWorldClick;
        
        /** 是否可忽略（WARN 级别通常可忽略） */
        public final boolean canIgnore;
        
        public CheckItem(String ruleId, Level level, String title, String description, 
                        String impact, FixAction fixAction, String fixSuggestion,
                        String phase, String uiTargetId, boolean requiresWorldClick, boolean canIgnore) {
            this.ruleId = ruleId;
            this.level = level;
            this.title = title;
            this.description = description;
            this.impact = impact;
            this.fixAction = fixAction;
            this.fixSuggestion = fixSuggestion;
            this.phase = phase != null ? phase : "";
            this.uiTargetId = uiTargetId != null ? uiTargetId : "";
            this.requiresWorldClick = requiresWorldClick;
            this.canIgnore = canIgnore;
        }
        
        public static CheckItem ok(String ruleId, String title) {
            return new CheckItem(ruleId, Level.OK, title, "", "", FixAction.NONE, "", "", "", false, true);
        }
        
        public static CheckItem warn(String ruleId, String title, String description, 
                                   String impact, FixAction fixAction, String fixSuggestion) {
            // 根据规则ID推断阶段和UI目标
            String phase = inferPhase(ruleId);
            String uiTarget = inferUITarget(ruleId);
            boolean requiresWorld = ruleId.startsWith("H2-") && ruleId.contains("anchor");
            return new CheckItem(ruleId, Level.WARN, title, description, impact, fixAction, fixSuggestion,
                    phase, uiTarget, requiresWorld, true);
        }
        
        public static CheckItem error(String ruleId, String title, String description, 
                                    String impact, FixAction fixAction, String fixSuggestion) {
            // 根据规则ID推断阶段和UI目标
            String phase = inferPhase(ruleId);
            String uiTarget = inferUITarget(ruleId);
            boolean requiresWorld = ruleId.startsWith("H2-");
            return new CheckItem(ruleId, Level.ERROR, title, description, impact, fixAction, fixSuggestion,
                    phase, uiTarget, requiresWorld, false);
        }
        
        /**
         * 根据规则ID推断所属阶段
         */
        private static String inferPhase(String ruleId) {
            if (ruleId == null) return "";
            if (ruleId.startsWith("H1-")) return "SELECTION";
            if (ruleId.startsWith("H2-")) return "ANCHOR_ORIENTATION";
            if (ruleId.startsWith("H3-")) return "SEMANTIC";
            if (ruleId.startsWith("H4-")) return "AI_GUARANTEE";
            return "";
        }
        
        /**
         * 根据规则ID推断UI目标
         */
        private static String inferUITarget(String ruleId) {
            if (ruleId == null) return "";
            // H1: 选区相关
            return switch (ruleId) {
                case "H1-1", "H1-2", "H1-3" -> "section.selection";
                // H2: 锚点相关
                case "H2-1" -> "button.pickAnchor";
                case "H2-2" -> "button.pickAnchor";
                case "H2-3" -> "button.setInside";
                // H3: 语义相关
                case "H3-1" -> "button.category";
                // H4: AI相关
                case "H4-1" -> "section.socket";
                default -> "";
            };
        }
    }
    
    /** 所有检查项 */
    private final List<CheckItem> items = new ArrayList<>();
    
    /** 添加检查项 */
    public void add(CheckItem item) {
        items.add(item);
    }
    
    /** 获取所有检查项 */
    public List<CheckItem> getItems() {
        return new ArrayList<>(items);
    }
    
    /** 是否有错误 */
    public boolean hasErrors() {
        return items.stream().anyMatch(item -> item.level == Level.ERROR);
    }
    
    /** 是否有警告 */
    public boolean hasWarnings() {
        return items.stream().anyMatch(item -> item.level == Level.WARN);
    }
    
    /** 是否有可自动修复的项 */
    public boolean hasAutoFixable() {
        return items.stream().anyMatch(item -> item.fixAction == FixAction.AUTO);
    }
    
    /** 获取所有可自动修复的项 */
    public List<CheckItem> getAutoFixableItems() {
        List<CheckItem> result = new ArrayList<>();
        for (CheckItem item : items) {
            if (item.fixAction == FixAction.AUTO) {
                result.add(item);
            }
        }
        return result;
    }
    
    /** 获取最高级别（ERROR > WARN > OK） */
    public Level getOverallLevel() {
        if (hasErrors()) return Level.ERROR;
        if (hasWarnings()) return Level.WARN;
        return Level.OK;
    }
    
    /** 是否健康（无错误和警告） */
    public boolean isHealthy() {
        return !hasErrors() && !hasWarnings();
    }
}
