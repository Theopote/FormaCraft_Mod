package com.formacraft.client.ai.prompt;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps raw chat text to {@link PromptMode} when the UI no longer exposes mode buttons.
 * Prefer explicit incremental language over BUILD so patch requests do not silently rebuild.
 */
public final class PromptModeResolver {
    private PromptModeResolver() {}

    private static final Pattern INCREMENTAL = Pattern.compile(
            "只改局部|增量修改|增量编辑|不要重建|不要整栋|局部修改|局部改|"
                    + "把.{0,24}(改成|换成)|改成|换成|修改这|改这栋|改屋顶|改外墙|"
                    + "对当前建筑|对这栋|仅修改|仅改|"
                    + "\\bincremental\\b|\\bpatch\\b|only modify|do not rebuild|don't rebuild|change the (roof|wall|window)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern REGION_ONLY = Pattern.compile(
            "仅修改选区|只改选区|选区内|modify region|selection only",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Resolve mode from player-visible text (not the assembled system prompt). */
    public static PromptMode fromUserText(String rawUserText) {
        String text = rawUserText == null ? "" : rawUserText.trim();
        if (text.isEmpty()) {
            return PromptMode.BUILD;
        }
        if (REGION_ONLY.matcher(text).find()) {
            return PromptMode.MODIFY_REGION;
        }
        if (INCREMENTAL.matcher(text).find()) {
            return PromptMode.PATCH;
        }
        // English "replace X with Y" without rebuild verbs — treat as patch.
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.contains("replace ") || lower.contains("change "))
                && (lower.contains("window") || lower.contains("roof") || lower.contains("wall")
                || lower.contains("door") || lower.contains("glass"))) {
            return PromptMode.PATCH;
        }
        return PromptMode.BUILD;
    }
}
