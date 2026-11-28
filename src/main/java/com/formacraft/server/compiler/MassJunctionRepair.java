package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.BuildingGeometryContext;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassDomainRelation;
import com.formacraft.common.generation.component.util.MassJunctionGeometry;
import com.formacraft.common.generation.component.util.MassJunctionIntent;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Multi-mass junction design intent → geometry stamps (Phase 02.3.3 / 02.3.3.1).
 * <p>
 * Coordinate contract: contact / authority cells in <strong>plan-space</strong>;
 * omit keys packed as <strong>host-local</strong> (= plan − domainOriginPlan) for generator probes;
 * portal {@code relativePosition} is <strong>slot-local</strong> (= plan − slotAnchor).
 * Classification: GEOMETRY execution of USER/AI pair-scoped junction intent.
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
        Map<String, DomainCache> domainCache = new HashMap<>();
        List<Map<String, Object>> edgeTable = new ArrayList<>();
        Set<String> portalIds = new LinkedHashSet<>();

        for (int i = 0; i < out.size(); i++) {
            Component a = out.get(i);
            if (!isMass(a)) continue;
            for (int j = i + 1; j < out.size(); j++) {
                Component b = out.get(j);
                if (!isMass(b)) continue;

                MassJunctionIntent.EdgeResult edge = MassJunctionIntent.forEdge(plan, a, b);
                if (edge.conflict()) {
                    CapabilityGap gap = new CapabilityGap(
                            "E_JUNCTION_INTENT_CONFLICT",
                            edge.conflictDetail() == null
                                    ? "conflicting junction intents between " + idOf(a) + " and " + idOf(b)
                                    : edge.conflictDetail(),
                            "components[].params.junction_by_peer|junction_intent",
                            List.of(
                                    "Align mutual junction intents for the pair",
                                    "Use plan_meta.junctions / params.junctions edge list",
                                    "Or clear one side's conflicting intent"
                            )
                    );
                    CompileCapabilityDiagnostics.set(gap);
                    AssemblyCompileDiagnostics.set(gap);
                    return plan;
                }
                MassJunctionIntent intent = edge.intent();
                if (intent == MassJunctionIntent.INDEPENDENT) {
                    continue;
                }

                var ab = planBounds(plan, a);
                var bb = planBounds(plan, b);
                if (ab == null || bb == null) continue;

                DomainPair domains = domainsFor(plan, a, b, ab, bb, domainCache);
                MassDomainRelation.Query rel = MassDomainRelation.classify(
                        ab, bb, domains.domainA(), domains.originPlanA(),
                        domains.domainB(), domains.originPlanB());

                if (intent == MassJunctionIntent.VERTICAL_CONNECTION) {
                    if (rel.relation() != MassDomainRelation.VERTICAL_STACK
                            && rel.relation() != MassDomainRelation.CONTAINMENT
                            && rel.relation() != MassDomainRelation.PARTIAL_INTERSECT) {
                        continue;
                    }
                    if (stampPair(out, i, j, intent, rel, a, b)) changed = true;
                    recordEdge(edgeTable, a, b, intent, rel);
                    continue;
                }

                if (intent == MassJunctionIntent.INTENTIONAL_INTERLOCK) {
                    if (rel.relation() == MassDomainRelation.DISJOINT
                            || rel.relation() == MassDomainRelation.CORNER_CONTACT
                            || rel.relation() == MassDomainRelation.EDGE_CONTACT) {
                        continue;
                    }
                    if (stampPair(out, i, j, intent, rel, a, b)) changed = true;
                    stampAuthorizedCells(out, i, j, a, b, ab, bb, domains, rel, plan);
                    recordEdge(edgeTable, a, b, intent, rel);
                    changed = true;
                    continue;
                }

                if (rel.relation() == MassDomainRelation.CORNER_CONTACT
                        || rel.relation() == MassDomainRelation.EDGE_CONTACT
                        || rel.relation() == MassDomainRelation.DISJOINT) {
                    continue;
                }

                // Deep intersect / containment is not shared-wall collapse territory.
                // Shallow 1-cell seam overlap (typical party wall) still collapses.
                boolean deepSharedSkip = intent == MassJunctionIntent.SHARED_WALL
                        && isDeepIntersectOrContainment(ab, bb, rel, a, b);
                if (deepSharedSkip) {
                    FormacraftMod.LOGGER.info(
                            "MassJunctionRepair: {} ↔ {} shared_wall on {} — diagnostic, no thickness collapse",
                            idOf(a), idOf(b), rel.relation());
                }

                if (intent == MassJunctionIntent.PASSAGE || intent == MassJunctionIntent.OPEN_CONNECTION) {
                    if (elevationMismatch(a, b, intent)) {
                        CapabilityGap gap = new CapabilityGap(
                                "E_JUNCTION_ELEVATION_MISMATCH",
                                "junction " + intent.name().toLowerCase(Locale.ROOT)
                                        + " between " + idOf(a) + " and " + idOf(b)
                                        + " has mismatched floor elevations (no silent stair)",
                                "components[].params.floor_height|ground_clear",
                                List.of(
                                        "Align storey bands / ResolvedFloorLayout.floorYs",
                                        "Use vertical_connection with an explicit stair plan",
                                        "Or change junction_intent to shared_wall"
                                )
                        );
                        CompileCapabilityDiagnostics.set(gap);
                        AssemblyCompileDiagnostics.set(gap);
                        return plan;
                    }
                    if (corridorBlockedByWalls(a, b, ab, bb, rel, domains)) {
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

                if (stampPair(out, i, j, intent, rel, a, b)) {
                    changed = true;
                    a = out.get(i);
                    b = out.get(j);
                }

                if (intent == MassJunctionIntent.SHARED_WALL && !deepSharedSkip) {
                    if (applySharedWallCollapse(out, i, j, a, b, ab, bb, domains, rel, plan)) {
                        changed = true;
                        a = out.get(i);
                        b = out.get(j);
                    }
                }

                if (intent == MassJunctionIntent.OPEN_CONNECTION || intent == MassJunctionIntent.PASSAGE) {
                    OpeningStamp stamp = applyTraversableJunction(
                            out, i, j, a, b, ab, bb, domains, rel, intent, plan);
                    if (stamp.isGap()) {
                        return plan;
                    }
                    if (stamp.changed()) {
                        changed = true;
                        a = out.get(i);
                        b = out.get(j);
                    }
                    if (stamp.portal() != null) {
                        String pid = idOf(stamp.portal());
                        if (portalIds.add(pid)) {
                            portals.add(stamp.portal());
                        }
                    }
                }

                recordEdge(edgeTable, a, b, intent, rel);
                FormacraftMod.LOGGER.info(
                        "MassJunctionRepair: {} ↔ {} intent={} relation={} face={} domainAuth={}",
                        idOf(a), idOf(b), intent, rel.relation(), rel.sharedFace(),
                        rel.domainAuthoritative());
            }
        }

        if (!portals.isEmpty()) {
            out.addAll(portals);
            changed = true;
        }
        if (!edgeTable.isEmpty()) {
            Map<String, Object> meta = plan.planMeta() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(plan.planMeta());
            meta.put("compiler_junction_edges", edgeTable);
            return PlanComponentRewrite.withComponentsAndMeta(plan, out, meta);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /**
     * True when separation must not nudge this pair — junction intent already owns contact cleanup.
     */
    static boolean junctionHandlesPair(LlmPlan plan, Component a, Component b) {
        MassJunctionIntent intent = MassJunctionIntent.forPair(plan, a, b);
        return intent == MassJunctionIntent.SHARED_WALL
                || intent == MassJunctionIntent.OPEN_CONNECTION
                || intent == MassJunctionIntent.PASSAGE
                || intent == MassJunctionIntent.VERTICAL_CONNECTION;
    }

    /** Clear derived omit/portal stamps when mass pose/size mutates after junction freeze. */
    static LlmPlan invalidateDerived(LlmPlan plan) {
        if (plan == null || plan.components() == null) return plan;
        List<Component> out = new ArrayList<>();
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null) {
                out.add(null);
                continue;
            }
            if ("ENTRANCE".equalsIgnoreCase(c.componentType())
                    && c.params() != null
                    && Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))) {
                changed = true;
                continue; // drop stale portal
            }
            if (c.params() == null) {
                out.add(c);
                continue;
            }
            Map<String, Object> p = new HashMap<>(c.params());
            boolean local = false;
            for (String key : List.of(
                    "junction_omit_cells",
                    "compiler_junction_authorized_cells",
                    "junction_carve_cells",
                    "junction_floor_place_cells",
                    "compiler_junction_by_peer",
                    "compiler_junction_shared_face",
                    "junction_shared_wall_owner"
            )) {
                if (p.remove(key) != null) local = true;
            }
            if (local) {
                changed = true;
                out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), p));
            } else {
                out.add(c);
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
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
            MassDomainRelation.Query rel,
            LlmPlan plan
    ) {
        // Without domain authority, do not delete walls from AABB alone.
        if (!rel.domainAuthoritative()
                && (domains.domainA() == null || domains.domainB() == null)) {
            FormacraftMod.LOGGER.debug(
                    "MassJunctionRepair: skip shared-wall omit without domain authority {}↔{}",
                    idOf(a), idOf(b));
        }

        boolean aFirst = idOf(a).compareTo(idOf(b)) <= 0;
        Component primary = aFirst ? a : b;
        Component secondary = aFirst ? b : a;
        int primaryIdx = aFirst ? i : j;
        int secondaryIdx = aFirst ? j : i;
        var primaryDomain = aFirst ? domains.domainA() : domains.domainB();
        var secondaryDomain = aFirst ? domains.domainB() : domains.domainA();
        var primaryOrigin = aFirst ? domains.originPlanA() : domains.originPlanB();
        var secondaryOrigin = aFirst ? domains.originPlanB() : domains.originPlanA();
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

        Set<Long> omitPlan;
        if (secondaryDomain != null && !secondaryDomain.isEmpty()
                && primaryDomain != null && !primaryDomain.isEmpty()) {
            omitPlan = MassJunctionGeometry.thicknessCollapseOmitCells(
                    secondaryDomain, secondaryOrigin, primaryDomain, primaryOrigin,
                    faceSec, bandMin, bandMax, secThickness);
        } else {
            omitPlan = aabbSharedFaceOmitPlan(secondary, primary, faceSec, bandMin, bandMax, secThickness, plan);
        }
        if (omitPlan.isEmpty()) return false;

        Vec3i secSlot = slotOrigin(plan, secondary.slotId());
        // Host-local = plan − slotAnchor (matches MassMainGenerator origin+voxel before slot translate).
        Set<Long> omitHost = toHostLocal(omitPlan, secSlot);

        Map<String, Object> params = new HashMap<>();
        if (secondary.params() != null) params.putAll(secondary.params());
        mergeOmit(params, omitHost);
        params.put("junction_shared_wall_owner", idOf(primary));
        // Authorized seam cells (plan-space) for occupancy.
        params.put("compiler_junction_authorized_cells", packList(omitPlan));
        out.set(secondaryIdx, new Component(
                secondary.componentType(), secondary.slotId(), secondary.relativePosition(),
                secondary.dimensions(), secondary.features(), params));

        Map<String, Object> pParams = new HashMap<>();
        if (primary.params() != null) pParams.putAll(primary.params());
        MassJunctionIntent.stampPeer(pParams, idOf(secondary));
        mergeAuthorized(pParams, omitPlan);
        out.set(primaryIdx, new Component(
                primary.componentType(), primary.slotId(), primary.relativePosition(),
                primary.dimensions(), primary.features(), pParams));
        return true;
    }

    private static OpeningStamp applyTraversableJunction(
            List<Component> out,
            int i,
            int j,
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            DomainPair domains,
            MassDomainRelation.Query rel,
            MassJunctionIntent intent,
            LlmPlan plan
    ) {
        String faceA = rel.sharedFace() != null ? rel.sharedFace() : inferFace(ab, bb);
        if (faceA == null) return OpeningStamp.noop();

        int doorW = intent == MassJunctionIntent.OPEN_CONNECTION
                ? ComponentParamParsers.intParam(a.params(), 4, "door_width", "doorWidth", "opening_width")
                : ComponentParamParsers.intParam(a.params(), 2, "door_width", "doorWidth", "opening_width");
        int doorH = intent == MassJunctionIntent.OPEN_CONNECTION
                ? ComponentParamParsers.intParam(a.params(), 4, "door_height", "doorHeight", "opening_height")
                : ComponentParamParsers.intParam(a.params(), 3, "door_height", "doorHeight", "opening_height");
        int wa = Math.max(1, ComponentParamParsers.intParam(a.params(), 1, "wall_thickness", "wallThickness"));
        int wb = Math.max(1, ComponentParamParsers.intParam(b.params(), 1, "wall_thickness", "wallThickness"));

        int floorY = sharedFloorY(a, b, ab, bb);
        int slab = Math.max(
                ResolvedFloorLayout.from(a, Math.max(1, ab.height())).slabThickness(),
                ResolvedFloorLayout.from(b, Math.max(1, bb.height())).slabThickness());

        List<Vec3i> contact = List.of();
        if (domains.domainA() != null && domains.domainB() != null
                && !domains.domainA().isEmpty() && !domains.domainB().isEmpty()) {
            contact = MassDomainRelation.faceContactCellsA(
                    domains.domainA(), domains.originPlanA(),
                    domains.domainB(), domains.originPlanB(), faceA);
        }
        if (contact.isEmpty()) {
            // Fallback: synthesize contact band from AABB shared face (broad-only, still through-thickness).
            contact = MassJunctionGeometry.openingCells(
                    ab, bb, faceA, Math.max(ab.minY(), bb.minY()), Math.min(ab.maxY(), bb.maxY()),
                    Math.max(doorW, 4), Math.max(doorH, 3), slab);
        }

        MassJunctionGeometry.OpeningResult opening = MassJunctionGeometry.throughThicknessOpening(
                contact, faceA, wa, wb, doorW, doorH, floorY, slab);
        if (opening.insufficientContact()) {
            CapabilityGap gap = new CapabilityGap(
                    "E_JUNCTION_OPENING_INSUFFICIENT_CONTACT",
                    "opening between " + idOf(a) + " and " + idOf(b)
                            + " lacks continuous contact for authored " + doorW + "x" + doorH
                            + " (no silent shrink)",
                    "components[].params.junction_intent",
                    List.of(
                            "Widen shared face / contact region",
                            "Reduce door_width / door_height",
                            "Or use shared_wall without opening"
                    )
            );
            CompileCapabilityDiagnostics.set(gap);
            AssemblyCompileDiagnostics.set(gap);
            return OpeningStamp.gapResult();
        }

        Set<Long> omitHostA = toHostLocal(packSet(opening.omitPlanA()), slotOrigin(plan, a.slotId()));
        Set<Long> omitHostB = toHostLocal(packSet(opening.omitPlanB()), slotOrigin(plan, b.slotId()));
        Set<Long> carvePlan = packSet(opening.carvePlan());
        Set<Long> floorPlan = packSet(opening.floorPlacePlan());
        Set<Long> authPlan = new LinkedHashSet<>(carvePlan);
        authPlan.addAll(floorPlan);

        Map<String, Object> pa = new HashMap<>();
        if (a.params() != null) pa.putAll(a.params());
        mergeOmit(pa, omitHostA);
        pa.put("junction_carve_cells", packList(carvePlan));
        pa.put("junction_floor_place_cells", packList(floorPlan));
        mergeAuthorized(pa, authPlan);
        out.set(i, new Component(a.componentType(), a.slotId(), a.relativePosition(),
                a.dimensions(), a.features(), pa));

        Map<String, Object> pb = new HashMap<>();
        if (b.params() != null) pb.putAll(b.params());
        mergeOmit(pb, omitHostB);
        pb.put("junction_carve_cells", packList(carvePlan));
        pb.put("junction_floor_place_cells", packList(floorPlan));
        mergeAuthorized(pb, authPlan);
        out.set(j, new Component(b.componentType(), b.slotId(), b.relativePosition(),
                b.dimensions(), b.features(), pb));

        Component portal = buildPortal(plan, a, b, opening, intent);
        return new OpeningStamp(true, false, portal);
    }

    private static void stampAuthorizedCells(
            List<Component> out, int i, int j,
            Component a, Component b,
            ComponentFootprintUtil.Bounds ab, ComponentFootprintUtil.Bounds bb,
            DomainPair domains, MassDomainRelation.Query rel, LlmPlan plan
    ) {
        Set<Long> cells = new LinkedHashSet<>();
        if (domains.domainA() != null && domains.domainB() != null
                && !domains.domainA().isEmpty() && !domains.domainB().isEmpty()) {
            String face = rel.sharedFace() != null ? rel.sharedFace() : inferFace(ab, bb);
            if (face != null) {
                for (Vec3i c : MassDomainRelation.faceContactCellsA(
                        domains.domainA(), domains.originPlanA(),
                        domains.domainB(), domains.originPlanB(), face)) {
                    if (MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                        cells.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
                    }
                }
            }
            // Overlap cells also authorized for interlock.
            for (var v : domains.domainA().voxels()) {
                int x = v.x() + domains.originPlanA().x();
                int y = v.y() + domains.originPlanA().y();
                int z = v.z() + domains.originPlanA().z();
                if (domains.domainB().contains(
                        x - domains.originPlanB().x(),
                        y - domains.originPlanB().y(),
                        z - domains.originPlanB().z())
                        && MassBooleanComposition.inPackRange3(x, y, z)) {
                    cells.add(MassBooleanComposition.pack3(x, y, z));
                }
            }
        } else {
            // AABB overlap band as authorized when domains unknown — still cell-scoped at emit.
            int x0 = Math.max(ab.minX(), bb.minX());
            int x1 = Math.min(ab.maxX(), bb.maxX());
            int y0 = Math.max(ab.minY(), bb.minY());
            int y1 = Math.min(ab.maxY(), bb.maxY());
            int z0 = Math.max(ab.minZ(), bb.minZ());
            int z1 = Math.min(ab.maxZ(), bb.maxZ());
            for (int x = x0; x < x1; x++) {
                for (int y = y0; y < y1; y++) {
                    for (int z = z0; z < z1; z++) {
                        if (MassBooleanComposition.inPackRange3(x, y, z)) {
                            cells.add(MassBooleanComposition.pack3(x, y, z));
                        }
                    }
                }
            }
        }
        if (cells.isEmpty()) return;
        Map<String, Object> pa = new HashMap<>();
        if (out.get(i).params() != null) pa.putAll(out.get(i).params());
        mergeAuthorized(pa, cells);
        out.set(i, new Component(a.componentType(), a.slotId(), a.relativePosition(),
                a.dimensions(), a.features(), pa));
        Map<String, Object> pb = new HashMap<>();
        if (out.get(j).params() != null) pb.putAll(out.get(j).params());
        mergeAuthorized(pb, cells);
        out.set(j, new Component(b.componentType(), b.slotId(), b.relativePosition(),
                b.dimensions(), b.features(), pb));
    }

    private static Component buildPortal(
            LlmPlan plan,
            Component a,
            Component b,
            MassJunctionGeometry.OpeningResult opening,
            MassJunctionIntent intent
    ) {
        if (opening == null || opening.portalPlanAnchor() == null) return null;
        Vec3i planCell = opening.portalPlanAnchor();
        Vec3i slot = slotOrigin(plan, a.slotId());
        // Slot-local = plan − slotAnchor (emit translates by slot once).
        Vec3i slotLocal = new Vec3i(
                planCell.x() - slot.x(),
                planCell.y() - slot.y(),
                planCell.z() - slot.z());
        String face = opening.faceATowardB();
        int doorW = opening.doorWidth();
        int doorH = opening.doorHeight();
        Map<String, Object> params = new HashMap<>();
        String portalId = idOf(a) + "#junction_portal_" + idOf(b);
        params.put("component_id", portalId);
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
        params.put("entrance_plan_position", Map.of(
                "x", planCell.x(), "y", planCell.y(), "z", planCell.z()));
        params.put("entrance_world_facing", face);
        boolean xAxis = "EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face);
        return new Component(
                "ENTRANCE",
                a.slotId(),
                slotLocal,
                new Dimensions(xAxis ? 1 : doorW, xAxis ? doorW : 1, doorH),
                List.of("passage"),
                params
        );
    }

    /**
     * Floor elevation continuity via ResolvedFloorLayout.floorYs (+ slab), not AABB minY alone.
     */
    /** True when volumetric overlap is deeper than a party-wall seam (skip thickness collapse). */
    private static boolean isDeepIntersectOrContainment(
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            MassDomainRelation.Query rel,
            Component a,
            Component b
    ) {
        if (rel.relation() == MassDomainRelation.CONTAINMENT) return true;
        if (rel.relation() != MassDomainRelation.PARTIAL_INTERSECT) return false;
        int wa = Math.max(1, ComponentParamParsers.intParam(a.params(), 1, "wall_thickness", "wallThickness"));
        int wb = Math.max(1, ComponentParamParsers.intParam(b.params(), 1, "wall_thickness", "wallThickness"));
        int penX = Math.min(ab.maxX(), bb.maxX()) - Math.max(ab.minX(), bb.minX());
        int penZ = Math.min(ab.maxZ(), bb.maxZ()) - Math.max(ab.minZ(), bb.minZ());
        int seamBudget = Math.max(wa, wb) + 1;
        // Shallow seam: the smaller horizontal penetration is within wall thicknesses.
        return Math.min(penX, penZ) > seamBudget;
    }

    private static boolean elevationMismatch(Component a, Component b, MassJunctionIntent intent) {
        if (intent != MassJunctionIntent.PASSAGE && intent != MassJunctionIntent.OPEN_CONNECTION) {
            return false;
        }
        int ha = a.dimensions() == null ? 1 : Math.max(1, a.dimensions().height());
        int hb = b.dimensions() == null ? 1 : Math.max(1, b.dimensions().height());
        ResolvedFloorLayout la = ResolvedFloorLayout.from(a, ha);
        ResolvedFloorLayout lb = ResolvedFloorLayout.from(b, hb);
        int ya = la.floorYs().isEmpty() ? 0 : la.floorYs().getFirst();
        int yb = lb.floorYs().isEmpty() ? 0 : lb.floorYs().getFirst();
        // Compare host-local storey bottoms; relativePosition.y already folds ground offset.
        int absA = (a.relativePosition() == null ? 0 : a.relativePosition().y()) + ya;
        int absB = (b.relativePosition() == null ? 0 : b.relativePosition().y()) + yb;
        return absA != absB;
    }

    private static int sharedFloorY(
            Component a, Component b,
            ComponentFootprintUtil.Bounds ab, ComponentFootprintUtil.Bounds bb
    ) {
        int ha = a.dimensions() == null ? 1 : Math.max(1, a.dimensions().height());
        ResolvedFloorLayout la = ResolvedFloorLayout.from(a, ha);
        int local = la.floorYs().isEmpty() ? 0 : la.floorYs().getFirst();
        int base = a.relativePosition() == null ? ab.minY() : a.relativePosition().y();
        // Plan-space floor slab bottom for the shared storey.
        return Math.max(ab.minY(), Math.max(bb.minY(), base + local));
    }

    private static boolean corridorBlockedByWalls(
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            MassDomainRelation.Query rel,
            DomainPair domains
    ) {
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
        boolean narrowCue = MassJunctionIntent.truthy(a.params(), "narrow_passage", "narrowPassage")
                || MassJunctionIntent.truthy(b.params(), "narrow_passage", "narrowPassage");
        // Detect sealed corridor without requiring narrow_passage: contact span cannot fit a 1-cell opening.
        if (span <= 0) return true;
        if (narrowCue && span <= wa + wb) return true;
        // Domain-authoritative: zero face-contact cells with positive AABB touch → sealed.
        if (rel.domainAuthoritative() && rel.relation() == MassDomainRelation.FACE_CONTACT
                && rel.contactCellCount() == 0) {
            return true;
        }
        int depth;
        if ("EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face)) {
            depth = Math.min(ab.maxX(), bb.maxX()) - Math.max(ab.minX(), bb.minX());
            if (depth <= 0) depth = 1;
        } else {
            depth = Math.min(ab.maxZ(), bb.maxZ()) - Math.max(ab.minZ(), bb.minZ());
            if (depth <= 0) depth = 1;
        }
        return narrowCue && depth <= wa + wb && span <= wa + wb;
    }

    private static DomainPair domainsFor(
            LlmPlan plan,
            Component a,
            Component b,
            ComponentFootprintUtil.Bounds ab,
            ComponentFootprintUtil.Bounds bb,
            Map<String, DomainCache> cache
    ) {
        DomainCache ca = cachedDomain(plan, a, ab, cache);
        DomainCache cb = cachedDomain(plan, b, bb, cache);
        return new DomainPair(ca.domain(), ca.originPlan(), cb.domain(), cb.originPlan());
    }

    private static DomainCache cachedDomain(
            LlmPlan plan, Component c, ComponentFootprintUtil.Bounds planBounds, Map<String, DomainCache> cache
    ) {
        String id = idOf(c);
        if (!id.isEmpty() && cache.containsKey(id)) return cache.get(id);
        Vec3i originPlan = domainOriginPlan(plan, c, planBounds);
        MassBooleanComposition.CsgResult domain = tryDomain(c);
        DomainCache entry = new DomainCache(domain, originPlan);
        if (!id.isEmpty()) cache.put(id, entry);
        return entry;
    }

    private static MassBooleanComposition.CsgResult tryDomain(Component c) {
        try {
            if (BuildingGeometryContext.isActive()) {
                var entry = BuildingGeometryContext.get(idOf(c));
                if (entry != null && entry.domain() != null) {
                    return entry.domain();
                }
            }
            if (MassBooleanComposition.needsCsg(c)) {
                return MassBooleanComposition.composeFromComponent(c);
            }
            var parts = ResolvedMassPart.resolve(c);
            var solid = ResolvedMassPart.solidParts(parts);
            if (solid.size() >= 1 && parts.size() >= 2) {
                return MassBooleanComposition.compose(
                        MassBooleanComposition.partsFromResolved(solid), idOf(c));
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

    /** Plan-space origin of domain local (0,0,0): slotAnchor + resolveMinCornerOrigin. */
    private static Vec3i domainOriginPlan(LlmPlan plan, Component c, ComponentFootprintUtil.Bounds planBounds) {
        Vec3i slot = slotOrigin(plan, c == null ? null : c.slotId());
        Vec3i minCorner = ComponentFootprintUtil.resolveMinCornerOrigin(c);
        if (minCorner != null) {
            return new Vec3i(slot.x() + minCorner.x(), slot.y() + minCorner.y(), slot.z() + minCorner.z());
        }
        if (planBounds != null) {
            return new Vec3i(planBounds.minX(), planBounds.minY(), planBounds.minZ());
        }
        Vec3i rel = c == null || c.relativePosition() == null ? new Vec3i(0, 0, 0) : c.relativePosition();
        return new Vec3i(slot.x() + rel.x(), slot.y() + rel.y(), slot.z() + rel.z());
    }

    /**
     * Host-local omit key = planCell − slotAnchor.
     * Matches MassMainGenerator {@code pack3(origin + voxel)} before ComponentEmitPass slot translate.
     * (When slot is zero this equals plan-space; domainOriginPlan = slot + minCorner.)
     */
    private static Set<Long> toHostLocal(Set<Long> planKeys, Vec3i slotAnchor) {
        Set<Long> out = new LinkedHashSet<>();
        if (planKeys == null || planKeys.isEmpty()) return out;
        Vec3i o = slotAnchor == null ? new Vec3i(0, 0, 0) : slotAnchor;
        for (long k : planKeys) {
            int x = MassBooleanComposition.unpack3X(k) - o.x();
            int y = MassBooleanComposition.unpack3Y(k) - o.y();
            int z = MassBooleanComposition.unpack3Z(k) - o.z();
            if (MassBooleanComposition.inPackRange3(x, y, z)) {
                out.add(MassBooleanComposition.pack3(x, y, z));
            }
        }
        return out;
    }

    private static Set<Long> aabbSharedFaceOmitPlan(
            Component secondary,
            Component primary,
            String face,
            int bandMin,
            int bandMax,
            int thickness,
            LlmPlan plan
    ) {
        Set<Long> out = new LinkedHashSet<>();
        var sb = planBounds(plan, secondary);
        var pb = planBounds(plan, primary);
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

    /**
     * Host-local omit merge. Param {@code junction_omit_cells} stores host-local pack3 keys
     * matching MassMainGenerator {@code origin + voxel} probes (not plan-space).
     */
    private static void mergeOmit(Map<String, Object> params, Set<Long> omitHostLocal) {
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
        existing.addAll(omitHostLocal);
        params.put("junction_omit_cells", packList(existing));
    }

    private static void mergeAuthorized(Map<String, Object> params, Set<Long> planCells) {
        Set<Long> existing = new LinkedHashSet<>();
        Object raw = params.get("compiler_junction_authorized_cells");
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
        existing.addAll(planCells);
        params.put("compiler_junction_authorized_cells", packList(existing));
    }

    private static Set<Long> packSet(List<Vec3i> cells) {
        Set<Long> out = new LinkedHashSet<>();
        if (cells == null) return out;
        for (Vec3i c : cells) {
            if (c != null && MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                out.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
            }
        }
        return out;
    }

    private static List<Long> packList(Set<Long> keys) {
        return new ArrayList<>(keys);
    }

    private static void recordEdge(
            List<Map<String, Object>> edgeTable,
            Component a, Component b,
            MassJunctionIntent intent,
            MassDomainRelation.Query rel
    ) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", idOf(a));
        edge.put("to", idOf(b));
        edge.put("intent", intent.name().toLowerCase(Locale.ROOT));
        edge.put("relation", rel.relation().name());
        if (rel.sharedFace() != null) edge.put("face", rel.sharedFace());
        edgeTable.add(edge);
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
        if (a.maxX() == b.minX()) return "EAST";
        if (b.maxX() == a.minX()) return "WEST";
        if (a.maxZ() == b.minZ()) return "SOUTH";
        if (b.maxZ() == a.minZ()) return "NORTH";
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
            Vec3i originPlanA,
            MassBooleanComposition.CsgResult domainB,
            Vec3i originPlanB
    ) {}

    private record DomainCache(MassBooleanComposition.CsgResult domain, Vec3i originPlan) {}

    private record OpeningStamp(boolean changed, boolean isGap, Component portal) {
        static OpeningStamp noop() { return new OpeningStamp(false, false, null); }
        static OpeningStamp gapResult() { return new OpeningStamp(false, true, null); }
    }
}
