package com.formacraft.common.component.health;

import java.util.ArrayList;
import java.util.List;

/**
 * 构件健康检查结果
 * 
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
        
        public CheckItem(String ruleId, Level level, String title, String description, 
                        String impact, FixAction fixAction, String fixSuggestion) {
            this.ruleId = ruleId;
            this.level = level;
            this.title = title;
            this.description = description;
            this.impact = impact;
            this.fixAction = fixAction;
            this.fixSuggestion = fixSuggestion;
        }
        
        public static CheckItem ok(String ruleId, String title) {
            return new CheckItem(ruleId, Level.OK, title, "", "", FixAction.NONE, "");
        }
        
        public static CheckItem warn(String ruleId, String title, String description, 
                                   String impact, FixAction fixAction, String fixSuggestion) {
            return new CheckItem(ruleId, Level.WARN, title, description, impact, fixAction, fixSuggestion);
        }
        
        public static CheckItem error(String ruleId, String title, String description, 
                                    String impact, FixAction fixAction, String fixSuggestion) {
            return new CheckItem(ruleId, Level.ERROR, title, description, impact, fixAction, fixSuggestion);
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
