package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-private design-omit host binding helpers carved from RequestIntentInjector.
 */
final class DesignOmitBindingResolver {
    private DesignOmitBindingResolver() {}

    static OmitBindResult resolveDesignOmitBindings(
            LlmPlan plan,
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding> bindings
    ) {
        Map<String, Set<String>> out = new HashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean gap = false;
        if (bindings == null || bindings.isEmpty()) {
            return new OmitBindResult(out, diagnostics, false);
        }
        if (hosts == null || hosts.isEmpty()) {
            for (var b : bindings) {
                if (b == null || b.domain() == null || b.domain().isBlank()) continue;
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_DESIGN_HOST_MISSING");
                detail.put("domain", b.domain());
                detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                detail.put("message", "explicit design omit with no design-constraint host");
                diagnostics.add(detail);
            }
            return new OmitBindResult(out, diagnostics, gap);
        }
        for (var b : bindings) {
            if (b == null || b.domain() == null || b.domain().isBlank()) continue;
            String hint = b.roleHint();
            Set<String> javaTargets = new LinkedHashSet<>();
            boolean ambiguous = false;
            boolean scopedIdentity = hint != null && !hint.isBlank();
            // Unscoped omit may bind the sole building body; named/ordinal/multi always verify identity.
            if (!scopedIdentity) {
                if (!multi || hosts.size() == 1) {
                    String one = primary;
                    if (one == null || !BuildingHostResolver.isKnownDesignHostId(hosts, one)) {
                        one = BuildingHostResolver.massId(hosts.getFirst());
                    }
                    if (one != null && BuildingHostResolver.isKnownDesignHostId(hosts, one)) {
                        javaTargets.add(one);
                    } else {
                        ambiguous = true;
                        gap = true;
                        diagnostics.add(Map.of(
                                "code", "E_OMIT_HOST_MISSING",
                                "domain", b.domain(),
                                "message", "omit target is not a design-constraint host"));
                    }
                } else {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_UNSCOPED",
                            "domain", b.domain(),
                            "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                            "message", "multi-mass omit without role/ordinal/quantifier scope"));
                }
            } else if ("all_main_masses".equals(hint)) {
                for (Component host : hosts) {
                    String mid = BuildingHostResolver.massId(host);
                    if (mid != null && !mid.isBlank()) javaTargets.add(mid);
                }
                if (javaTargets.isEmpty()) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_UNSCOPED",
                            "domain", b.domain(),
                            "roleHint", "all_main_masses",
                            "message", "all-mass omit found no host masses"));
                }
            } else if ("multi_named".equals(hint)) {
                List<String> keys = b.namedKeys() == null ? List.of() : b.namedKeys();
                if (keys.isEmpty()) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_UNSCOPED",
                            "domain", b.domain(),
                            "roleHint", "multi_named",
                            "message", "multi_named omit missing named_keys"));
                } else {
                    for (String key : keys) {
                        List<String> ids = namedKeyHostIds(hosts, key, b.sourceText());
                        if (ids.isEmpty()) {
                            ambiguous = true;
                            gap = true;
                            Map<String, Object> detail = new HashMap<>();
                            detail.put("code", "E_OMIT_HOST_TIE");
                            detail.put("domain", b.domain());
                            detail.put("roleHint", "multi_named");
                            detail.put("namedKey", key);
                            detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                            detail.put("message", "multi_named omit subject missing or not unique");
                            diagnostics.add(detail);
                            javaTargets.clear();
                            break;
                        }
                        javaTargets.addAll(ids);
                    }
                }
            } else if (hint.startsWith("building_")) {
                String javaTarget = resolveOrdinalOmitHost(hosts, primary, hint);
                if (javaTarget == null) {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_TIE",
                            "domain", b.domain(),
                            "roleHint", hint,
                            "message", "ordinal omit host is not unique"));
                } else {
                    javaTargets.add(javaTarget);
                }
            } else {
                // namedKeys first (wing≠pavilion); roleHint only when no named identity.
                List<String> namedHits = resolveNamedOrRoleHostIds(
                        hosts, hint, b.namedKeys(), b.sourceText());
                if (!namedHits.isEmpty()) {
                    javaTargets.addAll(namedHits);
                } else {
                    ambiguous = true;
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_OMIT_HOST_TIE",
                            "domain", b.domain(),
                            "roleHint", hint == null ? "" : hint,
                            "sourceText", b.sourceText() == null ? "" : b.sourceText(),
                            "message", "omit host role score is not unique or missing"));
                }
            }
            // Text ambiguity: never stamp and never accept plan-supplied target_components.
            if (ambiguous) {
                continue;
            }
            PythonOmitLookup pyLookup = pythonOmitTargetsForDeclaration(plan, b);
            Set<String> targets = javaTargets;
            if (pyLookup.contractPresent() && pyLookup.unresolvedDeclaration()) {
                targets = Set.of();
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_OMIT_PYTHON_TARGET_UNVERIFIED");
                detail.put("domain", b.domain());
                detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                detail.put("message", "building_contract present but omit declaration could not be matched");
                diagnostics.add(detail);
            } else if (!pyLookup.targets().isEmpty()) {
                if (pyLookup.targets().equals(javaTargets)) {
                    targets = javaTargets;
                } else {
                    targets = Set.of();
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_OMIT_HOST_MISMATCH");
                    detail.put("domain", b.domain());
                    detail.put("sourceText", b.sourceText() == null ? "" : b.sourceText());
                    detail.put("pythonTargets", List.copyOf(pyLookup.targets()));
                    detail.put("javaTargets", List.copyOf(javaTargets));
                    detail.put("message", "python and java omit hosts disagree for this declaration");
                    diagnostics.add(detail);
                }
            }
            for (String target : targets) {
                if (target == null || target.isBlank()) continue;
                if (!BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_OMIT_HOST_MISSING");
                    detail.put("domain", b.domain());
                    detail.put("target", target);
                    detail.put("message", "omit target is not a design-constraint host");
                    diagnostics.add(detail);
                    continue;
                }
                out.computeIfAbsent(target, k -> new HashSet<>()).add(b.domain());
            }
        }
        return new OmitBindResult(out, diagnostics, gap);
    }

    /**
     * Resolve a named building key via identity aliases — never hall-fallback for office/keep.
     * Stacked storeys of one tower ({@code pagoda_tier_1..N}) count as one building entity and
     * return every storey id so omit/height seals cover the whole shaft.
     */
    static String uniqueNamedKeyHostId(List<Component> hosts, String namedKey) {
        List<String> ids = namedKeyHostIds(hosts, namedKey, null);
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /**
     * Prefer explicit {@code namedKeys} (wing/office/…). Side-qualified misses stay fail-closed
     * (西厢房 must not bind 东厢房). Class aliases like 主殿→hall may still resolve via role score
     * when the plan used a generic component_id without the alias string.
     * Role-score-only path runs when the request has no named identity.
     * Classification: HARD_CONSTRAINT host identity.
     */
    static List<String> resolveNamedOrRoleHostIds(
            List<Component> hosts,
            String roleHint,
            List<String> namedKeys,
            String clauseText
    ) {
        if (namedKeys != null && !namedKeys.isEmpty()) {
            List<String> namedHits;
            if (namedKeys.size() == 1) {
                namedHits = namedKeyHostIds(hosts, namedKeys.getFirst(), clauseText);
            } else {
                List<String> all = new ArrayList<>();
                for (String key : namedKeys) {
                    List<String> part = namedKeyHostIds(hosts, key, clauseText);
                    if (part.isEmpty()) {
                        return List.of();
                    }
                    for (String id : part) {
                        if (!all.contains(id)) all.add(id);
                    }
                }
                namedHits = List.copyOf(all);
            }
            if (!namedHits.isEmpty()) return namedHits;
            // Side-qualified named miss (西厢房 / 东厢房 / …) — never role-fallback.
            if (isSideQualifiedNamedMiss(namedKeys, clauseText)) {
                return List.of();
            }
            // Class named key present in text but plan ids lack the alias string (house_main vs 主殿).
            return roleScoreHostIds(hosts, roleHint, clauseText);
        }
        return roleScoreHostIds(hosts, roleHint, clauseText);
    }

    /** True when namedKeys include wing and the clause demands a cardinal side. */
    static boolean isSideQualifiedNamedMiss(List<String> namedKeys, String clauseText) {
        if (namedKeys == null || namedKeys.isEmpty()) return false;
        boolean hasWing = false;
        for (String key : namedKeys) {
            if (key != null && "wing".equalsIgnoreCase(key.trim())) {
                hasWing = true;
                break;
            }
        }
        return hasWing && !wingSidesFromText(clauseText).isEmpty();
    }

    static List<String> roleScoreHostIds(
            List<Component> hosts, String roleHint, String clauseText) {
        if (roleHint == null || roleHint.isBlank()) return List.of();
        List<String> hits = namedKeyHostIds(hosts, roleHint, clauseText);
        if (!hits.isEmpty()) return hits;
        String roleTarget = uniqueRoleHostId(hosts, roleHint);
        return roleTarget == null ? List.of() : List.of(roleTarget);
    }

    static List<String> namedKeyHostIds(List<Component> hosts, String namedKey) {
        return namedKeyHostIds(hosts, namedKey, null);
    }

    static List<String> namedKeyHostIds(List<Component> hosts, String namedKey, String clauseText) {
        if (namedKey == null || namedKey.isBlank()) return List.of();
        String key = namedKey.trim().toLowerCase(Locale.ROOT);
        List<String> aliases = com.formacraft.common.typology.UserConstraintContract.aliasesForNamedKey(key);
        List<String> hits = new ArrayList<>();
        Map<String, Component> byId = new HashMap<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid == null) continue;
            String blob = hostIdentityBlob(host);
            boolean matched = mid.equalsIgnoreCase(key);
            if (!matched) {
                for (String alias : aliases) {
                    if (alias != null && !alias.isBlank() && blob.contains(alias.toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) {
                hits.add(mid);
                byId.put(mid, host);
            }
        }
        if (hits.isEmpty()) return List.of();
        if ("wing".equals(key)) {
            Set<String> sides = wingSidesFromText(clauseText);
            if (!sides.isEmpty()) {
                List<String> resolved = new ArrayList<>();
                for (String side : sides) {
                    List<String> sided = new ArrayList<>();
                    for (String id : hits) {
                        Component host = byId.get(id);
                        if (host != null && hostMatchesWingSide(hostIdentityBlob(host), side)) {
                            sided.add(id);
                        }
                    }
                    // Each declared side must uniquely bind; empty/multi → ambiguity.
                    if (sided.size() != 1) return List.of();
                    if (!resolved.contains(sided.getFirst())) {
                        resolved.add(sided.getFirst());
                    }
                }
                return List.copyOf(resolved);
            }
            // Plural role-class without side cue — keep every wing.
            return List.copyOf(hits);
        }
        if (hits.size() == 1) return List.copyOf(hits);
        // Stacked storeys of one tower (pagoda_tier_*) count as one building entity.
        if (isStackedFamilyIds(hits)) {
            return List.copyOf(hits);
        }
        // Singular named alias matching unrelated buildings — fail closed.
        return List.of();
    }

    /** {@code pagoda_tier_3} → {@code pagoda}; non-tier ids → null. */
    static String stackedFamilyKey(String componentId) {
        if (componentId == null || componentId.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)(?:_tier_?|_level_?|_storey_?)(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(componentId.trim());
        if (!m.matches()) return null;
        String stem = m.group(1);
        return stem == null || stem.isBlank() ? null : stem.toLowerCase(Locale.ROOT);
    }

    static boolean isStackedFamilyIds(List<String> ids) {
        if (ids == null || ids.size() < 2) return false;
        String family = stackedFamilyKey(ids.getFirst());
        if (family == null) return false;
        return ids.stream().allMatch(id -> family.equals(stackedFamilyKey(id)));
    }

    /** Distinct player-facing buildings; stacked storeys of one shaft count as one. */
    static int distinctBuildingEntityCount(List<Component> hosts) {
        if (hosts == null || hosts.isEmpty()) return 0;
        Set<String> entities = new HashSet<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid == null || mid.isBlank()) continue;
            String family = stackedFamilyKey(mid);
            entities.add(family != null ? "family:" + family : "id:" + mid.toLowerCase(Locale.ROOT));
        }
        return entities.size();
    }

    static List<String> stackedFamilyMassIds(List<Component> hosts) {
        if (hosts == null || hosts.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        for (Component host : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid != null && !mid.isBlank()) ids.add(mid);
        }
        return isStackedFamilyIds(ids) ? List.copyOf(ids) : List.of();
    }

    /**
     * All wing sides named in the local clause (west/east/south/north).
     * Joint subjects 「西厢房和东厢房都…」return both; first-only matching is rejected.
     */
    static Set<String> wingSidesFromText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String t = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> sides = new LinkedHashSet<>();
        if (t.matches("(?s).*(?:西(?:厢|翼|侧)|west[_\\s-]?wing|\\bwest\\b|左厢).*")) sides.add("west");
        if (t.matches("(?s).*(?:东(?:厢|翼|侧)|east[_\\s-]?wing|\\beast\\b|右厢).*")) sides.add("east");
        if (t.matches("(?s).*(?:南(?:厢|翼|侧)|south[_\\s-]?wing|\\bsouth\\b).*")) sides.add("south");
        if (t.matches("(?s).*(?:北(?:厢|翼|侧)|north[_\\s-]?wing|\\bnorth\\b).*")) sides.add("north");
        return Set.copyOf(sides);
    }

    /** First wing side when exactly one is present; else null (use {@link #wingSidesFromText}). */
    static String wingSideHint(String text) {
        Set<String> sides = wingSidesFromText(text);
        return sides.size() == 1 ? sides.iterator().next() : null;
    }

    static boolean hostMatchesWingSide(String identityBlob, String side) {
        if (identityBlob == null || side == null) return false;
        String blob = identityBlob.toLowerCase(Locale.ROOT);
        return switch (side) {
            case "west" -> blob.contains("west") || blob.contains("西") || blob.contains("左厢")
                    || blob.contains("west_wing") || blob.contains("wing_west");
            case "east" -> blob.contains("east") || blob.contains("东") || blob.contains("右厢")
                    || blob.contains("east_wing") || blob.contains("wing_east");
            case "south" -> blob.contains("south") || blob.contains("南")
                    || blob.contains("south_wing") || blob.contains("wing_south");
            case "north" -> blob.contains("north") || blob.contains("北")
                    || blob.contains("north_wing") || blob.contains("wing_north");
            default -> false;
        };
    }

    /**
     * Proportionally rescale stacked tier heights and relative Y so the authoritative envelope
     * matches the player height. Building mode reserves crown/roof rise above the shaft.
     * Never copies the envelope onto each tier.
     */

    static String satelliteHostId(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null || String.valueOf(host).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(host))) {
            return null;
        }
        return String.valueOf(host).trim();
    }

    static String hostIdentityBlob(Component host) {
        StringBuilder sb = new StringBuilder();
        String mid = BuildingHostResolver.massId(host);
        if (mid != null) sb.append(mid).append(' ');
        if (host.slotId() != null) sb.append(host.slotId()).append(' ');
        if (host.params() != null) {
            for (String k : List.of(
                    "component_id", "building_id", "requirement_scope",
                    "building_role", "buildingRole", "role", "part", "label")) {
                Object v = host.params().get(k);
                if (v != null) sb.append(v).append(' ');
            }
        }
        if (host.features() != null) {
            for (String f : host.features()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Unique role-score winner; ties return null (order must not decide). */
    static String uniqueRoleHostId(List<Component> hosts, String hint) {
        Component best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Component host : hosts) {
            int score = StairAndHeightSealBinder.heightRoleScore(host, hint);
            if (score > bestScore) {
                bestScore = score;
                best = host;
                tie = false;
            } else if (score > 0 && score == bestScore) {
                tie = true;
            }
        }
        if (tie || best == null || bestScore <= 0) return null;
        return BuildingHostResolver.massId(best);
    }

    record PythonOmitLookup(
            boolean contractPresent,
            boolean unresolvedDeclaration,
            Set<String> targets
    ) {
        static PythonOmitLookup absent() {
            return new PythonOmitLookup(false, false, Set.of());
        }
    }

    /**
     * Targets for the same player omit declaration as {@code binding} (by source span / scope / role).
     * Distinguishes "no building_contract" from "contract present but declaration match failed".
     */
    @SuppressWarnings("unchecked")
    static PythonOmitLookup pythonOmitTargetsForDeclaration(
            LlmPlan plan,
            com.formacraft.common.typology.UserConstraintContract.DesignOmitBinding binding
    ) {
        Set<String> out = new HashSet<>();
        if (plan == null || binding == null || binding.domain() == null) return PythonOmitLookup.absent();
        String prop = switch (binding.domain()) {
            case "roof" -> "roof_type";
            case "window" -> "window_style";
            case "entrance" -> "entrance_type";
            default -> "";
        };
        if (prop.isEmpty()) return PythonOmitLookup.absent();
        String sourceText = binding.sourceText() == null ? "" : binding.sourceText().trim();
        String hint = binding.roleHint();
        Object hints = plan.proportionHints();
        if (!(hints instanceof Map<?, ?> hintMap)) return PythonOmitLookup.absent();
        Object contract = hintMap.get("building_contract");
        if (!(contract instanceof Map<?, ?> cMap)) return PythonOmitLookup.absent();
        Object reqs = cMap.get("requirements");
        if (!(reqs instanceof List<?> list)) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        boolean sawOmitReq = false;
        Map<?, ?> best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> req)) continue;
            if (!prop.equals(String.valueOf(req.get("property")))) continue;
            if (!"none".equalsIgnoreCase(String.valueOf(req.get("value")))) continue;
            sawOmitReq = true;
            int score = 0;
            Object st = req.get("source_text");
            if (st instanceof List<?> texts && !sourceText.isEmpty()) {
                for (Object t : texts) {
                    if (t != null && sourceText.equals(String.valueOf(t).trim())) {
                        score += 3;
                        break;
                    }
                }
            }
            Object span = req.get("match_span");
            if (span instanceof List<?> sp && sp.size() >= 2
                    && binding.matchStart() >= 0 && binding.matchEnd() >= 0) {
                try {
                    int a = Integer.parseInt(String.valueOf(sp.get(0)));
                    int b = Integer.parseInt(String.valueOf(sp.get(1)));
                    if (a == binding.matchStart() && b == binding.matchEnd()) score += 4;
                } catch (NumberFormatException ignored) {
                    // ignore malformed spans
                }
            }
            String scope = String.valueOf(req.get("scope") == null ? "" : req.get("scope")).trim();
            if (hint != null && !hint.isBlank() && omitScopeMatchesRole(scope, hint)) {
                score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = req;
                tie = false;
            } else if (score > 0 && score == bestScore) {
                best = null;
                tie = true;
            }
        }
        if (!sawOmitReq) {
            // Contract exists but has no omit rows for this domain — treat as absent cross-check.
            return new PythonOmitLookup(true, false, Set.of());
        }
        if (best == null || bestScore <= 0 || tie) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        Object targets = best.get("target_components");
        if (targets instanceof List<?> ids) {
            for (Object id : ids) {
                if (id != null && !String.valueOf(id).isBlank()) {
                    out.add(String.valueOf(id).trim());
                }
            }
        }
        // Matched declaration but empty targets still counts as unresolved when contract had the omit.
        if (out.isEmpty()) {
            return new PythonOmitLookup(true, true, Set.of());
        }
        return new PythonOmitLookup(true, false, out);
    }

    /** Map Java omit role hints onto Python named_/building_ scopes for per-declaration match. */
    static boolean omitScopeMatchesRole(String scope, String roleHint) {
        if (scope == null || roleHint == null) return false;
        String s = scope.toLowerCase(Locale.ROOT);
        String r = roleHint.toLowerCase(Locale.ROOT);
        if (r.startsWith("building_")) {
            return s.equals(r);
        }
        return switch (r) {
            case "hall" -> s.contains("main_hall") || s.contains("hall") || s.contains("main_body")
                    || s.contains("正殿") || s.contains("主殿") || s.equals("named_main_hall");
            case "pavilion" -> s.contains("pavilion") || s.contains("亭") || s.equals("named_pavilion");
            case "wing" -> s.contains("wing") || s.contains("厢") || s.equals("named_wing");
            case "tower" -> s.contains("tower") || s.contains("pagoda") || s.contains("塔");
            case "all_main_masses" -> s.equals("all_main_masses") || s.equals("plan");
            case "multi_named" -> s.equals("multi_named");
            default -> s.equals("named_" + r) || s.equals(r) || s.endsWith("_" + r);
        };
    }

    static String resolveOrdinalOmitHost(
            List<Component> hosts, String primary, String buildingHint) {
        List<String> scoped = new ArrayList<>();
        for (Component host : hosts) {
            if (host.params() == null) continue;
            Object scope = host.params().get("requirement_scope");
            if (scope != null && buildingHint.equals(String.valueOf(scope).trim())) {
                String mid = BuildingHostResolver.massId(host);
                if (mid != null) scoped.add(mid);
            }
        }
        if (scoped.size() == 1) return scoped.getFirst();
        if (scoped.size() > 1) return null; // ambiguous — never first-in-list
        int n;
        try {
            n = Integer.parseInt(buildingHint.substring("building_".length()));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (n == 1 && primary != null) return primary;
        if (n == 2) {
            List<String> secondaries = new ArrayList<>();
            for (Component host : hosts) {
                String mid = BuildingHostResolver.massId(host);
                if (mid != null && (primary == null || !primary.equals(mid))) {
                    secondaries.add(mid);
                }
            }
            return secondaries.size() == 1 ? secondaries.getFirst() : null;
        }
        return null;
    }

    record OmitBindResult(
            Map<String, Set<String>> byMassId,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {}

    /**
     * Unique bind of a player stair-tread material onto one direct stair component or one
     * nested STAIR_SYSTEM op. Ambiguous or missing targets become capability gaps — never guess.
     */

}
