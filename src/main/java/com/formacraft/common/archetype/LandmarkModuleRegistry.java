package com.formacraft.common.archetype;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * LandmarkModuleRegistry（Phase 7：地标精品库化）。
 * <p>
 * 把 {@link ArchetypeRegistry} 中已有的、有专用整栋生成器的"固定形象地标"
 * （埃菲尔铁塔 / 长城 / 天坛 / 土楼 …）暴露为 LlmPlan 可直接引用的<b>命名模块</b>。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>不新增生成器，只在 archetype 之上提供一个"面向 LLM"的目录 + 解析入口；</li>
 *   <li>LLM 通过在某个 component 上写 feature {@code "landmark:<module_id>"}
 *       （或 {@code "module:<module_id>"}）来引用模块，走既有的
 *       {@code StructureGeneratorAdaptor → GeneratorRouter} 链路；</li>
 *   <li>此类只依赖 {@code common}，因此客户端 Prompt 组装与服务端路由都能用。</li>
 * </ul>
 */
public final class LandmarkModuleRegistry {

    private LandmarkModuleRegistry() {}

    /** 面向 LLM 的模块视图：稳定 id + 人类可读别名（用于 prompt 列表）。 */
    public record LandmarkModule(String moduleId, String category, List<String> aliases) {
        /** 取一个 CJK 别名 + 一个拉丁别名，用于 prompt 展示。 */
        String displayHint() {
            String cjk = null;
            String latin = null;
            for (String a : aliases) {
                if (a == null || a.isBlank()) continue;
                boolean hasCjk = a.chars().anyMatch(ch -> ch > 127);
                if (hasCjk && cjk == null) {
                    cjk = a.trim();
                } else if (!hasCjk && latin == null) {
                    latin = a.trim();
                }
            }
            if (cjk != null && latin != null) return cjk + " / " + latin;
            if (cjk != null) return cjk;
            if (latin != null) return latin;
            return moduleId;
        }
    }

    /**
     * 全部可用地标模块（当前 = archetypes_v1.json 中登记且有生成器的条目）。
     */
    public static List<LandmarkModule> listModules() {
        List<LandmarkModule> out = new ArrayList<>();
        for (ArchetypeCatalog.ArchetypeDef def : ArchetypeRegistry.all()) {
            if (def == null || def.id == null || def.id.isBlank()) continue;
            if (!def.hasModuleGenerator()) continue;
            out.add(new LandmarkModule(
                    def.id.trim().toLowerCase(Locale.ROOT),
                    def.category == null ? "LANDMARK" : def.category,
                    def.aliases == null ? List.of() : def.aliases));
        }
        out.sort(Comparator.comparing(LandmarkModule::moduleId));
        return out;
    }

    /**
     * 把用户意图文本解析为规范 module_id；无法解析返回 {@code null}。
     * <p>
     * 与 {@link #resolveModuleId(String)} 相同，供路由/Compiler 侧语义化调用。
     */
    public static String resolveModuleIdFromIntent(String intentText) {
        return resolveModuleId(intentText);
    }

    /**
     * 把用户文本 / 模块名解析为规范 module_id；无法解析返回 {@code null}。
     * <p>
     * 既支持精确 id，也支持关键词（别名子串）匹配，与 {@code GeneratorRouter} 一致。
     */
    public static String resolveModuleId(String text) {
        if (text == null || text.isBlank()) return null;
        ArchetypeCatalog.ArchetypeDef def = ArchetypeRegistry.getById(text.trim());
        if (def == null) def = ArchetypeRegistry.matchByKeyword(text);
        if (def == null || def.id == null || def.id.isBlank()) return null;
        if (!def.hasModuleGenerator()) return null;
        return def.id.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isModule(String id) {
        return resolveModuleId(id) != null;
    }

    /**
     * 生成注入 prompt 的"可用地标模块"清单。无模块时返回空串（调用方据此跳过）。
     */
    public static String promptListing() {
        List<LandmarkModule> modules = listModules();
        if (modules.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================\n");
        sb.append("AVAILABLE LANDMARK MODULES (fixed-form iconic structures)\n");
        sb.append("========================================\n");
        sb.append("Some buildings have a FIXED, well-known canonical form (a specific landmark),\n");
        sb.append("NOT an imaginative/generic building. For those, DO NOT improvise the geometry.\n");
        sb.append("Instead reference a prebuilt module by adding a feature string to ONE component:\n");
        sb.append("  { \"component_type\": \"MODULE\", \"relative_position\": {\"x\":0,\"y\":0,\"z\":0},\n");
        sb.append("    \"dimensions\": {\"width\":W,\"depth\":D,\"height\":H},\n");
        sb.append("    \"features\": [\"landmark:<module_id>\"] }\n");
        sb.append("- dimensions are a size HINT for the module (it scales toward them within its own limits).\n");
        sb.append("- Use a module ONLY when the request clearly names/implies that specific landmark.\n");
        sb.append("- For generic or imaginative buildings, IGNORE this section and use normal semantic components.\n");
        sb.append("\nLANDMARK ROUTING HINTS:\n");
        sb.append("- 鸟巢 / Bird's Nest / 国家体育场 (explicit name) → MANDATORY landmark:birds_nest_stadium\n");
        sb.append("- 椭圆/椭圆形 + 体育场/体育馆 → RECOMMENDED landmark:birds_nest_stadium OR compositional MASS tiers\n");
        sb.append("- 原创/独特/不要地标 → do NOT force MODULE; compose with varied MASS + PAVING + ROOF\n");
        sb.append("- MassMainGenerator cannot render true elliptical bowl seating; use MODULE or tiered masses/plan_program.\n");
        sb.append("Available module_id values:\n");
        for (LandmarkModule m : modules) {
            sb.append("  * ").append(m.moduleId())
              .append(" (").append(m.displayHint()).append(")\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 按 {@link LandmarkRoutingPolicy} 分级注入地标路由提示（注入 PromptAssembler）。
     */
    public static String promptRoutingHintForIntent(String userIntentText) {
        LandmarkRoutingPolicy.RoutingDecision decision =
                LandmarkRoutingPolicy.resolveForUserIntent(userIntentText);
        return LandmarkRoutingPolicy.promptBlockForDecision(decision);
    }
}
