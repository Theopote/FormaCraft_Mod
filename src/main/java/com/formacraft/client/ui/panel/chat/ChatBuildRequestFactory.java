package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.context.BrushContext;
import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ai.prompt.PromptMode;
import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.ReferenceInputExtractor;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Builds the C2S {@link FormaRequest} for chat send (user text + structured constraints only). */
public final class ChatBuildRequestFactory {
    private static final FcaLog LOG = FcaLog.of("ChatBuildRequestFactory");

    private ChatBuildRequestFactory() {}

    public static FormaRequest fromChat(
            String userText,
            PromptMode mode,
            String sessionId,
            List<String> chatHistory
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        PromptMode effectiveMode = mode == null ? PromptMode.BUILD : mode;
        String text = userText == null ? "" : userText.trim();

        var bc = BuildContextResolver.resolve(effectiveMode == PromptMode.MODIFY_REGION);
        BlockPos origin = (bc != null && bc.origin != null)
                ? bc.origin
                : (client.player != null ? client.player.getBlockPos().add(2, 0, 2) : BlockPos.ORIGIN);

        FormaRequest req = new FormaRequest();
        req.setRequestText(text);
        req.setUserMessage(text);
        req.setReferences(ReferenceInputExtractor.extractFromText(text));
        req.setPromptMode(effectiveMode.name());
        req.setOutputFormat("llmplan");
        req.setPlayerPos(origin);
        if (effectiveMode == PromptMode.BUILD && com.formacraft.client.tool.WorkPlaneTool.INSTANCE.state().fixed()) {
            req.setFixedBaseY(com.formacraft.client.tool.WorkPlaneTool.INSTANCE.state().height());
        }
        try {
            if (bc != null && bc.facing != null) {
                req.setFacing(bc.facing.name());
            } else if (client.player != null) {
                req.setFacing(client.player.getHorizontalFacing().name());
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request facing failed", t);
        }
        req.setSelectionMin(SelectionContext.hasSelection() ? SelectionContext.min() : null);
        req.setSelectionMax(SelectionContext.hasSelection() ? SelectionContext.max() : null);

        try {
            if (BrushContext.hasBrushSelection() && !SelectionContext.hasSelection()) {
                int[] bounds = BrushContext.getBounds();
                if (bounds != null) {
                    req.setBrushMin(new BlockPos(bounds[0], bounds[1], bounds[2]));
                    req.setBrushMax(new BlockPos(bounds[3], bounds[4], bounds[5]));
                }
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request brush bounds failed", t);
        }

        try {
            if (bc != null) {
                req.setOutline(bc.outline);
                req.setProtectedZones(bc.protectedZones);
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request outline/zones failed", t);
        }

        try {
            com.formacraft.common.skeleton.PathSkeleton skel =
                    com.formacraft.client.tool.PathTool.INSTANCE.toSkeleton();
            if (skel.isValid()) {
                req.setPathNodes(new java.util.ArrayList<>(skel.nodes));
                req.setPathRadius(skel.corridorRadius);
            }
        } catch (Throwable t) {
            LOG.debug("enrich build request path corridor failed", t);
        }

        req.setSessionId(sessionId);
        req.setChatHistory(chatHistory == null ? List.of() : chatHistory);

        SettingsConfig cfg = SettingsConfig.INSTANCE;
        String apiKey = cfg.apiKey != null ? cfg.apiKey.trim() : "";
        if (!apiKey.isEmpty() && client.isInSingleplayer()) {
            req.setApiKey(apiKey);
        }
        String model = cfg.model != null ? cfg.model.trim() : "";
        if (!model.isEmpty()) req.setModel(model);
        req.setTemperature(cfg.temperature);
        String provider = cfg.llmProvider != null ? cfg.llmProvider.trim() : "";
        if (!provider.isEmpty()) req.setLlmProvider(provider);
        String baseUrl = cfg.llmBaseUrl != null ? cfg.llmBaseUrl.trim() : "";
        if (!baseUrl.isEmpty()) req.setLlmBaseUrl(baseUrl);
        String searchProvider = cfg.searchProvider != null ? cfg.searchProvider.trim() : "";
        if (!searchProvider.isEmpty()) req.setSearchProvider(searchProvider);
        String searchApiKey = cfg.searchApiKey != null ? cfg.searchApiKey.trim() : "";
        if (!searchApiKey.isEmpty()) req.setSearchApiKey(searchApiKey);
        String googleCseCx = cfg.googleCseCx != null ? cfg.googleCseCx.trim() : "";
        if (!googleCseCx.isEmpty()) req.setGoogleCseCx(googleCseCx);

        return req;
    }
}
