package com.formacraft.ai.prompt;

/**
 * Prompt 模式：
 * - BUILD：全新建造
 * - PATCH：增量修改（已有建筑）
 * - MODIFY_REGION：仅修改选区
 */
public enum PromptMode {
    BUILD,
    PATCH,
    MODIFY_REGION
}


