package com.formacraft.common.archetype;

import java.util.List;
import java.util.Locale;

/**
 * 地标 MODULE 路由分级：区分「指名地标」「类型相似」「原创/多样化」用户意图。
 * <p>
 * 同一风格与尺寸也应允许不同作品——只有用户明确点名地标时才强制 MODULE；
 * 类型匹配（如椭圆体育场）仅作推荐；原创/不要地标类措辞则跳过强制路由。
 */
public final class LandmarkRoutingPolicy {

    public enum RoutingTier {
        /** 不注入地标路由 */
        NONE,
        /** 推荐 MODULE，但允许 MASS/plan_program 等原创组合 */
        SUGGESTED,
        /** 用户指名地标，必须 MODULE */
        MANDATORY
    }

    public record RoutingDecision(String moduleId, RoutingTier tier, String reason) {
        public boolean applies() {
            return moduleId != null && !moduleId.isBlank() && tier != RoutingTier.NONE;
        }
    }

    private static final List<String> LANDMARK_REJECTION_MARKERS = List.of(
            "不要地标", "不要鸟巢", "不要仿", "不要复制", "别照搬", "非地标",
            "not landmark", "not a landmark", "no landmark",
            "don't copy", "do not copy"
    );

    private static final List<String> VARIATION_INTENT_MARKERS = List.of(
            "原创", "独创", "独特", "不一样", "不要一样", "每次不同", "创新", "想象", "自由发挥",
            "自行设计", "自己设计",
            "generic", "original", "unique", "creative", "imaginative", "custom design",
            "varied", "different each time"
    );

    /** @deprecated use {@link #rejectsLandmarkModule} / {@link #isVariationIntent} */
    @SuppressWarnings("unused")
    private static final List<String> CREATIVE_INTENT_MARKERS = List.of(
            "原创", "独创", "独特", "不一样", "不要一样", "每次不同", "创新", "想象", "自由发挥",
            "不要地标", "不要鸟巢", "不要仿", "不要复制", "别照搬", "非地标", "自行设计", "自己设计",
            "generic", "original", "unique", "creative", "imaginative", "custom design",
            "don't copy", "do not copy", "not landmark", "not a landmark", "one of a kind",
            "varied", "different each time", "no landmark"
    );

    /** 指名鸟巢/国家体育场——强制 MODULE */
    private static final List<String> BIRDS_NEST_EXPLICIT = List.of(
            "鸟巢", "鸟巢体育馆", "国家体育场", "北京鸟巢",
            "bird's nest", "birds nest", "birds' nest", "beijing national stadium"
    );

    /** 类型相似（椭圆/碗状体育场）——推荐 MODULE，允许原创路径 */
    private static final List<String> STADIUM_TYPOLOGY = List.of(
            "体育场", "体育馆", "stadium", "arena", "球场"
    );
    private static final List<String> ELLIPSE_TYPOLOGY = List.of(
            "椭圆", "椭圆形", "elliptical", "oval", "碗状", "看台"
    );

    private static final List<String> NAMED_ARCHITECT_MARKERS = List.of(
            "扎哈", "zaha", "哈迪德", "hadid",
            "贝聿铭", "i.m. pei", "pei",
            "安藤忠雄", "tadao ando",
            "诺曼·福斯特", "norman foster",
            "伦佐·皮亚诺", "renzo piano",
            "让·努维尔", "jean nouvel"
    );

    private LandmarkRoutingPolicy() {}

