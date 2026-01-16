package com.formacraft.common.llm.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.formacraft.common.llm.dto.PlanSkeleton;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PlanSkeleton Parser（解析 + 校验）
 * <p>
 * 核心功能：
 * - 解析 LLM 或系统生成的 PlanSkeleton JSON
 * - 基础校验（zone ID 唯一性、edge 引用合法性等）
 * <p>
 * 设计原则：
 * - 支持 AI 生成和系统生成
 * - 重点校验语义关系（edges, courtyards, axes）
 * - 渐进式校验：允许部分字段缺失
 */
public final class PlanSkeletonParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private PlanSkeletonParser() {}

    /**
     * 解析 + 校验（失败抛异常）
     */
    public static PlanSkeleton parseAndValidate(String json) throws PlanParseException {
        final PlanSkeleton skeleton;
        try {
            skeleton = MAPPER.readValue(json, PlanSkeleton.class);
        } catch (JsonProcessingException e) {
            throw new PlanParseException("Invalid JSON: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }

        validate(skeleton);
        return skeleton;
    }

    /**
     * 只解析不校验（一般不建议）
     */
    public static PlanSkeleton parse(String json) throws PlanParseException {
        try {
            return MAPPER.readValue(json, PlanSkeleton.class);
        } catch (JsonProcessingException e) {
            throw new PlanParseException("Invalid JSON: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }
    }

    private static void validate(PlanSkeleton skeleton) throws PlanParseException {
        if (skeleton == null) {
            throw new PlanParseException("PlanSkeleton is null");
        }

        // Schema 版本检查（可选）
        if (skeleton.schema() != null && !skeleton.schema().startsWith("formacraft.plan_skeleton")) {
            throw new PlanParseException("Unknown schema: " + skeleton.schema());
        }

        // Zones 校验
        List<PlanSkeleton.PlanZone> zones = skeleton.zones();
        if (zones == null || zones.isEmpty()) {
            throw new PlanParseException("PlanSkeleton must have at least one zone");
        }

        // Zone ID 唯一性
        Set<String> zoneIds = new HashSet<>();
        for (PlanSkeleton.PlanZone zone : zones) {
            if (zone == null) {
                throw new PlanParseException("Zone cannot be null");
            }
            if (isBlank(zone.id())) {
                throw new PlanParseException("Zone id is required");
            }
            if (!zoneIds.add(zone.id())) {
                throw new PlanParseException("Duplicate zone id: " + zone.id());
            }
        }

        // Edges 校验：所有引用的 zone id 必须存在
        List<PlanSkeleton.Edge> edges = skeleton.edges();
        if (edges != null) {
            for (PlanSkeleton.Edge edge : edges) {
                if (edge == null) continue;
                if (isBlank(edge.id())) {
                    throw new PlanParseException("Edge id is required");
                }
                if (edge.zones() != null) {
                    for (String zoneId : edge.zones()) {
                        if (!zoneIds.contains(zoneId)) {
                            throw new PlanParseException("Edge references unknown zone: " + zoneId + " (edge: " + edge.id() + ")");
                        }
                    }
                }
            }
        }

        // Courtyards 校验：所有引用的 zone id 必须存在
        List<PlanSkeleton.Courtyard> courtyards = skeleton.courtyards();
        if (courtyards != null) {
            for (PlanSkeleton.Courtyard courtyard : courtyards) {
                if (courtyard == null) continue;
                if (isBlank(courtyard.id())) {
                    throw new PlanParseException("Courtyard id is required");
                }
                if (courtyard.adjacentZones() != null) {
                    for (String zoneId : courtyard.adjacentZones()) {
                        if (!zoneIds.contains(zoneId)) {
                            throw new PlanParseException("Courtyard references unknown zone: " + zoneId + " (courtyard: " + courtyard.id() + ")");
                        }
                    }
                }
            }
        }

        // Axes 校验：所有引用的 zone id 必须存在
        List<PlanSkeleton.Axis> axes = skeleton.axes();
        if (axes != null) {
            for (PlanSkeleton.Axis axis : axes) {
                if (axis == null) continue;
                if (isBlank(axis.id())) {
                    throw new PlanParseException("Axis id is required");
                }
                if (axis.zones() != null) {
                    for (String zoneId : axis.zones()) {
                        if (!zoneIds.contains(zoneId)) {
                            throw new PlanParseException("Axis references unknown zone: " + zoneId + " (axis: " + axis.id() + ")");
                        }
                    }
                }
            }
        }

        // Zone connected_to 校验：所有引用的 zone id 必须存在
        for (PlanSkeleton.PlanZone zone : zones) {
            if (zone.connectedTo() != null) {
                for (String connectedId : zone.connectedTo()) {
                    if (!zoneIds.contains(connectedId)) {
                        throw new PlanParseException("Zone connected_to references unknown zone: " + connectedId + " (zone: " + zone.id() + ")");
                    }
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
