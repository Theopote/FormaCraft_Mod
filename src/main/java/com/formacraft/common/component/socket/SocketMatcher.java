package com.formacraft.common.component.socket;

/**
 * SocketMatcher（Socket 匹配器）v1：核心匹配算法。
 * <p>
 * 职责：
 * - 判断 Provider × Consumer 是否可以匹配
 * - 快速过滤不兼容的组合
 * <p>
 * 匹配规则（全部满足才算匹配）：
 * 1. role：必须是 PROVIDER × CONSUMER
 * 2. context：必须相同（WALL 只能匹配 WALL）
 * 3. shape：必须相同（RECT 只能匹配 RECT）
 * 4. tags：Provider 的 tags 必须包含 Consumer 的所有 tags
 * 5. size：尺寸约束必须有交集
 */
public final class SocketMatcher {
    private SocketMatcher() {}

    /**
     * 检查 Provider × Consumer 是否可以匹配（核心方法）。
     * <p>
     * 策略：
     * - 先检查快速过滤条件（role/context/shape）
     * - 再检查复杂条件（tags/size）
     * <p>
     * 返回：
     * - true：可以匹配，可以继续定位查询
     * - false：不可匹配，直接跳过
     */
    public static boolean canMatch(ComponentSocket provider, ComponentSocket consumer) {
        if (provider == null || consumer == null) return false;

        // 1. role：必须是 PROVIDER × CONSUMER
        if (provider.role != SocketRole.PROVIDER) return false;
        if (consumer.role != SocketRole.CONSUMER) return false;

        // 2. context：必须相同
        if (provider.context != consumer.context) return false;

        // 3. shape：必须相同
        if (provider.shape != consumer.shape) return false;

        // 4. tags：Provider 必须包含 Consumer 的所有 tags
        if (!provider.hasAllTags(consumer.tags)) return false;

        // 5. size：尺寸约束必须有交集
        if (!provider.size.compatibleWith(consumer.size)) return false;

        return true;
    }

    /**
     * 批量匹配：从 Provider 列表中筛选所有可匹配的。
     */
    public static java.util.List<ComponentSocket> filterCompatibleProviders(
            java.util.List<ComponentSocket> providers,
            ComponentSocket consumer
    ) {
        if (providers == null || providers.isEmpty()) return java.util.List.of();
        if (consumer == null) return java.util.List.of();

        return providers.stream()
                .filter(p -> canMatch(p, consumer))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 计算匹配分数（用于 AI/Tool 排序）。
     * <p>
     * 策略：
     * - tag 重叠度越高，分数越高
     * - 尺寸越接近，分数越高
     * <p>
     * 返回：
     * - 0.0：不匹配
     * - 0.0~1.0：匹配分数（越高越好）
     */
    public static double matchScore(ComponentSocket provider, ComponentSocket consumer) {
        if (!canMatch(provider, consumer)) return 0.0;

        double score = 0.0;

        // tag 重叠度（权重：0.6）
        if (provider.tags != null && consumer.tags != null && !consumer.tags.isEmpty()) {
            long overlap = consumer.tags.stream()
                    .filter(provider::hasTag)
                    .count();
            score += 0.6 * (overlap / (double) consumer.tags.size());
        }

        // 尺寸接近度（权重：0.4）
        if (provider.size != null && consumer.size != null) {
            double sizeScore = calculateSizeScore(provider.size, consumer.size);
            score += 0.4 * sizeScore;
        }

        return Math.min(1.0, score);
    }

    /**
     * 计算尺寸接近度（0.0~1.0）。
     * <p>
     * 策略：
     * - 尺寸完全匹配 → 1.0
     * - 尺寸有交集但不完全匹配 → 0.5~1.0
     * - 尺寸无交集 → 0.0（不应该出现，因为 canMatch 已过滤）
     */
    private static double calculateSizeScore(SizeConstraint providerSize, SizeConstraint consumerSize) {
        if (providerSize == null || consumerSize == null) return 0.5;

        // 计算第一维（宽度/长度）的重叠比例
        int pMin = providerSize.min[0];
        int pMax = providerSize.max[0];
        int cMin = consumerSize.min[0];
        int cMax = consumerSize.max[0];

        int overlapMin = Math.max(pMin, cMin);
        int overlapMax = Math.min(pMax, cMax);
        if (overlapMax < overlapMin) return 0.0; // 无交集

        int overlapLen = overlapMax - overlapMin + 1;
        int pLen = pMax - pMin + 1;
        int cLen = cMax - cMin + 1;
        double ratio = overlapLen / (double) Math.max(pLen, cLen);

        return Math.min(1.0, ratio);
    }
}
