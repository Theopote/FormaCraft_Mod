package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-private stacked-height envelope allocation helpers carved from RequestIntentInjector.
 */
final class StackedHeightEnvelopeAllocator {
    private StackedHeightEnvelopeAllocator() {}

    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes
    ) {
        return applyStackedHeightEnvelopes(null, components, hosts, envelopes, null);
    }

    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes,
            String userText
    ) {
        return applyStackedHeightEnvelopes(null, components, hosts, envelopes, userText);
    }

    static EnvelopeApplyResult applyStackedHeightEnvelopes(
            LlmPlan plan,
            List<Component> components,
            List<Component> hosts,
            List<StackedHeightEnvelope> envelopes,
            String userText
    ) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        if (components == null || envelopes == null || envelopes.isEmpty()) {
            return new EnvelopeApplyResult(
                    components == null ? List.of() : components, diagnostics, false, false,
                    Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<String, Component> hostById = new HashMap<>();
        for (Component host : hosts == null ? List.<Component>of() : hosts) {
            String mid = BuildingHostResolver.massId(host);
            if (mid != null) hostById.put(mid, host);
        }
        Map<String, Integer> newHeight = new HashMap<>();
        Map<String, Integer> newRelY = new HashMap<>();
        Map<String, Integer> sealedHeights = new HashMap<>();
        Map<String, Integer> envelopeTargetByMass = new HashMap<>();
        Map<String, String> envelopeFamilyByMass = new HashMap<>();
        Map<String, String> envelopeModeByMass = new HashMap<>();
        boolean gap = false;
        for (StackedHeightEnvelope env : envelopes) {
            if (env == null || env.massIds() == null || env.massIds().isEmpty() || env.targetHeight() <= 0) {
                continue;
            }
            List<Component> members = new ArrayList<>();
            for (String id : env.massIds()) {
                Component c = hostById.get(id);
                if (c == null || c.dimensions() == null || c.relativePosition() == null) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_STACKED_HEIGHT_UNSUPPORTED");
                    detail.put("target", id == null ? "" : id);
                    detail.put("message", "stacked height envelope missing geometry for a storey");
                    diagnostics.add(detail);
                    members.clear();
                    break;
                }
                members.add(c);
            }
            if (members.isEmpty()) continue;
            int minY = Integer.MAX_VALUE;
            int maxTop = Integer.MIN_VALUE;
            for (Component c : members) {
                int y = c.relativePosition().y();
                int h = Math.max(1, c.dimensions().height());
                minY = Math.min(minY, y);
                maxTop = Math.max(maxTop, y + h);
            }
            int envelope = maxTop - minY;
            if (envelope <= 0) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height envelope is non-positive"));
                continue;
            }
            members.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
            String family = env.familyKey() != null && !env.familyKey().isBlank()
                    ? env.familyKey()
                    : DesignOmitBindingResolver.stackedFamilyKey(BuildingHostResolver.massId(members.getFirst()));
            if (family == null) family = "stacked";
            // Per-binding mode from the local height clause — never whole-request text.
            String mode = env.mode() == null ? "shaft" : env.mode();
            boolean buildingMode = "building".equals(mode);
            // Building mode: player target is whole building; shaft budget excludes crown rise
            // above the shaft top (existing roofs/finials AND roofs the compiler will infer).
            // Mid-tier eaves below the shaft top must not suppress inferred top-roof budget.
            int crownRise = 0;
            if (buildingMode) {
                int measured = measureCrownRiseAboveShaft(components, members, maxTop);
                int inferred = estimateInferredRoofRise(plan, components, members);
                crownRise = Math.max(measured, inferred);
            }
            int shaftTarget = buildingMode
                    ? env.targetHeight() - crownRise
                    : env.targetHeight();
            if (shaftTarget <= 0) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "building-height target cannot reserve roof/finial rise"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            if (envelope == shaftTarget) {
                boolean infeasible = false;
                for (Component c : members) {
                    int h = Math.max(1, c.dimensions().height());
                    int need = authoredFloorNeed(c);
                    if (need > h) {
                        infeasible = true;
                        String mid = BuildingHostResolver.massId(c);
                        diagnostics.add(Map.of(
                                "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                                "target", mid == null ? "" : mid,
                                "blocks", env.targetHeight(),
                                "message", "storey floor bands exceed height under matched envelope"));
                        break;
                    }
                }
                if (infeasible) {
                    gap = true;
                    newHeight.clear();
                    newRelY.clear();
                    break;
                }
                // Keep current shaft H/Y so the rewrite loop still seats enclosed roofs
                // at hostTop-1 (AlignRoofToMass) even when the shaft already matches.
                for (Component c : members) {
                    String mid = BuildingHostResolver.massId(c);
                    if (mid != null) {
                        int curH = Math.max(1, c.dimensions().height());
                        sealedHeights.put(mid, curH);
                        newHeight.put(mid, curH);
                        newRelY.put(mid, c.relativePosition().y());
                        envelopeTargetByMass.put(mid, env.targetHeight());
                        envelopeFamilyByMass.put(mid, family);
                        envelopeModeByMass.put(mid, mode);
                    }
                }
                continue;
            }
            int n = members.size();
            int[] oldHeights = new int[n];
            int[] gaps = new int[n];
            int[] minHeights = new int[n];
            int minRequiredSum = 0;
            for (int i = 0; i < n; i++) {
                Component c = members.get(i);
                oldHeights[i] = Math.max(1, c.dimensions().height());
                minHeights[i] = minFeasibleStoreyHeight(c);
                minRequiredSum += minHeights[i];
                if (i + 1 < n) {
                    int top = c.relativePosition().y() + oldHeights[i];
                    int nextY = members.get(i + 1).relativePosition().y();
                    gaps[i] = Math.max(0, nextY - top);
                } else {
                    gaps[i] = 0;
                }
                if (gaps[i] > 0) {
                    minRequiredSum += 1;
                }
            }
            if (minRequiredSum > shaftTarget) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height target cannot fit minimum storey bands and gaps"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            int[] scaledHeights = new int[n];
            int[] scaledGaps = new int[n];
            if (!allocateStackedShaftWithGaps(
                    oldHeights, gaps, minHeights, shaftTarget, scaledHeights, scaledGaps)) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_STACKED_HEIGHT_UNSUPPORTED",
                        "blocks", env.targetHeight(),
                        "message", "stacked height scale cannot preserve storey bands and gaps"));
                newHeight.clear();
                newRelY.clear();
                break;
            }
            int yCursor = minY;
            for (int i = 0; i < n; i++) {
                Component c = members.get(i);
                String mid = BuildingHostResolver.massId(c);
                if (mid == null) {
                    gap = true;
                    break;
                }
                newHeight.put(mid, scaledHeights[i]);
                sealedHeights.put(mid, scaledHeights[i]);
                envelopeTargetByMass.put(mid, env.targetHeight());
                envelopeFamilyByMass.put(mid, family);
                envelopeModeByMass.put(mid, mode);
                newRelY.put(mid, yCursor);
                yCursor += scaledHeights[i] + scaledGaps[i];
            }
            if (gap) {
                newHeight.clear();
                newRelY.clear();
                sealedHeights.clear();
                envelopeTargetByMass.clear();
                envelopeFamilyByMass.clear();
                envelopeModeByMass.clear();
                break;
            }
        }
        if (gap) {
            return new EnvelopeApplyResult(
                    components, diagnostics, true, false, Map.of(), Map.of(), Map.of(), Map.of());
        }
        if (newHeight.isEmpty() && newRelY.isEmpty() && envelopeTargetByMass.isEmpty()) {
            return new EnvelopeApplyResult(
                    components, diagnostics, false, false,
                    Map.copyOf(sealedHeights), Map.of(), Map.of(), Map.copyOf(envelopeModeByMass));
        }
        Map<String, int[]> oldHostGeom = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            if (mid == null || (!newHeight.containsKey(mid) && !newRelY.containsKey(mid))) continue;
            oldHostGeom.put(mid, new int[]{c.relativePosition().y(), Math.max(1, c.dimensions().height())});
        }
        List<Component> out = new ArrayList<>(components.size());
        boolean changed = false;
        for (Component c : components) {
            if (c == null) {
                out.add(null);
                continue;
            }
            String mid = BuildingHostResolver.massId(c);
            Integer h = mid == null ? null : newHeight.get(mid);
            Integer y = mid == null ? null : newRelY.get(mid);
            Integer envTarget = mid == null ? null : envelopeTargetByMass.get(mid);
            String envFamily = mid == null ? null : envelopeFamilyByMass.get(mid);
            String envMode = mid == null ? null : envelopeModeByMass.get(mid);
            String hostId = DesignOmitBindingResolver.satelliteHostId(c);
            int[] oldHost = hostId == null ? null : oldHostGeom.get(hostId);
            Integer hostNewY = hostId == null ? null : newRelY.get(hostId);
            Integer hostNewH = hostId == null ? null : newHeight.get(hostId);
            boolean satelliteShift = oldHost != null
                    && c.relativePosition() != null
                    && (hostNewY != null || hostNewH != null)
                    && h == null && y == null;
            boolean stampEnvelope = envTarget != null;
            if (h == null && y == null && !satelliteShift && !stampEnvelope) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
            com.formacraft.common.llm.dto.Dimensions dims = c.dimensions();
            Vec3i rel = c.relativePosition();
            if (h != null) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyAuthoritativeHeightValue(params, h);
                if (dims == null) {
                    dims = new com.formacraft.common.llm.dto.Dimensions(8, 8, h);
                } else {
                    dims = new com.formacraft.common.llm.dto.Dimensions(
                            Math.max(1, dims.width()), Math.max(1, dims.depth()), h);
                }
            } else if (stampEnvelope && sealedHeights.containsKey(mid)) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyAuthoritativeHeightValue(params, sealedHeights.get(mid));
            }
            if (stampEnvelope) {
                params.put(RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY, envTarget);
                if (envFamily != null) {
                    params.put(RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY, envFamily);
                }
                params.put(RequestIntentInjector.ENVELOPE_MODE_KEY, envMode == null ? "shaft" : envMode);
                // Seal envelope fields with the same server digest as height — not height alone.
                com.formacraft.common.typology.UserConstraintContract.stampVerified(
                        params,
                        "height", "towerHeight",
                        RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY,
                        RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY,
                        RequestIntentInjector.ENVELOPE_MODE_KEY);
            }
            if (y != null && rel != null && rel.y() != y) {
                rel = new Vec3i(rel.x(), y, rel.z());
            } else if (satelliteShift && rel != null && oldHost != null) {
                int newHY = hostNewY != null ? hostNewY : oldHost[0];
                int newHH = hostNewH != null ? hostNewH : oldHost[1];
                Component hostComp = hostId == null ? null : hostById.get(hostId);
                Map<String, Object> hostParams = hostComp != null ? hostComp.params() : null;
                Integer nextY = relocateSatelliteY(c, oldHost, newHY, newHH, hostParams);
                if (nextY == null) {
                    gap = true;
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("code", "E_STACKED_HEIGHT_UNSUPPORTED");
                    detail.put("target", hostId == null ? "" : hostId);
                    detail.put("message", "satellite cannot fit inside scaled host facade");
                    diagnostics.add(detail);
                    break;
                }
                if (nextY != rel.y()) {
                    rel = new Vec3i(rel.x(), nextY, rel.z());
                }
            }
            changed = true;
            out.add(new Component(c.componentType(), c.slotId(), rel, dims, c.features(), params));
        }
        if (gap) {
            return new EnvelopeApplyResult(
                    components, diagnostics, true, false, Map.of(), Map.of(), Map.of(), Map.of());
        }
        return new EnvelopeApplyResult(
                out, diagnostics, false, changed,
                Map.copyOf(sealedHeights),
                Map.copyOf(envelopeTargetByMass),
                Map.copyOf(envelopeFamilyByMass),
                Map.copyOf(envelopeModeByMass));
    }

    /**
     * Prefer per-binding {@link com.formacraft.common.typology.UserConstraintContract#inferEnvelopeMode}.
     * Kept for focused unit tests that pass a single local clause as the whole string.
     */
    static boolean wantsBuildingEnvelope(String userText) {
        return "building".equals(
                com.formacraft.common.typology.UserConstraintContract.inferEnvelopeMode(userText));
    }

    /**
     * Net roof rise the compiler will actually infer on the top storey (0 when it will not).
     * Reuses {@link ComponentSatelliteInference} roof creation rules, including enclosed
     * top-overlap of 1. Mid-tier roofs do not suppress top-storey budget.
     */
    static int estimateInferredRoofRise(List<Component> components, List<Component> shaftMembers) {
        return estimateInferredRoofRise(null, components, shaftMembers);
    }

    static int estimateInferredRoofRise(
            LlmPlan plan, List<Component> components, List<Component> shaftMembers) {
        if (shaftMembers == null || shaftMembers.isEmpty()) return 0;
        // Without compiler satellites, no independent ROOF will be created later.
        if (plan == null || !plan.allowCompilerSatellites()) return 0;
        Component top = shaftMembers.getLast();
        if (top == null || top.dimensions() == null || top.relativePosition() == null) return 0;
        String topId = BuildingHostResolver.massId(top);
        // Only an existing top-hosted ROOF covers the inferred-roof budget. Finials / banners
        // (TOP_PROTRUSION) and non-ROOF crowns still count in measureCrownRiseAboveShaft, but must
        // not suppress satellite roof inference (GEOMETRY — matches ComponentSatelliteInference).
        if (components != null && topId != null) {
            for (Component c : components) {
                if (c == null || c.componentType() == null) continue;
                String type = c.componentType().trim().toUpperCase(Locale.ROOT);
                if (!type.startsWith("ROOF")) continue;
                String host = DesignOmitBindingResolver.satelliteHostId(c);
                if (topId.equals(host)) return 0;
            }
        }
        return ComponentSatelliteInference.estimatedInferredRoofNetRise(plan, top);
    }

    /**
     * Extra rise of roofs/finials above the current shaft top (0 when none).
     * Each ROOF is seated on its own host storey — mid-tier eaves never invent rise at shaftTop.
     */
    static int measureCrownRiseAboveShaft(
            List<Component> components, List<Component> shaftMembers, int shaftTop) {
        if (components == null || shaftMembers == null || shaftMembers.isEmpty()) return 0;
        Set<String> shaftIds = new HashSet<>();
        Map<String, Component> shaftById = new HashMap<>();
        for (Component m : shaftMembers) {
            String id = BuildingHostResolver.massId(m);
            if (id != null) {
                shaftIds.add(id);
                shaftById.put(id, m);
            }
        }
        int maxTop = shaftTop;
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!countsTowardBuildingEnvelope(c)) continue;
            String host = DesignOmitBindingResolver.satelliteHostId(c);
            String mid = BuildingHostResolver.massId(c);
            boolean attached = (host != null && shaftIds.contains(host))
                    || (mid != null && shaftIds.contains(mid))
                    || familyMatchesShaft(mid, shaftIds)
                    || familyMatchesShaft(host, shaftIds);
            if (!attached) continue;
            int satH = Math.max(1, c.dimensions().height());
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            // AlignRoofToMass / makeRoofComponent: seat on the roof's own host, not shaftTop.
            if (type.startsWith("ROOF")) {
                Component hostMass = host == null ? null : shaftById.get(host);
                if (hostMass != null
                        && hostMass.relativePosition() != null
                        && hostMass.dimensions() != null) {
                    int hostTop = hostMass.relativePosition().y()
                            + Math.max(1, hostMass.dimensions().height());
                    int seatY = roofSeatYOnHost(hostTop, hostMass.params());
                    maxTop = Math.max(maxTop, seatY + satH);
                } else {
                    // Unknown host: trust authored placement; never lift to shaftTop.
                    maxTop = Math.max(maxTop, c.relativePosition().y() + satH);
                }
                continue;
            }
            maxTop = Math.max(maxTop, c.relativePosition().y() + satH);
        }
        return Math.max(0, maxTop - shaftTop);
    }

    /**
     * Roof bearing Y on a host mass. Open-frame uses host MASS params (column tops);
     * enclosed seals at hostTop-1. Matches {@code alignRoofToMass} / {@code makeRoofComponent}.
     */
    static int roofSeatYOnHost(int hostTop, Map<String, Object> hostParams) {
        if (com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(hostParams)) {
            return hostTop;
        }
        return hostTop - 1;
    }

    static boolean familyMatchesShaft(String id, Set<String> shaftIds) {
        if (id == null || shaftIds.isEmpty()) return false;
        String fam = DesignOmitBindingResolver.stackedFamilyKey(id);
        if (fam == null) return false;
        for (String sid : shaftIds) {
            if (fam.equals(DesignOmitBindingResolver.stackedFamilyKey(sid))) return true;
        }
        return false;
    }

    /**
     * Shared crown / top-protrusion classification for budget, relocate, and integrity.
     * Ordinary facade {@code DECOR_DETAIL} is not a crown; roofs/finials and named top
     * protrusions (spire/banner/flagpole) are.
     */
    static RequestIntentInjector.CrownKind classifyCrownAttachment(Component c) {
        if (c == null) return RequestIntentInjector.CrownKind.NONE;
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase();
        if (type.contains("WINDOW") || type.contains("FACADE")
                || type.contains("ENTRANCE") || type.contains("DOOR")
                || type.contains("STAIR") || type.contains("OPENING")
                || "FLOOR_SLAB".equals(type) || type.contains("FLOOR")) {
            return RequestIntentInjector.CrownKind.NONE;
        }
        if (type.startsWith("ROOF") || type.contains("CROWN") || "FINIAL".equals(type)) {
            return RequestIntentInjector.CrownKind.STRUCTURAL_CROWN;
        }
        String mid = BuildingHostResolver.massId(c);
        String lower = mid == null ? "" : mid.toLowerCase(Locale.ROOT);
        boolean namedCrown = lower.contains("roof") || lower.contains("finial")
                || lower.contains("crown") || lower.contains("spire");
        boolean namedProtrusion = lower.contains("banner") || lower.contains("flag")
                || lower.contains("flagpole") || lower.contains("pole")
                || lower.contains("finial") || lower.contains("spire");
        if (namedCrown) return RequestIntentInjector.CrownKind.STRUCTURAL_CROWN;
        if ("DECOR_DETAIL".equals(type) || type.contains("DECOR") || type.contains("ORNAMENT")) {
            return namedProtrusion ? RequestIntentInjector.CrownKind.TOP_PROTRUSION : RequestIntentInjector.CrownKind.NONE;
        }
        return RequestIntentInjector.CrownKind.NONE;
    }

    /** True when the component sticks to the host top after shaft scale. */
    static boolean sticksToHostTop(Component c) {
        RequestIntentInjector.CrownKind k = classifyCrownAttachment(c);
        return k == RequestIntentInjector.CrownKind.STRUCTURAL_CROWN || k == RequestIntentInjector.CrownKind.TOP_PROTRUSION;
    }

    /** True when the component contributes to building-mode envelope height. */
    static boolean countsTowardBuildingEnvelope(Component c) {
        return sticksToHostTop(c);
    }

    /**
     * Relocate a satellite after its host height changes.
     * Only true roofs/finials/top protrusions stick to the new top — never openings/stairs
     * or ordinary facade decor, even when start Y is within 1 of the old host top.
     * Returns null when the satellite cannot fit — caller must emit a capability gap.
     */
    static Integer relocateSatelliteY(Component c, int[] oldHost, int newHY, int newHH) {
        return relocateSatelliteY(c, oldHost, newHY, newHH, null);
    }

    /**
     * @param hostParams host MASS params for open/closed seating — never the orphan ROOF's own
     *                   {@code open} flag (alignRoofToMass keys off the mass).
     */
    static Integer relocateSatelliteY(
            Component c, int[] oldHost, int newHY, int newHH, Map<String, Object> hostParams) {
        if (c == null || c.relativePosition() == null || oldHost == null || oldHost.length < 2) {
            return null;
        }
        int satH = c.dimensions() == null ? 1 : Math.max(1, c.dimensions().height());
        int oldHY = oldHost[0];
        int oldHH = Math.max(1, oldHost[1]);
        int oldTop = oldHY + oldHH;
        int relY = c.relativePosition().y();
        int offsetFromTop = relY - oldTop;
        if (sticksToHostTop(c)) {
            String crownType = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            // Match ComponentSatelliteAligner / makeRoofComponent: host MASS open policy.
            if (crownType.startsWith("ROOF")) {
                return roofSeatYOnHost(newHY + newHH, hostParams);
            }
            return newHY + newHH + offsetFromTop;
        }
        // Openings / stairs / floors / facade decor: proportional then clamp inside host.
        int offsetFromBottom = relY - oldHY;
        int nextY = newHY + (int) Math.round(offsetFromBottom * (double) newHH / (double) oldHH);
        int maxStart = newHY + newHH - satH;
        if (maxStart < newHY) {
            return null; // satellite taller than scaled host
        }
        if (nextY < newHY) nextY = newHY;
        if (nextY > maxStart) nextY = maxStart;
        return nextY;
    }

    /**
     * Register a stacked envelope; identical height+mode is deduped; conflicting heights or
     * two explicit contradictory modes at the same height gap.
     * @return false when a hard conflict was recorded
     */
    static boolean registerStackedEnvelope(
            List<StackedHeightEnvelope> envelopes,
            Map<String, Integer> heightByFamily,
            Map<String, String> modeByFamily,
            Map<String, Boolean> modeExplicitByFamily,
            List<Map<String, Object>> diagnostics,
            List<String> massIds,
            int blocks,
            String mode,
            boolean modeExplicit
    ) {
        if (massIds == null || massIds.isEmpty() || blocks <= 0) return true;
        String family = DesignOmitBindingResolver.stackedFamilyKey(massIds.getFirst());
        if (family == null || family.isBlank()) family = "stacked";
        String normMode = com.formacraft.common.typology.UserConstraintContract.normalizeEnvelopeMode(mode);
        Integer prior = heightByFamily.get(family);
        if (prior != null) {
            if (prior != blocks) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_HEIGHT_HOST_CONFLICT");
                detail.put("target", family);
                detail.put("blocks", blocks);
                detail.put("priorBlocks", prior);
                detail.put("message", "conflicting stacked height cues for the same tower family");
                diagnostics.add(detail);
                return false;
            }
            String priorMode = modeByFamily.getOrDefault(family, "shaft");
            boolean priorExplicit = Boolean.TRUE.equals(modeExplicitByFamily.get(family));
            if (priorMode.equals(normMode)) {
                if (modeExplicit) modeExplicitByFamily.put(family, true);
                return true;
            }
            // Same numeric target, different modes.
            if (priorExplicit && modeExplicit) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_HEIGHT_HOST_CONFLICT");
                detail.put("target", family);
                detail.put("blocks", blocks);
                detail.put("priorMode", priorMode);
                detail.put("mode", normMode);
                detail.put("message",
                        "conflicting shaft vs building height semantics for the same tower family");
                diagnostics.add(detail);
                return false;
            }
            // Explicit declaration overrides a default shaft inference; never demote building→shaft.
            if (modeExplicit) {
                modeByFamily.put(family, normMode);
                modeExplicitByFamily.put(family, true);
                for (int i = 0; i < envelopes.size(); i++) {
                    StackedHeightEnvelope e = envelopes.get(i);
                    if (family.equals(e.familyKey())) {
                        envelopes.set(i, new StackedHeightEnvelope(
                                e.massIds(), e.targetHeight(), e.familyKey(), normMode));
                    }
                }
            }
            return true;
        }
        heightByFamily.put(family, blocks);
        modeByFamily.put(family, normMode);
        modeExplicitByFamily.put(family, modeExplicit);
        envelopes.add(new StackedHeightEnvelope(massIds, blocks, family, normMode));
        return true;
    }

    /** floor_count when authored; 0 means no multi-storey band constraint on this mass. */
    static int storeyFloorCount(Component c) {
        if (c == null || c.params() == null) return 0;
        return Math.max(0, com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_count", "floorCount"));
    }

    /**
     * Minimum clear-air storey band for scale allocation.
     * USER_EXPLICIT / server-sealed floor_height is a hard band; otherwise minStoreyBand.
     * Bare shells (no floor_count) need at least 1.
     */
    static int minFeasibleStoreyHeight(Component c) {
        int floorCount = storeyFloorCount(c);
        if (floorCount <= 0) return 1;
        int hardFh = hardFloorHeight(c);
        int band = hardFh > 0
                ? hardFh
                : com.formacraft.common.generation.component.util.InteriorSpaceDefaults.minStoreyBand();
        return Math.max(1, floorCount * band);
    }

    /**
     * Authored floor_count × floor_height (hard or minBand) that must fit the current storey height
     * when the whole-building envelope already matches — conflict → capability gap.
     */
    static int authoredFloorNeed(Component c) {
        int floorCount = storeyFloorCount(c);
        if (floorCount <= 0) return 0;
        int hardFh = hardFloorHeight(c);
        int authoredFh = c.params() == null ? 0
                : com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_height", "floorHeight");
        int band = hardFh > 0
                ? hardFh
                : (authoredFh > 0
                ? authoredFh
                : com.formacraft.common.generation.component.util.InteriorSpaceDefaults.minStoreyBand());
        return Math.max(1, floorCount * band);
    }

    /** Player-hard floor_height when server-sealed or _param_sources USER_EXPLICIT. */
    static int hardFloorHeight(Component c) {
        if (c == null || c.params() == null) return 0;
        int fh = com.formacraft.common.generation.component.util.ComponentParamParsers
                .intParam(c.params(), "floor_height", "floorHeight");
        if (fh <= 0) return 0;
        if (com.formacraft.common.typology.UserConstraintContract
                .isServerVerified(c.params(), "floor_height")
                || com.formacraft.common.typology.UserConstraintContract
                .isServerVerified(c.params(), "floorHeight")) {
            return fh;
        }
        if (com.formacraft.common.typology.TypologyParamResolver
                .isUserHardConstraint(c.params(), "floor_height", "floorHeight")) {
            return fh;
        }
        return 0;
    }

    /**
     * Assign min bands first, then distribute {@code target - sum(mins)} by expandable weight
     * using the largest-remainder method so the sum equals {@code target} exactly.
     * Kept for tightly-stacked shafts (no gaps). Prefer {@link #allocateStackedShaftWithGaps}.
     */
    static int[] allocateStackedHeights(int[] oldHeights, int[] minHeights, int target) {
        int n = oldHeights.length;
        int[] gaps = new int[n];
        int[] outH = new int[n];
        int[] outG = new int[n];
        if (!allocateStackedShaftWithGaps(oldHeights, gaps, minHeights, target, outH, outG)) {
            return minHeights.clone();
        }
        return outH;
    }

    /**
     * Scale storey solids and inter-storey gaps together so eave/transition voids are preserved
     * and excess height is distributed by original slice weight (not dumped on the crown).
     * {@code outHeights}/{@code outGaps} are filled; returns false when infeasible.
     */
    static boolean allocateStackedShaftWithGaps(
            int[] oldHeights,
            int[] gaps,
            int[] minHeights,
            int target,
            int[] outHeights,
            int[] outGaps
    ) {
        int n = oldHeights.length;
        if (n == 0 || outHeights.length != n || outGaps.length != n) return false;
        int[] minGaps = new int[n];
        int minSum = 0;
        for (int i = 0; i < n; i++) {
            minGaps[i] = gaps[i] > 0 ? 1 : 0;
            minSum += minHeights[i] + minGaps[i];
        }
        if (minSum > target) return false;
        // Slice layout: solid0, gap0, solid1, gap1, ... (trailing gap of last is always 0).
        int sliceCount = n * 2;
        int[] oldSlices = new int[sliceCount];
        int[] minSlices = new int[sliceCount];
        for (int i = 0; i < n; i++) {
            oldSlices[i * 2] = oldHeights[i];
            minSlices[i * 2] = minHeights[i];
            oldSlices[i * 2 + 1] = gaps[i];
            minSlices[i * 2 + 1] = minGaps[i];
        }
        int[] outSlices = allocateSlicesLargestRemainder(oldSlices, minSlices, target);
        if (outSlices == null) return false;
        for (int i = 0; i < n; i++) {
            outHeights[i] = outSlices[i * 2];
            outGaps[i] = outSlices[i * 2 + 1];
        }
        return true;
    }

    /** Min-first then weight by (old-min); largest-remainder so sum == target. */
    static int[] allocateSlicesLargestRemainder(int[] oldSlices, int[] minSlices, int target) {
        int n = oldSlices.length;
        int[] out = new int[n];
        int minSum = 0;
        for (int i = 0; i < n; i++) {
            out[i] = Math.max(0, minSlices[i]);
            minSum += out[i];
        }
        if (minSum > target) return null;
        int remainder = target - minSum;
        if (remainder == 0) return out;
        long weightSum = 0L;
        int[] weights = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = Math.max(0, oldSlices[i] - out[i]);
            weightSum += weights[i];
        }
        if (weightSum <= 0) {
            for (int i = 0; i < n && remainder > 0; i++) {
                // Prefer solids (even indices) when spreading with no weight.
                int idx = (i * 2) % n;
                out[idx] += 1;
                remainder--;
            }
            if (remainder > 0) out[0] += remainder;
            return out;
        }
        int[] baseExtra = new int[n];
        double[] frac = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = remainder * (weights[i] / (double) weightSum);
            baseExtra[i] = (int) Math.floor(exact);
            frac[i] = exact - baseExtra[i];
            assigned += baseExtra[i];
        }
        int left = remainder - assigned;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(frac[b], frac[a]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int k = 0; k < left; k++) {
            baseExtra[order[k % n]] += 1;
        }
        for (int i = 0; i < n; i++) {
            out[i] += baseExtra[i];
        }
        return out;
    }


    record EnvelopeApplyResult(
            List<Component> components,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap,
            boolean changed,
            Map<String, Integer> sealedHeights,
            Map<String, Integer> envelopeTargetByMass,
            Map<String, String> envelopeFamilyByMass,
            Map<String, String> envelopeModeByMass
    ) {
        EnvelopeApplyResult {
            sealedHeights = sealedHeights == null ? Map.of() : Map.copyOf(sealedHeights);
            envelopeTargetByMass = envelopeTargetByMass == null ? Map.of() : Map.copyOf(envelopeTargetByMass);
            envelopeFamilyByMass = envelopeFamilyByMass == null ? Map.of() : Map.copyOf(envelopeFamilyByMass);
            envelopeModeByMass = envelopeModeByMass == null ? Map.of() : Map.copyOf(envelopeModeByMass);
        }
    }

    /**
     * Whole-building height for stacked storeys — never copy onto each tier's dimensions.height.
     * {@code familyKey} is the stable stacked identity (e.g. pagoda); {@code mode} is shaft|building.
     */
    record StackedHeightEnvelope(
            List<String> massIds, int targetHeight, String familyKey, String mode) {
        StackedHeightEnvelope {
            massIds = massIds == null ? List.of() : List.copyOf(massIds);
            mode = com.formacraft.common.typology.UserConstraintContract.normalizeEnvelopeMode(mode);
        }
    }

    static LlmPlan withStackedEnvelopeTargetsMeta(
            LlmPlan plan,
            Map<String, Integer> targetByMass,
            Map<String, String> familyByMass,
            Map<String, String> modeByMass
    ) {
        if (plan == null || targetByMass == null || targetByMass.isEmpty()) return plan;
        Map<String, Map<String, Object>> byFamily = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : targetByMass.entrySet()) {
            String massId = e.getKey();
            String family = familyByMass == null ? null : familyByMass.get(massId);
            if (family == null || family.isBlank()) {
                family = DesignOmitBindingResolver.stackedFamilyKey(massId);
            }
            if (family == null || family.isBlank()) family = massId;
            String mode = modeByMass == null ? null : modeByMass.get(massId);
            if (!"building".equals(mode)) mode = "shaft";
            String finalMode = mode;
            Map<String, Object> row = byFamily.computeIfAbsent(family, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("family", k);
                m.put("target_height", e.getValue());
                m.put("mode", finalMode);
                m.put("mass_ids", new ArrayList<String>());
                return m;
            });
            row.put("target_height", e.getValue());
            row.put("mode", mode);
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) row.get("mass_ids");
            if (!ids.contains(massId)) ids.add(massId);
        }
        List<Map<String, Object>> rows = new ArrayList<>(byFamily.values());
        Map<String, Object> meta = plan.planMeta() == null
                ? new HashMap<>()
                : new HashMap<>(plan.planMeta());
        meta.put(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META, rows);
        meta.put(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_SEAL_META,
                com.formacraft.common.typology.UserConstraintContract.sealStackedEnvelopeTargets(rows));
        return RequestIntentInjector.withMeta(plan, meta);
    }


}
