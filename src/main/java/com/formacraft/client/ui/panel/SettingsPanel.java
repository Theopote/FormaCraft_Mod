package com.formacraft.client.ui.panel;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import com.formacraft.config.SettingsConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/**
 * FormaCraft 设置面板（HUD 左侧栏）
 * <p>
 * Settings panel for FormaCraft HUD overlay.
 * Handles configuration for API keys, model selection, temperature, and font size.
 */
public class SettingsPanel extends BasePanel implements SettingsPanelRenderHost {

    private static final FcaLog LOG = FcaLog.of("SettingsPanel");


    private final MinecraftClient client = MinecraftClient.getInstance();

    // 输入组件（HUD 模式，不依赖 Screen）
    private final SettingsBackendSection backendSection = new SettingsBackendSection(this);
    private final SettingsChoicePanel choices = new SettingsChoicePanel();
    private final SettingsDetailsPanel details = new SettingsDetailsPanel();
    private final SettingsUiBindings ui = new SettingsUiBindings();
    private final java.util.EnumMap<SettingsGroup,ButtonWidget> groupButtons = new java.util.EnumMap<>(SettingsGroup.class);
    private final java.util.EnumSet<SettingsGroup> collapsed = java.util.EnumSet.of(SettingsGroup.BACKEND);
    private final HudTextInput reachInput = new HudTextInput(), temperatureInput = new HudTextInput();
    private ButtonWidget statusButton;
    private long searchRevision;
    private SearchIdentity searchIdentity;
    private record SearchIdentity(String backend,String provider,String key,String cx) {}
    private long connectionRevision;
    private record ConnectionIdentity(String backend, String key, String provider, String baseUrl) {}
    private ConnectionIdentity connectionIdentity;
    private boolean modelNeedsConfirmation;
    private final HudTextInput orchestratorInput = new HudTextInput();
    private final HudTextInput apiKeyInput = new HudTextInput();
    private final HudTextInput llmBaseUrlInput = new HudTextInput();
    private final HudTextInput modelInput = new HudTextInput();
    private final HudTextInput searchApiKeyInput = new HudTextInput();
    private final HudTextInput googleCseCxInput = new HudTextInput();
    // 默认显示明文（用户可以手动点 Hide 隐藏）
    private boolean hideKey = false;

    // 草稿态（UI）——只有 Save 时才写入 SettingsConfig
    // 允许为空：表示“让后端自行决定模型”
    private String draftModel = "";
    /** auto / deepseek / openai / openai_compat / ollama */
    private String draftLlmProvider = "auto";
    /** OpenAI-compatible base URL（可为空，表示由后端环境变量/默认决定） */
    private String draftLlmBaseUrl = "";
    /** 调试：是否在聊天面板显示后端 debugWarnings */
    private boolean draftShowDebugWarnings = false;
    /** auto / duckduckgo / bing / google_cse / wikipedia_only */
    private String draftSearchProvider = "auto";
    private float draftTemperature = 0.7f;
    private int draftInteractionReach = DEFAULT_INTERACTION_REACH;

    // 简易提示（保存成功/失败）
    private String toast = null;
    private boolean isToastError = false;

    // 原版风格控件（参考 Pushdozer：ButtonWidget / SliderWidget）
    private ButtonWidget showHideButton;
    private ButtonWidget pasteButton;
    private ButtonWidget testKeyButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget resetButton;
    private ButtonWidget detectModelButton;
    private ButtonWidget modelChoicesButton;
    private ButtonWidget llmProviderButton;
    private ButtonWidget llmBaseUrlPresetButton;
    private ButtonWidget debugWarningsButton;
    ButtonWidget searchProviderButton;
    ButtonWidget testSearchKeyButton;
    private SettingsSliders.Temperature temperatureSlider;
    private SettingsSliders.InteractionReach interactionReachSlider;
    private SliderWidget activeSlider = null; // 只允许同时操作一个滑条

    // 地址预设只负责显式填充；输入框始终允许编辑。
    // 统一来源：SettingsBaseUrlPresets.CATALOG —— 选中预设时会同时写好 provider + baseUrl。
    private record BaseUrlPreset(String id, String label, String url, String provider, boolean local) {}
    private static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();

    private static List<BaseUrlPreset> buildPresetsFromCatalog() {
        List<BaseUrlPreset> out = new ArrayList<>(SettingsBaseUrlPresets.CATALOG.size());
        for (SettingsBaseUrlPresets.Entry e : SettingsBaseUrlPresets.CATALOG) {
            out.add(new BaseUrlPreset(e.id(), e.label(), e.url(), e.provider(), e.local()));
        }
        return List.copyOf(out);
    }
    private int baseUrlPresetIndex = 0;

    // 模型探测（HTTP）
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private volatile boolean detectingModel = false;
    private volatile boolean validatingKey = false;
    private volatile boolean validatingSearchKey = false;

    private record DetectResponse(int status, String body) {
    }

    // 缓存的计算值（性能优化）

    // 面板滚动（Settings 内容较多时允许滚轮上下滚动）
    private int scrollY = 0;
    private int maxScrollY = 0;

    // 模型选择：候选列表（来自 /models）
    private List<String> availableModels = new ArrayList<>();
    private long availableModelsUpdatedAtMs = 0L;

    public SettingsPanel() {
        SettingsConfig.load();
        loadFromConfig();
        initWidgets();
    }

