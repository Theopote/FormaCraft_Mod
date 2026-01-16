package com.formacraft.common.llm.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.formacraft.common.llm.dto.PlanProgram;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PlanProgram Parser（解析 + 校验）
 * <p>
 * 核心功能：
 * - 解析 LLM 输出的 PlanProgram JSON
 * - 基础校验（必填字段、zone ID 唯一性、adjacency 合法性等）
 * <p>
 * 设计原则：
 * - LLM 友好：允许字段缺失（提供默认值）
 * - 拓扑优先：重点校验关系（adjacency）而不是几何
 * - 渐进式校验：不强制所有字段都必须完整
 */
public final class PlanProgramParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private PlanProgramParser() {}

    /**
     * 解析 + 校验（失败抛异常）
     */
    public static PlanProgram parseAndValidate(String json) throws PlanParseException {
        final PlanProgram program;
        try {
            program = MAPPER.readValue(json, PlanProgram.class);
        } catch (JsonProcessingException e) {
            throw new PlanParseException("Invalid JSON: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }

        validate(program);
        return program;
    }

    /**
     * 只解析不校验（一般不建议）
     */
    public static PlanProgram parse(String json) throws PlanParseException {
        try {
            return MAPPER.readValue(json, PlanProgram.class);
        } catch (JsonProcessingException e) {
            throw new PlanParseException("Invalid JSON: " + e.getOriginalMessage(), e);
        } catch (Exception e) {
            throw new PlanParseException("Parse error: " + e.getMessage(), e);
        }
    }

    private static void validate(PlanProgram program) throws PlanParseException {
        if (program == null) {
            throw new PlanParseException("PlanProgram is null");
        }

        // Schema 版本检查（可选，但建议有）
        if (program.schema() != null && !program.schema().startsWith("formacraft.plan_program")) {
            throw new PlanParseException("Unknown schema: " + program.schema());
        }

        // Zones 校验
        List<PlanProgram.PlanZone> zones = program.zones();
        if (zones == null || zones.isEmpty()) {
            throw new PlanParseException("PlanProgram must have at least one zone");
        }

        // Zone ID 唯一性
        Set<String> zoneIds = new HashSet<>();
        for (PlanProgram.PlanZone zone : zones) {
            if (zone == null) {
                throw new PlanParseException("Zone cannot be null");
            }
            if (isBlank(zone.id())) {
                throw new PlanParseException("Zone id is required");
            }
            if (!zoneIds.add(zone.id())) {
                throw new PlanParseException("Duplicate zone id: " + zone.id());
            }

            // area_ratio 校验（如果提供，应该在合理范围）
            if (zone.areaRatio() != null) {
                double ratio = zone.areaRatio();
                if (ratio <= 0.0 || ratio > 1.0) {
                    throw new PlanParseException("Zone area_ratio must be in (0, 1]: " + zone.id() + " = " + ratio);
                }
            }
        }

        // Adjacency 校验：所有引用的 zone id 必须存在
        List<List<String>> adjacency = program.adjacency();
        if (adjacency != null) {
            for (List<String> pair : adjacency) {
                if (pair == null || pair.size() != 2) {
                    throw new PlanParseException("Adjacency must be pairs of zone ids: " + pair);
                }
                String id1 = pair.get(0);
                String id2 = pair.get(1);
                if (isBlank(id1) || isBlank(id2)) {
                    throw new PlanParseException("Adjacency zone id cannot be blank: " + pair);
                }
                if (!zoneIds.contains(id1)) {
                    throw new PlanParseException("Adjacency references unknown zone: " + id1);
                }
                if (!zoneIds.contains(id2)) {
                    throw new PlanParseException("Adjacency references unknown zone: " + id2);
                }
            }
        }

        // Circulation 校验：primary_axis 如果提供，必须是有效的 zone id
        PlanProgram.PlanCirculation circulation = program.circulation();
        if (circulation != null && !isBlank(circulation.primaryAxis())) {
            if (!zoneIds.contains(circulation.primaryAxis())) {
                throw new PlanParseException("Circulation primary_axis references unknown zone: " + circulation.primaryAxis());
            }
        }

        // Area ratio 总和校验（可选，但建议总和接近 1.0）
        double totalRatio = zones.stream()
                .filter(z -> z.areaRatio() != null)
                .mapToDouble(PlanProgram.PlanZone::areaRatio)
                .sum();
        if (totalRatio > 0.0 && Math.abs(totalRatio - 1.0) > 0.3) {
            // 警告但不失败（允许 LLM 有误差）
            // 实际使用中可以记录日志
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
