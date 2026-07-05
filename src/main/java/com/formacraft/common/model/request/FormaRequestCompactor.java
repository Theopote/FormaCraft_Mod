package com.formacraft.common.model.request;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps {@link FormaRequest} JSON under Minecraft's {@code PacketByteBuf.writeString} limit (32767).
 * <p>
 * Typical overflow sources: full assembled {@code requestText}, long {@code chatHistory},
 * and {@code references} with pasted base64 images (PR-4).
 */
public final class FormaRequestCompactor {

    /** Minecraft NBT/string packet limit. */
    public static final int MAX_PACKET_STRING = 32767;

    /** Target size with headroom for escaping / future fields. */
    public static final int TARGET_JSON_CHARS = 30_000;

    private static final int MAX_CHAT_MESSAGES = 8;
    private static final int MAX_CHAT_LINE_CHARS = 400;
    private static final int MAX_REFERENCE_CONTENT_CHARS = 512;

    private FormaRequestCompactor() {}

    /**
     * Returns a copy safe to serialize on {@code formacraft:request_build}.
     */
    public static FormaRequest compactForNetwork(FormaRequest source) {
        if (source == null) {
            return null;
        }
        FormaRequest req = copyOf(source);
        compactReferences(req);
        compactChatHistory(req);

        String json = JsonUtil.toJson(req);
        if (json.length() <= TARGET_JSON_CHARS) {
            return req;
        }

        int excess = json.length() - TARGET_JSON_CHARS;
        shrinkRequestText(req, excess + 256);

        json = JsonUtil.toJson(req);
        if (json.length() > TARGET_JSON_CHARS) {
            compactChatHistoryAggressive(req);
            shrinkRequestText(req, JsonUtil.toJson(req).length() - TARGET_JSON_CHARS + 256);
        }

        json = JsonUtil.toJson(req);
        if (json.length() > MAX_PACKET_STRING) {
            FormacraftMod.LOGGER.warn(
                    "FormaRequest still large after compaction: {} chars (max {})",
                    json.length(), MAX_PACKET_STRING
            );
            // Last resort: hard-cap requestText so packet can be sent.
            hardCapRequestText(req, MAX_PACKET_STRING / 2);
        }
        return req;
    }

    public static int jsonLength(FormaRequest req) {
        return req == null ? 0 : JsonUtil.toJson(req).length();
    }

    private static FormaRequest copyOf(FormaRequest src) {
        FormaRequest dst = new FormaRequest();
        dst.setRequestText(src.getRequestText());
        dst.setUserMessage(src.getUserMessage());
        dst.setPromptMode(src.getPromptMode());
        dst.setOutputFormat(src.getOutputFormat());
        dst.setPlayerPos(src.getPlayerPos());
        dst.setFacing(src.getFacing());
        dst.setDimension(src.getDimension());
        dst.setBiome(src.getBiome());
        dst.setSelectionMin(src.getSelectionMin());
        dst.setSelectionMax(src.getSelectionMax());
        dst.setBrushMin(src.getBrushMin());
        dst.setBrushMax(src.getBrushMax());
        dst.setOutline(src.getOutline());
        if (src.getProtectedZones() != null) {
            dst.setProtectedZones(new ArrayList<>(src.getProtectedZones()));
        }
        if (src.getPathNodes() != null) {
            dst.setPathNodes(new ArrayList<>(src.getPathNodes()));
        }
        dst.setPathRadius(src.getPathRadius());
        dst.setSessionId(src.getSessionId());
        if (src.getChatHistory() != null) {
            dst.setChatHistory(new ArrayList<>(src.getChatHistory()));
        }
        if (src.getReferences() != null) {
            List<ReferenceInput> refs = new ArrayList<>(src.getReferences().size());
            for (ReferenceInput r : src.getReferences()) {
                if (r == null) continue;
                refs.add(new ReferenceInput(r.getType(), r.getContent(), r.getCaption()));
            }
            dst.setReferences(refs);
        }
        dst.setApiKey(src.getApiKey());
        dst.setModel(src.getModel());
        dst.setTemperature(src.getTemperature());
        dst.setLlmProvider(src.getLlmProvider());
        dst.setLlmBaseUrl(src.getLlmBaseUrl());
        dst.setSearchProvider(src.getSearchProvider());
        dst.setSearchApiKey(src.getSearchApiKey());
        dst.setGoogleCseCx(src.getGoogleCseCx());
        return dst;
    }

