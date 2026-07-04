package com.formacraft.client.ui.panel.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一的 LLM Provider / Base URL 目录（Java 侧单一事实来源）。
 *
 * <p>每个条目同时携带：面板显示用的 {@code id/label/url}、后端识别用的 {@code provider} id，
 * 以及是否为本地部署 {@code local}。这样设置面板选中一个供应商即可一次性写好 provider + baseUrl，
 * 无需用户分别配置两处；本地供应商还可据 {@code local} 免填 API Key。</p>
 *
 * <p>绝大多数供应商都提供 OpenAI 兼容端点（{@code /chat/completions}），因此后端统一走
 * OpenAI SDK + base_url 即可覆盖 OpenAI / DeepSeek / Gemini / Anthropic / Mistral / xAI /
 * OpenRouter / Groq / Together / Moonshot(Kimi) / Zhipu(GLM) / Qwen(DashScope) / SiliconFlow，
 * 以及本地的 Ollama / LM Studio / vLLM / llama.cpp。</p>
 */
public final class SettingsBaseUrlPresets {

    /** 面板显示用的轻量记录（保持向后兼容：仅 id/label/url）。 */
    public record Preset(String id, String label, String url) {}

    /**
     * 完整目录条目。
     *
     * @param id       预设 id（与 provider 通常一致；auto/custom 例外）
     * @param label    面板显示名
     * @param url      默认 Base URL；{@code ""}=自动，{@code null}=自定义（用户手填）
     * @param provider 后端 provider id（{@code null} 表示不覆盖当前 provider，如 custom）
     * @param local    是否本地部署（本地可免 API Key）
     */
    public record Entry(String id, String label, String url, String provider, boolean local) {}

    /** 本地部署 provider（可不填 API Key）。 */
    public static final Set<String> LOCAL_PROVIDERS =
            Set.of("ollama", "lmstudio", "vllm", "llamacpp", "local", "localai");

    /** 统一目录（面板下拉与 provider 联动的唯一来源）。 */
    public static final List<Entry> CATALOG = List.of(
            new Entry("auto", "自动（由后端/Provider 决定）", "", "auto", false),
            // ---- 云端（OpenAI 兼容）----
            new Entry("openai", "OpenAI", "https://api.openai.com/v1", "openai", false),
            new Entry("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek", false),
            new Entry("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini", false),
            new Entry("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "anthropic", false),
            new Entry("openrouter", "OpenRouter（聚合）", "https://openrouter.ai/api/v1", "openrouter", false),
            new Entry("groq", "Groq", "https://api.groq.com/openai/v1", "groq", false),
            new Entry("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral", false),
            new Entry("xai", "xAI Grok", "https://api.x.ai/v1", "xai", false),
            new Entry("together", "Together", "https://api.together.xyz/v1", "together", false),
            new Entry("moonshot", "Moonshot / Kimi", "https://api.moonshot.cn/v1", "moonshot", false),
            new Entry("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "zhipu", false),
            new Entry("qwen", "通义千问（DashScope）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen", false),
            new Entry("siliconflow", "SiliconFlow 硅基流动", "https://api.siliconflow.cn/v1", "siliconflow", false),
            // ---- 本地（免 API Key）----
            new Entry("ollama", "Ollama（本地）", "http://localhost:11434/v1", "ollama", true),
            new Entry("lmstudio", "LM Studio（本地）", "http://127.0.0.1:1234/v1", "lmstudio", true),
            new Entry("vllm", "vLLM（本地）", "http://localhost:8000/v1", "vllm", true),
            new Entry("llamacpp", "llama.cpp（本地）", "http://localhost:8080/v1", "llamacpp", true),
            // ---- 兜底 ----
            new Entry("custom", "自定义…", null, null, false)
    );

    /** 向后兼容：仅 id/label/url 的列表（由 {@link #CATALOG} 派生）。 */
    public static final List<Preset> ALL = deriveAll();

    private static List<Preset> deriveAll() {
        List<Preset> out = new ArrayList<>(CATALOG.size());
        for (Entry e : CATALOG) {
            out.add(new Preset(e.id(), e.label(), e.url()));
        }
        return List.copyOf(out);
    }

    /** provider 是否为本地部署（可免 API Key）。null/空视为非本地。 */
    public static boolean isLocalProvider(String provider) {
        if (provider == null) return false;
        return LOCAL_PROVIDERS.contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    /** 按 id 查目录条目（大小写不敏感）；找不到返回 null。 */
    public static Entry byId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (Entry e : CATALOG) {
            if (e.id().equals(key)) return e;
        }
        return null;
    }

    private SettingsBaseUrlPresets() {}
}
