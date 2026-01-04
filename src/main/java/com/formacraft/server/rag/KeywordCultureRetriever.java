package com.formacraft.server.rag;

import java.util.*;

/**
 * Minimal keyword-based retriever:
 * prompt -> topK culture cards + exampleRefs + a macro/assembly draft.
 */
public final class KeywordCultureRetriever {
    private final CultureCardRepository repo;

    public KeywordCultureRetriever(CultureCardRepository repo) {
        this.repo = repo;
    }

    public record Hit(CultureCard card, double score, List<String> matchedKeywords, List<String> matchedNegatives) {}

    public record RetrievalResult(
            String prompt,
            List<Hit> hits,
            Map<String, Object> assemblyDraft,
            List<Object> fewShots,
            List<String> fewShotExampleRefs
    ) {}

    /**
     * Retrieve topK cards by simple substring keyword matching.
     *
     * P0+ scoring:
     * - keyword match: +weight (default 3, overridable by keywordWeights)
     * - synonym match: +min(2, keywordWeight)
     * - negative keyword match: -4 (per match)
     * - intent match: +1
     * - styleId token match: +1
     */
    public RetrievalResult retrieve(String prompt, int topK, int fewShotK) {
        String q = (prompt == null) ? "" : prompt.trim();
        String qNorm = q.toLowerCase(Locale.ROOT);

        List<Hit> hits = new ArrayList<>();
        for (CultureCard c : repo.allCards()) {
            double score = 0;
            ArrayList<String> matched = new ArrayList<>();
            ArrayList<String> matchedNeg = new ArrayList<>();

            // positive keywords
            for (String kw : c.keywords()) {
                if (kw == null || kw.isBlank()) continue;
                String kRaw = kw.trim();
                String k = kRaw.toLowerCase(Locale.ROOT);
                double w = Math.max(0.1, c.keywordWeights().getOrDefault(kRaw, 3.0));
                if (qNorm.contains(k)) {
                    score += w;
                    matched.add(kRaw);
                } else {
                    // synonyms: only apply if base keyword is not directly matched
                    List<String> syns = c.synonyms().getOrDefault(kRaw, List.of());
                    for (String syn : syns) {
                        if (syn == null || syn.isBlank()) continue;
                        String s0 = syn.trim();
                        if (qNorm.contains(s0.toLowerCase(Locale.ROOT))) {
                            score += Math.min(2.0, w);
                            matched.add(s0);
                            break;
                        }
                    }
                }
            }

            // negative keywords
            for (String neg : c.negativeKeywords()) {
                if (neg == null || neg.isBlank()) continue;
                String n = neg.trim().toLowerCase(Locale.ROOT);
                if (qNorm.contains(n)) {
                    score -= 4.0;
                    matchedNeg.add(neg.trim());
                }
            }
            for (String it : c.intents()) {
                if (it == null || it.isBlank()) continue;
                String k = it.trim().toLowerCase(Locale.ROOT);
                if (qNorm.contains(k)) score += 1.0;
            }
            for (String tok : splitStyleTokens(c.styleId())) {
                if (tok.isBlank()) continue;
                if (qNorm.contains(tok.toLowerCase(Locale.ROOT))) score += 1.0;
            }

            if (score > 0.01) {
                hits.add(new Hit(c, score, Collections.unmodifiableList(matched), Collections.unmodifiableList(matchedNeg)));
            }
        }

        hits.sort((a, b) -> {
            int s = Double.compare(b.score(), a.score());
            if (s != 0) return s;
            int mk = Integer.compare(b.matchedKeywords().size(), a.matchedKeywords().size());
            if (mk != 0) return mk;
            return a.card().id().compareToIgnoreCase(b.card().id());
        });

        if (topK <= 0) topK = 3;
        if (hits.size() > topK) hits = new ArrayList<>(hits.subList(0, topK));

        // Few-shot: take exampleRefs from top hit(s) + archetype exampleRef, dedupe.
        ArrayList<Object> fewShots = new ArrayList<>();
        ArrayList<String> fewShotRefs = new ArrayList<>();
        HashSet<String> used = new HashSet<>();
        int left = Math.max(0, fewShotK);
        for (Hit h : hits) {
            left = addExamples(h.card().exampleRefs(), used, fewShotRefs, fewShots, left);
            if (left > 0) {
                for (var a : h.card().archetypes()) {
                    if (left <= 0) break;
                    String ex = a.exampleRef();
                    if (ex == null || ex.isBlank()) continue;
                    left = addExamples(List.of(ex), used, fewShotRefs, fewShots, left);
                }
            }
            if (left <= 0) break;
        }

        Map<String, Object> draft = buildAssemblyDraft(q, hits);
        return new RetrievalResult(q, Collections.unmodifiableList(hits), draft, Collections.unmodifiableList(fewShots), Collections.unmodifiableList(fewShotRefs));
    }

    // ---------------- draft synthesis ----------------