    private void loadFromConfig() {
        blurInputs();
        choices.close();
        details.close();
        searchRevision++;
        validatingSearchKey = false;
        connectionRevision++;
        SettingsConfigCoordinator.load(this);
        connectionIdentity = currentConnectionIdentity();
        searchIdentity = currentSearchIdentity();
        detectingModel = false;
        validatingKey = false;
        if (testKeyButton != null) testKeyButton.setMessage(Text.literal("校验 Key"));
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        modelNeedsConfirmation = false;
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    private static int clampReach(int v) {
        if (v < MIN_INTERACTION_REACH) return MIN_INTERACTION_REACH;
        return Math.min(v, MAX_INTERACTION_REACH);
    }

    @Override
    protected void drawContents(DrawContext ctx) {
        ensureWidgets();
        syncConnectionIdentity(); syncSearchIdentity();
        int x=panelX+CONTENT_PADDING, w=panelWidth-CONTENT_PADDING*2;
        int contentTop=getContentY()+CONTENT_PADDING;
        int footerY=panelY+panelHeight-SettingsActionsSection.HEIGHT-CONTENT_PADDING;
        int clipBottom=Math.max(contentTop+1,footerY-4);
        ui.begin(getContentY()+1,clipBottom);
        drawContentBackground(ctx);
        int y=contentTop-scrollY;
        ctx.enableScissor(panelX+1,getContentY()+1,panelX+panelWidth-1,clipBottom);
        try {
            for (var group:SettingsGroup.values()) {
                ButtonWidget header=groupButtons.get(group);
                header.setMessage(Text.literal((collapsed.contains(group)?"▶ ":"▼ ")+group.label));
                ui.widget(ctx,client,header,x,y,w,BUTTON_HEIGHT); y+=BUTTON_ROW_HEIGHT;
                if (collapsed.contains(group)) continue;
                y=switch(group) {
                    case CONNECTION -> SettingsConnectionSection.drawSection(this,ctx,x,y,w);
                    case SEARCH -> SettingsSearchSection.drawSection(this,ctx,x,y,w);
                    case PREFERENCES -> SettingsPreferencesSection.drawSection(this,ctx,x,y,w);
                    case BACKEND -> backendSection.draw(ctx,x,y,w);
                };
                y+=GROUP_GAP;
            }
        } finally { ctx.disableScissor(); }
        maxScrollY=Math.max(0,y+scrollY-contentTop-(clipBottom-contentTop));
        scrollY=Math.max(0,Math.min(scrollY,maxScrollY));
        ctx.fill(panelX+1,footerY-3,panelX+panelWidth-1,panelY+panelHeight-1,0xFF171B24);
        int statusY=SettingsActionsSection.drawSection(this,ctx,x,footerY,w);
        String message=toast==null?"修改后保存；取消撤销草稿":toast;
        statusButton.setMessage(Text.literal(client.textRenderer.trimToWidth((isToastError?"错误：":"")+message+" [详情]",w-8)));
        ui.widget(ctx,client,statusButton,x,statusY,w,BUTTON_HEIGHT);
        ui.fixed(false); ui.finish();
        choices.render(ctx,client,panelX+panelWidth,getContentY());
        details.render(ctx,client,panelX+panelWidth,getContentY());
    }

    // =======================
    //   鼠标交互
    // =======================

    @Override
    public boolean mouseClicked(double mouseX,double mouseY,int button) {
        if(details.isOpen()) { details.click(mouseX,mouseY,button); return true; }
        if(choices.isOpen()) { choices.click(mouseX,mouseY,button); return true; }
        if(super.mouseClicked(mouseX,mouseY,button)) return true;
        return SettingsInputController.handleClick(this,mouseX,mouseY,button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return SettingsInputController.handleDrag(this, mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return SettingsInputController.handleRelease(this, mouseX, mouseY, button);
    }

    @Override
    public void mouseScrolled(double mouseX, double mouseY, double amount) {
        if (details.isOpen()) { details.scroll(amount); return; }
        if (choices.isOpen()) { choices.scroll(amount); return; }
        SettingsInputController.handleScroll(this, mouseX, mouseY, amount);
    }

    @Override
    protected boolean drawCustomTooltip(DrawContext ctx,double mouseX,double mouseY) {
        if(details.isOpen()) return true;
        String tip=choices.isOpen()?choices.hoveredLabel(mouseX,mouseY):ui.tooltip(mouseX,mouseY);
        if(tip==null) return false;
        drawTooltipCompat(ctx,List.of(Text.literal(tip)),(int)mouseX,(int)mouseY);
        return true;
    }

    // =======================
    // 原版控件：初始化与同步
    // =======================

    private void initWidgets() {
        llmBaseUrlInput.setPlaceholder("https://服务地址/v1");
        reachInput.setMaxLength(4); temperatureInput.setMaxLength(8);
        for(var group:SettingsGroup.values()) groupButtons.put(group,ButtonWidget.builder(Text.literal(group.label),b -> {
            if(!collapsed.add(group)) collapsed.remove(group);
            blurInputs();
        }).dimensions(0,0,100,BUTTON_HEIGHT).build());
        statusButton=ButtonWidget.builder(Text.literal("状态详情"),b -> openDetails(toast==null?"普通设置仅在保存后生效。取消会撤销草稿。后端启动、停止与检查立即执行。":toast))
                .dimensions(0,0,100,BUTTON_HEIGHT).build();
        // API Key：Show/Hide
        showHideButton = ButtonWidget.builder(getShowHideText(), b -> toggleHideKey())
                .dimensions(0, 0, SHOW_HIDE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.show_hide")))
                .build();

        // API Key：Paste
        pasteButton = ButtonWidget.builder(Text.translatable("formacraft.settings.paste"), b -> {
                    apiKeyInput.setFocused(true);
                    orchestratorInput.setFocused(false);
                    apiKeyInput.paste();
                })
                .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.paste")))
                .build();

        testKeyButton = ButtonWidget.builder(Text.literal("校验 Key"), b -> startValidateKey())
            .dimensions(0, 0, PASTE_BUTTON_WIDTH, INPUT_HEIGHT)
            .tooltip(Tooltip.of(Text.literal("调用后端 /models 快速校验当前 Key 是否可用")))
            .build();

        // Model list refresh (calls /models)
        detectModelButton = ButtonWidget.builder(getDetectModelButtonText(), b -> startDetectModel())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("刷新模型列表（调用后端 /models；失败时仍可手动输入模型名）")))
                .build();

        modelChoicesButton = ButtonWidget.builder(Text.literal("选择 / 输入…"), b -> openModels())
                .dimensions(0, 0, 0, BUTTON_HEIGHT).build();

        llmProviderButton = ButtonWidget.builder(getLlmProviderButtonText(), b -> openProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 LLM Provider（DeepSeek 优先；其它 OpenAI-compatible 也可用）")))
                .build();

        llmBaseUrlPresetButton = ButtonWidget.builder(getBaseUrlPresetButtonText(), b -> openBaseUrlPresets())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("选择 Base URL 预设；下方地址始终可手动编辑")))
                .build();

        debugWarningsButton = ButtonWidget.builder(getDebugWarningsButtonText(), b -> toggleDebugWarnings())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调试：将后端 debugWarnings 追加显示到聊天面板（便于调 prompt / 纠错链路）")))
                .build();

        searchProviderButton = ButtonWidget.builder(getSearchProviderButtonText(), b -> openSearchProviders())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("建筑研究联网搜索：选择搜索引擎或 API")))
                .build();

