package com.formacraft.server.assembly;

import com.formacraft.common.llm.dto.CapabilityGap;

import java.util.List;
import java.util.Locale;

/** Bilingual player-facing messages for MetaAssembly capability gaps. */
public final class CapabilityGapMessages {

    private CapabilityGapMessages() {}

    public static String formatPlayerMessage(CapabilityGap gap) {
        if (gap == null) {
            return "【ASSEMBLY 能力缺口】当前 MetaAssembly 无法编译该自由几何。\n—\n[Capability gap] unsupported geometry";
        }
        String code = gap.code() != null && !gap.code().isBlank()
                ? gap.code().trim()
                : "E_CAPABILITY_GAP";
        String zh = localizedSummary(code, gap.message());
        StringBuilder sb = new StringBuilder(256);
        sb.append("【ASSEMBLY 能力缺口】").append(zh);
        List<String> suggestions = gap.suggestions();
        if (suggestions != null && !suggestions.isEmpty()) {
            sb.append("\n建议：");
            int n = Math.min(3, suggestions.size());
            for (int i = 0; i < n; i++) {
                sb.append("\n• ").append(suggestions.get(i));
            }
        }
        sb.append("\n—\n[Capability gap] ").append(code).append(": ").append(gap.summary());
        return sb.toString();
    }

    private static String localizedSummary(String code, String fallbackMessage) {
        String upper = code.toUpperCase(Locale.ROOT);
        String mapped = switch (upper) {
            case "E_CONN_UNKNOWN_PORT" ->
                    "连接端口无效，请使用 ai-assembly-schema.json 中导出的端口名（如 top_center、start）。";
            case "E_CONN_UNKNOWN_COMPONENT" ->
                    "连接引用了不存在的 graph 构件 id。";
            case "E_UNKNOWN_PRESET" ->
                    "未知的 ASSEMBLY preset，请使用 spiral_watchtower / suspension_bridge_simple / gothic_shell_box。";
            case "E_ASSEMBLY_MISSING", "E_ASSEMBLY_EMPTY" ->
                    "ASSEMBLY 缺少 preset、graph.components 或 ops[]。";
            case "E_ASSEMBLY_MISSING_ANCHOR" ->
                    "ASSEMBLY 构件缺少 relative_position 锚点。";
            case "E_ASSEMBLY_COMPILE_EMPTY", "E_ASSEMBLY_EMPTY_OUTPUT" ->
                    "ASSEMBLY 无法编译为可执行的 MetaAssembly ops，或引擎输出为零方块。";
            case "E_NESTED_ASSEMBLY_IN_MASS" ->
                    "请勿在 MASS_* 内嵌套 params.assembly；应使用顶层 component_type=ASSEMBLY。";
            case "E_CAPABILITY_GAP" ->
                    fallbackMessage != null && !fallbackMessage.isBlank()
                            ? fallbackMessage
                            : "请求的几何超出当前 MetaAssembly 能力范围。";
            default -> fallbackMessage != null && !fallbackMessage.isBlank()
                    ? fallbackMessage
                    : "MetaAssembly 无法完成该 ASSEMBLY 计划。";
        };
        return mapped;
    }
}
