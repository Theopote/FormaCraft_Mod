package com.formacraft.common.network;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.request.FormaRequest;

/**
 * Turns orchestrator / LLM failures into user-facing chat messages.
 */
public final class OrchestratorErrorHumanizer {
    private static final FcaLog LOG = FcaLog.of("OrchestratorErrorHumanizer");

    private OrchestratorErrorHumanizer() {}

    public static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException) {
            Throwable c = cur.getCause();
            if (c == null) break;
            cur = c;
        }
        return cur;
    }

    public static String humanize(String stage, FormaRequest req, Throwable ex) {
        Throwable root = rootCause(ex);
        String rawMsg = root == null ? "" : String.valueOf(root.getMessage());
        if (rawMsg == null) rawMsg = "";

        String connectionError = detectConnectionError(root, rawMsg);
        if (connectionError != null) {
            String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
            return String.format(
                    """
                            无法连接到后端服务（%s）。
                            %s
                            请检查：
                            1. Python 后端是否正在运行（运行：cd python_backend && uvicorn app.main:app --reload）
                            2. 后端地址是否正确（当前：%s，可在设置中修改）
                            3. 防火墙是否允许连接
                            4. 如果后端在其他机器，确保端口已开放且可访问""",
                    stage != null && !stage.isBlank() ? stage : "请求失败",
                    connectionError,
                    endpoint
            );
        }

        String body = tryExtractBodyJson(rawMsg);
        String detail = tryExtractDetailFromBody(body);

        String best = summarizeKnownBillingOrAuthIssues((detail != null ? detail : rawMsg));
        if (best != null) {
            String header = "LLM 调用失败"
                    + (stage != null && !stage.isBlank() ? "（" + stage + "）" : "")
                    + "。";
            String hint = llmHint(req);
            return header + "\n原因：" + best + "\n建议：FormaCraft 设置 → LLM，核对 Provider / API Key / 模型，或切换至有余额的服务。"
                    + "\n" + hint;
        }

        if (detail != null && isPreHumanizedBackendDetail(detail)) {
            return detail;
        }

        String header = (stage == null || stage.isBlank()) ? "后端请求失败。" : ("后端请求失败（" + stage + "）。");
        String hint = llmHint(req);

        String d = (detail != null && !detail.isBlank()) ? detail : rawMsg;
        if (d.length() > 360) d = d.substring(0, 360) + "...";
        String tail = d.isBlank() ? "" : ("\n细节：" + d);

        return header + "\n" + hint + tail;
    }

    private static String llmHint(FormaRequest req) {
        if (req == null) return "";
        String provider = (req.getLlmProvider() == null || req.getLlmProvider().isBlank()) ? "auto" : req.getLlmProvider().trim();
        String model = (req.getModel() == null || req.getModel().isBlank()) ? "auto" : req.getModel().trim();
        String base = (req.getLlmBaseUrl() == null || req.getLlmBaseUrl().isBlank()) ? "" : (" @ " + req.getLlmBaseUrl().trim());
        return "当前 LLM：" + provider + "/" + model + base;
    }

    private static String tryExtractBodyJson(String message) {
        if (message == null) return null;
        int idx = message.indexOf(" body=");
        if (idx < 0) idx = message.indexOf("body=");
        if (idx < 0) return null;
        int start = message.indexOf('{', idx);
        if (start < 0) return null;
        return message.substring(start).trim();
    }

    private static String tryExtractDetailFromBody(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) JsonUtil.get().fromJson(bodyJson, java.util.Map.class);
            if (m == null) return null;
            Object d = m.get("detail");
            return d == null ? null : String.valueOf(d);
        } catch (Throwable ex) {
            LOG.debug("extract orchestrator error detail failed", ex);
            return null;
        }
    }

    private static String summarizeKnownBillingOrAuthIssues(String detailOrMsg) {
        if (detailOrMsg == null) return null;
        String s = detailOrMsg.toLowerCase();

        if (s.contains("credit balance is too low") || s.contains("plans & billing")) {
            return "Anthropic（Claude）账户余额不足。请前往 console.anthropic.com 充值，或在游戏设置中更换 Provider / API Key。";
        }
        if (s.contains("insufficient balance") || s.contains("balance is too low") || s.contains("error code: 402")) {
            if (s.contains("deepseek")) {
                return "DeepSeek 余额不足（402 Insufficient Balance）。请充值或更换有余额的 DeepSeek API Key。";
            }
            return "LLM 账户余额不足（402）。请充值或更换有余额的 API Key。";
        }
        if (s.contains("insufficient_quota") || s.contains("exceeded your current quota")
                || (s.contains("error code: 429") && s.contains("quota"))) {
            return "OpenAI 额度/配额不足（429 insufficient_quota）。请检查 OpenAI 账单/余额/组织配额，或更换有额度的 API Key。";
        }
        if (s.contains("error code: 403") || s.contains("permission denied") || s.contains("forbidden")
                || s.contains("access denied") || s.contains("not allowed to access")) {
            return "API 访问被拒绝（403）。请检查 Key 权限、组织策略，或该模型/端点是否对你开放。";
        }
        if (s.contains("invalid_api_key") || s.contains("incorrect api key") || s.contains("invalid x-api-key")
                || s.contains("authentication failed") || s.contains("error code: 401")
                || (s.contains("unauthorized") && (s.contains("api") || s.contains("key") || s.contains("auth")))) {
            return "API Key 无效或未授权（401）。请检查 Key 是否正确、是否过期，以及 Provider 是否与 Key 匹配。";
        }
        if (s.contains("model_not_found") || s.contains("no such model") || s.contains("error code: 404")) {
            return "模型不存在/不可用（404）。请在设置中更换可用模型，或点击“刷新模型列表”选择。";
        }
        if (s.contains("rate limit") || s.contains("too many requests")) {
            return "请求过于频繁（限流）。请稍后重试，或降低请求频率/更换模型。";
        }
        return null;
    }

    /** Python 后端已 humanize 的 detail，直接展示，避免再包一层「后端请求失败」。 */
    private static boolean isPreHumanizedBackendDetail(String detail) {
        if (detail == null || detail.isBlank()) return false;
        if (detail.startsWith("LLM 调用失败")) return true;
        if (detail.contains("当前 LLM：") && detail.contains("建议：FormaCraft 设置 → LLM")) return true;
        return (detail.contains("Anthropic（Claude）") && detail.contains("余额不足"))
                || detail.contains("DeepSeek 余额不足")
                || detail.contains("OpenAI 额度")
                || detail.contains("LLM 账户余额不足")
                || detail.contains("API Key 无效")
                || detail.contains("API 访问被拒绝");
    }

    private static String detectConnectionError(Throwable root, String rawMsg) {
        if (root == null) return null;

        String className = root.getClass().getSimpleName();
        String msg = rawMsg.toLowerCase();

        if (className.contains("ConnectException") || msg.contains("connection refused")
                || msg.contains("connect refused") || msg.contains("connection reset")) {
            return "连接被拒绝：后端服务可能未启动";
        }
        if (className.contains("UnknownHostException") || msg.contains("unknown host")
                || msg.contains("name or service not known")) {
            return "无法解析主机名：请检查后端地址是否正确";
        }
        if (className.contains("ConnectTimeoutException") || msg.contains("connection timed out")
                || msg.contains("connect timeout")) {
            return "连接超时：后端可能未响应，或网络有问题";
        }
        if (className.contains("SocketTimeoutException") || msg.contains("read timed out")
                || msg.contains("socket timeout")) {
            return "读取超时：后端响应时间过长";
        }
        if (className.contains("HttpConnectTimeoutException")) {
            return "HTTP 连接超时：无法连接到后端";
        }
        return null;
    }
}
