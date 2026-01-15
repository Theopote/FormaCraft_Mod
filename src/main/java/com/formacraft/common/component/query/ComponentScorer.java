package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.archetype.ComponentArchetype;
import com.formacraft.common.component.archetype.AttachmentSpec;
import com.formacraft.common.component.archetype.VariationSpec;
import com.formacraft.common.component.archetype.ComponentArchetypeStorage;

import java.util.List;

/**
 * ComponentScorer（构件评分器）：计算构件与查询的匹配评分。
 * <p>
 * 注意：这个类保留用于向后兼容，新的代码应该使用 ComponentRanker。
 * <p>
 * ComponentRanker 提供了更详细的多维评分系统。
 */
public final class ComponentScorer {
    private ComponentScorer() {}

    /**
     * 对构件进行评分（向后兼容方法）
     * 
     * @param component 构件定义
     * @param query 查询条件
     * @return 评分结果
     * @deprecated 使用 ComponentRanker.rank() 代替
     */
    @Deprecated
    public static ComponentScore score(ComponentDefinition component, ComponentQuery query) {
        if (component == null || query == null) {
            return new ComponentScore(component != null ? component.id : "unknown");
        }

        // 获取 Archetype（如果有）
        ComponentArchetype archetype = null;
        if (component.id != null) {
            archetype = ComponentArchetypeStorage.get(component.id);
        }

        // 创建元数据
        ComponentMetadata metadata = ComponentMetadata.fromComponent(component, archetype);

        // 使用 ComponentRanker 进行评分
        return ComponentRanker.rank(metadata, component, query);
    }
}
