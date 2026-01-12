package com.formacraft.common.component;

import java.util.Set;

/**
 * LLM 输出的“构件需求”（v1）：只描述语义需求，不指定具体 id。
 */
public class ComponentRequest {
    public ComponentCategory category;
    public Set<String> tags;
    public int approxW = -1;
    public int approxH = -1;
    public int approxD = -1;
}

