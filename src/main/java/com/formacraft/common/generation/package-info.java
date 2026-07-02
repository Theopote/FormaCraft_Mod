/**
 * 生成体系三层模型（Phase 3–6）。
 * <ul>
 *   <li>{@code structure} — 整栋建筑（{@code StructureGenerator} + {@code StructureGeneratorRegistry}）</li>
 *   <li>{@code component} — LlmPlan 构件（{@code ComponentGenerator} + {@code ComponentGeneratorRegistry}，包仍在 {@code common.generator}）</li>
 *   <li>{@code skeleton} — 骨架执行（{@code SkeletonExecutor} + {@code SkeletonGeneratorRegistry}）</li>
 * </ul>
 * 统一入口：{@code com.formacraft.server.generation.GenerationHub}
 */
package com.formacraft.common.generation;
