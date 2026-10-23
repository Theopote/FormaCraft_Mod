package com.formacraft.common.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

/** A rejected program never carries executable partial output. */
public final class FbdException extends IllegalArgumentException {
    public final String code;
    public final String stage;
    public final String node;
    public final String path;
    private final Map<String, Object> metrics;

    public FbdException(String code, String stage, String node, String path, String message) {
        this(code, stage, node, path, message, Map.of());
    }

    private FbdException(String code, String stage, String node, String path, String message, Map<String, Object> metrics) {
        super(message);
        this.code = code;
        this.stage = stage;
        this.node = node;
        this.path = path;
        this.metrics = metrics;
    }

    public static FbdException budget(String stage, String node, String path, String layer, long limit, long actual) {
        return new FbdException("budget_exceeded", stage, node, path, layer + " budget exceeded",
                Map.of("layer", layer, "limit", limit, "actual", actual));
    }

    public Map<String, Object> diagnostic() {
        var result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("stage", stage);
        result.put("path", path);
        result.put("message", getMessage());
        if (node != null) result.put("node", node);
        result.putAll(metrics);
        return Map.copyOf(result);
    }
}
