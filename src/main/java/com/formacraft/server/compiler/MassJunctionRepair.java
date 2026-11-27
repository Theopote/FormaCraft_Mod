package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassDomainRelation;
import com.formacraft.common.generation.component.util.MassJunctionGeometry;
import com.formacraft.common.generation.component.util.MassJunctionIntent;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Multi-mass junction design intent → geometry stamps (Phase 02.3.3 Tasks C–E).
 * <p>
 * Prefers authorized junction cleanup over moving locked envelopes. Does not auto-open
 * on mere CONTACTING. Classification: GEOMETRY execution of USER/AI junction intent.
 */
final class MassJunctionRepair {
    private MassJunctionRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().size() < 2) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components());
        boolean changed = false;
        List<Component> portals = new ArrayList<>();

        for (int i = 0; i < out.size(); i++) {
            Component a = out.get(i);
            if (!isMass(a)) continue;
            for (int j = i + 1; j < out.size(); j++) {
                Component b = out.get(j);
                if (!isMass(b)) continue;

                MassJunctionIntent intent = MassJunctionIntent.forPair(a, b);
                if (intent == MassJunctionIntent.INDEPENDENT) {
                    continue;
                }

                var ab = planBounds(plan, a);
                var bb = planBounds(plan, b);
                if (ab == null || bb == null) continue;

                DomainPair domains = domainsFor(a, b, ab, bb);
                MassDomainRelation.Query rel = MassDomainRelation.classify(
                        ab, bb, domains.domainA(), domains.originA(),
                        domains.domainB(), domains.originB());

                if (intent == MassJunctionIntent.VERTICAL_CONNECTION) {
                    if (rel.relation() != MassDomainRelation.VERTICAL_STACK
                            && rel.relation() != MassDomainRelation.CONTAINMENT
                            && rel.relation() != MassDomainRelation.PARTIAL_INTERSECT) {
                        // Intent without vertical geometry — leave alone (no silent move).
                        continue;
                    }
                    if (stampPair(out, i, j, intent, rel, a, b)) changed = true;
                    continue;
                }

                if (intent == MassJunctionIntent.INTENTIONAL_INTERLOCK) {
                    if (rel.relation() == MassDomainRelation.DISJOINT
                            || rel.relation() == MassDomainRelation.CORNER_CONTACT
                            || rel.relation() == MassDomainRelation.EDGE_CONTACT) {
                        continue;
                    }
                    if (stampPair(out, i, j, intent, rel, a, b)) changed = true;
                    continue;
                }

                // shared_wall / open / passage need face contact or shallow intersect — not corner-only.
                if (rel.relation() == MassDomainRelation.CORNER_CONTACT
                        || rel.relation() == MassDomainRelation.EDGE_CONTACT
                        || rel.relation() == MassDomainRelation.DISJOINT) {
                    // Corner/edge: never auto-open or invent a shared wall.
                    continue;
                }

                if (intent == MassJunctionIntent.PASSAGE || intent == MassJunctionIntent.OPEN_CONNECTION) {
                    if (elevationMismatch(ab, bb, intent)) {
                        CapabilityGap gap = new CapabilityGap(
                                "E_JUNCTION_ELEVATION_MISMATCH",
                                "junction " + intent.name().toLowerCase(Locale.ROOT)
                                        + " between " + idOf(a) + " and " + idOf(b)
                                        + " has mismatched floor elevations (no silent stair)",
                                "components[].params.junction_intent",
                                List.of(
                                        "Align storey bands / relative Y",
                                        "Use vertical_connection with an explicit stair plan",
                                        "Or change junction_intent to shared_wall"
                                )
                        );
                        CompileCapabilityDiagnostics.set(gap);
                        AssemblyCompileDiagnostics.set(gap);
                        return plan;
                    }
                    if (corridorBlockedByWalls(a, b, ab, bb, rel)) {
                        CapabilityGap gap = new CapabilityGap(
                                "E_JUNCTION_PASSAGE_BLOCKED",
                                "passage/corridor between " + idOf(a) + " and " + idOf(b)
                                        + " is fully occupied by wall thickness (no silent shrink)",
                                "components[].params.wall_thickness",
                                List.of(
                                        "Widen the corridor / connection span",
                                        "Reduce wall_thickness explicitly",
                                        "Or use open_connection with a wider shared face"
                                )
                        );
                        CompileCapabilityDiagnostics.set(gap);
                        AssemblyCompileDiagnostics.set(gap);
                        return plan;
                    }
                }

                // Prefer junction cleanup: stamp omit cells / openings; do not move locked peers.
                if (stampPair(out, i, j, intent, rel, a, b)) {
                    changed = true;
                    a = out.get(i);
                    b = out.get(j);
                }

                if (intent == MassJunctionIntent.SHARED_WALL) {
                    if (applySharedWallCollapse(out, i, j, a, b, ab, bb, domains, rel)) {
                        changed = true;
                        a = out.get(i);
                        b = out.get(j);
                    }
                }

                if (intent == MassJunctionIntent.OPEN_CONNECTION || intent == MassJunctionIntent.PASSAGE) {
                    Component portal = buildPortal(a, b, ab, bb, rel, intent);
                    if (portal != null) {
                        portals.add(portal);
                        // Mirror opening omit cells on both shells for floor continuity.
                        if (stampOpeningOmits(out, i, j, a, b, ab, bb, rel, intent)) {
                            changed = true;
                        }
                    }
                }

                FormacraftMod.LOGGER.info(
                        "MassJunctionRepair: {} ↔ {} intent={} relation={} face={}",
                        idOf(a), idOf(b), intent, rel.relation(), rel.sharedFace());
            }
        }

        if (!portals.isEmpty()) {
            out.addAll(portals);
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * True when separation must not nudge this pair — junction intent already owns contact cleanup.
     * Intentional interlock still flows through separation peer-stamping; shared/open/passage/
     * vertical prefer in-place junction handling over footprint moves.
     */
    static boolean junctionHandlesPair(LlmPlan plan, Component a, Component b) {
        MassJunctionIntent intent = MassJunctionIntent.forPair(a, b);
        return intent == MassJunctionIntent.SHARED_WALL
                || intent == MassJunctionIntent.OPEN_CONNECTION
                || intent == MassJunctionIntent.PASSAGE
                || intent == MassJunctionIntent.VERTICAL_CONNECTION;
    }

    private static boolean stampPair(
            List<Component> out,
            int i,
            int j,
            MassJunctionIntent intent,
            MassDomainRelation.Query rel,
            Component a,
            Component b
    ) {
        boolean changed = false;
        String faceA = rel.sharedFace();
        String faceB = opposite(faceA);
        if (updateComponent(out, i, a, intent, idOf(b), faceA, rel.contactMinY(), rel.contactMaxY())) {
            changed = true;
        }
        if (updateComponent(out, j, b, intent, idOf(a), faceB, rel.contactMinY(), rel.contactMaxY())) {
            changed = true;
        }
        return changed;
    }

    private static boolean updateComponent(
            List<Component> out,
            int index,
            Component c,
            MassJunctionIntent intent,
            String peerId,
            String face,
            int bandMinY,
            int bandMaxY
    ) {
        Map<String, Object> params = new HashMap<>();
        if (c.params() != null) params.putAll(c.params());
        MassJunctionIntent.stampJunction(params, intent, peerId, face, bandMinY, bandMaxY);
        out.set(index, new Component(
                c.componentType(), c.slotId(), c.relativePosition(),
                c.dimensions(), c.features(), params));
        return true;
    }

    private static boolean applySharedWallCollapse(
            List<Component> out,
            int i,
            int j,
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            DomainPair domains,
            MassDomainRelation.Query rel
    ) {
        // Lexicographically later id omits duplicate thickness (stable ownership: earlier keeps wall).
        boolean aFirst = idOf(a).compareTo(idOf(b)) <= 0;
        Component primary = aFirst ? a : b;
        Component secondary = aFirst ? b : a;
        int primaryIdx = aFirst ? i : j;
        int secondaryIdx = aFirst ? j : i;
        var primaryDomain = aFirst ? domains.domainA() : domains.domainB();
        var secondaryDomain = aFirst ? domains.domainB() : domains.domainA();
        var primaryOrigin = aFirst ? domains.originA() : domains.originB();
        var secondaryOrigin = aFirst ? domains.originB() : domains.originA();
        String faceSec = aFirst ? opposite(rel.sharedFace()) : rel.sharedFace();
        if (faceSec == null) {
            faceSec = inferFace(planLocalBounds(secondary), planLocalBounds(primary));
        }
        int bandMin = rel.contactMinY() > 0 || rel.contactMaxY() > rel.contactMinY()
                ? rel.contactMinY()
                : Math.max(ab.minY(), bb.minY());
        int bandMax = rel.contactMaxY() > bandMin
                ? rel.contactMaxY()
                : Math.min(ab.maxY(), bb.maxY());
        int secThickness = Math.max(1, ComponentParamParsers.intParam(
                secondary.params(), 1, "wall_thickness", "wallThickness"));

        Set<Long> omit;
        if (secondaryDomain != null && !secondaryDomain.isEmpty()
                && primaryDomain != null && !primaryDomain.isEmpty()) {
            omit = MassJunctionGeometry.thicknessCollapseOmitCells(
                    secondaryDomain, secondaryOrigin, primaryDomain, primaryOrigin,
                    faceSec, bandMin, bandMax, secThickness);
        } else {
            omit = aabbSharedFaceOmit(secondary, primary, faceSec, bandMin, bandMax, secThickness);
        }
        if (omit.isEmpty()) return false;

        Map<String, Object> params = new HashMap<>();
        if (secondary.params() != null) params.putAll(secondary.params());
        params.put("junction_omit_cells", packList(omit));
        params.put("junction_shared_wall_owner", idOf(primary));
        out.set(secondaryIdx, new Component(
                secondary.componentType(), secondary.slotId(), secondary.relativePosition(),
                secondary.dimensions(), secondary.features(), params));
        // Primary keeps material; stamp peer for occupancy mutual grant on seam.
        Map<String, Object> pParams = new HashMap<>();
        if (primary.params() != null) pParams.putAll(primary.params());
        MassJunctionIntent.stampPeer(pParams, idOf(secondary));
        out.set(primaryIdx, new Component(
                primary.componentType(), primary.slotId(), primary.relativePosition(),
                primary.dimensions(), primary.features(), pParams));
        return true;
    }

    private static boolean stampOpeningOmits(
            List<Component> out,
            int i,
            int j,
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            MassDomainRelation.Query rel,
            MassJunctionIntent intent
    ) {
        String faceA = rel.sharedFace() != null ? rel.sharedFace() : inferFace(ab, bb);
        if (faceA == null) return false;
        int bandMin = Math.max(ab.minY(), bb.minY());
        int bandMax = Math.min(ab.maxY(), bb.maxY());
        int doorW = intent == MassJunctionIntent.OPEN_CONNECTION ? 4 : 2;
        int doorH = intent == MassJunctionIntent.OPEN_CONNECTION ? 4 : 3;
        List<Vec3i> cells = MassJunctionGeometry.openingCells(
                ab, bb, faceA, bandMin, bandMax, doorW, doorH, 1);
        // Also open the opposite face on B.
        List<Vec3i> cellsB = MassJunctionGeometry.openingCells(
                bb, ab, opposite(faceA), bandMin, bandMax, doorW, doorH, 1);
        Set<Long> omitA = new LinkedHashSet<>();
        Set<Long> omitB = new LinkedHashSet<>();
        for (Vec3i c : cells) {
            if (MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                omitA.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
            }
        }
        for (Vec3i c : cellsB) {
            if (MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                omitB.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
            }
        }
        // Floor continuity: ensure slab Y cells across the seam are not dual-walled.
        int floorY = bandMin;
        for (Vec3i c : cells) {
            if (MassBooleanComposition.inPackRange3(c.x(), floorY, c.z())) {
                omitA.add(MassBooleanComposition.pack3(c.x(), floorY, c.z()));
            }
        }
        boolean changed = false;
        if (!omitA.isEmpty()) {
            Map<String, Object> pa = new HashMap<>();
            if (a.params() != null) pa.putAll(a.params());
            mergeOmit(pa, omitA);
            out.set(i, new Component(a.componentType(), a.slotId(), a.relativePosition(),
                    a.dimensions(), a.features(), pa));
            changed = true;
        }
        if (!omitB.isEmpty()) {
            Map<String, Object> pb = new HashMap<>();
            if (b.params() != null) pb.putAll(b.params());
            mergeOmit(pb, omitB);
            out.set(j, new Component(b.componentType(), b.slotId(), b.relativePosition(),
                    b.dimensions(), b.features(), pb));
            changed = true;
        }
        return changed;
    }

    private static Component buildPortal(
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            MassDomainRelation.Query rel,
            MassJunctionIntent intent
    ) {
        String face = rel.sharedFace() != null ? rel.sharedFace() : inferFace(ab, bb);
        if (face == null) return null;
        int bandMin = Math.max(ab.minY(), bb.minY());
        int doorW = intent == MassJunctionIntent.OPEN_CONNECTION ? 4 : 2;
        int doorH = intent == MassJunctionIntent.OPEN_CONNECTION ? 4 : 3;
        List<Vec3i> cells = MassJunctionGeometry.openingCells(
                ab, bb, face, bandMin, Math.min(ab.maxY(), bb.maxY()), doorW, doorH, 1);
        if (cells.isEmpty()) return null;
        Vec3i first = cells.getFirst();
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", idOf(a) + "#junction_portal_" + idOf(b));
        params.put("host_id", idOf(a));
        params.put("target_host_id", idOf(b));
        params.put("clear_passage", true);
        params.put("entrance_type", intent == MassJunctionIntent.OPEN_CONNECTION ? "archway" : "doorway");
        params.put("door_block", "minecraft:air");
        params.put("door_width", doorW);
        params.put("door_height", doorH);
        params.put("facing", face);
        params.put("anchor_mode", "min_corner");
        params.put("compiler_junction_portal", true);
        boolean xAxis = "EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face);
        return new Component(
                "ENTRANCE",
                a.slotId(),
                first,
                new Dimensions(xAxis ? 1 : doorW, xAxis ? doorW : 1, doorH),
                List.of("passage"),
                params
        );
    }

    private static boolean elevationMismatch(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b,
            MassJunctionIntent intent
    ) {
        if (intent != MassJunctionIntent.PASSAGE && intent != MassJunctionIntent.OPEN_CONNECTION) {
            return false;
        }
        // Same ground Y required for horizontal passage without stairs.
        return a.minY() != b.minY();
    }

    private static boolean corridorBlockedByWalls(
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            MassDomainRelation.Query rel
    ) {
        // Narrow corridor: connection span ≤ sum of wall thicknesses → fully sealed.
        int wa = Math.max(1, ComponentParamParsers.intParam(a.params(), 1, "wall_thickness", "wallThickness"));
        int wb = Math.max(1, ComponentParamParsers.intParam(b.params(), 1, "wall_thickness", "wallThickness"));
        String face = rel.sharedFace();
        if (face == null) return false;
        int span;
        if ("EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face)) {
            span = Math.min(ab.maxZ(), bb.maxZ()) - Math.max(ab.minZ(), bb.minZ());
        } else {
            span = Math.min(ab.maxX(), bb.maxX()) - Math.max(ab.minX(), bb.minX());
        }
        // Explicit narrow corridor flag or very thin contact span.
        boolean narrowCue = MassJunctionIntent.truthy(a.params(), "narrow_passage", "narrowPassage")
                || MassJunctionIntent.truthy(b.params(), "narrow_passage", "narrowPassage");
        if (narrowCue && span <= wa + wb) return true;
        // Depth of connection along normal ≤ combined thickness and span very small.
        int depth;
        if ("EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face)) {
            depth = Math.min(ab.maxX(), bb.maxX()) - Math.max(ab.minX(), bb.minX());
            if (depth <= 0) depth = 1; // face contact
        } else {
            depth = Math.min(ab.maxZ(), bb.maxZ()) - Math.max(ab.minZ(), bb.minZ());
            if (depth <= 0) depth = 1;
        }
        return narrowCue && depth <= wa + wb && span <= wa + wb;
    }

    private static DomainPair domainsFor(
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb
    ) {
        MassBooleanComposition.CsgResult da = tryDomain(a);
        MassBooleanComposition.CsgResult db = tryDomain(b);
        Vec3i oa = domainOrigin(a, ab);
        Vec3i ob = domainOrigin(b, bb);
        return new DomainPair(da, oa, db, ob);
    }

    private static MassBooleanComposition.CsgResult tryDomain(Component c) {
        try {
            if (MassBooleanComposition.needsCsg(c)) {
                return MassBooleanComposition.composeFromComponent(c);
            }
            var parts = ResolvedMassPart.resolve(c);
            if (parts.size() >= 2) {
                var nested = parts.subList(1, parts.size());
                return MassBooleanComposition.compose(
                        MassBooleanComposition.partsFromResolved(nested), idOf(c));
            }
            if (c.dimensions() != null) {
                return MassBooleanComposition.compose(List.of(
                        new MassBooleanComposition.CsgPart(
                                CsgOp.UNION,
                                0, 0, 0,
                                c.dimensions().width(),
                                c.dimensions().depth(),
                                c.dimensions().height())), idOf(c));
            }
        } catch (RuntimeException ignored) {
            // Broad-phase AABB only.
        }
        return null;
    }

    private static Vec3i domainOrigin(Component c, ComponentFootprintUtil.Bounds planBounds) {
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(c);
        if (origin != null) return origin;
        if (planBounds != null) {
            return new Vec3i(planBounds.minX(), planBounds.minY(), planBounds.minZ());
        }
        return c.relativePosition() == null ? new Vec3i(0, 0, 0) : c.relativePosition();
    }

    private static Set<Long> aabbSharedFaceOmit(
            Component secondary,
            Component primary,
            String face,
            int bandMin,
            int bandMax,
            int thickness
    ) {
        Set<Long> out = new LinkedHashSet<>();
        var sb = ComponentFootprintUtil.bounds(secondary);
        var pb = ComponentFootprintUtil.bounds(primary);
        if (sb == null || pb == null || face == null) return out;
        int t = Math.max(1, thickness);
        for (int y = bandMin; y < bandMax; y++) {
            if ("EAST".equalsIgnoreCase(face)) {
                for (int z = Math.max(sb.minZ(), pb.minZ()); z < Math.min(sb.maxZ(), pb.maxZ()); z++) {
                    for (int dx = 0; dx < t; dx++) {
                        int x = sb.maxX() - 1 - dx;
                        if (MassBooleanComposition.inPackRange3(x, y, z)) {
                            out.add(MassBooleanComposition.pack3(x, y, z));
                        }
                    }
                }
            } else if ("WEST".equalsIgnoreCase(face)) {
                for (int z = Math.max(sb.minZ(), pb.minZ()); z < Math.min(sb.maxZ(), pb.maxZ()); z++) {
                    for (int dx = 0; dx < t; dx++) {
                        int x = sb.minX() + dx;
                        if (MassBooleanComposition.inPackRange3(x, y, z)) {
                            out.add(MassBooleanComposition.pack3(x, y, z));
                        }
                    }
                }
            } else if ("SOUTH".equalsIgnoreCase(face)) {
                for (int x = Math.max(sb.minX(), pb.minX()); x < Math.min(sb.maxX(), pb.maxX()); x++) {
                    for (int dz = 0; dz < t; dz++) {
                        int z = sb.maxZ() - 1 - dz;
                        if (MassBooleanComposition.inPackRange3(x, y, z)) {
                            out.add(MassBooleanComposition.pack3(x, y, z));
                        }
                    }
                }
            } else if ("NORTH".equalsIgnoreCase(face)) {
                for (int x = Math.max(sb.minX(), pb.minX()); x < Math.min(sb.maxX(), pb.maxX()); x++) {
                    for (int dz = 0; dz < t; dz++) {
                        int z = sb.minZ() + dz;
                        if (MassBooleanComposition.inPackRange3(x, y, z)) {
                            out.add(MassBooleanComposition.pack3(x, y, z));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void mergeOmit(Map<String, Object> params, Set<Long> omit) {
        Set<Long> existing = new LinkedHashSet<>();
        Object raw = params.get("junction_omit_cells");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) existing.add(n.longValue());
                else if (o != null) {
                    try {
                        existing.add(Long.parseLong(String.valueOf(o)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        existing.addAll(omit);
        params.put("junction_omit_cells", packList(existing));
    }

    private static List<Long> packList(Set<Long> keys) {
        return new ArrayList<>(keys);
    }

    private static String opposite(String face) {
        if (face == null) return null;
        return switch (face.toUpperCase(Locale.ROOT)) {
            case "EAST" -> "WEST";
            case "WEST" -> "EAST";
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            default -> null;
        };
    }

    private static String inferFace(
            ComponentFootprintUtil.Bounds a,
            ComponentFootprintUtil.Bounds b
    ) {
        if (a == null || b == null) return null;
        if (a.maxX() == b.minX() || a.maxX() + 0 == b.minX()) return "EAST";
        if (b.maxX() == a.minX()) return "WEST";
        if (a.maxZ() == b.minZ()) return "SOUTH";
        if (b.maxZ() == a.minZ()) return "NORTH";
        // Shallow overlap: face from smaller penetration axis.
        int overlapX = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
        int overlapZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
        if (overlapX > 0 && overlapZ > 0) {
            if (overlapX <= overlapZ) {
                return a.minX() < b.minX() ? "EAST" : "WEST";
            }
            return a.minZ() < b.minZ() ? "SOUTH" : "NORTH";
        }
        return null;
    }

    private static ComponentFootprintUtil.Bounds planLocalBounds(Component c) {
        return ComponentFootprintUtil.bounds(c);
    }

    private static boolean isMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || "TOWER".equals(t) || "KEEP".equals(t);
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
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
        return new Vec3i(0, 0, 0);
    }

    private record DomainPair(
            MassBooleanComposition.CsgResult domainA,
            Vec3i originA,
            MassBooleanComposition.CsgResult domainB,
            Vec3i originB
    ) {}
}
