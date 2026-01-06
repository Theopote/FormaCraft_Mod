package com.formacraft.ai.prompt;

import com.formacraft.common.terrain.TerrainPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 的中间结构层（Prompt AST/Context），避免到处拼字符串。
 */
public class PromptContext {
    /** 模式 */
    public PromptMode mode = PromptMode.BUILD;

    /** 玩家自然语言输入 */
    public String userMessage = "";

    /** 地形策略（新增） */
    public TerrainPolicy terrainPolicy = null;

    /** 硬规则（必须遵守） */
    public final List<String> rules = new ArrayList<>();

    /** 结构化约束（边界/对称等） */
    public final List<String> constraints = new ArrayList<>();

    /** 结构化说明（区域语义/标签等） */
    public final List<String> annotations = new ArrayList<>();

    /** 调试信息（可选） */
    public final List<String> debug = new ArrayList<>();
}


