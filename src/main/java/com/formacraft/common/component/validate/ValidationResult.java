package com.formacraft.common.component.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 构件验证结果（结构化错误/警告列表）。
 * <p>
 * 用途：
 * - 组件 JSON 导入/保存时校验
 * - AI 输出引用组件时二次校验（防止脏数据）
 * - ComponentPanel 显示"哪里不对"
 * - 未来做自动修复（Auto-fix）打基础
 */
public final class ValidationResult {

    public enum Severity {
        /** 错误：必须修复才能使用 */
        ERROR,
        /** 警告：建议修复，但不影响基本功能 */
        WARN
    }

    /**
     * 单个验证问题
     */
    public static final class Issue {
        public final Severity severity;
        /** JSON 路径：例如 "placement.facing_policy" 或 "blocks[5].block" */
        public final String path;
        public final String message;

        public Issue(Severity severity, String path, String message) {
            this.severity = severity;
            this.path = path;
            this.message = message;
        }

        @Override
        public String toString() {
            return severity + " @ " + path + ": " + message;
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    public void error(String path, String msg) {
        issues.add(new Issue(Severity.ERROR, path, msg));
    }

    public void warn(String path, String msg) {
        issues.add(new Issue(Severity.WARN, path, msg));
    }

    /**
     * 检查是否有错误（警告不算错误）
     */
    public boolean ok() {
        return issues.stream().noneMatch(i -> i.severity == Severity.ERROR);
    }

    /**
     * 获取所有问题（只读）
     */
    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }

    /**
     * 获取所有错误（只读）
     */
    public List<Issue> errors() {
        return issues.stream()
                .filter(i -> i.severity == Severity.ERROR)
                .toList();
    }

    /**
     * 获取所有警告（只读）
     */
    public List<Issue> warnings() {
        return issues.stream()
                .filter(i -> i.severity == Severity.WARN)
                .toList();
    }

    /**
     * 获取问题总数
     */
    public int size() {
        return issues.size();
    }
}
