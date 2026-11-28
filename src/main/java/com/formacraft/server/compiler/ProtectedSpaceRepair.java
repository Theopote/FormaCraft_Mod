package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.ProtectedVoidIndex;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.generation.component.util.SpatialDomainQuery;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
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
 * Resolve compact {@code protected_spaces} / compat {@code protected_voids} into plan-space
 * PROTECT stamps after geometry freeze + junction (Phase 02.3.4 Tasks C–D).
 * <p>
 * Classification: HARD_CONSTRAINT execution of USER/AI spatial use — not DESIGN_OVERRIDE.
 */
final class ProtectedSpaceRepair {
    private ProtectedSpaceRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null) return plan;
        boolean ownScope = !ProtectedVoidIndex.isActive();
        try (ProtectedVoidIndex.Scope scope = ownScope ? ProtectedVoidIndex.open() : null) {
            return applyWithIndex(plan);
        }
    }

    private static LlmPlan applyWithIndex(LlmPlan plan) {
        List<Component> components = plan.components() == null ? List.of() : plan.components();
        List<Map<String, Object>> descriptors = new ArrayList<>();
        collectPlanMeta(plan, descriptors);
        for (Component c : components) {
            collectComponent(c, descriptors);
        }
        if (descriptors.isEmpty()) {
            // Still fold CSG carve voids into host protect stamps for atrium carve paths.
            return stampCsgCarveProtect(plan, components);
        }

        List<Component> out = new ArrayList<>(components);
        Map<String, Integer> byId = indexById(out);
        boolean changed = false;
        int idx = 0;
        for (Map<String, Object> desc : descriptors) {
            idx++;
            String id = str(desc.get("id"));
            if (id.isEmpty()) id = "void_" + idx;
            String hostId = str(desc.get("host_id"));
            if (hostId.isEmpty()) hostId = str(desc.get("hostId"));
            Component host = hostId.isEmpty() ? null : find(out, hostId);
            if (host == null && !hostId.isEmpty()) {
                CapabilityGap gap = new CapabilityGap(
                        "E_PROTECTED_SPACE_HOST_MISSING",
                        "protected_space " + id + " host_id=" + hostId + " not found",
                        "plan_meta.protected_spaces|components[].params.protected_spaces",
                        List.of("Point host_id at an existing MASS component_id")
                );
                CompileCapabilityDiagnostics.set(gap);
                AssemblyCompileDiagnostics.set(gap);
                return plan;
            }
            if (host == null) {
                host = firstMass(out);
            }
            if (host == null) continue;

            ProtectedVoidIndex.Kind kind = ProtectedVoidIndex.parseKind(str(desc.get("kind")));
            ProtectedVoidIndex.Policy policy = ProtectedVoidIndex.parsePolicy(str(desc.get("policy")));
            Set<Long> planCells = resolveCells(plan, host, desc, kind);
            if (planCells.isEmpty()) {
                CapabilityGap gap = new CapabilityGap(
                        "E_PROTECTED_SPACE_EMPTY",
                        "protected_space " + id + " resolved to zero plan-space cells",
                        "plan_meta.protected_spaces",
                        List.of("Provide box / footprint_ref / y-band / cells", "Or omit the descriptor")
                );
                CompileCapabilityDiagnostics.set(gap);
                AssemblyCompileDiagnostics.set(gap);
                return plan;
            }

            Set<String> allowRoles = stringSet(desc.get("allow_roles"));
            if (allowRoles.isEmpty()) allowRoles = stringSet(desc.get("allowRoles"));
            Set<String> allowIds = stringSet(desc.get("allow_component_ids"));
            if (allowIds.isEmpty()) allowIds = stringSet(desc.get("allowComponentIds"));
            if (kind == ProtectedVoidIndex.Kind.STAIRWELL && allowRoles.isEmpty()) {
                allowRoles = Set.of("STAIR", "STAIR_SYSTEM", "COLUMN", "RAIL");
            }
            if (kind == ProtectedVoidIndex.Kind.PILOTIS && allowRoles.isEmpty()) {
                allowRoles = Set.of("COLUMN", "PILOTIS", "FOUNDATION");
            }
            if (kind == ProtectedVoidIndex.Kind.COURTYARD && allowRoles.isEmpty()) {
                allowRoles = Set.of("COURTYARD_FLOOR", "LANDSCAPE", "PATH");
            }

            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    id, idOf(host), kind, policy, planCells, allowRoles, allowIds,
                    str(desc.get("provenance")).isEmpty() ? "protected_spaces" : str(desc.get("provenance"))
            ));

            int hi = byId.getOrDefault(idOf(host), -1);
            if (hi >= 0) {
                Component stamped = stampHost(out.get(hi), planCells, kind, policy);
                out.set(hi, stamped);
                changed = true;
            }
            FormacraftMod.LOGGER.info(
                    "ProtectedSpaceRepair: id={} kind={} policy={} cells={} host={}",
                    id, kind, policy, planCells.size(), idOf(host));
        }

        Map<String, Object> meta = plan.planMeta() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(plan.planMeta());
        meta.put("compiler_protected_spaces", ProtectedVoidIndex.toMetaList());
        return changed || !ProtectedVoidIndex.toMetaList().isEmpty()
                ? PlanComponentRewrite.withComponentsAndMeta(plan, out, meta)
                : plan;
    }

    private static LlmPlan stampCsgCarveProtect(LlmPlan plan, List<Component> components) {
        List<Component> out = new ArrayList<>(components);
        boolean changed = false;
        for (int i = 0; i < out.size(); i++) {
            Component c = out.get(i);
            if (!isMass(c)) continue;
            MassBooleanComposition.CsgResult domain = SpatialDomainQuery.resolveDomain(c);
            if (domain == null || domain.carveVoids().isEmpty()) continue;
            Vec3i origin = SpatialDomainQuery.originPlan(c, slotOrigin(plan, c.slotId()));
            Set<Long> planCells = new LinkedHashSet<>();
            for (var v : domain.carveVoids()) {
                int px = origin.x() + v.x();
                int py = origin.y() + v.y();
                int pz = origin.z() + v.z();
                if (MassBooleanComposition.inPackRange3(px, py, pz)) {
                    planCells.add(MassBooleanComposition.pack3(px, py, pz));
                }
            }
            if (planCells.isEmpty()) continue;
            String voidId = idOf(c) + "#csg_carve";
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    voidId, idOf(c), ProtectedVoidIndex.Kind.ATRIUM,
                    ProtectedVoidIndex.Policy.PROTECT, planCells, Set.of(), Set.of(),
                    "csg_carve_voids"));
            out.set(i, stampHost(c, planCells, ProtectedVoidIndex.Kind.ATRIUM,
                    ProtectedVoidIndex.Policy.PROTECT));
            changed = true;
        }
        if (!changed) return plan;
        Map<String, Object> meta = plan.planMeta() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(plan.planMeta());
        meta.put("compiler_protected_spaces", ProtectedVoidIndex.toMetaList());
        return PlanComponentRewrite.withComponentsAndMeta(plan, out, meta);
    }

    private static Component stampHost(
            Component host, Set<Long> planCells,
            ProtectedVoidIndex.Kind kind, ProtectedVoidIndex.Policy policy
    ) {
        Map<String, Object> p = new HashMap<>();
        if (host.params() != null) p.putAll(host.params());
        // Host-local protect keys for MassMain floor/shell probes (= plan − relative when slot=0).
        Vec3i rel = host.relativePosition() == null ? new Vec3i(0, 0, 0) : host.relativePosition();
        Set<Long> hostLocal = new LinkedHashSet<>();
        List<Map<String, Object>> voxelMaps = new ArrayList<>();
        for (long k : planCells) {
            int x = MassBooleanComposition.unpack3X(k) - rel.x();
            int y = MassBooleanComposition.unpack3Y(k) - rel.y();
            int z = MassBooleanComposition.unpack3Z(k) - rel.z();
            if (MassBooleanComposition.inPackRange3(x, y, z)) {
                hostLocal.add(MassBooleanComposition.pack3(x, y, z));
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("x", x);
                cell.put("y", y);
                cell.put("z", z);
                voxelMaps.add(cell);
            }
        }
        // Compat: MassBooleanComposition.protectedVoidKeys reads protected_voids maps.
        Object existing = p.get("protected_voids");
        List<Object> merged = new ArrayList<>();
        if (existing instanceof List<?> list) merged.addAll(list);
        merged.addAll(voxelMaps);
        p.put("protected_voids", merged);
        p.put("compiler_protected_void_cells", new ArrayList<>(hostLocal));
        p.put("compiler_protected_void_kind", kind.name());
        p.put("compiler_protected_void_policy", policy.name());
        return new Component(host.componentType(), host.slotId(), host.relativePosition(),
                host.dimensions(), host.features(), p);
    }

    private static Set<Long> resolveCells(
            LlmPlan plan, Component host, Map<String, Object> desc, ProtectedVoidIndex.Kind kind
    ) {
        Set<Long> out = new LinkedHashSet<>();
        // Explicit cell list (plan-space or host-local via coord_space).
        Object cellsRaw = desc.get("cells");
        if (cellsRaw == null) cellsRaw = desc.get("voxels");
        String coordSpace = str(desc.get("coord_space")).toLowerCase(Locale.ROOT);
        Vec3i slot = slotOrigin(plan, host.slotId());
        Vec3i origin = SpatialDomainQuery.originPlan(host, slot);
        if (cellsRaw instanceof List<?> list) {
            for (Object item : list) {
                int x, y, z;
                if (item instanceof Map<?, ?> m) {
                    x = intOf(m.get("x"), 0);
                    y = intOf(m.get("y"), 0);
                    z = intOf(m.get("z"), 0);
                } else if (item instanceof Number n) {
                    long k = n.longValue();
                    x = MassBooleanComposition.unpack3X(k);
                    y = MassBooleanComposition.unpack3Y(k);
                    z = MassBooleanComposition.unpack3Z(k);
                } else continue;
                if ("host".equals(coordSpace) || "host_local".equals(coordSpace)) {
                    x += origin.x();
                    y += origin.y();
                    z += origin.z();
                } else if ("slot".equals(coordSpace) || "slot_local".equals(coordSpace)) {
                    x += slot.x();
                    y += slot.y();
                    z += slot.z();
                }
                if (MassBooleanComposition.inPackRange3(x, y, z)) {
                    out.add(MassBooleanComposition.pack3(x, y, z));
                }
            }
            if (!out.isEmpty()) return out;
        }

        // Box geometry.
        Object box = desc.get("box");
        if (box instanceof Map<?, ?> b) {
            int minX = intOf(b.get("min_x"), intOf(b.get("x"), 0));
            int minY = intOf(b.get("min_y"), intOf(b.get("y"), 0));
            int minZ = intOf(b.get("min_z"), intOf(b.get("z"), 0));
            int w = Math.max(1, intOf(b.get("w"), intOf(b.get("width"), 1)));
            int h = Math.max(1, intOf(b.get("h"), intOf(b.get("height"), 1)));
            int d = Math.max(1, intOf(b.get("d"), intOf(b.get("depth"), 1)));
            boolean hostLocal = "host".equals(coordSpace) || "host_local".equals(coordSpace)
                    || Boolean.TRUE.equals(desc.get("host_local"));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < d; z++) {
                        int px = minX + x;
                        int py = minY + y;
                        int pz = minZ + z;
                        if (hostLocal) {
                            px += origin.x();
                            py += origin.y();
                            pz += origin.z();
                        }
                        if (MassBooleanComposition.inPackRange3(px, py, pz)) {
                            out.add(MassBooleanComposition.pack3(px, py, pz));
                        }
                    }
                }
            }
            return out;
        }

        // Footprint hole / interior band.
        String geom = str(desc.get("geometry")).toLowerCase(Locale.ROOT);
        if (geom.contains("footprint") || geom.contains("courtyard") || kind == ProtectedVoidIndex.Kind.COURTYARD
                || kind == ProtectedVoidIndex.Kind.ATRIUM || kind == ProtectedVoidIndex.Kind.DOUBLE_HEIGHT
                || kind == ProtectedVoidIndex.Kind.PILOTIS) {
            int w = host.dimensions() == null ? 0 : host.dimensions().width();
            int d = host.dimensions() == null ? 0 : host.dimensions().depth();
            int height = host.dimensions() == null ? 4 : Math.max(1, host.dimensions().height());
            ResolvedFootprint fp = w > 0 && d > 0 ? ResolvedFootprint.resolve(host, w, d) : null;
            int yMin = intOf(desc.get("y_min"), Integer.MIN_VALUE);
            int yMax = intOf(desc.get("y_max"), Integer.MAX_VALUE);
            if (yMin == Integer.MIN_VALUE || yMax == Integer.MAX_VALUE) {
                int storeyFrom = intOf(desc.get("storey_from"), 0);
                int storeyTo = intOf(desc.get("storey_to"), -1);
                ResolvedFloorLayout floors = ResolvedFloorLayout.from(host, height);
                if (floors != null && !floors.floorYs().isEmpty()) {
                    List<Integer> ys = floors.floorYs();
                    int from = Math.max(0, Math.min(storeyFrom, ys.size() - 1));
                    int to = storeyTo < 0 ? ys.size() - 1 : Math.min(storeyTo, ys.size() - 1);
                    yMin = origin.y() + ys.get(from);
                    int top = to + 1 < ys.size() ? ys.get(to + 1) : height;
                    yMax = origin.y() + top;
                } else {
                    yMin = origin.y() + Math.max(0, storeyFrom);
                    yMax = origin.y() + (storeyTo < 0 ? height : Math.max(storeyFrom + 1, storeyTo + 1));
                }
            }
            int wallT = Math.max(1, intOf(host.params() == null ? null
                    : host.params().get("wall_thickness"), 1));
            int inset = desc.get("inset") == null ? wallT : Math.max(0, intOf(desc.get("inset"), wallT));
            for (int y = yMin; y < yMax; y++) {
                for (int x = inset; x < w - inset; x++) {
                    for (int z = inset; z < d - inset; z++) {
                        if (fp != null && !fp.contains(x, z)) continue;
                        // Courtyard hole: only protect the hole cells when pattern is COURTYARD.
                        if (fp != null && fp.pattern() == ResolvedFootprint.PlanPattern.COURTYARD
                                && !fp.isCourtyardHole(x, z)
                                && kind == ProtectedVoidIndex.Kind.COURTYARD) {
                            continue;
                        }
                        int px = origin.x() + x;
                        int py = y;
                        int pz = origin.z() + z;
                        if (MassBooleanComposition.inPackRange3(px, py, pz)) {
                            out.add(MassBooleanComposition.pack3(px, py, pz));
                        }
                    }
                }
            }
            // DOUBLE_HEIGHT / ATRIUM: drop only upper slabs — cells already span storeys.
            return out;
        }

        // CSG carve voids on host.
        if (geom.contains("csg") || geom.contains("carve")) {
            MassBooleanComposition.CsgResult domain = SpatialDomainQuery.resolveDomain(host);
            if (domain != null) {
                for (var v : domain.carveVoids()) {
                    int px = origin.x() + v.x();
                    int py = origin.y() + v.y();
                    int pz = origin.z() + v.z();
                    if (MassBooleanComposition.inPackRange3(px, py, pz)) {
                        out.add(MassBooleanComposition.pack3(px, py, pz));
                    }
                }
            }
        }
        return out;
    }

    private static void collectPlanMeta(LlmPlan plan, List<Map<String, Object>> out) {
        if (plan.planMeta() == null) return;
        Object raw = plan.planMeta().get("protected_spaces");
        if (raw == null) raw = plan.planMeta().get("protectedSpaces");
        addDescriptors(raw, out);
    }

    private static void collectComponent(Component c, List<Map<String, Object>> out) {
        if (c == null || c.params() == null) return;
        Object raw = c.params().get("protected_spaces");
        if (raw == null) raw = c.params().get("protectedSpaces");
        if (raw != null) {
            addDescriptors(raw, out);
            // Ensure host_id defaults to this component.
            for (int i = out.size() - 1; i >= 0; i--) {
                Map<String, Object> d = out.get(i);
                if (str(d.get("host_id")).isEmpty() && str(d.get("hostId")).isEmpty()) {
                    Map<String, Object> copy = new LinkedHashMap<>(d);
                    copy.put("host_id", idOf(c));
                    out.set(i, copy);
                } else break;
            }
        }
        // Compat per-cell maps without protected_spaces wrapper.
        Object voids = c.params().get("protected_voids");
        if (voids == null) voids = c.params().get("stair_voids");
        if (voids instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first
                && (first.containsKey("x") || first.containsKey("y"))) {
            Map<String, Object> desc = new LinkedHashMap<>();
            desc.put("id", idOf(c) + "#compat_voids");
            desc.put("host_id", idOf(c));
            desc.put("kind", c.params().get("stair_voids") != null ? "STAIRWELL" : "GENERIC_PROTECTED_VOID");
            desc.put("policy", "PROTECT");
            desc.put("coord_space", "host_local");
            desc.put("cells", voids);
            desc.put("provenance", "compat_protected_voids");
            out.add(desc);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addDescriptors(Object raw, List<Map<String, Object>> out) {
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        } else if (raw instanceof Map<?, ?> m) {
            out.add(new LinkedHashMap<>((Map<String, Object>) m));
        }
    }

    private static Map<String, Integer> indexById(List<Component> components) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            String id = idOf(components.get(i));
            if (!id.isEmpty()) out.put(id, i);
        }
        return out;
    }

    private static Component find(List<Component> components, String id) {
        for (Component c : components) {
            if (id.equals(idOf(c))) return c;
        }
        return null;
    }

    private static Component firstMass(List<Component> components) {
        for (Component c : components) {
            if (isMass(c)) return c;
        }
        return null;
    }

    private static boolean isMass(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = c.componentType().toUpperCase(Locale.ROOT);
        return t.startsWith("MASS") || t.contains("TOWER") || t.contains("HALL");
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null || c.params().get("component_id") == null) return "";
        return String.valueOf(c.params().get("component_id")).trim();
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intOf(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Set<String> stringSet(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }
}
