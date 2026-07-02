package com.formacraft.common.skeleton;

import com.formacraft.common.logging.FcaLog;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * 可执行的 SkeletonPlan（用于 Generator 输入）
 * <p>
 * 从 LLM 输出、{@link SkeletonPlan} 桥接或工具推导出来的 skeleton 计划。
 * <p>
 * Phase 1：从 {@code server.skeleton.gen} 迁至 {@code common.skeleton}，
 * 作为骨架层统一 DTO，解除 common 编译器对 server 包的依赖。
 */
public class ExecutableSkeletonPlan {
    private static final FcaLog LOG = FcaLog.of("ExecutableSkeletonPlan");

    public final SkeletonType type;

    /** 参数表：例如 width, height, radius, points 等 */
    public final Map<String, Object> params = new HashMap<>();

    /** COMPOUND：子 skeleton 列表 */
    public final List<ExecutableSkeletonPlan> children = new ArrayList<>();

    public int length = 10;
    public int height = 5;
    public int width = 1;
    public Direction facing = Direction.NORTH;
    public boolean conformTerrain = true;
    public HeightPolicy heightPolicy = HeightPolicy.FLAT;

    public enum HeightPolicy {
        FLAT,
        FOLLOW_TERRAIN,
        STEP_UP,
        SLOPE
    }

    public ExecutableSkeletonPlan(SkeletonType type) {
        this.type = Objects.requireNonNull(type);
    }

    public ExecutableSkeletonPlan put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object v = params.get(key);
        if (v == null) return defaultValue;
        try {
            return (T) v;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    public ExecutableSkeletonPlan addChild(ExecutableSkeletonPlan child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }

    public ExecutableSkeletonPlan applyParams() {
        if (params.containsKey("length")) {
            this.length = toInt(params.get("length"), this.length);
        }
        if (params.containsKey("height")) {
            this.height = toInt(params.get("height"), this.height);
        }
        if (params.containsKey("width")) {
            this.width = toInt(params.get("width"), this.width);
        }

        if (params.containsKey("facing")) {
            Object v = params.get("facing");
            if (v instanceof Direction d) {
                this.facing = d;
            } else if (v instanceof String s) {
                try {
                    this.facing = Direction.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    LOG.debug("parse facing failed value={}", s, ex);
                }
            }
        }

        if (params.containsKey("conformTerrain")) {
            Object v = params.get("conformTerrain");
            if (v instanceof Boolean b) {
                this.conformTerrain = b;
            } else if (v instanceof String s) {
                this.conformTerrain = Boolean.parseBoolean(s);
            }
        }

        if (params.containsKey("heightPolicy")) {
            Object v = params.get("heightPolicy");
            if (v instanceof HeightPolicy hp) {
                this.heightPolicy = hp;
            } else if (v instanceof String s) {
                try {
                    this.heightPolicy = HeightPolicy.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    LOG.debug("parse heightPolicy failed value={}", s, ex);
                }
            }
        }

        return this;
    }

    /**
     * 从 LLM component.params 或 feature JSON 解析可执行骨架计划。
     */
    @SuppressWarnings("unchecked")
    public static ExecutableSkeletonPlan fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object typeObj = map.get("type");
        if (typeObj == null) {
            typeObj = map.get("skeleton_type");
        }
        if (typeObj == null) {
            return null;
        }
        SkeletonType type;
        try {
            type = SkeletonType.valueOf(String.valueOf(typeObj).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }

        ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(type);
        Object nestedParams = map.get("params");
        if (nestedParams instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (e.getKey() != null) {
                    plan.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            if (key.equals("type") || key.equals("skeleton_type") || key.equals("params") || key.equals("children")) {
                continue;
            }
            plan.put(key, e.getValue());
        }
        Object children = map.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> cm) {
                    ExecutableSkeletonPlan childPlan = fromMap((Map<String, Object>) cm);
                    if (childPlan != null) {
                        plan.addChild(childPlan);
                    }
                }
            }
        }
        return plan.applyParams();
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
