package com.formacraft.common.model.request;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户输入中提取参考图 / URL，供 PR-4 vision 管线使用。
 */
public final class ReferenceInputExtractor {

    private static final Pattern HTTP_URL = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DATA_IMAGE = Pattern.compile(
            "(data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)"
    );

    private ReferenceInputExtractor() {
    }

    public static List<ReferenceInput> extractFromText(String text) {
        List<ReferenceInput> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();

        Matcher dataMatcher = DATA_IMAGE.matcher(text);
        while (dataMatcher.find()) {
            String dataUri = dataMatcher.group(1).trim();
            if (seen.add(dataUri)) {
                out.add(new ReferenceInput("image_base64", dataUri, "pasted image"));
            }
        }

        Matcher urlMatcher = HTTP_URL.matcher(text);
        while (urlMatcher.find()) {
            String url = trimTrailingPunctuation(urlMatcher.group(1).trim());
            if (url.isEmpty() || seen.contains(url)) {
                continue;
            }
            seen.add(url);
            String type = isImageUrl(url) ? "image_url" : "web_url";
            out.add(new ReferenceInput(type, url, null));
        }

        return out;
    }

    private static String trimTrailingPunctuation(String url) {
        while (!url.isEmpty()) {
            char c = url.charAt(url.length() - 1);
            if (c == ')' || c == ']' || c == '>' || c == '.' || c == ',' || c == ';') {
                url = url.substring(0, url.length() - 1);
            } else {
                break;
            }
        }
        return url;
    }

    private static boolean isImageUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".webp") || lower.contains(".gif")
                || lower.contains("images") || lower.contains("/img/");
    }
}
