package com.formacraft.server.edit;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects Phase-1 “replace roof material, keep everything else” requests. */
public final class RoofMaterialReplaceIntent {
    private static final Pattern ROOF_EDIT = Pattern.compile(
            "屋顶|屋面|屋瓦|瓦片|\\broof\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern MATERIAL_ACTION = Pattern.compile(
            "换[成成]|改[成成]|替换|换成|改为|改成|更换|改用|用.{0,12}替换|"
                    + "replace|change\\s+(?:the\\s+)?roof|roof\\s+(?:to|into|with)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern KEEP_OTHERS = Pattern.compile(
            "其他|其余|别的|保持|保留|不要改|不变|only\\s+roof|keep\\s+the\\s+rest|nothing\\s+else",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern MATERIAL_CAPTURE = Pattern.compile(
            "(?:换成|改成|改为|改用|用|to|into|with)\\s*([\\w\\u4e00-\\u9fff:._\\-]+(?:\\s+[\\w\\u4e00-\\u9fff:._\\-]+)?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private RoofMaterialReplaceIntent() {}

    public static boolean matches(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String text = userMessage.replaceAll("\\s+", " ").trim();
        if (!ROOF_EDIT.matcher(text).find()) return false;
        if (!MATERIAL_ACTION.matcher(text).find()) return false;
        // Prefer explicit keep-others, but "换屋顶材质/颜色" style without rebuild verbs is enough.
        if (KEEP_OTHERS.matcher(text).find()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        return !lower.contains("重建") && !lower.contains("rebuild") && !lower.contains("整栋");
    }

    public static Optional<String> materialPhrase(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        String text = userMessage.replaceAll("\\s+", " ").trim();
        Matcher m = MATERIAL_CAPTURE.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last == null || last.isBlank()) {
            // Fall back: whole message for RoofMaterialIntent aliases (深色橡木 etc.)
            return Optional.of(text);
        }
        String cleaned = last.replaceAll("(屋顶|屋面|材质|颜色|瓦|其他.*)$", "").trim();
        if (cleaned.isBlank()) cleaned = last.trim();
        return Optional.of(cleaned);
    }
}
