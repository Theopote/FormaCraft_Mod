package com.formacraft.common.llm.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.formacraft.common.llm.dto.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM Plan Parser（解析 + 校验）
 * <p>
 * 核心功能：
 * - 解析 LLM 输出的 JSON
 * - 基础校验（必填字段、尺寸合法、相对坐标等）
 * - 支持 build 和 patch 两种模式
 */
public final class LlmPlanParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private LlmPlanParser() {}

    /**
     * 解析 + 校验（失败抛异常，便于你在 ChatPanel 里显示错误）
     */
    public static LlmPlan parseAndValidate(String json) throws PlanParseException {
        LlmPlan plan;
        try {
            plan = MAPPER.readValue(json, LlmPlan.class);
        } catch (JsonProcessingException e) {
            throw new PlanParseException("Invalid JSON: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }

        plan = LlmPlanAnchorNormalizer.normalize(plan);
        validate(plan);
        return plan;
    }

    /**
     * 只解析不校验（一般不建议）
     */
    public static LlmPlan parse(String json) throws PlanParseException {
        try {
            LlmPlan plan = MAPPER.readValue(json, LlmPlan.class);
            return LlmPlanAnchorNormalizer.normalize(plan);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }
    }

    private static void validate(LlmPlan plan) throws PlanParseException {
        if (plan == null) throw new PlanParseException("Plan is null");
        if (plan.mode() == null) throw new PlanParseException("Missing field: mode");
        if (plan.anchor() == null) throw new PlanParseException("Missing field: anchor");

        // build / patch 都允许 style_profile 为空（让后端套默认），但建议有
        if (plan.globalConstraints() == null) {
            // 允许为空：后端给默认
        } else {
            // facing/symmetry/terrain_strategy 都允许为空：后端给默认
        }

        // layout 可选：比如单体建筑不走 slots
        Layout layout = plan.layout();
        if (layout != null) {
            // slots 可为空，但若有则校验 slot_id 唯一、anchor 不为空
            List<Slot> slots = layout.slots();
            if (slots != null && !slots.isEmpty()) {
                Set<String> ids = new HashSet<>();
                for (Slot s : slots) {
                    if (s == null) continue;
                    if (isBlank(s.slotId())) throw new PlanParseException("slot.slot_id is required");
                    if (!ids.add(s.slotId())) throw new PlanParseException("Duplicate slot_id: " + s.slotId());
                    if (s.anchor() == null) throw new PlanParseException("slot.anchor is required (slot_id=" + s.slotId() + ")");
                }
            }
        }

        // components：build 可以为空（让生成器从 slots/preset 推导）；patch 通常应提供
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (isBlank(c.componentType())) throw new PlanParseException("component.component_type is required");
                if (c.relativePosition() == null) throw new PlanParseException("component.relative_position is required");
                if (c.dimensions() == null) throw new PlanParseException("component.dimensions is required");
                
                // 平面组件允许 height = 0（如 COURTYARD、PATH、PLAZA 等）
                // 立面组件允许 depth = 0（如 FACADE_WINDOWS 等）
                String componentType = c.componentType().toUpperCase();
                boolean isPlanarComponent = isPlanarComponentType(componentType);
                boolean isFacadeComponent = isFacadeComponentType(componentType);
                
                // width 必须 > 0
                if (c.dimensions().width() <= 0) {
                    throw new PlanParseException("component.dimensions.width must be > 0 (type=" + c.componentType() + ")");
                }
                
                // depth 必须 > 0，除非是立面组件（立面组件允许 depth = 0）
                if (!isFacadeComponent && c.dimensions().depth() <= 0) {
                    throw new PlanParseException("component.dimensions.depth must be > 0 (type=" + c.componentType() + ")");
                }
                
                // 立面组件的 depth 应该 >= 0（允许 0）
                if (isFacadeComponent && c.dimensions().depth() < 0) {
                    throw new PlanParseException("component.dimensions.depth must be >= 0 for facade components (type=" + c.componentType() + ")");
                }
                
                // 对于平面组件，允许 height = 0；对于其他组件，height 必须 > 0
                if (!isPlanarComponent && c.dimensions().height() <= 0) {
                    throw new PlanParseException("component.dimensions.height must be > 0 (type=" + c.componentType() + ")");
                }
                
                // 平面组件的 height 应该 >= 0（允许 0）
                if (isPlanarComponent && c.dimensions().height() < 0) {
                    throw new PlanParseException("component.dimensions.height must be >= 0 for planar components (type=" + c.componentType() + ")");
                }
            }
        }

        // patch mode 附加约束
        if (plan.mode() == LlmPlan.Mode.patch) {
            // 允许两种 patch：语义组件 patch 或 block patch
            boolean hasComponentPatch = plan.components() != null && !plan.components().isEmpty();
            boolean hasBlockPatch = plan.patch() != null && plan.patch().blocks() != null && !plan.patch().blocks().isEmpty();

            if (!hasComponentPatch && !hasBlockPatch) {
                throw new PlanParseException("mode=patch requires either components[] or patch.blocks[]");
            }
            if (hasBlockPatch) {
                if (plan.patch().origin() == null) throw new PlanParseException("patch.origin is required");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
    
    /**
     * 判断组件类型是否为平面组件（允许 height = 0）
     * <p>
     * 平面组件包括：
     * - COURTYARD（庭院）
     * - PATH（路径）
     * - PLAZA（广场）
     * - FLOOR（地板，如果作为平面使用）
     * - GROUND（地面）
     */
    private static boolean isPlanarComponentType(String componentType) {
        if (componentType == null) return false;
        
        String upper = componentType.toUpperCase();
        return upper.equals("COURTYARD") ||
               upper.equals("PATH") ||
               upper.equals("PLAZA") ||
               upper.equals("FLOOR") ||
               upper.equals("GROUND") ||
               upper.equals("TERRAIN") ||
               upper.equals("PARKING") ||
               upper.equals("GARDEN");
    }
    
    /**
     * 判断组件类型是否为立面组件（允许 depth = 0）
     * <p>
     * 立面组件包括：
     * - FACADE_WINDOWS（立面窗户）
     * - FACADE（立面）
     * - WALL_FACADE（墙体立面）
     */
    private static boolean isFacadeComponentType(String componentType) {
        if (componentType == null) return false;
        
        String upper = componentType.toUpperCase();
        return upper.equals("FACADE_WINDOWS") ||
               upper.equals("FACADE") ||
               upper.equals("WALL_FACADE");
    }
}

