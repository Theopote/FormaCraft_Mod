package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassSpatialRelation;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nudge secondary / pavilion masses out of overlapping primary footprints so
 * compound gardens do not trip {@code E_PATCH_CONFLICT} at shared cells.
 * <p>
 * Phase 02.1: only {@link MassSpatialRelation#AABB3_INTERSECT} is a conflict —
 * XZ-only / Y-disjoint stacks are left alone. Default: do not move authored
 * positions unless {@code allow_mass_separation} or soft-annex opt-in; otherwise
 * emit {@code E_MASS_FOOTPRINT_CONFLICT}. Mutates relativePosition only.
 */
final class MassFootprintSeparationRepair {
    private static final int GAP = 2;

    private MassFootprintSeparationRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().size() < 2) {
            return plan;
        }
        boolean interlockingIntent = asksInterlockingMasses(plan);
        boolean planSeparationOptIn = planAllowsMassSeparation(plan);
        List<Component> out = new ArrayList<>(plan.components());
        boolean changed = false;
        for (int i = 0; i < out.size(); i++) {
            Component a = out.get(i);
            if (!isMovableMass(a)) continue;
            for (int j = 0; j < out.size(); j++) {
                if (i == j) continue;
                Component b = out.get(j);
                if (!isAnchorMass(b)) continue;
                // Stepped towers / pagoda tiers intentionally nest in XZ at different Y.
                if (isAuthoredVerticalStack(plan, a, b)) continue;
                // Phase 02.2: authored subtract/intersect/interlock peers must not be nudged apart.
                if (MassBooleanComposition.peersAreBooleanComposition(a, b)
                        || MassBooleanComposition.isFoldedCsgSatellite(a)
                        || MassBooleanComposition.isFoldedCsgSatellite(b)) {
                    continue;
                }

                var ab = planBounds(plan, a);
                var bb = planBounds(plan, b);
                boolean intentionalPair = interlockingIntent && intentionalInterlockPair(a, b);
                if (!intentionalPair && MassBooleanComposition.peersAreBooleanComposition(a, b)) {
                    intentionalPair = true;
                }
                MassSpatialRelation rel = MassSpatialRelation.classify(ab, bb, intentionalPair);
                // Phase 02.3.3: explicit junction intent prefers cleanup over nudge/move.
                if (MassJunctionRepair.junctionHandlesPair(plan, a, b)) {
                    continue;
                }
                if (rel != MassSpatialRelation.CONNECTION_SEAM && linkedByPassage(plan, a, b)) {
                    continue;
                }
                // Authored interlocking: keep positions; pair-scope shared cells (not component-wide overwrite).
                if (rel == MassSpatialRelation.INTENTIONAL_INTERLOCK) {
                    if (stampInterlockPeer(out, i, idOf(b)) | stampInterlockPeer(out, j, idOf(a))) {
                        changed = true;
                    }
                    continue;
                }

                boolean aSoftAnnex = looksHorizontalAnnex(idOf(a).toLowerCase(Locale.ROOT))
                        || looksSideHallRole(a);
                boolean bSoftAnnex = looksHorizontalAnnex(idOf(b).toLowerCase(Locale.ROOT))
                        || looksSideHallRole(b);
                boolean softAnnexHint = aSoftAnnex || bSoftAnnex;
                // Pair may be considered when either side has opt-in, plan opt-in, or soft-annex hint.
                // Actual move still requires moverAuthorized(a) (Phase 02.3.1 Task F).
                boolean pairEligible = componentAllowsMassSeparation(plan, a, planSeparationOptIn)
                        || componentAllowsMassSeparation(plan, b, planSeparationOptIn)
                        || planSeparationOptIn;
                if (!pairEligible && softAnnexHint && !intentionalPair
                        && (rel == MassSpatialRelation.AABB3_INTERSECT
                        || rel == MassSpatialRelation.CONNECTION_SEAM)) {
                    pairEligible = true;
                }
                boolean canNudge = pairEligible;

                if (rel == MassSpatialRelation.CONNECTION_SEAM) {
                    // Soft-annex shallow seam → courtyard GAP path (not seam-collapse) — Phase 02.1.
                    if (softAnnexHint && !intentionalPair) {
                        // fall through to AABB3-style GAP nudge below
                    } else if (!canNudge
                            && !linkedByPassage(plan, a, b)
                            && !authoredCompoundSeamJoin(plan, a, b)) {
                        // P1-01: unlinked seams on non-COMPOUND plans still need allow_mass_separation.
                        // Passage / COMPOUND primary joins authorize GEOMETRY seam-collapse only.
                        AssemblyCompileDiagnostics.set(new CapabilityGap(
                                "E_MASS_FOOTPRINT_CONFLICT",
                                "CONNECTION_SEAM overlap without allow_mass_separation opt-in",
                                "components[].relative_position",
                                List.of(
                                        "Set allow_mass_separation on the mover or plan_meta",
                                        "Separate the volumes in the plan",
                                        "Or mark intentional_interlock for shared shells"
                                )
                        ));
                        return plan;
                    } else {
                        if (priority(a) > priority(b)) continue;
                        if (priority(a) == priority(b) && idOf(a).compareTo(idOf(b)) >= 0) continue;
                        // Phase 02.3.2 Task E: opt-in / soft-annex seams authorize the chosen mover.
                        // Passage link + authored COMPOUND primary join remain GEOMETRY (no mover stamp).
                        boolean compoundOrPassage = linkedByPassage(plan, a, b)
                                || authoredCompoundSeamJoin(plan, a, b);
                        if (!compoundOrPassage
                                && !moverAuthorized(plan, a, planSeparationOptIn, aSoftAnnex, intentionalPair)) {
                            AssemblyCompileDiagnostics.set(new CapabilityGap(
                                    "E_MASS_FOOTPRINT_CONFLICT",
                                    "CONNECTION_SEAM chosen mover lacks allow_mass_separation",
                                    "components[].params.allow_mass_separation",
                                    List.of(
                                            "Stamp allow_mass_separation on the mass that should move",
                                            "Or set plan_meta.allow_mass_separation=true",
                                            "Or separate volumes in the plan"
                                    )
                            ));
                            return plan;
                        }
                        var beforeSeam = ab;
                        Component seamNudged = nudgeSeamToContact(plan, a, b);
                        if (seamNudged == a) continue;
                        var afterSeam = planBounds(plan, seamNudged);
                        int sdx = (beforeSeam != null && afterSeam != null)
                                ? afterSeam.minX() - beforeSeam.minX()
                                : seamNudged.relativePosition().x() - a.relativePosition().x();
                        int sdy = (beforeSeam != null && afterSeam != null)
                                ? afterSeam.minY() - beforeSeam.minY()
                                : seamNudged.relativePosition().y() - a.relativePosition().y();
                        int sdz = (beforeSeam != null && afterSeam != null)
                                ? afterSeam.minZ() - beforeSeam.minZ()
                                : seamNudged.relativePosition().z() - a.relativePosition().z();
                        FormacraftMod.LOGGER.info(
                                "MassFootprintSeparationRepair: seam-resolve {} vs {} → {} (Δ={},{},{}) auth=moverAuthorized",
                                idOf(a), idOf(b), seamNudged.relativePosition(), sdx, sdy, sdz);
                        out.set(i, seamNudged);
                        shiftHostedSatellites(out, a, sdx, sdy, sdz);
                        a = seamNudged;
                        changed = true;
                        continue;
                    }
                }
                // Soft-annex CONNECTION_SEAM falls through as GAP nudge; other forbidsNudge skip.
                if (rel != MassSpatialRelation.CONNECTION_SEAM
                        && (MassSpatialRelation.forbidsNudge(rel) || rel != MassSpatialRelation.AABB3_INTERSECT)) {
                    continue;
                }
                if (rel != MassSpatialRelation.CONNECTION_SEAM && rel != MassSpatialRelation.AABB3_INTERSECT) {
                    continue;
                }

                if (!canNudge) {
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            "E_MASS_FOOTPRINT_CONFLICT",
                            "Peer masses intersect in 3D without allow_mass_separation / soft-annex opt-in",
                            "components[].relative_position",
                            List.of(
                                    "Set allow_mass_separation on the mover or plan_meta",
                                    "Separate the volumes in the plan",
                                    "Or mark interlocking composition via intentional_interlock / mass_composition"
                            )
                    ));
                    return plan;
                }
                // Prefer moving the pavilion / smaller body; equal score → lexicographically later id moves.
                if (priority(a) > priority(b)) continue;
                if (priority(a) == priority(b) && idOf(a).compareTo(idOf(b)) >= 0) continue;
                // Phase 02.3.1 Task F: only the chosen mover may move — peer opt-in does not relocate a.
                if (!moverAuthorized(plan, a, planSeparationOptIn, aSoftAnnex, intentionalPair)) {
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            "E_MASS_FOOTPRINT_CONFLICT",
                            "Chosen mover lacks allow_mass_separation (peer opt-in does not transfer)",
                            "components[].params.allow_mass_separation",
                            List.of(
                                    "Stamp allow_mass_separation on the mass that should move",
                                    "Or set plan_meta.allow_mass_separation=true",
                                    "Or separate volumes in the plan"
                            )
                    ));
                    return plan;
                }

                var before = ab;
                Component nudged = nudgeAway(plan, a, b);
                if (nudged == a) continue;
                var after = planBounds(plan, nudged);
                int dx;
                int dy;
                int dz;
                if (before != null && after != null) {
                    dx = after.minX() - before.minX();
                    dy = after.minY() - before.minY();
                    dz = after.minZ() - before.minZ();
                } else {
                    dx = nudged.relativePosition().x() - a.relativePosition().x();
                    dy = nudged.relativePosition().y() - a.relativePosition().y();
                    dz = nudged.relativePosition().z() - a.relativePosition().z();
                }
                FormacraftMod.LOGGER.info(
                        "MassFootprintSeparationRepair: move {} away from {} → {} (Δ={},{},{}) auth=opt-in/soft-annex",
                        idOf(a), idOf(b), nudged.relativePosition(), dx, dy, dz);
                out.set(i, nudged);
                shiftHostedSatellites(out, a, dx, dy, dz);
                a = nudged;
                changed = true;
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * Pair-scoped interlock metadata for {@link PatchOccupancyIndex} — not component-wide
     * {@code allow_patch_overwrite} (Phase 02.2.1 Task E / P1-02).
     */
    private static boolean stampInterlockPeer(List<Component> parts, int index, String peerId) {
        if (parts == null || index < 0 || index >= parts.size() || peerId == null || peerId.isBlank()) {
            return false;
        }
        Component c = parts.get(index);
        if (c == null) return false;
        Map<String, Object> params = new HashMap<>();
        if (c.params() != null) params.putAll(c.params());
        // Multi-peer merge (Phase 02.3.3 Task F): A↔B and B↔C may coexist.
        java.util.Set<String> peers = new java.util.LinkedHashSet<>(
                com.formacraft.common.generation.component.util.MassJunctionIntent.peersOf(c));
        boolean already = peers.contains(peerId);
        peers.add(peerId);
        params.put("intentional_interlock_peer_id", peerId);
        params.put("intentional_interlock_peer_ids", new java.util.ArrayList<>(peers));
        params.put("intentional_interlock_shared_cells", true);
        // Do NOT stamp allow_patch_overwrite — occupancy enforces pair scope.
        params.remove("allow_patch_overwrite");
        parts.set(index, new Component(
                c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), c.features(), params));
        return !already;
    }

    /** Plan-meta / proportion_hints opt-in (does not OR every component — P1-01). */
    private static boolean planAllowsMassSeparation(LlmPlan plan) {
        if (plan == null) return false;
        return truthyParam(plan.planMeta(), "allow_mass_separation", "allowMassSeparation")
                || truthyParam(plan.planMeta(), "auto_layout", "autoLayout")
                || truthyParam(plan.proportionHints(), "allow_mass_separation", "allowMassSeparation")
                || truthyParam(plan.proportionHints(), "auto_layout", "autoLayout");
    }

    /** Component-scoped separation: plan opt-in or this component stamped. */
    private static boolean componentAllowsMassSeparation(LlmPlan plan, Component c, boolean planOptIn) {
        if (planOptIn) return true;
        return truthyParam(c == null ? null : c.params(), "allow_mass_separation", "allowMassSeparation");
    }

    /**
     * The component that will actually move must itself hold authority.
     * Soft-annex NONBINDING_HINT only licenses the annex mover, never a locked primary peer.
     */
    private static boolean moverAuthorized(
            LlmPlan plan,
            Component mover,
            boolean planOptIn,
            boolean moverIsSoftAnnex,
            boolean intentionalPair
    ) {
        if (componentAllowsMassSeparation(plan, mover, planOptIn)) return true;
        return moverIsSoftAnnex && !intentionalPair;
    }

    private static boolean truthyParam(Map<String, Object> params, String a, String b) {
        if (params == null) return false;
        Object raw = params.get(a);
        if (raw == null) raw = params.get(b);
        if (raw == null) return false;
        if (raw instanceof Boolean bool) return bool;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static boolean massCompositionInterlock(Map<String, Object> bag) {
        if (bag == null) return false;
        Object mode = bag.get("mass_composition");
        if (mode == null) mode = bag.get("massComposition");
        if (mode == null) return false;
        String s = String.valueOf(mode).trim().toLowerCase(Locale.ROOT);
        return s.contains("interlock") || s.contains("intersect") || "union".equals(s);
    }

    private static void shiftHostedSatellites(List<Component> parts, Component host, int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) return;
        String hostId = idOf(host);
        for (int i = 0; i < parts.size(); i++) {
            Component c = parts.get(i);
            if (c == null || c == host || c.relativePosition() == null) continue;
            if (isMovableMass(c) && priority(c) >= priority(host)) continue;
            // Explicit host_id / hostId is authoritative — never drag a foreign building's roof/door.
            String boundHost = explicitHostId(c);
            boolean hosted = hostId != null && !hostId.isBlank() && hostId.equals(boundHost);
            if (boundHost != null && !boundHost.isBlank() && !hosted) {
                continue;
            }
            // No explicit host: only follow by clear id-family belonging (not mere same slot).
            boolean idFamily = boundHost == null && sharesIdFamily(hostId, idOf(c));
            if (!hosted && !idFamily) continue;
            if (isMovableMass(c) && !idFamily) continue;
            Vec3i p = c.relativePosition();
            parts.set(i, new Component(
                    c.componentType(), c.slotId(),
                    new Vec3i(p.x() + dx, p.y() + dy, p.z() + dz),
                    c.dimensions(), c.features(), c.params()));
        }
    }

    /** {@code host_id} / {@code hostId} only — building_id is not a host binding. */
    private static String explicitHostId(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null) return null;
        String s = String.valueOf(host).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static boolean sharesIdFamily(String hostId, String otherId) {
        if (hostId == null || otherId == null) return false;
        String a = hostId.toLowerCase(Locale.ROOT);
        String b = otherId.toLowerCase(Locale.ROOT);
        if (a.equals(b) || a.isBlank() || b.isBlank()) return false;
        // north_side_hall ↔ north_side_hall_roof / north_side_foundation
        return b.startsWith(a) || a.startsWith(b)
                || (a.contains("side") && b.contains("side")
                && ((a.contains("north") && b.contains("north"))
                || (a.contains("south") && b.contains("south"))
                || (a.contains("east") && b.contains("east"))
                || (a.contains("west") && b.contains("west"))
                || (a.contains("左") && b.contains("左"))
                || (a.contains("右") && b.contains("右"))));
    }

    private static boolean isMovableMass(Component c) {
        if (c == null || c.dimensions() == null || c.relativePosition() == null) return false;
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if (!(t.startsWith("MASS") || "TOWER".equals(t) || "GATE".equals(t) || "STRUCTURE".equals(t))) {
            return false;
        }
        // Plinth / steps / porch posts belong under the host mass — never separate them.
        if (isFoundationLike(c)) {
            return false;
        }
        // Interior / tower stair flights are circulation, not peer masses. Nudging them off the
        // host shell plants treads in exterior volume → E_CIRCULATION_EXTERIOR_CONFLICT.
        if (isStairFlight(c)) {
            return false;
        }
        // Hosted STRUCTURE satellites (stairs, shafts) stay glued; typology STRUCTURE halls
        // without host_id remain movable anchors (e.g. japanese_haiden).
        if ("STRUCTURE".equals(t) && explicitHostId(c) != null) {
            return false;
        }
        // Peristyle / porch columns are ring ornaments — nudging them destroys the colonnade.
        if (isThinColumnOrPeristyle(c)) {
            return false;
        }
        if (c.params() != null) {
            // Bridge / hosted plates intentionally touch two hosts — never nudge them apart.
            if (c.params().containsKey("target_host_id") || c.params().containsKey("targetHostId")) {
                return false;
            }
            Object role = c.params().get("plate_role");
            if (role == null) role = c.params().get("plateRole");
            if (role != null && String.valueOf(role).toLowerCase(Locale.ROOT).contains("bridge")) {
                return false;
            }
            return !"plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")));
        }
        return true;
    }

    /** Authored stair runs / stair-named STRUCTURE — never peer-separate from the host shell. */
    private static boolean isStairFlight(Component c) {
        if (c == null) return false;
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if ("STAIR_SYSTEM".equals(t) || "STAIRCASE".equals(t) || "STAIRS".equals(t)) {
            return true;
        }
        if (c.params() != null
                && c.params().get("from") instanceof Map<?, ?>
                && c.params().get("to") instanceof Map<?, ?>) {
            return true;
        }
        String id = idOf(c).toLowerCase(Locale.ROOT);
        if (id.contains("stair") || id.contains("staircase") || id.contains("stairway")) {
            return true;
        }
        if (c.features() == null) return false;
        for (String f : c.features()) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.startsWith("stair:") || lower.contains("straight_single_run")) {
                return true;
            }
        }
        return false;
    }

    /** 1×1 / 2×2 shafts and named peristyle columns must stay on the authored ring. */
    private static boolean isThinColumnOrPeristyle(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        if (id.contains("column") || id.contains("peristyle") || id.contains("pillar")
                || id.contains("colonade") || id.contains("colonnade") || id.contains("柱")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("peristyle") || lower.contains("colonnade")
                        || lower.contains("quartz_column") || lower.contains("corinthian")) {
                    return true;
                }
            }
        }
        Dimensions d = c.dimensions();
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_ANCILLARY".equals(t) && d.width() <= 2 && d.depth() <= 2 && d.height() >= 4;
    }

    /** Foundations, platforms, and porch posts that must stay co-located with the hall. */
    private static boolean isFoundationLike(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        // Id tokens only — feature strings often mention plaza "steps" / paths without being a plinth.
        if (idContainsFoundationToken(id)) {
            return true;
        }
        // Free-standing "pillars" accessory under a shrine porch — not a hall mass.
        if (id.contains("pillar") && (id.contains("kouhai") || id.contains("porch") || id.contains("向拝"))) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                // Strong foundation cues only — never match loose "steps" inside plaza descriptions.
                if (lower.contains("plinth") || f.contains("台基") || lower.contains("foundation")
                        || f.contains("台阶") || lower.contains("stair_approach")
                        || lower.contains("approach_stair")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean idContainsFoundationToken(String id) {
        return id.contains("plinth") || id.contains("foundation") || id.contains("台基")
                || id.contains("steps") || id.contains("台阶") || id.contains("stair_approach")
                || id.contains("approach_stair") || id.contains("kouhai") || id.contains("向拝")
                || id.contains("porch");
    }

    private static boolean isAnchorMass(Component c) {
        return isMovableMass(c);
    }

    private static int priority(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        int score = volume(c);
        if (id.contains("pavilion") || id.contains("gazebo") || id.contains("ting")
                || id.contains("shuixie") || id.contains("kiosk") || id.contains("water")
                || id.contains("side") || id.contains("wing") || id.contains("配殿")
                || id.contains("厢")) {
            score -= 1_000_000;
        }
        if (id.contains("main") || id.contains("大雄") || id.contains("宝殿")
                || (id.contains("hall") && !id.contains("side") && !id.contains("wing"))) {
            score += 1_000_000;
        }
        if (id.contains("殿") || id.contains("堂")) {
            if (id.contains("配") || id.contains("侧") || id.contains("厢")) score -= 500_000;
            else score += 500_000;
        }
        Object role = c.params() == null ? null : c.params().get("building_role");
        if (role == null && c.params() != null) role = c.params().get("buildingRole");
        if (role != null) {
            String r = String.valueOf(role).toLowerCase(Locale.ROOT);
            if (r.contains("pavilion") || r.contains("side") || r.contains("wing")) score -= 500_000;
            if (r.contains("hall") || r.contains("main") || r.contains("primary")) score += 500_000;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("pavilion") || f.contains("亭") || lower.contains("水榭")
                        || f.contains("配殿")) score -= 250_000;
                if (lower.contains("main_hall") || f.contains("大雄") || lower.contains("宝殿")) {
                    score += 250_000;
                }
            }
        }
        return score;
    }

    private static int volume(Component c) {
        Dimensions d = c.dimensions();
        return Math.max(1, d.width()) * Math.max(1, d.depth()) * Math.max(1, d.height());
    }

    private static String idOf(Component c) {
        if (c.params() == null) return c.componentType() == null ? "" : c.componentType();
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? String.valueOf(c.componentType()) : String.valueOf(id);
    }

    private static boolean asksInterlockingMasses(LlmPlan plan) {
        if (plan == null) return false;
        // Explicit composition cues (preferred over free-text hint).
        if (truthyParam(plan.planMeta(), "intentional_interlock", "intentionalInterlock")
                || truthyParam(plan.proportionHints(), "intentional_interlock", "intentionalInterlock")) {
            return true;
        }
        if (massCompositionInterlock(plan.planMeta()) || massCompositionInterlock(plan.proportionHints())) {
            return true;
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null) continue;
                if (truthyParam(c.params(), "intentional_interlock", "intentionalInterlock")) {
                    return true;
                }
                // Component feature tags (e.g. penetrates_hall_roof) are authored composition cues.
                if (c.features() != null) {
                    for (String f : c.features()) {
                        if (f != null) {
                            String lower = f.toLowerCase(Locale.ROOT);
                            if (lower.contains("penetrat") || lower.contains("interlock")
                                    || f.contains("穿入") || f.contains("穿插") || f.contains("交叠")) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        // NONBINDING_HINT: free-text may cue interlocking sculptures (never decides typology).
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(ri).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
        }
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        String blob = sb.toString().toLowerCase(Locale.ROOT);
        return blob.contains("交叠") || blob.contains("交错") || blob.contains("互相穿插")
                || blob.contains("穿入") || blob.contains("穿楼")
                || blob.contains("interlock") || blob.contains("interlocking")
                || blob.contains("overlapping mass") || blob.contains("overlapping volumes")
                || blob.contains("penetrat") // penetrate / penetrates / interpenetrate
                || blob.contains("rising above the main roof")
                || blob.contains("rising above the main")
                || blob.contains("sphere pod") || blob.contains("splayed")
                || blob.contains("support legs") || blob.contains("东方明珠");
    }

    /**
     * Under an interlocking plan cue: two sculpture volumes, or a tower/keep penetrating a
     * primary shell (clock tower through hall roof). Soft annex wings stay on the nudge path.
     */
    private static boolean intentionalInterlockPair(Component a, Component b) {
        if (a == null || b == null) return false;
        if (looksSideHallRole(a) || looksSideHallRole(b)
                || looksHorizontalAnnex(idOf(a).toLowerCase(Locale.ROOT))
                || looksHorizontalAnnex(idOf(b).toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean aVol = looksInterlockVolume(a);
        boolean bVol = looksInterlockVolume(b);
        if (aVol && bVol) return true;
        // Tower/keep penetrating a primary hall shell (authored 穿入 / penetrates_hall_roof).
        return (aVol && isPrimaryShell(b)) || (bVol && isPrimaryShell(a));
    }

    private static boolean isPrimaryShell(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t) || "TOWER".equals(t)
                || "MASS_SECONDARY".equals(t);
    }

    /**
     * Authored {@code layout.skeleton_type=COMPOUND} multi-mass plans expect shallow join tissue
     * between peer primary shells (staggered lodge / fortress cluster). GEOMETRY seam-collapse
     * only — never courtyard GAP and never from building-name keywords. Soft annex stays on the
     * GAP path; P1-01 d02 (no COMPOUND layout) still gaps.
     */
    private static boolean authoredCompoundSeamJoin(LlmPlan plan, Component a, Component b) {
        if (plan == null || plan.layout() == null || plan.layout().skeletonType() == null) {
            return false;
        }
        if (plan.layout().skeletonType() != Layout.SkeletonType.COMPOUND) {
            return false;
        }
        if (a == null || b == null) return false;
        if (looksSideHallRole(a) || looksSideHallRole(b)
                || looksHorizontalAnnex(idOf(a).toLowerCase(Locale.ROOT))
                || looksHorizontalAnnex(idOf(b).toLowerCase(Locale.ROOT))) {
            return false;
        }
        String ta = a.componentType() == null ? "" : a.componentType().trim().toUpperCase(Locale.ROOT);
        String tb = b.componentType() == null ? "" : b.componentType().trim().toUpperCase(Locale.ROOT);
        boolean aPrimary = "MASS_MAIN".equals(ta) || "MAIN_MASS".equals(ta) || "TOWER".equals(ta);
        boolean bPrimary = "MASS_MAIN".equals(tb) || "MAIN_MASS".equals(tb) || "TOWER".equals(tb);
        return aPrimary && bPrimary;
    }

    /**
     * Abstract interlocking sculpture volumes — not courtyard halls, peidian, or pavilions that
     * merely share a plan that also mentions 「交错」somewhere.
     */
    private static boolean looksInterlockVolume(Component c) {
        if (c == null) return false;
        if (looksSideHallRole(c) || looksHorizontalAnnex(idOf(c).toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (looksFortressLink(idOf(c))) {
            return true;
        }
        String blob = idOf(c).toLowerCase(Locale.ROOT);
        if (c.params() != null) {
            for (String key : List.of("building_role", "buildingRole", "mass_role", "composition_role")) {
                Object v = c.params().get(key);
                if (v != null) blob += " " + v;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null) blob += " " + f;
            }
        }
        String lower = blob.toLowerCase(Locale.ROOT);
        return lower.contains("volume") || lower.contains("prism") || lower.contains("block_")
                || lower.contains("geometric") || lower.contains("sculpture") || lower.contains("museum")
                || lower.contains("interlock") || lower.contains("sphere") || lower.contains("shaft")
                || lower.contains("leg_") || lower.contains("_leg") || lower.endsWith("leg")
                || lower.contains("podium") || blob.contains("体块") || blob.contains("体量")
                || blob.contains("交叠") || blob.contains("穿插") || blob.contains("穿入");
    }

    /**
     * Compare footprints in plan space. Different {@code slotId} values alone do not prove
     * non-collision — hall vs pavilion often use distinct slot labels in one shared frame.
     * When {@code layout.slots} defines anchors, local relative positions are offset into plan
     * space; undefined slots keep relative coords as plan-space (never skip as "incomparable").
     */
    /** True only on 3D AABB intersection (Phase 02.1 — XZ-only is not a conflict). */
    private static boolean overlaps(LlmPlan plan, Component a, Component b) {
        return MassSpatialRelation.aabb3Intersect(planBounds(plan, a), planBounds(plan, b));
    }

    /**
     * Nested footprints at different Y (or explicit tier/host binding) are a vertical stack,
     * not a courtyard collision. Horizontal annexes (wing/side/pavilion) still separate unless
     * they also carry a tier cue or host_id link.
     */
    private static boolean isAuthoredVerticalStack(LlmPlan plan, Component a, Component b) {
        var ab = planBounds(plan, a);
        var bb = planBounds(plan, b);
        if (ab == null || bb == null) return false;
        if (!xzNested(ab, bb) && !xzNested(bb, ab)) return false;
        String idA = idOf(a).toLowerCase(Locale.ROOT);
        String idB = idOf(b).toLowerCase(Locale.ROOT);
        // Wings/corridors often carry host_id for roofs/stairs — that alone must not skip separation.
        if (looksHorizontalAnnex(idA) || looksHorizontalAnnex(idB)) {
            return looksTierToken(idA) || looksTierToken(idB);
        }
        // Different floor datums alone do not prove a stack — A at Y=0..20 and B at Y=2..18
        // still collide in 3D even though |minY| differs by ≥2.
        int dy = Math.abs(ab.minY() - bb.minY());
        if (dy >= 2 && !yRangesOverlap(ab, bb)) return true;
        return looksTierToken(idA) || looksTierToken(idB) || linkedByHost(a, b);
    }

    /** Exclusive maxY bounds — true when the vertical intervals share any cell. */
    private static boolean yRangesOverlap(
            ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        return a.minY() < b.maxY() && b.minY() < a.maxY();
    }

    private static boolean xzNested(
            ComponentFootprintUtil.Bounds inner, ComponentFootprintUtil.Bounds outer) {
        return inner.minX() >= outer.minX() - 1 && inner.maxX() <= outer.maxX() + 1
                && inner.minZ() >= outer.minZ() - 1 && inner.maxZ() <= outer.maxZ() + 1;
    }

    private static boolean looksHorizontalAnnex(String id) {
        return id.contains("wing") || id.contains("side") || id.contains("配") || id.contains("厢")
                || id.contains("pavilion") || id.contains("gazebo") || id.contains("ting")
                || id.contains("corridor") || id.contains("廊") || id.contains("annex");
    }

    /**
     * Ordinary left/right 配殿 / side halls should share the main hall floor. Raised or
     * cantilevered annexes keep their authored Y.
     */
    private static boolean shouldAlignFloorDatum(Component mover) {
        if (mover == null) return false;
        if (looksRaisedOrCantilevered(mover)) return false;
        return looksSideHallRole(mover);
    }

    private static boolean looksSideHallRole(Component c) {
        String id = idOf(c).toLowerCase(Locale.ROOT);
        String role = "";
        if (c.params() != null) {
            Object raw = c.params().get("building_role");
            if (raw == null) raw = c.params().get("buildingRole");
            if (raw != null) role = String.valueOf(raw).toLowerCase(Locale.ROOT);
        }
        if (role.contains("side") || role.contains("peidian") || role.contains("配殿") || role.contains("厢")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("side_hall") || f.contains("配殿") || f.contains("厢房")
                        || lower.contains("peidian")) {
                    return true;
                }
            }
        }
        return id.contains("peidian") || id.contains("side_hall") || id.contains("sidehall")
                || id.contains("配殿") || id.contains("厢房")
                || ((id.contains("side") || id.contains("wing"))
                && (id.contains("hall") || id.contains("殿") || id.contains("厢")));
    }

    private static boolean looksRaisedOrCantilevered(Component c) {
        String blob = idOf(c).toLowerCase(Locale.ROOT);
        if (c.params() != null) {
            for (String key : List.of("building_role", "buildingRole", "elevation_role", "support")) {
                Object v = c.params().get(key);
                if (v != null) blob += " " + v;
            }
            if (Boolean.TRUE.equals(c.params().get("stilts"))
                    || Boolean.TRUE.equals(c.params().get("cantilevered"))
                    || Boolean.TRUE.equals(c.params().get("raised"))) {
                return true;
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null) blob += " " + f;
            }
        }
        String lower = blob.toLowerCase(Locale.ROOT);
        return lower.contains("stilt") || lower.contains("cantilever") || lower.contains("raised")
                || lower.contains("floating") || lower.contains("suspended") || lower.contains("split_level")
                || lower.contains("split-level") || blob.contains("架空") || blob.contains("悬挑")
                || blob.contains("错层");
    }

    private static boolean looksTierToken(String id) {
        return id.contains("tier") || id.contains("pagoda") || id.contains("layer")
                || id.contains("storey") || id.contains("story") || id.contains("level_")
                || id.matches(".*[_-]l\\d+.*") || id.contains("层") || id.contains("檐");
    }

    private static boolean linkedByHost(Component a, Component b) {
        String aId = idOf(a);
        String bId = idOf(b);
        String aHost = explicitHostId(a);
        String bHost = explicitHostId(b);
        return (aHost != null && aHost.equals(bId)) || (bHost != null && bHost.equals(aId));
    }

    /** True when an ENTRANCE with passage/carve binds one mass and faces the other. */
    private static boolean linkedByPassage(LlmPlan plan, Component a, Component b) {
        if (plan == null || plan.components() == null || a == null || b == null) return false;
        String aId = idOf(a);
        String bId = idOf(b);
        if (aId.isBlank() || bId.isBlank()) return false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!"ENTRANCE".equals(type)) continue;
            if (!isPassageOrInternalConnection(c)) continue;
            String host = explicitHostId(c);
            String target = null;
            Object rawTarget = c.params().get("target_host_id");
            if (rawTarget == null) rawTarget = c.params().get("targetHostId");
            if (rawTarget != null) {
                String t = String.valueOf(rawTarget).trim();
                if (!t.isEmpty() && !"null".equalsIgnoreCase(t)) target = t;
            }
            if (host == null) continue;
            if (target != null) {
                if ((host.equals(aId) && target.equals(bId)) || (host.equals(bId) && target.equals(aId))) {
                    return true;
                }
                continue;
            }
            // No target_host_id: keep a shared wall when the door sits on the seam between a
            // keep/tower body and its peer primary shell (hall need not be named "fortress").
            if (!(host.equals(aId) || host.equals(bId))) continue;
            Component hostMass = host.equals(aId) ? a : b;
            Component neighbor = host.equals(aId) ? b : a;
            if (looksHorizontalAnnex(idOf(neighbor).toLowerCase(Locale.ROOT))
                    || looksHorizontalAnnex(idOf(hostMass).toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean fortressPair = looksFortressLink(idOf(hostMass)) || looksFortressLink(idOf(neighbor));
            if (!fortressPair) continue;
            if (passageTouchesNeighbor(plan, c, hostMass, neighbor)) {
                return true;
            }
        }
        return false;
    }

    /** clear_passage / portal / carve+passage, or authored door:internal_connection. */
    private static boolean isPassageOrInternalConnection(Component entrance) {
        if (entrance == null || entrance.params() == null) return false;
        if (Boolean.TRUE.equals(entrance.params().get("clear_passage"))
                || Boolean.TRUE.equals(entrance.params().get("compiler_internal_portal"))) {
            return true;
        }
        if (Boolean.TRUE.equals(entrance.params().get("carve"))
                && entrance.features() != null
                && entrance.features().stream()
                .anyMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("passage"))) {
            return true;
        }
        if (entrance.features() == null) return false;
        for (String f : entrance.features()) {
            if (f == null) continue;
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains("internal_connection") || lower.contains("internal-connection")
                    || lower.contains("shared_passage") || lower.equals("passage")
                    || lower.startsWith("passage:") || lower.contains(":passage")) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksFortressLink(String id) {
        if (id == null || id.isBlank()) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        // Bare "hall" is too broad (temple halls, town halls). Require defensive / link cues.
        return lower.contains("tower") || lower.contains("keep") || lower.contains("bastion")
                || lower.contains("barbican") || lower.contains("gatehouse") || lower.contains("curtain")
                || lower.contains("passage") || lower.contains("link") || lower.contains("donjon")
                || lower.contains("fortress") || lower.contains("castle");
    }

    /** Passage footprint must touch both shells (shared-wall seam), not a distant unrelated mass. */
    private static boolean passageTouchesNeighbor(
            LlmPlan plan, Component passage, Component host, Component neighbor) {
        var pb = planBounds(plan, passage);
        var hb = planBounds(plan, host);
        var nb = planBounds(plan, neighbor);
        if (pb == null || hb == null || nb == null) return false;
        if (!xzTouchOrOverlap(pb, hb) || !xzTouchOrOverlap(pb, nb)) return false;
        // Host and neighbor must themselves share a wall / near-touch so the passage is a link.
        return xzTouchOrOverlap(hb.expandHorizontal(1), nb);
    }

    private static boolean xzTouchOrOverlap(
            ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        // Expand by 1 so adjacent shared-wall cells count as connected.
        return a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    private static ComponentFootprintUtil.Bounds planBounds(LlmPlan plan, Component c) {
        if (c == null) return null;
        Vec3i origin = slotOrigin(plan, c.slotId());
        int ox = origin.x();
        int oy = origin.y();
        int oz = origin.z();
        var local = ComponentFootprintUtil.bounds(c);
        if (local != null) {
            if (ox == 0 && oy == 0 && oz == 0) return local;
            return new ComponentFootprintUtil.Bounds(
                    local.minX() + ox, local.minY() + oy, local.minZ() + oz,
                    local.maxX() + ox, local.maxY() + oy, local.maxZ() + oz);
        }
        if (c.relativePosition() == null || c.dimensions() == null) return null;
        Vec3i p = c.relativePosition();
        Dimensions d = c.dimensions();
        return new ComponentFootprintUtil.Bounds(
                p.x() + ox, p.y() + oy, p.z() + oz,
                p.x() + ox + d.width(), p.y() + oy + d.height(), p.z() + oz + d.depth());
    }

    private static Vec3i slotOrigin(LlmPlan plan, String slotId) {
        if (slotId == null || slotId.isBlank() || plan == null || plan.layout() == null
                || plan.layout().slots() == null) {
            return new Vec3i(0, 0, 0);
        }
        for (Slot slot : plan.layout().slots()) {
            if (slot != null && slotId.equals(slot.slotId()) && slot.anchor() != null) {
                return slot.anchor();
            }
        }
        // Authored slot label without a layout anchor — relative coords stay plan-space.
        return new Vec3i(0, 0, 0);
    }

    /**
     * Collapse a {@link MassSpatialRelation#CONNECTION_SEAM} to face-contacting: move only by the
     * shallow overlap extent (no courtyard {@link #GAP}). Classification: GEOMETRY.
     */
    private static Component nudgeSeamToContact(LlmPlan plan, Component mover, Component anchor) {
        var mb = planBounds(plan, mover);
        var ab = planBounds(plan, anchor);
        Vec3i mo = mover.relativePosition();
        if (mb == null || ab == null || mo == null || mover.dimensions() == null) {
            return mover;
        }
        Dimensions md = mover.dimensions();
        int overlapX = Math.min(mb.maxX(), ab.maxX()) - Math.max(mb.minX(), ab.minX());
        int overlapZ = Math.min(mb.maxZ(), ab.maxZ()) - Math.max(mb.minZ(), ab.minZ());
        if (overlapX <= 0 || overlapZ <= 0) return mover;

        int dx = 0;
        int dz = 0;
        int centerMx = (mb.minX() + mb.maxX() - 1) / 2;
        int centerAx = (ab.minX() + ab.maxX() - 1) / 2;
        int centerMz = (mb.minZ() + mb.maxZ() - 1) / 2;
        int centerAz = (ab.minZ() + ab.maxZ() - 1) / 2;
        if (overlapX <= overlapZ) {
            dx = centerMx >= centerAx ? overlapX : -overlapX;
        } else {
            dz = centerMz >= centerAz ? overlapZ : -overlapZ;
        }
        if (dx == 0 && dz == 0) return mover;

        Map<String, Object> params = new HashMap<>();
        if (mover.params() != null) params.putAll(mover.params());
        params.put("anchor_mode", "min_corner");
        params.put("footprint_seam_resolved", true);
        Vec3i slotOx = slotOrigin(plan, mover.slotId());
        return new Component(
                mover.componentType(),
                mover.slotId(),
                new Vec3i(mb.minX() + dx - slotOx.x(), mo.y(), mb.minZ() + dz - slotOx.z()),
                md,
                mover.features(),
                params);
    }

    private static Component nudgeAway(LlmPlan plan, Component mover, Component anchor) {
        var mb = planBounds(plan, mover);
        var ab = planBounds(plan, anchor);
        Vec3i mo = mover.relativePosition();
        if (mb == null || ab == null || mo == null || mover.dimensions() == null) {
            return nudgeAwayRaw(mover, anchor);
        }
        Dimensions md = mover.dimensions();
        int mMinX = mb.minX();
        int mMinZ = mb.minZ();
        int mMaxX = mb.maxX() - 1;
        int mMaxZ = mb.maxZ() - 1;
        int aMinX = ab.minX();
        int aMinZ = ab.minZ();
        int aMaxX = ab.maxX() - 1;
        int aMaxZ = ab.maxZ() - 1;

        int overlapX = Math.min(mMaxX, aMaxX) - Math.max(mMinX, aMinX) + 1;
        int overlapZ = Math.min(mMaxZ, aMaxZ) - Math.max(mMinZ, aMinZ) + 1;
        if (overlapX <= 0 || overlapZ <= 0) return mover;

        int preferDx = 0;
        int preferDz = 0;
        String id = idOf(mover).toLowerCase(Locale.ROOT);
        if (id.contains("north") || id.contains("北") || id.contains("后")) preferDz = -1;
        else if (id.contains("south") || id.contains("南") || id.contains("前")) preferDz = 1;
        if (id.contains("east") || id.contains("东") || id.contains("右")) preferDx = 1;
        else if (id.contains("west") || id.contains("西") || id.contains("左")) preferDx = -1;
        // 左右配殿 of a south-facing hall sit on east/west — prefer X when id says side/wing without N/S.
        if (preferDx == 0 && preferDz == 0
                && (id.contains("side") || id.contains("wing") || id.contains("配") || id.contains("厢"))) {
            int centerM = (mMinX + mMaxX) / 2;
            int centerA = (aMinX + aMaxX) / 2;
            preferDx = centerM >= centerA ? 1 : -1;
        }

        int dx = 0;
        int dz = 0;
        if (preferDx != 0 && (preferDz == 0 || overlapX <= overlapZ + 2)) {
            dx = preferDx > 0
                    ? (aMaxX + GAP + 1) - mMinX
                    : (aMinX - GAP - md.width()) - mMinX;
        } else if (preferDz != 0) {
            dz = preferDz > 0
                    ? (aMaxZ + GAP + 1) - mMinZ
                    : (aMinZ - GAP - md.depth()) - mMinZ;
        } else if (overlapX <= overlapZ) {
            int centerM = (mMinX + mMaxX) / 2;
            int centerA = (aMinX + aMaxX) / 2;
            dx = centerM >= centerA
                    ? (aMaxX + GAP + 1) - mMinX
                    : (aMinX - GAP - md.width()) - mMinX;
        } else {
            int centerM = (mMinZ + mMaxZ) / 2;
            int centerA = (aMinZ + aMaxZ) / 2;
            dz = centerM >= centerA
                    ? (aMaxZ + GAP + 1) - mMinZ
                    : (aMinZ - GAP - md.depth()) - mMinZ;
        }
        if (dx == 0 && dz == 0) return mover;

        // Align ordinary side halls / peidian to the host floor datum (even when LLM sank them
        // several blocks). Preserve stilts, cantilevers, and explicit split-level offsets.
        Vec3i anchorOrigin = ComponentFootprintUtil.resolveMinCornerOrigin(anchor);
        int targetY = mo.y();
        if (anchorOrigin != null) {
            if (shouldAlignFloorDatum(mover)) {
                targetY = anchorOrigin.y();
            } else if (Math.abs(mo.y() - anchorOrigin.y()) <= 1) {
                targetY = anchorOrigin.y();
            }
        }

        Map<String, Object> params = new HashMap<>();
        if (mover.params() != null) params.putAll(mover.params());
        params.put("anchor_mode", "min_corner");
        params.put("footprint_separated", true);
        // Emit min-corner in the mover's slot-local frame (subtract slot anchor from plan bounds).
        Vec3i slotOx = slotOrigin(plan, mover.slotId());
        int newMinX = mMinX + dx - slotOx.x();
        int newMinZ = mMinZ + dz - slotOx.z();
        return new Component(
                mover.componentType(),
                mover.slotId(),
                new Vec3i(newMinX, targetY, newMinZ),
                md,
                mover.features(),
                params);
    }

    /** Legacy path when bounds cannot be resolved. */
    private static Component nudgeAwayRaw(Component mover, Component anchor) {
        Vec3i mo = mover.relativePosition();
        Vec3i ao = anchor.relativePosition();
        Dimensions md = mover.dimensions();
        Dimensions ad = anchor.dimensions();
        int mMaxX = mo.x() + md.width() - 1;
        int mMaxZ = mo.z() + md.depth() - 1;
        int aMaxX = ao.x() + ad.width() - 1;
        int aMaxZ = ao.z() + ad.depth() - 1;

        int overlapX = Math.min(mMaxX, aMaxX) - Math.max(mo.x(), ao.x()) + 1;
        int overlapZ = Math.min(mMaxZ, aMaxZ) - Math.max(mo.z(), ao.z()) + 1;
        if (overlapX <= 0 || overlapZ <= 0) return mover;

        int dx = 0;
        int dz = 0;
        if (overlapX <= overlapZ) {
            int centerM = mo.x() + md.width() / 2;
            int centerA = ao.x() + ad.width() / 2;
            dx = centerM >= centerA
                    ? (aMaxX + GAP + 1) - mo.x()
                    : (ao.x() - GAP - md.width()) - mo.x();
        } else {
            int centerM = mo.z() + md.depth() / 2;
            int centerA = ao.z() + ad.depth() / 2;
            dz = centerM >= centerA
                    ? (aMaxZ + GAP + 1) - mo.z()
                    : (ao.z() - GAP - md.depth()) - mo.z();
        }
        if (dx == 0 && dz == 0) return mover;

        Map<String, Object> params = new HashMap<>();
        if (mover.params() != null) params.putAll(mover.params());
        params.put("anchor_mode", "min_corner");
        params.put("footprint_separated", true);
        return new Component(
                mover.componentType(),
                mover.slotId(),
                new Vec3i(mo.x() + dx, ao.y(), mo.z() + dz),
                md,
                mover.features(),
                params);
    }
}