    public static boolean rejectsLandmarkModule(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : LANDMARK_REJECTION_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVariationIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : VARIATION_INTENT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCreativeOrOriginalIntent(String text) {
        return rejectsLandmarkModule(text) || isVariationIntent(text);
    }

    /**
     * 解析用户意图对应的地标路由决策；无匹配返回 {@code null}。
     * <p>指名地标（MANDATORY）优先于「不一样」等多样化措辞。</p>
     */
    public static RoutingDecision resolveForUserIntent(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (rejectsLandmarkModule(text)) {
            return null;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        if (containsAny(lower, BIRDS_NEST_EXPLICIT)) {
            return new RoutingDecision("birds_nest_stadium", RoutingTier.MANDATORY, "explicit_birds_nest");
        }

        // 其它地标：用户指名别名时强制 MODULE（优先于多样化措辞）
        for (LandmarkModuleRegistry.LandmarkModule module : LandmarkModuleRegistry.listModules()) {
            if ("birds_nest_stadium".equals(module.moduleId())) {
                continue;
            }
            if (mentionsExplicitLandmark(lower, module)) {
                return new RoutingDecision(module.moduleId(), RoutingTier.MANDATORY, "explicit_landmark");
            }
        }

        if (containsAny(lower, NAMED_ARCHITECT_MARKERS) && containsAny(lower, STADIUM_TYPOLOGY)) {
            return null;
        }

        if (isVariationIntent(text)) {
            return null;
        }

        boolean stadium = containsAny(lower, STADIUM_TYPOLOGY);
        boolean elliptical = containsAny(lower, ELLIPSE_TYPOLOGY);
        if (stadium && elliptical) {
            return new RoutingDecision("birds_nest_stadium", RoutingTier.SUGGESTED, "typological_elliptical_stadium");
        }
        if (stadium && (lower.contains("现代") || lower.contains("modern"))) {
            return new RoutingDecision("birds_nest_stadium", RoutingTier.SUGGESTED, "typological_modern_stadium");
        }

        return null;
    }

    /**
     * 注入 prompt 的「建筑多样化」原则——对所有 build 请求生效。
     */
    public static String promptVariationPrinciples() {
        return """

            ========================================
            BUILDING VARIATION (every user deserves a distinct result)
            ========================================
            Even with the same style_profile and similar dimensions, DO NOT output identical plans.
            - Vary facade_profile, entrance placement/count, roof_type, decorative_elements, masses[] offsets.
            - Vary style_attributes accents (materials, colors) within the requested style family.
            - For MODULE landmarks: still vary dimensions (within limits), params.facing, params.meshStructure,
              params.designSeed (integer), params.bowlSteepness (0.2–0.5), style_attributes materials.
            - Prefer compositional MASS + ROOF + PAVING + ENTRANCE when user wants 原创/独特/不要地标.
            - Only reproduce a canonical landmark form when the user explicitly names that landmark.

            """;
    }

    static String promptBlockForDecision(RoutingDecision decision) {
        if (decision == null || !decision.applies()) {
            return "";
        }
        String moduleId = decision.moduleId();
        if (decision.tier() == RoutingTier.MANDATORY) {
            return """

                ========================================
                LANDMARK MODULE ROUTING (MANDATORY FOR THIS REQUEST)
                ========================================
                User explicitly named landmark module: %s
                You MUST output exactly ONE component:
                  { "component_type": "MODULE",
                    "relative_position": {"x":0,"y":0,"z":0},
                    "dimensions": {"width":60,"depth":80,"height":28},
                    "features": ["landmark:%s"],
                    "params": { "module_id": "%s", "meshStructure": true, "designSeed": <vary 1-9999> } }
                Do NOT substitute MASS_MAIN for this named landmark.
                Still vary designSeed, facing, dimensions hints, and style_attributes within the style.

                """.formatted(moduleId, moduleId, moduleId);
        }
        return """

            ========================================
            LANDMARK MODULE ROUTING (RECOMMENDED — creative alternative allowed)
            ========================================
            User intent matches typology for module: %s
            PREFERRED path (high-fidelity bowl/ellipse):
              { "component_type": "MODULE",
                "relative_position": {"x":0,"y":0,"z":0},
                "dimensions": {"width":60,"depth":80,"height":28},
                "features": ["landmark:%s"],
                "params": { "module_id": "%s", "meshStructure": true, "designSeed": <vary 1-9999>,
                            "bowlSteepness": <0.25-0.45>, "facing": "SOUTH|NORTH|EAST|WEST" } }
            ALTERNATIVE (原创/独特): compositional MASS_MAIN (shape=circle|rounded_rect, masses[] tiers)
              + PAVING inner field + ROOF canopy + ENTRANCE — vary layout each time.
            Do NOT default to a plain rectangular MASS box for elliptical stadium requests.
            MassMain cannot render true elliptical bowl seating; use MODULE or plan_program + tiered masses.

            """.formatted(moduleId, moduleId, moduleId);
    }

    private static boolean containsAny(String lower, List<String> markers) {
        for (String m : markers) {
            if (m != null && !m.isBlank() && lower.contains(m.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsExplicitLandmark(String lower, LandmarkModuleRegistry.LandmarkModule module) {
        if (module.aliases() == null) return false;
        for (String alias : module.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            String a = alias.toLowerCase(Locale.ROOT).trim();
            if (a.length() < 2) continue;
            // 跳过过宽的英文泛词，避免「stadium」误触发非鸟巢地标
            if (a.equals("stadium") || a.equals("arena") || a.equals("elliptical stadium")
                    || a.equals("oval stadium") || a.equals("体育馆") || a.equals("体育场")) {
                continue;
            }
            if (lower.contains(a)) {
                return true;
            }
        }
        return lower.contains(module.moduleId());
    }
}