    private static void compactReferences(FormaRequest req) {
        if (req.getReferences() == null || req.getReferences().isEmpty()) {
            return;
        }
        List<ReferenceInput> kept = new ArrayList<>();
        for (ReferenceInput ref : req.getReferences()) {
            if (ref == null || ref.getContent() == null) {
                continue;
            }
            String type = ref.getType() == null ? "" : ref.getType().trim().toLowerCase();
            String content = ref.getContent();
            // Never send pasted binary over C2S — use URL references instead.
            if ("image_base64".equals(type) || content.startsWith("data:image")) {
                FormacraftMod.LOGGER.info(
                        "Dropped inline image reference from build packet ({} chars); use image URL instead",
                        content.length()
                );
                continue;
            }
            if (content.length() > MAX_REFERENCE_CONTENT_CHARS) {
                kept.add(new ReferenceInput(
                        type.isEmpty() ? "web_url" : type,
                        content.substring(0, MAX_REFERENCE_CONTENT_CHARS),
                        ref.getCaption()
                ));
            } else {
                kept.add(ref);
            }
        }
        req.setReferences(kept);
    }

    private static void compactChatHistory(FormaRequest req) {
        if (req.getChatHistory() == null || req.getChatHistory().isEmpty()) {
            return;
        }
        List<String> hist = req.getChatHistory();
        int from = Math.max(0, hist.size() - MAX_CHAT_MESSAGES);
        List<String> trimmed = new ArrayList<>();
        for (int i = from; i < hist.size(); i++) {
            trimmed.add(truncateLine(hist.get(i), MAX_CHAT_LINE_CHARS));
        }
        req.setChatHistory(trimmed);
    }

    private static void compactChatHistoryAggressive(FormaRequest req) {
        if (req.getChatHistory() == null || req.getChatHistory().isEmpty()) {
            return;
        }
        List<String> hist = req.getChatHistory();
        int from = Math.max(0, hist.size() - 4);
        List<String> trimmed = new ArrayList<>();
        for (int i = from; i < hist.size(); i++) {
            trimmed.add(truncateLine(hist.get(i), 200));
        }
        req.setChatHistory(trimmed);
    }

    private static void shrinkRequestText(FormaRequest req, int charsToRemove) {
        if (charsToRemove <= 0 || req.getRequestText() == null) {
            return;
        }
        String text = req.getRequestText();
        if (text.length() <= charsToRemove + 500) {
            req.setRequestText(preserveUserRequestSection(text, Math.max(500, text.length() - charsToRemove)));
            return;
        }
        req.setRequestText(preserveUserRequestSection(text, text.length() - charsToRemove));
    }

    private static void hardCapRequestText(FormaRequest req, int maxChars) {
        if (req.getRequestText() == null) {
            return;
        }
        req.setRequestText(preserveUserRequestSection(req.getRequestText(), maxChars));
    }

    /**
     * Keeps tail containing USER REQUEST when truncating assembled system prompt.
     */
    static String preserveUserRequestSection(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        String marker = "USER REQUEST:";
        int idx = text.lastIndexOf(marker);
        if (idx >= 0) {
            int tailBudget = Math.min(maxChars, text.length() - idx);
            int start = Math.max(0, text.length() - maxChars);
            if (start > idx) {
                // Include marker + user tail; drop middle of system prompt
                return "[... prompt truncated for network packet ...]\n\n"
                        + text.substring(idx, Math.min(text.length(), idx + tailBudget));
            }
            return text.substring(start);
        }
        return text.substring(0, maxChars) + "\n[... truncated ...]";
    }

    private static String truncateLine(String line, int max) {
        if (line == null) {
            return "";
        }
        if (line.length() <= max) {
            return line;
        }
        return line.substring(0, max) + "...";
    }
}
