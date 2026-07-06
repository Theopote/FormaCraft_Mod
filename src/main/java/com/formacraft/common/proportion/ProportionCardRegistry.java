package com.formacraft.common.proportion;

import com.formacraft.common.json.JsonUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads proportion_cards/*.json for prompt injection and validation hints.
 */
public final class ProportionCardRegistry {

    public record RatioSpec(double min, double ideal, double max, String desc) {}

    public record OpeningGrammar(
            List<String> windowAspect,
            double minEnclosureCoverage,
            double maxVoidRatio
    ) {}

    public record ProportionCard(
            String id,
            String typology,
            List<String> matchKeywords,
            Map<String, RatioSpec> ratios,
            OpeningGrammar openingGrammar,
            String aiInstruction
    ) {}

    private static volatile List<ProportionCard> cached;

    private ProportionCardRegistry() {}

    public static List<ProportionCard> listCards() {
        if (cached != null) {
            return cached;
        }
        synchronized (ProportionCardRegistry.class) {
            if (cached != null) {
                return cached;
            }
            cached = loadCards();
            return cached;
        }
    }

    public static ProportionCard resolveForPrompt(String userText) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        ProportionCard best = null;
        double bestScore = 0;
        for (ProportionCard card : listCards()) {
            double score = 0;
            for (String kw : card.matchKeywords()) {
                if (kw != null && !kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    score += Math.max(2.0, kw.length() * 0.5);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = card;
            }
        }
        return bestScore > 0.01 ? best : null;
    }

    public static ProportionCard getById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        for (ProportionCard c : listCards()) {
            if (key.equals(c.id())) {
                return c;
            }
        }
        return null;
    }

    public static String promptBlockForCard(ProportionCard card) {
        if (card == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""

            ========================================
            PROPORTION ONTOLOGY (research before dimensions)
            ========================================
            """);
        sb.append("Typology: ").append(card.typology() != null ? card.typology() : card.id()).append("\n");
        if (card.aiInstruction() != null && !card.aiInstruction().isBlank()) {
            sb.append(card.aiInstruction()).append("\n");
        }
        sb.append("You MUST output proportion_hints in LlmPlan (numeric targets from ratios below).\n");
        if (card.ratios() != null && !card.ratios().isEmpty()) {
            sb.append("Ratio targets:\n");
            for (Map.Entry<String, RatioSpec> e : card.ratios().entrySet()) {
                RatioSpec r = e.getValue();
                sb.append("  - ").append(e.getKey())
                  .append(": ideal=").append(r.ideal())
                  .append(" range=[").append(r.min()).append(",").append(r.max()).append("]");
                if (r.desc() != null && !r.desc().isBlank()) {
                    sb.append(" ").append(r.desc());
                }
                sb.append("\n");
            }
        }
        OpeningGrammar og = card.openingGrammar();
        if (og != null) {
            sb.append("Opening / enclosure: window_aspect=").append(og.windowAspect())
              .append(" min_enclosure_coverage=").append(og.minEnclosureCoverage())
              .append(" max_void_ratio=").append(og.maxVoidRatio()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public static String promptBlockForIntent(String userIntent, String proportionCardId) {
        ProportionCard card = null;
        if (proportionCardId != null && !proportionCardId.isBlank()) {
            card = getById(proportionCardId);
        }
        if (card == null) {
            card = resolveForPrompt(userIntent);
        }
        return promptBlockForCard(card);
    }

    @SuppressWarnings("unchecked")
    private static List<ProportionCard> loadCards() {
        List<String> names = List.of(
                "cottage_refined.json",
                "castle_wall.json",
                "stadium_bowl.json",
                "suspension_bridge.json",
                "gothic_cathedral_hall.json",
                "courtyard_compound.json",
                "classical_monument.json",
                "siheyuan_courtyard.json",
                "square_tower_five_story.json",
                "temple_of_heaven.json",
                "famen_pagoda.json",
                "foguang_temple_hall.json"
        );
        List<ProportionCard> out = new ArrayList<>();
        for (String name : names) {
            String path = "/assets/formacraft/proportion_cards/" + name;
            try (InputStream in = ProportionCardRegistry.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                Map<String, Object> root = JsonUtil.get().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8), Map.class);
                ProportionCard card = parseCard(root);
                if (card != null) {
                    out.add(card);
                }
            } catch (Exception ignored) {
                // best-effort load
            }
        }
        // Dev fallback: scan classpath via file system not available in prod jar — list is fixed
        return Collections.unmodifiableList(out);
    }

    @SuppressWarnings("unchecked")
    private static ProportionCard parseCard(Map<String, Object> root) {
        if (root == null) {
            return null;
        }
        String id = str(root.get("id"));
        if (id == null) {
            return null;
        }
        String typology = Optional.ofNullable(str(root.get("typology"))).orElse(id);
        List<String> keywords = strList(root.get("matchKeywords"));

        Map<String, RatioSpec> ratios = new LinkedHashMap<>();
        Object ratiosObj = root.get("ratios");
        if (ratiosObj instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof Map<?, ?> spec)) {
                    continue;
                }
                ratios.put(key, new RatioSpec(
                        dbl(spec.get("min"), 0),
                        dbl(spec.get("ideal"), 0),
                        dbl(spec.get("max"), 1),
                        str(spec.get("desc"))
                ));
            }
        }

        OpeningGrammar og = null;
        Object ogObj = root.get("openingGrammar");
        if (ogObj instanceof Map<?, ?> om) {
            og = new OpeningGrammar(
                    strList(om.get("window_aspect")),
                    dbl(om.get("min_enclosure_coverage"), 0.8),
                    dbl(om.get("max_void_ratio"), 0.3)
            );
        }

        return new ProportionCard(id, typology, keywords, ratios, og, str(root.get("aiInstruction")));
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static double dbl(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = str(o);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }
}
