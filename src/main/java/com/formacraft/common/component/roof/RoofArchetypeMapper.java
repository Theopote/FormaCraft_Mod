package com.formacraft.common.component.roof;

import com.formacraft.common.component.RoofArchetype;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.FormacraftMod;

import java.util.*;

/**
 * RoofArchetypeMapper（屋顶构件原型映射器）
 * <p>
 * 🎯 核心职责：
 * 将 SocketType 映射到 RoofArchetype，用于组件装配时的查询和匹配
 * <p>
 * 映射关系：
 * - RIDGE_LINE Socket → RIDGE_DECORATION Archetype
 * - EAVE_LINE Socket → EAVE_DECORATION / EAVE_STRUCTURAL Archetype
 * - ROOF_SURFACE Socket → ROOF_TILE / ROOF_ORNAMENT Archetype
 */
public final class RoofArchetypeMapper {

    private RoofArchetypeMapper() {}

    /**
     * 从 SocketType 推断可能的 RoofArchetype
     * <p>
     * 一个 SocketType 可能对应多个 Archetype（例如 EAVE_LINE 可以对应装饰或结构）
     *
     * @param socketType Socket 类型
     * @return 可能的 RoofArchetype 列表（按优先级排序）
     */
    public static List<RoofArchetype> inferArchetypes(SocketType socketType) {
        if (socketType == null) {
            return List.of();
        }

        return switch (socketType) {
            case RIDGE_LINE -> List.of(
                    RoofArchetype.RIDGE_DECORATION,
                    RoofArchetype.ROOF_ORNAMENT
            );
            case EAVE_LINE -> List.of(
                    RoofArchetype.EAVE_DECORATION,
                    RoofArchetype.EAVE_STRUCTURAL,
                    RoofArchetype.ROOF_ORNAMENT
            );
            case ROOF_SURFACE -> List.of(
                    RoofArchetype.ROOF_TILE,
                    RoofArchetype.ROOF_ORNAMENT
            );
            default -> List.of(); // 其他 SocketType 不映射到 RoofArchetype
        };
    }

    /**
     * 获取主要（推荐）的 RoofArchetype
     * <p>
     * 返回列表中的第一个（优先级最高）
     *
     * @param socketType Socket 类型
     * @return 主要的 RoofArchetype，如果不支持则返回 null
     */
    public static RoofArchetype getPrimaryArchetype(SocketType socketType) {
        List<RoofArchetype> archetypes = inferArchetypes(socketType);
        return archetypes.isEmpty() ? null : archetypes.getFirst();
    }

    /**
     * 检查 SocketType 是否支持 RoofArchetype
     *
     * @param socketType Socket 类型
     * @return 是否支持
     */
    public static boolean supportsRoofArchetype(SocketType socketType) {
        return socketType == SocketType.RIDGE_LINE ||
               socketType == SocketType.EAVE_LINE ||
               socketType == SocketType.ROOF_SURFACE;
    }

    /**
     * 创建语义标签（用于 Socket 的 semanticTag）
     * <p>
     * 格式：roof_{archetype.name().toLowerCase()}
     * 例如：roof_ridge_decoration
     *
     * @param archetype RoofArchetype
     * @return 语义标签字符串
     */
    public static String createSemanticTag(RoofArchetype archetype) {
        if (archetype == null) {
            return "roof";
        }
        return "roof_" + archetype.name().toLowerCase();
    }

    /**
     * 从语义标签解析 RoofArchetype
     * <p>
     * 格式：roof_{archetype.name().toLowerCase()}
     *
     * @param semanticTag 语义标签
     * @return RoofArchetype，如果无法解析则返回 null
     */
    public static RoofArchetype parseFromSemanticTag(String semanticTag) {
        if (semanticTag == null || !semanticTag.startsWith("roof_")) {
            return null;
        }

        String archetypeName = semanticTag.substring(5).toUpperCase(); // 去掉 "roof_" 前缀
        try {
            return RoofArchetype.valueOf(archetypeName);
        } catch (IllegalArgumentException e) {
            FormacraftMod.LOGGER.debug("RoofArchetypeMapper: Cannot parse archetype from tag: {}", semanticTag);
            return null;
        }
    }
}
