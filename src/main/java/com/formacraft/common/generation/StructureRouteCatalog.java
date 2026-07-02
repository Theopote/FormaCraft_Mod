package com.formacraft.common.generation;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 整栋生成器数据驱动路由表（Phase 3）。
 * <p>
 * 从 {@code assets/formacraft/generation/structure_routes_v1.json} 加载
 * styleProfile / template / buildingType 到 generatorKey 的映射。
 * 具体实例化由 {@code com.formacraft.common.generation.structure.router.StructureGeneratorRegistry} 完成。
 */
public final class StructureRouteCatalog {

    private static final String RESOURCE_PATH = "/assets/formacraft/generation/structure_routes_v1.json";

    private static volatile boolean loaded = false;
    private static Root root = new Root();

    private StructureRouteCatalog() {}

    public static String matchStyleProfile(String styleProfileId) {
        ensureLoaded();
        if (styleProfileId == null || styleProfileId.isBlank()) {
            return null;
        }
        String id = styleProfileId.trim();
        for (StyleProfileRoute route : root.styleProfiles) {
            if (route != null && id.equals(route.styleProfileId)) {
                return route.generatorKey;
            }
        }
        return null;
    }

    /**
     * 按 JSON 声明顺序匹配 template 关键词（更具体的规则应排在前面）。
     */
    public static String matchTemplate(String templateValue) {
        ensureLoaded();
        if (templateValue == null || templateValue.isBlank()) {
            return null;
        }
        String haystack = templateValue.trim().toLowerCase(Locale.ROOT);
        for (TemplateRoute route : root.templateRoutes) {
            if (route == null || route.keywords == null) continue;
            for (String keyword : route.keywords) {
                if (keyword == null || keyword.isBlank()) continue;
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return route.generatorKey;
                }
            }
        }
        return null;
    }

    public static String matchBuildingType(String buildingTypeName) {
        ensureLoaded();
        if (buildingTypeName == null || buildingTypeName.isBlank()) {
            return null;
        }
        String type = buildingTypeName.trim().toUpperCase(Locale.ROOT);
        for (BuildingTypeRoute route : root.buildingTypeFallback) {
            if (route != null && type.equals(route.buildingType)) {
                return route.generatorKey;
            }
        }
        return null;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        try (InputStream in = StructureRouteCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                FormacraftMod.LOGGER.warn("StructureRouteCatalog: resource not found: {}", RESOURCE_PATH);
                loaded = true;
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Root parsed = JsonUtil.fromJson(json, Root.class);
            if (parsed != null) {
                root = parsed;
            }
            loaded = true;
            FormacraftMod.LOGGER.info(
                    "StructureRouteCatalog loaded: {} styleProfiles, {} templateRoutes, {} typeFallbacks",
                    root.styleProfiles.size(),
                    root.templateRoutes.size(),
                    root.buildingTypeFallback.size()
            );
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("StructureRouteCatalog: failed to load {}", RESOURCE_PATH, t);
            loaded = true;
        }
    }

    public static final class Root {
        public int version = 1;
        public List<StyleProfileRoute> styleProfiles = new ArrayList<>();
        public List<TemplateRoute> templateRoutes = new ArrayList<>();
        public List<BuildingTypeRoute> buildingTypeFallback = new ArrayList<>();
    }

    public static final class StyleProfileRoute {
        public String styleProfileId;
        public String generatorKey;
    }

    public static final class TemplateRoute {
        public String id;
        public List<String> keywords = List.of();
        public String generatorKey;
    }

    public static final class BuildingTypeRoute {
        public String buildingType;
        public String generatorKey;
    }
}