        testSearchKeyButton = ButtonWidget.builder(Text.literal("测试搜索配置"), b -> startValidateSearchKey())
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("调用后端 /search/validate 探测当前搜索引擎配置")))
                .build();

        // Sliders（用原版 SliderWidget 渲染）
        temperatureSlider = new SettingsSliders.Temperature(this, 0, 0, 0, INPUT_HEIGHT, Text.empty(), clamp01(draftTemperature));
        interactionReachSlider = new SettingsSliders.InteractionReach(this, 0, 0, 0, INPUT_HEIGHT, Text.empty(), reachToValue(draftInteractionReach));
        temperatureSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.temperature")));
        interactionReachSlider.setTooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.interaction_reach")));

        // Buttons row
        saveButton = ButtonWidget.builder(Text.translatable("formacraft.settings.save"), b -> {
                    if (saveSettings()) {
                        blurInputs();
                    }
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.save")))
                .build();
        cancelButton = ButtonWidget.builder(Text.translatable("formacraft.settings.cancel"), b -> {
                    loadFromConfig();
                    blurInputs();
                    showToast(Text.translatable("formacraft.settings.cancelled").getString());
                })
                .dimensions(0, 0, 0, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("formacraft.settings.tooltip.cancel")))
                .build();
        resetButton = ButtonWidget.builder(Text.literal("恢复分组默认…"),b -> openResetGroups())
                .dimensions(0,0,0,BUTTON_HEIGHT).build();

        syncWidgetStateFromDraft();
    }

    private Text getDebugWarningsButtonText() {
        return Text.literal("调试信息：" + (draftShowDebugWarnings ? "开启" : "关闭"));
    }

    private void toggleDebugWarnings() {
        draftShowDebugWarnings = !draftShowDebugWarnings;
        if (debugWarningsButton != null) {
            debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        showToast("DebugWarnings=" + (draftShowDebugWarnings ? "ON" : "OFF"), false);
    }

    private Text getDetectModelButtonText() {
        if (detectingModel) {
            return Text.literal("刷新中...");
        }
        int n = (availableModels == null) ? 0 : availableModels.size();
        return Text.literal(n > 0 ? ("刷新模型列表（" + n + "）") : "刷新模型列表");
    }

    private Text getLlmProviderButtonText() {
        String p = (draftLlmProvider == null || draftLlmProvider.isBlank()) ? "auto" : draftLlmProvider.trim();
        return Text.literal("Provider（当前：" + p + "）");
    }

    private Text getSearchProviderButtonText() {
        String p = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim();
        for (SettingsSearchProviders.Entry e : SettingsSearchProviders.CYCLE) {
            if (e.id().equalsIgnoreCase(p)) {
                return Text.literal("搜索：" + e.label());
            }
        }
        return Text.literal("搜索：" + p);
    }


    private void openResetGroups() {
        blurInputs();
        choices.open("恢复哪个分组？保存后生效",java.util.Arrays.stream(SettingsGroup.values())
                .map(g -> new SettingsChoicePanel.Option(g.name(),g.label)).toList(),"",false,value -> {
            SettingsConfig defaults=new SettingsConfig();
            SettingsGroup group=SettingsGroup.valueOf(value);
            switch(group) {
                case CONNECTION -> {
                    apiKeyInput.setText(defaults.apiKey); modelInput.setText(defaults.model); draftModel=defaults.model;
                    orchestratorInput.setText(defaults.orchestratorEndpoint);
                    draftLlmProvider=defaults.llmProvider; draftLlmBaseUrl=defaults.llmBaseUrl;
                    llmBaseUrlInput.setText(defaults.llmBaseUrl); syncConnectionIdentity(); modelNeedsConfirmation=false;
                    llmProviderButton.setMessage(getLlmProviderButtonText());
                }
                case SEARCH -> {
                    draftSearchProvider=defaults.searchProvider; searchApiKeyInput.setText(defaults.searchApiKey); googleCseCxInput.setText(defaults.googleCseCx);
                    syncSearchIdentity(); searchProviderButton.setMessage(getSearchProviderButtonText());
                }
                case PREFERENCES -> {
                    draftShowDebugWarnings=defaults.showDebugWarnings;
                    interactionReachSlider.setCustomValue(reachToValue(defaults.interactionReach));
                    temperatureSlider.setCustomValue(defaults.temperature);
                }
                case BACKEND -> backendSection.load(defaults);
            }
            collapsed.remove(group);
            showToast("已恢复“"+group.label+"”草稿；保存后生效",false);
        });
    }

    private void afterTextEdit(HudTextInput input,String before) {
        if(before.equals(input.getText())) return;
        if(input==modelInput) { draftModel=input.getText().trim(); modelNeedsConfirmation=false; }
        if(input==reachInput || input==temperatureInput) {
            try {
                var values=SettingsPreferenceValues.parse(reachInput.getText(),temperatureInput.getText());
                interactionReachSlider.setCustomValue(reachToValue(values.reach()));
                temperatureSlider.setCustomValue(values.temperature());
            } catch(IllegalArgumentException ignored) { }
        }
    }

    private SearchIdentity currentSearchIdentity() {
        return new SearchIdentity(orchestratorInput.getText().trim(),draftSearchProvider,searchApiKeyInput.getText().trim(),googleCseCxInput.getText().trim());
    }
    private void syncSearchIdentity() {
        SearchIdentity next=currentSearchIdentity();
        if(next.equals(searchIdentity)) return;
        searchIdentity=next; searchRevision++; validatingSearchKey=false;
    }

    private void startValidateSearchKey() {
        syncSearchIdentity();
        if (validatingSearchKey) return;
        long revision=searchRevision;
        SearchIdentity identity=currentSearchIdentity();
        validatingSearchKey = true;
        if (testSearchKeyButton != null) {
            testSearchKeyButton.setMessage(Text.literal("测试中..."));
            testSearchKeyButton.active = false;
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String provider = (draftSearchProvider == null || draftSearchProvider.isBlank()) ? "auto" : draftSearchProvider.trim();
        String searchApiKey = SettingsSearchProviders.showsCredentials(provider) ? searchApiKeyInput.getText().trim() : "";
        String googleCx = SettingsSearchProviders.showsGoogleCx(provider) ? googleCseCxInput.getText().trim() : "";

        String computedUrl = base + "/search/validate";
        try {
            StringBuilder q = new StringBuilder();
            q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
            if (!googleCx.isBlank()) {
                q.append("&google_cse_cx=").append(URLEncoder.encode(googleCx, StandardCharsets.UTF_8));
            }
            computedUrl = computedUrl + "?" + q;
        } catch (Exception e) {
            LOG.debug("build search validate URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(httpClient, finalUrl, searchApiKey, Duration.ofSeconds(15));
            return new DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> client.execute(() -> {
            if(revision!=searchRevision || !identity.equals(currentSearchIdentity())) return;
            validatingSearchKey = false;
            if (testSearchKeyButton != null) {
                testSearchKeyButton.setMessage(Text.literal("测试搜索配置"));
                testSearchKeyButton.active = true;
            }
            if (resp == null) {
                showToast("搜索校验失败：未知错误", true);
                return;
            }
            if (resp.status < 0) {
                showToast("搜索校验失败：" + (resp.body == null ? "" : resp.body), true);
                return;
            }
            if (resp.status < 200 || resp.status >= 300) {
                showToast("搜索校验 HTTP " + resp.status + "：" + (resp.body == null ? "" : resp.body), true);
                return;
            }
            String msg = readSearchValidateMessage(resp.body);
            boolean ok = readSearchValidateOk(resp.body);
            showToast(msg != null && !msg.isBlank() ? msg : (ok ? "搜索配置校验通过" : "搜索配置校验失败"), !ok);
        }));
    }

    private static boolean readSearchValidateOk(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            return obj != null && obj.has("ok") && obj.get("ok").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readSearchValidateMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            if (obj != null && obj.has("message_zh") && !obj.get("message_zh").isJsonNull()) {
                return obj.get("message_zh").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private BaseUrlPreset getSelectedBaseUrlPreset() {
        if (baseUrlPresetIndex < 0 || baseUrlPresetIndex >= BASE_URL_PRESETS.size()) {
            baseUrlPresetIndex = 0;
        }
        return BASE_URL_PRESETS.get(baseUrlPresetIndex);
    }

    private Text getBaseUrlPresetButtonText() {
        BaseUrlPreset p = getSelectedBaseUrlPreset();
        String name = (p == null) ? "自动" : p.label;
        return Text.literal("预设：" + name);
    }

    private void applyBaseUrlPreset(int idx) {
        if (idx < 0 || idx >= BASE_URL_PRESETS.size()) return;
        ConnectionIdentity before = currentConnectionIdentity();
        baseUrlPresetIndex = idx;

        BaseUrlPreset p = BASE_URL_PRESETS.get(idx);
        if (p.url == null) {
            draftLlmProvider = "openai";
            // A custom endpoint uses the OpenAI-compatible adapter and retains its address.
            llmBaseUrlInput.setFocused(true);
        } else {
            draftLlmBaseUrl = p.url;
            llmBaseUrlInput.setText(p.url);
            llmBaseUrlInput.setFocused(false);
            // 一次配好：选中供应商预设时同时写入后端 provider id（custom 除外）。
            if (p.provider != null && !p.provider.isBlank()) {
                draftLlmProvider = p.provider;
                if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
            }
        }

        if (!before.equals(currentConnectionIdentity())) syncConnectionIdentity();
        if (llmProviderButton != null) llmProviderButton.setMessage(getLlmProviderButtonText());
        if (llmBaseUrlPresetButton != null) llmBaseUrlPresetButton.setMessage(getBaseUrlPresetButtonText());
        if (p.local) {
            showToast("本地供应商：API Key 可留空", false);
        }

    }

    @Override
    public void syncBaseUrlPresetFromValue(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();

        if (v.isEmpty()) {
            // auto
            for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
                if ("auto".equals(BASE_URL_PRESETS.get(i).id)) {
                    baseUrlPresetIndex = i;
                    return;
                }
            }
            baseUrlPresetIndex = 0;
            return;
        }

        for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
            BaseUrlPreset p = BASE_URL_PRESETS.get(i);
            if (p.url != null && p.url.equalsIgnoreCase(v)) {
                baseUrlPresetIndex = i;
                return;
            }
        }

        // custom
        for (int i = 0; i < BASE_URL_PRESETS.size(); i++) {
            if ("custom".equals(BASE_URL_PRESETS.get(i).id)) {
                baseUrlPresetIndex = i;
                return;
            }
        }
    }

    private ConnectionIdentity currentConnectionIdentity() {
        return new ConnectionIdentity(orchestratorInput.getText().trim(), apiKeyInput.getText().trim(),
                draftLlmProvider == null ? "auto" : draftLlmProvider, llmBaseUrlInput.getText().trim());
    }

    private void syncConnectionIdentity() {
        ConnectionIdentity next = currentConnectionIdentity();
        if (next.equals(connectionIdentity)) return;
        boolean changed = connectionIdentity != null;
        connectionIdentity = next;
        connectionRevision++;
        detectingModel = false;
        validatingKey = false;
        if (testKeyButton != null) testKeyButton.setMessage(Text.literal("校验 Key"));
        availableModels = new ArrayList<>();
        availableModelsUpdatedAtMs = 0L;
        if (changed && !modelInput.getText().isBlank()) modelNeedsConfirmation = true;
        syncBaseUrlPresetFromValue(llmBaseUrlInput.getText());
    }

    @Override public boolean modelNeedsConfirmation() { return modelNeedsConfirmation; }

    private void blurInputs() {
        ui.blur();
        for(var input:List.of(orchestratorInput,apiKeyInput,llmBaseUrlInput,modelInput,searchApiKeyInput,googleCseCxInput,reachInput,temperatureInput)) input.setFocused(false);
    }

    private void openModels() {
        syncConnectionIdentity();
        blurInputs();
        choices.open("选择或输入模型 ID", availableModels.stream()
                .map(m -> new SettingsChoicePanel.Option(m, m)).toList(), modelInput.getText(), true, value -> {
            modelInput.setText(value);
            draftModel = value;
            modelNeedsConfirmation = false;
        });
    }

    private void openProviders() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        options.add(new SettingsChoicePanel.Option("openai", "自定义 / OpenAI 兼容"));
        for (var p : BASE_URL_PRESETS) if (p.provider != null)
            options.add(new SettingsChoicePanel.Option(p.provider, p.label));
        choices.open("选择供应商（保留当前 URL）", options, draftLlmProvider, false, value -> {
            draftLlmProvider = value;
            llmProviderButton.setMessage(getLlmProviderButtonText());
            syncConnectionIdentity();
        });
    }

    private void openBaseUrlPresets() {
        blurInputs();
        var options = new ArrayList<SettingsChoicePanel.Option>();
        for (var p : BASE_URL_PRESETS) options.add(new SettingsChoicePanel.Option(p.id, p.label));
        choices.open("填入地址预设", options, getSelectedBaseUrlPreset().id, false, value -> {
            for (int i = 0; i < BASE_URL_PRESETS.size(); i++) if (BASE_URL_PRESETS.get(i).id.equals(value)) { applyBaseUrlPreset(i); break; }
        });
    }

    private void openSearchProviders() {
        blurInputs();
        choices.open("选择搜索引擎", SettingsSearchProviders.CYCLE.stream()
                .map(p -> new SettingsChoicePanel.Option(p.id(), p.label())).toList(), draftSearchProvider, false, value -> {
            draftSearchProvider = value;
            searchProviderButton.setMessage(getSearchProviderButtonText());
        });
    }

    private void startDetectModel() {
        syncConnectionIdentity();
        if (detectingModel) return;
        long revision = connectionRevision;
        ConnectionIdentity identity = currentConnectionIdentity();
        detectingModel = true;
        if (detectModelButton != null) {
            detectModelButton.setMessage(getDetectModelButtonText());
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        // 注意：这里用的是“当前输入框的 API Key”（草稿态），而不是已保存配置。
        // 否则用户未点 Save 时会一直使用旧 key，体验很差。
        String apiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().trim() : "";
        String provider = (draftLlmProvider == null) ? "" : draftLlmProvider.trim();
        String llmBaseUrlRaw = llmBaseUrlInput.getText() != null ? llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            detectingModel = false;
            showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
            if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
            return;
        }

        // 附带 provider/baseUrl（让 orchestrator /models 也能探测真实 LLM 端点）
        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank())
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            LOG.debug("build models query URL failed", e);
        }
        final String finalUrl = computedUrl;

        // 记录关键信息到日志（避免 toast 过长看不清）
        FormacraftMod.LOGGER.debug(
                "[DetectModel] backend={} provider={} base_url={} url={}",
                base, provider, llmBaseUrl, finalUrl);

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(httpClient, finalUrl, apiKey, Duration.ofSeconds(12));
            return new DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> client.execute(() -> {
            if (revision != connectionRevision || !identity.equals(currentConnectionIdentity())) return;
            detectingModel = false;
            if (resp == null) {
                showToast("刷新失败：未知错误（resp=null）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            // 本地异常（URI.create/连接失败等）
            if (resp.status < 0) {
                String err = (resp.body == null ? "" : resp.body);
                // 针对超时做更友好的提示：后端可能健康，但上游模型列表探测不可达/太慢
                if (err.toLowerCase().contains("httptimeoutexception") || err.toLowerCase().contains("request timed out")) {
                    showToast("刷新超时：上游模型列表可能不可达（仍可手动输入模型）", true);
                } else {
                    showToast("刷新失败：请求异常（详见日志） err=" + err, true);
                }
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            if (resp.body == null || resp.body.isBlank()) {
                showToast("刷新失败：后端无响应内容（详见日志）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            if (resp.status < 200 || resp.status >= 300) {
                // 常见：OpenAI 兼容端点未带 key → 401；URL 不对 → 404
                showToast("刷新失败：" + resp.status + "（详见日志）", true);
                if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
                return;
            }

            // 解析返回：models[] + default_model + remote_models_ok
            String suggested = SettingsModelCatalog.parseDetectedModel(resp.body);
            List<String> models = SettingsModelCatalog.parseModelsList(resp.body);
            availableModels = (models == null) ? new ArrayList<>() : new ArrayList<>(models);
            availableModelsUpdatedAtMs = System.currentTimeMillis();
            if (choices.isEditingValue()) choices.updateOptions(availableModels.stream()
                    .map(m -> new SettingsChoicePanel.Option(m, m)).toList());

            boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body);
            String modelsSource = SettingsModelCatalog.readModelsSource(resp.body);

            // 同步草稿态（以输入框为准；留空=自动）
            draftModel = modelInput.getText() == null ? "" : modelInput.getText().trim();

            if (availableModels.isEmpty()) {
                showToast("模型列表为空（可手动输入模型）", true);
            } else {
                String tip = "模型列表已更新：" + availableModels.size();
                if (modelsSource != null && !modelsSource.isBlank()) {
                    if ("fallback".equalsIgnoreCase(modelsSource)) tip += "（常用预设）";
                    if ("remote".equalsIgnoreCase(modelsSource)) tip += "（在线拉取）";
                }
                if (!remoteOk) tip += "（上游不可达）";
                if (suggested != null && !suggested.isBlank()) tip += "（推荐：" + suggested.trim() + "）";
                showToast(tip, false);
            }

            if (detectModelButton != null) detectModelButton.setMessage(getDetectModelButtonText());
        }));
    }

    private void startValidateKey() {
        syncConnectionIdentity();
        if (validatingKey) return;
        long revision = connectionRevision;
        ConnectionIdentity identity = currentConnectionIdentity();
        validatingKey = true;
        if (testKeyButton != null) {
            testKeyButton.setMessage(Text.literal("校验中..."));
            testKeyButton.active = false;
        }

        String base = sanitizeEndpoint(orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        String apiKey = apiKeyInput.getText() != null ? apiKeyInput.getText().trim() : "";
        String provider = (draftLlmProvider == null) ? "" : draftLlmProvider.trim();
        String model = modelInput.getText() != null ? modelInput.getText().trim() : "";
        String llmBaseUrlRaw = llmBaseUrlInput.getText() != null ? llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        String providerHint = provider.isBlank() ? "auto" : provider;
        String modelHint = model.isBlank() ? "auto" : model;
        String baseHint = (llmBaseUrl == null || llmBaseUrl.isBlank()) ? "(auto)" : llmBaseUrl;
        final String llmHint = providerHint + "/" + modelHint + " @ " + baseHint;

        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            validatingKey = false;
            showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
            if (testKeyButton != null) {
                testKeyButton.setMessage(Text.literal("校验 Key"));
                testKeyButton.active = true;
            }
            return;
        }

        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank()) {
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                }
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            LOG.debug("build validate key URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(httpClient, finalUrl, apiKey, Duration.ofSeconds(10));
            return new DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> client.execute(() -> {
            if (revision != connectionRevision || !identity.equals(currentConnectionIdentity())) return;
            validatingKey = false;
            if (testKeyButton != null) {
                testKeyButton.setMessage(Text.literal("校验 Key"));
                testKeyButton.active = true;
            }

            if (resp == null) {
                showToast("校验失败：未知错误（resp=null）", true);
                return;
            }

            if (resp.status < 0) {
                showToast("校验失败：请求异常 " + (resp.body == null ? "" : resp.body), true);
                return;
            }

            if (resp.status >= 200 && resp.status < 300) {
                boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body);
                String source = SettingsModelCatalog.readModelsSource(resp.body);
                if (!remoteOk) {
                    String src = (source == null || source.isBlank()) ? "fallback" : source;
                    showToast("上游模型端点不可达（" + src + "），暂无法确认 Key 有效性。当前 LLM：" + llmHint, true);
                    return;
                }
                showToast("Key 校验通过（" + providerHint + "）", false);
                return;
            }

            if (resp.status == 401 || resp.status == 403) {
                showToast("Key 无效/未授权（" + resp.status + "）。当前 LLM：" + llmHint, true);
                return;
            }

            showToast("校验失败：" + resp.status + "。当前 LLM：" + llmHint + "。" + (resp.body == null ? "" : resp.body), true);
        }));
    }

    private void toggleHideKey() {
        hideKey = !hideKey;
        apiKeyInput.setPasswordMode(hideKey);
        searchApiKeyInput.setPasswordMode(hideKey);
        if (showHideButton != null) {
            showHideButton.setMessage(getShowHideText());
        }
    }

    private Text getShowHideText() {
        return hideKey
                ? Text.translatable("formacraft.settings.show")
                : Text.translatable("formacraft.settings.hide");
    }

    @Override
    public void syncWidgetStateFromDraft() {
        reachInput.setText(Integer.toString(draftInteractionReach));
        temperatureInput.setText(String.format(java.util.Locale.ROOT,"%.2f",draftTemperature));
        if (showHideButton != null) {
            showHideButton.setMessage(getShowHideText());
        }
        if (detectModelButton != null) {
            detectModelButton.setMessage(getDetectModelButtonText());
        }
        if (llmProviderButton != null) {
            llmProviderButton.setMessage(getLlmProviderButtonText());
        }
        if (debugWarningsButton != null) {
            debugWarningsButton.setMessage(getDebugWarningsButtonText());
        }
        if (searchProviderButton != null) {
            searchProviderButton.setMessage(getSearchProviderButtonText());
        }
        if (testSearchKeyButton != null) {
            testSearchKeyButton.setMessage(validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索配置"));
            testSearchKeyButton.active = !validatingSearchKey;
        }
        if (draftLlmBaseUrl != null) {
            // 避免 loadFromConfig 后输入框仍显示旧值
            llmBaseUrlInput.setText(draftLlmBaseUrl);
        }
        if (draftModel != null) {
            modelInput.setText(draftModel);
        }
        if (interactionReachSlider != null) {
            interactionReachSlider.setCustomValue(reachToValue(draftInteractionReach));
        }
        if (temperatureSlider != null) {
            temperatureSlider.setCustomValue(clamp01(draftTemperature));
        }
    }

    private static double reachToValue(int reach) {
        int clamped = Math.max(MIN_INTERACTION_REACH, Math.min(MAX_INTERACTION_REACH, reach));
        return (clamped - MIN_INTERACTION_REACH) / (double) (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH);
    }

    private static int valueToReach(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return MIN_INTERACTION_REACH + (int) Math.round(v * (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH));
    }

    /**
     * 验证并清理端点URL
     */
    private static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "http://localhost:8000";
        }
        String v = endpoint.trim();
        
        // 简单容错：如果用户只填了 localhost:8000，则补协议
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        
        // 移除末尾斜杠
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        
        // 验证URL格式（使用URI避免弃用警告）
        try {
            URI uri = new URI(v);
            // 检查是否为有效的http/https URI
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return "http://localhost:8000";
            }
        } catch (URISyntaxException e) {
            // URL格式无效，返回默认值
            return "http://localhost:8000";
        }
        
        return v;
    }

    /**
     * 保存设置（带验证）
     */
    private boolean saveSettings() {
        syncConnectionIdentity();
        if (modelNeedsConfirmation) {
            showToast("连接已更改，请确认或重新填写 Model 后再保存", true);
            openModels();
            return false;
        }
        boolean saved = SettingsConfigCoordinator.save(this);
        if (saved) {
            connectionRevision++;
            connectionIdentity = currentConnectionIdentity();
            detectingModel = false;
        }
        return saved;
    }

    @Override
    public void showToast(String msg, boolean isError) {
        this.toast = msg;
        this.isToastError = isError;

    }

    private void showToast(String msg) {
        showToast(msg, false);
    }

    // =======================
    //   键盘输入
    // =======================

    @Override
    public void charTyped(char chr) {
        syncConnectionIdentity(); syncSearchIdentity();
        if(details.isOpen()) return;
        if(choices.isOpen()) { choices.charTyped(chr); return; }
        var focused=ui.focused();
        if(focused==null) return;
        String before=focused.getText(); focused.charTyped(chr);
        afterTextEdit(focused,before);
    }

    @Override
    public void keyPressed(int keyCode) {
        // 兼容旧调用：没有 modifiers 时仍能处理 Backspace / ESC
        keyPressed(keyCode, 0, 0);
    }

    @Override
    public void keyPressed(int keyCode,int scanCode,int modifiers) {
        syncConnectionIdentity(); syncSearchIdentity();
        if(details.isOpen()) { if(keyCode==GLFW.GLFW_KEY_ESCAPE) details.close(); return; }
        if(choices.isOpen()) { choices.keyPressed(keyCode,modifiers); return; }
        if(keyCode==GLFW.GLFW_KEY_TAB) {
            setScrollY(Math.max(0,Math.min(maxScrollY,scrollY+ui.tab((modifiers&GLFW.GLFW_MOD_SHIFT)!=0))));
            return;
        }
        var focused=ui.focused();
        if(focused!=null) {
            String before=focused.getText(); focused.keyPressed(keyCode,modifiers); afterTextEdit(focused,before);
        }
        if(keyCode==GLFW.GLFW_KEY_ENTER) saveSettings();
    }

    @Override
    public boolean wantsKeyboardInput() { return choices.isOpen() || details.isOpen() || ui.focused()!=null; }

    @Override public void loadBackendSettings(SettingsConfig cfg) { backendSection.load(cfg); }
    @Override public void stageBackendSettings(SettingsConfig cfg) { backendSection.stage(cfg); }
    @Override public SettingsUiBindings ui() { return ui; }
    @Override public HudTextInput reachInput() { return reachInput; }
    @Override public HudTextInput temperatureInput() { return temperatureInput; }
    @Override public void openDetails(String text) { blurInputs(); choices.close(); details.open(text); }
    @Override public String preferenceError() {
        try { SettingsPreferenceValues.parse(reachInput.getText(),temperatureInput.getText()); return null; }
        catch(IllegalArgumentException e) { return e.getMessage(); }
    }

    // ---- SettingsPanelRenderHost ----

    @Override
    public void ensureWidgets() {
        if (showHideButton == null) {
            initWidgets();
        }
    }

    @Override
    public MinecraftClient client() {
        return client;
    }

    @Override
    public HudTextInput orchestratorInput() {
        return orchestratorInput;
    }

    @Override
    public HudTextInput apiKeyInput() {
        return apiKeyInput;
    }

    @Override
    public HudTextInput llmBaseUrlInput() {
        return llmBaseUrlInput;
    }

    @Override
    public HudTextInput modelInput() {
        return modelInput;
    }

    @Override
    public boolean hideKey() {
        return hideKey;
    }

    @Override
    public boolean detectingModel() {
        return detectingModel;
    }

    @Override
    public boolean draftShowDebugWarnings() {
        return draftShowDebugWarnings;
    }

    @Override
    public int draftInteractionReach() {
        return draftInteractionReach;
    }

    @Override
    public ButtonWidget showHideButton() {
        return showHideButton;
    }

    @Override
    public ButtonWidget pasteButton() {
        return pasteButton;
    }

    @Override
    public ButtonWidget testKeyButton() {
        return testKeyButton;
    }

    @Override
    public ButtonWidget detectModelButton() {
        return detectModelButton;
    }

    @Override
    public ButtonWidget modelChoicesButton() {
        return modelChoicesButton;
    }

    @Override
    public ButtonWidget llmProviderButton() {
        return llmProviderButton;
    }

    @Override
    public ButtonWidget llmBaseUrlPresetButton() {
        return llmBaseUrlPresetButton;
    }

    @Override
    public ButtonWidget debugWarningsButton() {
        return debugWarningsButton;
    }

    @Override
    public ButtonWidget searchProviderButton() {
        return searchProviderButton;
    }

    @Override
    public HudTextInput searchApiKeyInput() {
        return searchApiKeyInput;
    }

    @Override
    public HudTextInput googleCseCxInput() {
        return googleCseCxInput;
    }

    @Override
    public Text searchProviderButtonText() {
        return getSearchProviderButtonText();
    }

    @Override
    public ButtonWidget testSearchKeyButton() {
        return testSearchKeyButton;
    }

    @Override
    public Text testSearchKeyButtonText() {
        return validatingSearchKey ? Text.literal("测试中...") : Text.literal("测试搜索配置");
    }

    @Override
    public boolean validatingSearchKey() {
        return validatingSearchKey;
    }

    @Override
    public String draftSearchProvider() {
        return draftSearchProvider;
    }

    @Override
    public void setDraftSearchProvider(String provider) {
        this.draftSearchProvider = provider;
    }

    @Override
    public SliderWidget temperatureSlider() {
        return temperatureSlider;
    }

    @Override
    public SliderWidget interactionReachSlider() {
        return interactionReachSlider;
    }

    @Override
    public ButtonWidget saveButton() {
        return saveButton;
    }

    @Override
    public ButtonWidget cancelButton() {
        return cancelButton;
    }

    @Override
    public ButtonWidget resetButton() {
        return resetButton;
    }

    @Override
    public Text detectModelButtonText() {
        return getDetectModelButtonText();
    }

    @Override
    public Text llmProviderButtonText() {
        return getLlmProviderButtonText();
    }

    @Override
    public Text baseUrlPresetButtonText() {
        return getBaseUrlPresetButtonText();
    }

    @Override
    public Text debugWarningsButtonText() {
        return getDebugWarningsButtonText();
    }

    // ---- 输入交互（SettingsInputController）----

    @Override
    public int contentStartX() {
        return panelX + CONTENT_PADDING;
    }

    @Override
    public int contentWidth() {
        return panelWidth - CONTENT_PADDING * 2;
    }

    @Override
    public int contentTopY() {
        return getContentY();
    }

    @Override
    public boolean isInteractiveArea(double mouseX, double mouseY) {
        return visible && (choices.isOpen() || details.isOpen() || super.isInteractiveArea(mouseX, mouseY));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY) || choices.contains(mouseX, mouseY) || details.contains(mouseX,mouseY);
    }

    @Override
    public int scrollY() {
        return scrollY;
    }

    @Override
    public void setScrollY(int scrollY) {
        this.scrollY = scrollY;
    }

    @Override
    public int maxScrollY() {
        return maxScrollY;
    }

    @Override
    public void setDraftModel(String model) {
        this.draftModel = model;
    }

    @Override
    public SliderWidget activeSlider() {
        return activeSlider;
    }

    @Override
    public void setActiveSlider(SliderWidget slider) {
        this.activeSlider = slider;
    }

    @Override
    public void applyTemperatureFromSlider(double value) {
        draftTemperature = clamp01((float) value);
        if (!temperatureInput.isFocused()) temperatureInput.setText(String.format(java.util.Locale.ROOT,"%.2f",draftTemperature));
    }

    @Override
    public void applyInteractionReachFromSlider(double value) {
        draftInteractionReach = clampReach(valueToReach(value));
        if (!reachInput.isFocused()) reachInput.setText(Integer.toString(draftInteractionReach));
    }

    @Override
    public String draftLlmProvider() {
        return draftLlmProvider;
    }

    @Override
    public float draftTemperature() {
        return draftTemperature;
    }

    @Override
    public void setDraftLlmProvider(String provider) {
        this.draftLlmProvider = provider;
    }

    @Override
    public void setDraftLlmBaseUrl(String baseUrl) {
        this.draftLlmBaseUrl = baseUrl;
    }

    @Override
    public void setDraftShowDebugWarnings(boolean show) {
        this.draftShowDebugWarnings = show;
    }

    @Override
    public void setDraftTemperature(float temperature) {
        this.draftTemperature = clamp01(temperature);
    }

    @Override
    public void setDraftInteractionReach(int reach) {
        this.draftInteractionReach = reach;
    }
}
