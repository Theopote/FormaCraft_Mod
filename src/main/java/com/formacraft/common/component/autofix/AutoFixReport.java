package com.formacraft.common.component.autofix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AutoFix 修复报告（记录改了什么）。
 * <p>
 * 用途：
 * - UI 显示修复内容
 * - 日志记录
 * - Debug 调试
 */
public final class AutoFixReport {

    /**
     * 单个修复操作
     */
    public static final class Fix {
        /** JSON 路径：例如 "placement.attachment" 或 "blocks[5].block" */
        public final String path;
        /** 修复描述：例如 "Defaulted to WALL_OPENING" */
        public final String message;

        public Fix(String path, String message) {
            this.path = path;
            this.message = message;
        }

        @Override
        public String toString() {
            return "[FIX] " + path + ": " + message;
        }
    }

    private final List<Fix> fixes = new ArrayList<>();

    /**
     * 添加修复记录
     */
    public void add(String path, String msg) {
        fixes.add(new Fix(path, msg));
    }

    /**
     * 检查是否有修复
     */
    public boolean empty() {
        return fixes.isEmpty();
    }

    /**
     * 获取所有修复（只读）
     */
    public List<Fix> fixes() {
        return Collections.unmodifiableList(fixes);
    }

    /**
     * 获取修复数量
     */
    public int size() {
        return fixes.size();
    }
}
