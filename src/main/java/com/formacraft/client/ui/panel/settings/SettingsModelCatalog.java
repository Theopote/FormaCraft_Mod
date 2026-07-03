package com.formacraft.client.ui.panel.settings;

import com.formacraft.common.logging.FcaLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM model list parsing and URL sanitization for {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public final class SettingsModelCatalog {
    private static final FcaLog LOG = FcaLog.of("SettingsModelCatalog");

    private static final Pattern JSON_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private SettingsModelCatalog() {}

    public static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "http://localhost:8000";
        }
        String v = endpoint.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public static String sanitizeLlmBaseUrlOrNull(String input) {
        if (input == null) return "";
        String v = input.trim();
        if (v.isEmpty()) return "";

        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "https://" + v;
        }

        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);

        try {
            URI uri = new URI(v);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;

            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            boolean hostOk = "localhost".equalsIgnoreCase(host)
                    || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    || host.contains(".");
            if (!hostOk) return null;

            String rest = v.substring((scheme + "://").length());
            if (rest.contains("://")) return null;
        } catch (URISyntaxException e) {
            return null;
        }
        return v;
    }

    public static String shortErr(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        int max = 140;
        return t.length() <= max ? t : (t.substring(0, max) + "…");
    }

    public static List<String> parseModelsList(String body) {
        if (body == null || body.isBlank()) return List.of();

        try {
            JsonElement root = JsonParser.parseString(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("models") && obj.get("models").isJsonArray()) {
                    List<String> out = new ArrayList<>();
                    JsonArray arr = obj.getAsJsonArray("models");
                    for (JsonElement e : arr) {
                        if (e != null && e.isJsonPrimitive()) {
                            String s = e.getAsString();
                            if (s != null && !s.isBlank()) out.add(s.trim());
                        }
                    }
                    return out;
                }
                if (obj.has("data") && obj.get("data").isJsonArray()) {
                    return getStrings(obj.getAsJsonArray("data"));
                }
            }
        } catch (Exception e) {
            LOG.debug("parse models list JSON failed", e);
        }

        List<String> out = new ArrayList<>();
        Matcher m = JSON_ID_PATTERN.matcher(body);
        while (m.find()) {
            String id = m.group(1);
            if (id != null && !id.isBlank()) out.add(id);
            if (out.size() >= 50) break;
        }
        return out;
    }

    public static String parseDetectedModel(String body) {
        if (body == null || body.isBlank()) return null;

        try {
            JsonElement root = JsonParser.parseString(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                if (obj.has("default_model") && obj.get("default_model").isJsonPrimitive()) {
                    return obj.get("default_model").getAsString();
                }

                if (obj.has("model") && obj.get("model").isJsonPrimitive()) {
                    return obj.get("model").getAsString();
                }

                if (obj.has("data") && obj.get("data").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("data");
                    List<String> ids = getStrings(arr);
                    String picked = pickPreferredModel(ids);
                    if (picked != null && !picked.isBlank()) return picked;
                }
            }
        } catch (Exception e) {
            LOG.debug("pick preferred model from response failed", e);
        }

        Matcher m0 = Pattern.compile("\"default_model\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m0.find()) return m0.group(1);
        Matcher m1 = JSON_MODEL_PATTERN.matcher(body);
        if (m1.find()) return m1.group(1);
        Matcher m2 = JSON_ID_PATTERN.matcher(body);
        if (m2.find()) return m2.group(1);
        return null;
    }

    public static boolean readRemoteModelsOk(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("remote_models_ok") && obj.get("remote_models_ok").isJsonPrimitive()) {
                    return obj.get("remote_models_ok").getAsBoolean();
                }
            }
        } catch (Exception e) {
            LOG.debug("parse remote_models_ok failed", e);
        }
        return true;
    }

    public static String readModelsSource(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("models_source") && obj.get("models_source").isJsonPrimitive()) {
                    return obj.get("models_source").getAsString();
                }
            }
        } catch (Exception e) {
            LOG.debug("parse models_source failed", e);
        }
        return null;
    }

    private static @NotNull List<String> getStrings(JsonArray arr) {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e != null && e.isJsonObject()) {
                JsonObject m = e.getAsJsonObject();
                if (m.has("id") && m.get("id").isJsonPrimitive()) {
                    String id = m.get("id").getAsString();
                    if (id != null && !id.isBlank()) ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String pickPreferredModel(List<String> ids) {
        if (ids == null || ids.isEmpty()) return null;

        Set<String> set = new HashSet<>();
        List<String> unique = new ArrayList<>();
        for (String id : ids) {
            if (id == null) continue;
            String t = id.trim();
            if (t.isEmpty()) continue;
            if (set.add(t)) unique.add(t);
        }
        if (unique.isEmpty()) return null;

        String[] prefer = new String[]{"gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4"};
        for (String p : prefer) {
            for (String id : unique) {
                if (id.equals(p)) return id;
            }
        }
        for (String p : prefer) {
            for (String id : unique) {
                if (id.startsWith(p)) return id;
            }
        }
        return unique.getFirst();
    }
}