    private static Map<String, Object> buildAssemblyDraft(String prompt, List<Hit> hits) {
        // Seed styleId from best card, but blend macro sliders from topK (fusion).
        CultureCard best = hits.isEmpty() ? null : hits.get(0).card();
        String styleId = (best != null) ? best.styleId() : "Unknown";

        double[] base = new double[]{0.6, 0.6, 0.6, 0.4, 0.5}; // density, symmetry, verticality, transparency, structureExposure
        double sum = 0;
        for (Hit h : hits) sum += Math.max(0.0, h.score());
        if (sum > 1e-6) {
            double[] acc = new double[]{0,0,0,0,0};
            for (Hit h : hits) {
                double w = Math.max(0.0, h.score()) / sum;
                double[] v = defaultVectorFor(h.card());
                for (int i = 0; i < acc.length; i++) acc[i] += v[i] * w;
            }
            base = acc;
        }

        // Prompt evidence nudges
        double density = clamp01(base[0] + (hasAny(prompt, "密集", "繁复", "高密度") ? 0.15 : 0.0));
        double symmetry = clamp01(base[1] + (hasAny(prompt, "对称", "轴线") ? 0.2 : 0.0));
        double verticality = clamp01(base[2] + (hasAny(prompt, "高耸", "尖塔", "垂直", "神圣") ? 0.25 : 0.0));
        double transparency = clamp01(base[3] + (hasAny(prompt, "玻璃", "透明", "幕墙", "采光") ? 0.25 : 0.0));
        double structureExposure = clamp01(base[4] + (hasAny(prompt, "结构外露", "桁架", "骨骼", "钢") ? 0.3 : 0.0));

        String intent = guessIntent(prompt, best);

        // Minimal default component: a primary SHELL_BOX. LLM/user can overwrite w/d/h later.
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("id", "Primary");
        primary.put("type", "SHELL_BOX");
        primary.put("at", Map.of("x", 0, "y", 0, "z", 0));
        primary.put("w", 18);
        primary.put("d", 12);
        primary.put("h", 14);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("components", List.of(primary));
        graph.put("connections", List.of());

        Map<String, Object> style = new LinkedHashMap<>();
        style.put("styleId", styleId);
        if (intent != null) style.put("intent", intent);
        style.put("density", density);
        style.put("symmetry", symmetry);
        style.put("verticality", verticality);
        style.put("transparency", transparency);
        style.put("structureExposure", structureExposure);

        Map<String, Object> macro = new LinkedHashMap<>();
        macro.put("style", style);

        Map<String, Object> assembly = new LinkedHashMap<>();
        assembly.put("graph", graph);
        assembly.put("macro", macro);
        return assembly;
    }

    private int addExamples(List<String> refs,
                            Set<String> used,
                            List<String> outRefs,
                            List<Object> outObjs,
                            int left) {
        for (String ex : refs) {
            if (left <= 0) break;
            if (ex == null || ex.isBlank()) continue;
            String key = ex.trim();
            if (!used.add(key)) continue;
            Object exObj = repo.loadExample(key);
            if (exObj != null) {
                outRefs.add(key);
                outObjs.add(exObj);
                left--;
            }
        }
        return left;
    }

    private static String guessIntent(String prompt, CultureCard card) {
        if (prompt == null) return (card != null && !card.intents().isEmpty()) ? card.intents().get(0) : null;
        String p = prompt.toLowerCase(Locale.ROOT);
        if (card != null) {
            for (String it : card.intents()) {
                if (it == null || it.isBlank()) continue;
                if (p.contains(it.trim().toLowerCase(Locale.ROOT))) return it.trim();
            }
        }
        if (p.contains("神圣") || p.contains("sacred")) return "神圣";
        if (p.contains("工业") || p.contains("industrial")) return "工业";
        if (p.contains("禅") || p.contains("zen")) return "禅意";
        if (p.contains("现代") || p.contains("modern")) return "现代";
        return (card != null && !card.intents().isEmpty()) ? card.intents().get(0) : null;
    }

    private static boolean hasAny(String s, String... keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static List<String> splitStyleTokens(String styleId) {
        if (styleId == null) return List.of();
        // split CamelCase and underscores: Gothic_Cathedral -> ["Gothic","Cathedral"]
        String normalized = styleId.replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) continue;
            // CamelCase split
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (i > 0 && Character.isUpperCase(ch) && cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(ch);
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double[] defaultVectorFor(CultureCard c) {
        String sid = (c.styleId() == null) ? "" : c.styleId().toUpperCase(Locale.ROOT);
        // density, symmetry, verticality, transparency, structureExposure
        if (sid.contains("GOTHIC")) return new double[]{0.7, 0.75, 0.9, 0.35, 0.8};
        if (sid.contains("INDUSTRIAL")) return new double[]{0.6, 0.7, 0.55, 0.5, 0.95};
        if (sid.contains("MODERN") || sid.contains("INTERNATIONAL")) return new double[]{0.55, 0.75, 0.55, 0.75, 0.35};
        if (sid.contains("JAPANESE")) return new double[]{0.45, 0.55, 0.5, 0.35, 0.25};
        if (sid.contains("CLASSICAL") || sid.contains("GRECOROMAN")) return new double[]{0.55, 0.8, 0.6, 0.25, 0.4};
        return new double[]{0.6, 0.6, 0.6, 0.4, 0.5};
    }
}


