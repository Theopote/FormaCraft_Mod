package com.formacraft.common.generation.routing;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 单体 {@link BuildingSpec} 路由策略（LlmPlan vs 整栋生成器）。
 * <p>
 * Phase P1：默认走 LlmPlan + {@code landmark:&lt;module_id&gt;} 引用精品库；
 * 仅在后端/客户端<b>显式</b>要求 BuildingSpec 整栋链路时才跳过 LlmPlan 预览。
 *
 * @see com.formacraft.server.network.BuildRequestProcessor
 * @see com.formacraft.server.network.LlmPlanPreviewBuilder
 */
public final class BuildingSpecRoutingPolicy {

    /** {@link com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder} 路由键（template → courtyard_compound） */
    public static final String TEMPLATE_MINGQING_COURTYARD = "mingqing_courtyard";

    private BuildingSpecRoutingPolicy() {}

    /**
     * 在整栋生成前为 {@link BuildingSpec} 写入路由提示（不覆盖 LLM / 用户已显式设置的字段）。
     * <p>
     * 仅写入 {@code extra.template} 提示，不再自动设置旧 force 标记。
     *
     * @return {@code true} 若修改了 spec
     */
    public static boolean applySpecDefaults(BuildingSpec spec, FormaRequest req) {
        if (spec == null) {
            return false;
        }
        String normalized = normalize(com.formacraft.common.model.request.RequestIntentText.resolve(req));
        boolean changed = false;

        if (isMingQingCourtyardIntent(normalized) && !hasTemplate(spec, TEMPLATE_MINGQING_COURTYARD)) {
            Map<String, Object> extra = ensureExtra(spec);
            if (!extra.containsKey("template")) {
                extra.put("template", TEMPLATE_MINGQING_COURTYARD);
                changed = true;
                FormacraftMod.LOGGER.debug(
                        "BuildingSpecRoutingPolicy: set extra.template={} for courtyard intent",
                        TEMPLATE_MINGQING_COURTYARD
                );
            }
        }

        return changed;
    }

    static String normalize(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            return "";
        }
        return requestText.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 明清官式院落意图（收紧）：需 module_id / 官式语汇 + 院落语汇同时命中，
     * 不再因单独的 {@code ming}/{@code qing}/{@code courtyard} 触发。
     */
    static boolean isMingQingCourtyardIntent(String normalizedRequestText) {
        if (normalizedRequestText == null || normalizedRequestText.isBlank()) {
            return false;
        }
        if (normalizedRequestText.contains("mingqing_courtyard")
                || normalizedRequestText.contains("明清官式")
                || normalizedRequestText.contains("明清院落")
                || normalizedRequestText.contains("ming qing courtyard")) {
            return true;
        }
        boolean courtyard = normalizedRequestText.contains("四合院")
                || normalizedRequestText.contains("宅院")
                || (normalizedRequestText.contains("院落") && !normalizedRequestText.contains("院落群"));
        boolean period = normalizedRequestText.contains("明清") || normalizedRequestText.contains("官式");
        return courtyard && period;
    }

    private static boolean hasTemplate(BuildingSpec spec, String templateId) {
        return extraValueContains(spec.getExtra(), "template", templateId);
    }

    private static boolean extraValueContains(Map<String, Object> extra, String key, String expected) {
        if (extra == null || key == null || expected == null) {
            return false;
        }
        Object value = extra.get(key);
        if (value == null) {
            return false;
        }
        return expected.equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static Map<String, Object> ensureExtra(BuildingSpec spec) {
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) {
            extra = new HashMap<>();
            spec.setExtra(extra);
        }
        return extra;
    }
}
