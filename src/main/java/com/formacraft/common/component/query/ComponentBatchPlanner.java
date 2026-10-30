package com.formacraft.common.component.query;

import java.util.*;
import java.util.function.Function;

/** Coordinates already permitted fits; installation and geometry commits remain separate. */
public final class ComponentBatchPlanner {
    private ComponentBatchPlanner() {}

    public record Need(String id, ComponentQuery query, boolean required) {
        public Need {
            if (id == null || id.isBlank() || query == null) throw new IllegalArgumentException("Need requires id and query");
        }
    }
    public record Alternative(String suite, double score, int matchedNeeds) {}
    public record Plan(String suite, boolean complete,
                       Map<String, ComponentRetriever.FittedCandidate> selected,
                       Map<String, ComponentResolveResult> unresolved,
                       List<Alternative> alternatives) {
        public Plan {
            selected = Collections.unmodifiableMap(new LinkedHashMap<>(selected));
            unresolved = Collections.unmodifiableMap(new LinkedHashMap<>(unresolved));
            alternatives = List.copyOf(alternatives);
        }
    }

    public static Plan plan(List<Need> needs, long seed) { return plan(needs, seed, .05); }

    public static Plan plan(List<Need> needs, long seed, double suiteBonus) {
        return plan(needs, suiteBonus, need -> ComponentRetriever.retrieveCandidatePool(
                need.query(), new Random(seed ^ need.id().hashCode())));
    }

    static Plan plan(List<Need> needs, double bonus,
                     Function<Need, ComponentRetriever.CandidatePool> resolver) {
        if (!Double.isFinite(bonus) || bonus < 0 || bonus > 1)
            throw new IllegalArgumentException("Suite bonus must be finite and between 0 and 1");
        var ordered = new TreeMap<String, Need>();
        for (Need need : needs) {
            Objects.requireNonNull(need, "need");
            if (ordered.putIfAbsent(need.id(), need) != null) throw new IllegalArgumentException("Duplicate need: " + need.id());
        }
        var pools = new LinkedHashMap<String, ComponentRetriever.CandidatePool>();
        var suites = new TreeSet<String>(); suites.add("");
        var unresolved = new LinkedHashMap<String, ComponentResolveResult>();
        boolean complete = true;
        for (Need need : ordered.values()) {
            var pool = Objects.requireNonNull(resolver.apply(need), "candidate pool");
            pools.put(need.id(), pool);
            if (pool.candidates().isEmpty()) {
                unresolved.put(need.id(), pool.report().result());
                if (need.required()) complete = false;
            } else for (var candidate : pool.candidates()) suites.addAll(candidate.suites());
        }
        var alternatives = new ArrayList<Alternative>();
        Map<String, ComponentRetriever.FittedCandidate> bestSelection = Map.of();
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestSuite = "";
        for (String suite : suites) {
            var selected = new LinkedHashMap<String, ComponentRetriever.FittedCandidate>();
            double total = 0;
            int matched = 0;
            for (Need need : ordered.values()) {
                ComponentRetriever.FittedCandidate best = null;
                double value = Double.NEGATIVE_INFINITY;
                boolean explicit = need.query().selection != null && need.query().selection.preferredSuite != null
                        && !need.query().selection.preferredSuite.isBlank();
                for (var candidate : pools.get(need.id()).candidates()) {
                    double score = candidate.score().total()
                            + (!explicit && candidate.suites().contains(suite) ? bonus : 0);
                    if (score > value || (Double.compare(score, value) == 0
                            && (best == null || candidate.sourceId().compareTo(best.sourceId()) < 0))) {
                        best = candidate; value = score;
                    }
                }
                if (best != null) {
                    selected.put(need.id(), best); total += value;
                    if (best.suites().contains(suite)) matched++;
                }
            }
            alternatives.add(new Alternative(suite, total, matched));
            // Sorted enumeration keeps no-preference first, then stable suite identity on ties.
            if (total > bestScore) { bestSelection = selected; bestScore = total; bestSuite = suite; }
        }
        return new Plan(bestSuite, complete, bestSelection, unresolved, alternatives);
    }
}
