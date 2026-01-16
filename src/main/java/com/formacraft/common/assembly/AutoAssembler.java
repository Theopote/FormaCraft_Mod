package com.formacraft.common.assembly;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.anchor.ComponentAnchor;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.query.ComponentRetriever;
import com.formacraft.common.component.query.ComponentScore;
import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.component.socket.match.SocketMatchResult;
import com.formacraft.common.component.socket.place.ComponentInstanceTransform;
import com.formacraft.common.component.socket.place.SocketAnchorResolver;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.common.component.variant.VariantGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AutoAssembler（自动装配器）：Socket × Query × Rank × Variant × Place。
 * <p>
 * 这一步把我们前面所有模块串起来。
 * <p>
 * 完整链路：
 * - Socket → Query（AssemblyPlanner）
 * - Query → Rank（ComponentRanker）
 * - Rank → Variant（VariantGenerator）
 * - Variant → Match（SocketMatcher）
 * - Match → Place（ComponentPlanCompiler）
 */
public final class AutoAssembler {
    private AutoAssembler() {}

    /**
     * 在 Socket 上自动装配构件
     * 
     * @param sockets Socket 列表
     * @param rules 构件规则
     * @param skeletonKind 骨架类型（例如："WALL", "TOWER", "ROAD"）
     * @param styleProfile 风格配置
     * @param materialTone 材质色调
     * @param random 随机数生成器
     * @return 装配结果列表（ComponentDefinition + ComponentVariant + Socket）
     */
    public static List<AssemblyResult> assembleOnSockets(
            List<Socket> sockets,
            SkeletonComponentRules rules,
            String skeletonKind,
            String styleProfile,
            String materialTone,
            Random random
    ) {
        List<AssemblyResult> results = new ArrayList<>();

        List<SkeletonComponentRules.Rule> ruleList = rules.get(skeletonKind);

        for (Socket s : sockets) {
            if (s.occupied) continue;

            // 规则 → Query
            List<ComponentQuery> queries = AssemblyPlanner.toQueries(ruleList, s.type, styleProfile, materialTone);
            if (queries.isEmpty()) continue;

            // v1：对每个 socket 只取第一个 query（后续可按 weight 抽样/多选）
            ComponentQuery q = queries.getFirst();

            // 获取所有候选构件（通过 ComponentRetriever）
            // 使用 ComponentRetriever.retrieve() 会先进行硬过滤和排序
            List<ComponentScore> scoredResults = ComponentRetriever.retrieve(q, 10);
            
            // 转换为 ComponentMetadata（需要重新加载，简化处理：直接使用 ComponentRetriever 的结果）
            // 这里我们直接使用 ComponentRetriever 的结果，因为它已经完成了硬过滤和排序
            if (scoredResults.isEmpty()) continue;
            
            // 获取最佳匹配的构件 ID
            String bestComponentId = scoredResults.getFirst().componentId;

            // 加载 ComponentDefinition（需要 worldDir，v1 使用 null 表示全局目录）
            ComponentDefinition component = ComponentStorage.loadComponent(null, bestComponentId);
            if (component == null) continue;

            // 使用新的详细匹配逻辑验证 Socket
            if (component.placementSpec != null) {
                List<SocketMatchResult> matchResults = com.formacraft.common.component.socket.match.SocketMatcher.match(
                        List.of(s), component.placementSpec, s.center()
                );

                if (matchResults.isEmpty() || !matchResults.getFirst().valid) {
                    continue; // Socket 不匹配，跳过
                }
            }

            // 生成变体
            ComponentVariant variant = VariantGenerator.generate(component, q, random);

            // 解析 Socket 到 Component 实例变换（H3）
            ComponentAnchor anchor = ComponentAnchor.fromDefinition(component.anchor);
            ComponentInstanceTransform transform = SocketAnchorResolver.resolve(
                    s, anchor, component.placementSpec
            );

            // 创建装配结果（包含 transform）
            AssemblyResult result = new AssemblyResult(component, variant, s, transform);
            results.add(result);

            // 标记 Socket 为已占用
            s.occupied = true;
        }

        return results;
    }

    /**
     * 装配结果
     */
    public record AssemblyResult(
            ComponentDefinition component,
            ComponentVariant variant,
            Socket socket,
            ComponentInstanceTransform transform
    ) {}
}
