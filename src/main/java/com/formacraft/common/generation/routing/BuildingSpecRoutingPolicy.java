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
     * 收到 LlmPlan 后是否跳过构件预览，直接走 {@code GenerationHub.routeStructure()}。
     * <p>
     * P1：不再凭关键词（如「四合院」）跳过；仅 {@code outputFormat=buildingspec} 时跳过。
     */
    public static boolean shouldSkipLlmPlanPreview(FormaRequest req) {
        if (req == null) {
            return false;
        }
        String fmt = req.getOutputFormat();
        if (fmt != null && "buildingspec".equalsIgnoreCase(fmt.trim())) {
            return true;
        }
        return false;
    }

    /**
     * 提取“用户真实意图文本”，用于关键词路由判定。
     * <p>
     * <b>务必不要直接用 {@link FormaRequest#getRequestText()}</b>：服务端在发送前已把整段
     * 工程化 System Prompt 写入该字段，其中的 landmark 目录恒定包含
     * {@code "mingqing_courtyard (明清官式院落 / courtyard)"} 一行——含 {@code ming/qing/明清/官式/courtyard}
     * 等关键词，会让 {@link #isMingQingCourtyardIntent} 对任意 BUILD 请求恒为真，
     * 从而误跳过 LlmPlan 预览、导致“已跳过假 BuildingSpec 回退”报错。
     * <p>
     * 优先级：{@code userMessage} → 最近一条聊天记录 → 兜底 {@code requestText}。
     */
    static String intentText(FormaRequest req) {
        if (req == null) {
            return "";
        }
        String userMessage = req.getUserMessage();
        if (userMessage != null && !userMessage.isBlank()) {
            return userMessage;
        }
        try {
            java.util.List<String> history = req.getChatHistory();
            if (history != null && !history.isEmpty()) {
                String last = history.get(history.size() - 1);
                if (last != null && !last.isBlank()) {
                    return last;
                }
            }
        } catch (Exception ignored) {
            // 聊天记录不可用时忽略，继续兜底
        }
        String requestText = req.getRequestText();
        return requestText != null ? requestText : "";
    }

    /**
     * 提取用户真实意图文本（供日志、路由、指标使用）。
     *
     * @see #intentText(FormaRequest)
     */
    public static String userIntentText(FormaRequest req) {
        return intentText(req);
    }

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
        String normalized = normalize(intentText(req));
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
