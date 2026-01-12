package com.formacraft.common.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.semantic.BlockStatePropertyUtil;
import com.formacraft.common.component.semantic.SemanticBlockStatePicker;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Player Component Library（构件库）展开器：
 * - 从 LLM component.features 中读取 component_request:{...}
 * - 在服务端 world save 中匹配/加载 ComponentDefinition
 * - 展开为 BlockPatch（支持旋转/镜像 + 可选语义换皮）
 *
 * 约定：
 * - 返回 null 表示“该组件不是构件库请求”，上层应走正常生成器
 * - 返回 empty list 表示“是构件库请求，但未匹配到/无法生成”
 */
public final class PlayerComponentExpander {
    private PlayerComponentExpander() {}

    private static final String PREFIX = "component_request:";

    public static List<BlockPatch> tryExpand(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null || world == null) return null;
        String featureJson = extractFeatureJson(semantic.source().features());
        if (featureJson == null) return null;

        Map<String, Object> reqMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) JsonUtil.get().fromJson(featureJson, Map.class);
            reqMap = m;
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("PlayerComponentExpander: invalid component_request json: {}", featureJson);
            return List.of();
        }
        if (reqMap == null) return List.of();

        // 1) 确定要加载的构件
        String explicitId = getString(reqMap, "id", "component_id", "componentId");
        ComponentDefinition def;
        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        if (explicitId != null) {
            def = ComponentStorage.loadComponent(worldDir, explicitId);
        } else {
            ComponentRequest req = parseRequest(reqMap);
            def = ComponentLibrary.findBest(worldDir, req);
        }
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            return List.of();
        }

        // 2) 解析变换参数（facing/mirror）
        Direction fromFacing = parseDir(def.anchor != null ? def.anchor.facing : null);
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        Direction targetFacing = parseDir(getString(reqMap, "facing", "target_facing"));
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            targetFacing = facingFromSlot(semantic);
        }
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;

        Mirror mirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode"));
        ComponentTransform transform = new ComponentTransform(targetFacing, mirror);

        // 3) 放置偏移（相对于 slot anchor）
        int baseX = 0, baseY = 0, baseZ = 0;
        if (semantic.source().relativePosition() != null) {
            baseX = semantic.source().relativePosition().x();
            baseY = semantic.source().relativePosition().y();
            baseZ = semantic.source().relativePosition().z();
        }

        // 4) 是否启用语义换皮
        boolean semanticSkin = getBool(reqMap, true, "semantic_skin", "semanticSkin");
        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) {
            semanticStyleId = resolveSemanticStyleId(semantic.styleProfile());
        }

        // 5) 展开为 patches（相对 slot anchor）
        List<BlockPatch> out = new ArrayList<>(def.blocks.size());
        long worldSeed = world.getSeed();
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            BlockPos local = new BlockPos(be.dx, be.dy, be.dz);
            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, transform);

            int dx = baseX + off.getX();
            int dy = baseY + off.getY();
            int dz = baseZ + off.getZ();

            String block;
            if (semanticSkin && be.semantic != null) {
                long seed = mixSeed(worldSeed, dx, dy, dz, be.semantic.ordinal());
                BlockState picked = SemanticBlockStatePicker.pick(semanticStyleId, be.semantic, seed);

                // 若原始 blockstate 带有 facing，则把 facing 迁移到“换皮后”的方块上
                Direction capturedFacing = BlockStateStringUtil.extractFacing(be.block);
                if (capturedFacing != null) {
                    Direction tf = FacingTransformUtil.transformFacing(capturedFacing, fromFacing, transform);
                    picked = BlockStatePropertyUtil.applyFacing(picked, tf);
                }
                block = BlockStateStringUtil.fromState(picked);
            } else {
                if (be.block == null || be.block.isBlank()) continue;
                block = BlockStateStringUtil.withTransformedFacing(be.block, fromFacing, transform);
            }

            out.add(new BlockPatch(BlockPatch.PLACE, dx, dy, dz, block));
        }
        return out;
    }

    private static String extractFeatureJson(List<String> features) {
        if (features == null) return null;
        for (String f : features) {
            if (f == null) continue;
            String s = f.trim();
            if (s.isEmpty()) continue;
            if (s.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                String json = s.substring(PREFIX.length()).trim();
                return json.isEmpty() ? null : json;
            }
        }
        return null;
    }

    private static ComponentRequest parseRequest(Map<String, Object> reqMap) {
        ComponentRequest req = new ComponentRequest();

        String cat = getString(reqMap, "category", "type");
        if (cat != null) {
            try {
                req.category = ComponentCategory.valueOf(cat.trim().toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {
                req.category = null;
            }
        }

        Set<String> tags = parseTags(reqMap.get("tags"));
        req.tags = tags.isEmpty() ? null : tags;

        Object approx = reqMap.get("approx_size");
        if (approx instanceof Map<?, ?> am) {
            req.approxW = getInt(am, -1, "w", "width");
            req.approxH = getInt(am, -1, "h", "height");
            req.approxD = getInt(am, -1, "d", "depth");
        } else {
            req.approxW = getInt(reqMap, -1, "approxW", "approx_w");
            req.approxH = getInt(reqMap, -1, "approxH", "approx_h");
            req.approxD = getInt(reqMap, -1, "approxD", "approx_d");
        }
        return req;
    }

    private static Set<String> parseTags(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v == null) return out;
        if (v instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        String s = String.valueOf(v);
        if (s == null) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String resolveSemanticStyleId(String styleProfile) {
        if (styleProfile != null) {
            String s = styleProfile.trim();
            if (!s.isEmpty() && SemanticStyleProfileRegistry.get(s) != null) {
                return s;
            }
        }
        return "DEFAULT";
    }

    private static Direction facingFromSlot(SemanticComponent semantic) {
        if (semantic == null || semantic.slot() == null || semantic.slot().facing() == null) return null;
        return switch (semantic.slot().facing()) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    private static Direction parseDir(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Direction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Mirror parseMirror(String s) {
        if (s == null || s.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return Mirror.NONE;
        }
    }

    private static String getString(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static boolean getBool(Map<?, ?> m, boolean def, String... keys) {
        if (m == null || keys == null) return def;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        return def;
    }

    private static int getInt(Map<?, ?> m, int def, String... keys) {
        if (m == null || keys == null) return def;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private static long mixSeed(long base, int x, int y, int z, int t) {
        long h = 1469598103934665603L ^ base;
        h ^= x; h *= 1099511628211L;
        h ^= y; h *= 1099511628211L;
        h ^= z; h *= 1099511628211L;
        h ^= t; h *= 1099511628211L;
        return h;
    }
}

