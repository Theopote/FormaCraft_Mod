package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.proportion.CrownGrammarResolver;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.common.style.StyleIntentResolver;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Satellite-to-MASS realignment for roofs, facades, entrances, foundations, and crowns.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR1). Factory helpers ({@code make*}) remain on the
 * compiler until the inference extract (PR2); this class calls them as package-private bridges.
 */
final class ComponentSatelliteAligner {
    private ComponentSatelliteAligner() {}

    /**
     * LLM 常把 ROOF/FACADE/ENTRANCE 的 relative_position 写成与 MASS 相同的中心坐标；
     * 这些附属组件生成器按 min_corner 解释坐标。在此统一贴回 {@link ComponentPlanCompiler#resolveMassOrigin(Component)}。
     * <p>assembly-primary slot 无 MASS 锚点，跳过以免误对齐。</p>
     */
    static void realignSatellitesToMass(
            List<Component> components,
            LlmPlan plan,
            Map<String, Slot> slotMap,
            Set<String> assemblyPrimarySlots
    ) {
        if (components == null || components.isEmpty()) {
            return;
        }

        Map<String, Component> massBySlot = new HashMap<>();
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if (assemblyPrimarySlots != null && assemblyPrimarySlots.contains(ComponentPlanTypes.slotKey(c))) {
                continue;
            }
            if (ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(c.componentType()))
                    && !com.formacraft.common.generation.component.util.MassComponentKinds.isPlate(c)) {
                massBySlot.putIfAbsent(ComponentPlanTypes.slotKey(c), c);
            }
        }
        if (massBySlot.isEmpty()) {
            return;
        }

        int realigned = 0;
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (c == null) {
                continue;
            }
            String slotKey = ComponentPlanTypes.slotKey(c);
            if (assemblyPrimarySlots != null && assemblyPrimarySlots.contains(slotKey)) {
                continue;
            }
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if (ComponentPlanTypes.isMassType(type) || ComponentPlanCompiler.isResolvedPartRoof(c, components, plan)) {
                continue;
            }
            Object hostId = c.params() == null ? null : c.params().get("host_id");
            Component mass = massBySlot.get(slotKey);
            if (hostId != null) {
                mass = null;
                for (Component candidate : components) {
                    if (candidate == null || !(ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(candidate.componentType()))
                            || "TOWER".equals(ComponentPlanTypes.normalizeType(candidate.componentType()))
                            || "MASS_ANCILLARY".equals(ComponentPlanTypes.normalizeType(candidate.componentType())))
                            || candidate.params() == null
                            || !hostId.equals(candidate.params().get("component_id"))) continue;
                    // Explicit ownership cannot equate different real slot coordinate frames.
                    if (ComponentPlanTypes.slotKey(candidate).equals(slotKey)
                            || (slotMap != null && !slotMap.containsKey(candidate.slotId())
                                && !slotMap.containsKey(c.slotId()))) mass = candidate;
                    break;
                }
            }
            if (mass == null) {
                continue;
            }
            // Several unassigned masses share __global__. Attach to the nearest body,
            // rather than moving every facade and roof onto the first building.
            if (hostId == null && c.relativePosition() != null) {
                double nearest = Double.POSITIVE_INFINITY;
                for (Component candidate : components) {
                    if (!"MASS_MAIN".equals(ComponentPlanTypes.normalizeType(candidate.componentType()))
                            || !ComponentPlanTypes.slotKey(candidate).equals(slotKey)) continue;
                    Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(candidate);
                    if (origin == null || candidate.dimensions() == null) continue;
                    double cx = origin.x() + (candidate.dimensions().width() - 1) / 2.0;
                    double cz = origin.z() + (candidate.dimensions().depth() - 1) / 2.0;
                    double x = c.relativePosition().x(), z = c.relativePosition().z();
                    if (ComponentFootprintUtil.isCornerAnchor(c.params()) && c.dimensions() != null) {
                        x += (c.dimensions().width() - 1) / 2.0;
                        z += (c.dimensions().depth() - 1) / 2.0;
                    }
                    double distance = (cx-x)*(cx-x) + (cz-z)*(cz-z);
                    if (distance < nearest) { nearest = distance; mass = candidate; }
                }
            }

            GlobalConstraints.Facing facing = resolveSlotFacing(plan, slotMap, c.slotId());
            Component aligned = switch (type) {
                case "FACADE_WINDOWS" -> alignFacadeToMass(c, mass, plan, facing);
                case "ENTRANCE" -> ExteriorStairEndpointRepair.preservesAuthoredDoor(c,mass,components)
                        ? ExteriorStairEndpointRepair.preserveDoor(c) : alignEntranceToMass(c, mass, plan, facing);
                case "CROWN", "CUPOLA", "DOME" -> alignCrownToMass(c, mass, plan, components);
                case "FOUNDATION", "TERRACE", "BASE" -> alignFoundationToMass(c, mass);
                case "DECOR_DETAIL", "CHIMNEY" -> alignHostedDetail(c, mass);
                default -> ComponentPlanTypes.isRoofType(type) ? alignRoofToMass(c, mass, plan) : c;
            };
            if (AssemblyCompileDiagnostics.hasGap()) {
                return;
            }
            if (aligned != null && mass.params() != null && mass.params().get("component_id") != null) {
                var params = new HashMap<String, Object>();
                if (aligned.params() != null) params.putAll(aligned.params());
                params.putIfAbsent("host_id", mass.params().get("component_id"));
                params.putIfAbsent("building_id", mass.params().getOrDefault("building_id", mass.params().get("component_id")));
                if (Set.of("ENTRANCE", "FACADE_WINDOWS", "ROOF", "ROOF_STRUCTURE").contains(type) && ComponentPlanTypes.hasComponentRequest(aligned.features())) {
                    params.put("resolved_host_attachment", true);
                }
                if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, mass.params())) params.put("no_complex_decor", true);
                aligned = new Component(aligned.componentType(), aligned.slotId(), aligned.relativePosition(),
                        aligned.dimensions(), aligned.features(), params);
            }
            if (aligned != null && aligned != c) {
                components.set(i, aligned);
                realigned++;
            }
        }
        if (realigned > 0) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: realigned {} satellite component(s) to MASS min_corner", realigned);
        }
    }

    static GlobalConstraints.Facing resolveSlotFacing(LlmPlan plan, Map<String, Slot> slotMap, String slotId) {
        if (slotId != null && slotMap != null) {
            Slot slot = slotMap.get(slotId);
            if (slot != null) {
                // Generators default a real slot without facing to SOUTH, not the global facing.
                return slot.facing() != null ? slot.facing() : GlobalConstraints.Facing.SOUTH;
            }
        }
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            return plan.globalConstraints().facing();
        }
        return GlobalConstraints.Facing.SOUTH;
    }

    private static Component alignHostedDetail(Component detail, Component mass) {
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(mass);
        Dimensions body = mass.dimensions(), dims = detail.dimensions();
        if (origin == null || body == null || dims == null || detail.relativePosition() == null) return detail;
        String identity = ComponentPlanTypes.getParamString(detail.params(), "component_id");
        boolean chimney = "CHIMNEY".equals(ComponentPlanTypes.normalizeType(detail.componentType()))
                || identity != null && identity.toLowerCase(Locale.ROOT).contains("chimney");
        boolean perimeter = dims.width() >= body.width() - 2 && dims.depth() >= body.depth() - 2
                && dims.height() <= 1;
        if (!chimney && !perimeter) return detail;
        var params = new HashMap<String, Object>();
        if (detail.params() != null) params.putAll(detail.params());
        params.put("anchor_mode", "min_corner");
        params.put("resolved_host_attachment", true);
        Vec3i position;
        if (chimney) {
            int x = Math.max(origin.x() + 1, Math.min(origin.x() + body.width() - dims.width() - 1, detail.relativePosition().x()));
            int z = Math.max(origin.z() + 1, Math.min(origin.z() + body.depth() - dims.depth() - 1, detail.relativePosition().z()));
            position = new Vec3i(x, origin.y() + body.height() - 1, z);
        } else {
            // A cornice surrounds the facade; it must not fill the glazing plane.
            dims = new Dimensions(Math.max(dims.width(), body.width() + 2), Math.max(dims.depth(), body.depth() + 2), 1);
            position = new Vec3i(origin.x() - Math.max(0, (dims.width() - body.width()) / 2),
                    origin.y() + body.height() - 1,
                    origin.z() - Math.max(0, (dims.depth() - body.depth()) / 2));
        }
        return new Component(chimney ? "CHIMNEY" : detail.componentType(), detail.slotId(), position,
                dims, detail.features(), params);
    }

    private static Component alignFoundationToMass(Component foundation, Component mass) {
        // A terrace is an authored deck, not buried support beneath the host.
        if ("TERRACE".equalsIgnoreCase(foundation.componentType())) return foundation;
        Vec3i massOrigin = ComponentPlanCompiler.resolveMassOrigin(mass);
        Vec3i fp = foundation.relativePosition();
        Dimensions dims = foundation.dimensions();
        if (massOrigin == null || fp == null || dims == null) return foundation;
        Map<String, Object> params = new HashMap<>();
        if (foundation.params() != null) params.putAll(foundation.params());
        boolean rebased = Boolean.TRUE.equals(params.get("coordinate_frame_rebased"));
        boolean corner = ComponentFootprintUtil.isCornerAnchor(params) && !rebased;
        params.put("anchor_mode", "min_corner");
        // Fill support up to the host floor.
        int bottom = Math.min(fp.y(), massOrigin.y() - 1);
        if (Boolean.TRUE.equals(params.get("terrain_adaptive")) && !params.containsKey("foundation_margin")) {
            return new Component(foundation.componentType(), foundation.slotId(),
                    new Vec3i(massOrigin.x(), bottom, massOrigin.z()),
                    new Dimensions(mass.dimensions().width(), mass.dimensions().depth(), massOrigin.y()-bottom),
                    foundation.features(), params);
        }
        return new Component(foundation.componentType(), foundation.slotId(),
                new Vec3i(corner ? fp.x() : massOrigin.x() - (rebased ? Math.max(0, (dims.width() - mass.dimensions().width()) / 2) : 0),
                        bottom, corner ? fp.z() : massOrigin.z() - (rebased ? Math.max(0, (dims.depth() - mass.dimensions().depth()) / 2) : 0)),
                new Dimensions(dims.width(), dims.depth(), massOrigin.y() - bottom), foundation.features(), params);
    }

    static Component alignRoofToMass(Component llmRoof, Component mass, LlmPlan plan) {
        Component template = ComponentPlanCompiler.makeRoofComponent(plan, mass, mass.slotId());
        if (template == null) {
            return llmRoof;
        }

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmRoof.params() != null) {
            params.putAll(llmRoof.params());
        }
        copyFootprintParams(mass.params(), params);
        params.put("anchor_mode", "min_corner");

        Dimensions templateDims = template.dimensions();
        Dimensions llmDims = llmRoof.dimensions();
        int roofHeight = templateDims != null ? templateDims.height() : 3;
        if (llmDims != null && llmDims.height() > 0) {
            roofHeight = llmDims.height();
        }

        int baseW = templateDims != null ? templateDims.width() : 1;
        int baseD = templateDims != null ? templateDims.depth() : 1;
        if (llmDims != null) {
            int extraW = Math.max(0, llmDims.width() - baseW);
            int extraD = Math.max(0, llmDims.depth() - baseD);
            int bboxOverhang = Math.max(extraW / 2, extraD / 2);
            Integer explicitOverhang = ComponentParamParsers.intOrNull(params,
                    ComponentParamParsers.keysFor("overhang"));
            boolean overhangLocked = com.formacraft.common.generation.design.DesignParameterRegistry
                    .isDesignLocked(plan, params, "overhang");
            boolean overhangExplicit = explicitOverhang != null || overhangLocked
                    || hasRawOverhangKey(params);
            if (overhangExplicit) {
                int existing = explicitOverhang != null ? explicitOverhang
                        : ComponentParamParsers.intParam(params, 0, ComponentParamParsers.keysFor("overhang"));
                // Some model roofs include eaves around the unretreated host.
                // Accept that exact representation without counting style setbacks as eaves.
                if (existing >= 0 && llmDims.width() == mass.dimensions().width() + 2 * existing
                        && llmDims.depth() == mass.dimensions().depth() + 2 * existing) {
                    bboxOverhang = existing;
                }
                if (bboxOverhang > 0 && existing != bboxOverhang) {
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            "E_ROOF_OVERHANG_CONFLICT",
                            "explicit overhang=" + existing + " conflicts with roof bbox overhang=" + bboxOverhang
                                    + " host=" + ComponentPlanTypes.paramIdentity(mass) + " host_size=" + mass.dimensions() + " roof_size=" + llmDims,
                            "components[]",
                            List.of("Align roof dimensions with overhang, or omit overhang to derive from bbox.")));
                    return null;
                }
                // Keep explicit value; do not raise via Math.max(existing, bbox).
            } else if (bboxOverhang > 0) {
                params.put("overhang", bboxOverhang);
            }
        }

        Vec3i roofOrigin = template.relativePosition();
        // A one-layer pavilion body is a deck, not an enclosed four-high house.
        // Its explicitly hosted columns define the roof bearing plane.
        if (mass.dimensions().height() == 1 && mass.params() != null
                && ("plate".equals(mass.params().get("extrude_mode"))
                    || Boolean.FALSE.equals(mass.params().get("hollow")))) {
            int top = roofOrigin.y();
            for (Component support : plan.components()) {
                if (support == null || support.params() == null || support.dimensions() == null
                        || support.relativePosition() == null || !java.util.Objects.equals(support.slotId(), mass.slotId())) continue;
                String host = ComponentPlanTypes.getParamString(support.params(), "host_id", "supports");
                if (ComponentPlanTypes.paramIdentity(mass).equals(host)
                        && "column".equalsIgnoreCase(ComponentPlanTypes.getParamString(support.params(), "role")))
                    top = Math.max(top, support.relativePosition().y() + support.dimensions().height());
            }
            roofOrigin = new Vec3i(roofOrigin.x(), top, roofOrigin.z());
        }
        return ComponentPlanCompiler.applyRoofGrammar(plan, new Component(
                llmRoof.componentType(),
                llmRoof.slotId(),
                roofOrigin,
                new Dimensions(baseW, baseD, roofHeight),
                mergeFeatureLists(template.features(), llmRoof.features()),
                params
        ));
    }

    static Component alignFacadeToMass(
            Component llmFacade,
            Component mass,
            LlmPlan plan,
            GlobalConstraints.Facing facing
    ) {
        Component template = ComponentPlanCompiler.makeFacadeComponent(mass, mass.slotId());
        template = StyleIntentResolver.apply(plan, template);
        template = OpeningGrammarResolver.apply(plan, template);

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmFacade.params() != null) {
            params.putAll(llmFacade.params());
        }
        params.put("anchor_mode", "min_corner");
        // Inherit host wall thickness only when facade did not author its own.
        if (ComponentParamParsers.intOrNull(llmFacade.params(), "wall_thickness", "wallThickness") == null) {
            params.put("wall_thickness",
                    ComponentParamParsers.intParam(mass.params(), 1, "wall_thickness", "wallThickness"));
        }

        Dimensions massDims = mass.dimensions();
        Dimensions llmDims = llmFacade.dimensions();
        Vec3i massOrigin = template.relativePosition();
        if (massDims == null || massOrigin == null) {
            return llmFacade;
        }

        List<String> features = mergeFeatureLists(template.features(), llmFacade.features());
        int openingWidth = ComponentParamParsers.intParam(llmFacade.params(),"window_width","windowWidth");
        int openingHeight = ComponentParamParsers.intParam(llmFacade.params(),"window_height","windowHeight");
        int authoredCount = ComponentParamParsers.intParam(llmFacade.params(),"window_count");
        boolean authoredSingleRow = ComponentParamParsers.intParam(llmFacade.params(),"rows") == 1 || authoredCount > 0;
        if (llmDims != null && llmFacade.relativePosition() != null && openingWidth > 0 && openingHeight > 0
                && (llmDims.width() == 1 || llmDims.depth() == 1)
                && (authoredSingleRow || (llmDims.width() == 1 ? llmDims.depth() : llmDims.width()) <= openingWidth+2)
                && llmDims.height() <= openingHeight+2
                && llmFacade.relativePosition().y()+openingHeight <= massOrigin.y()+massDims.height()
                && isFacadeStripOnHost(llmFacade,massOrigin,massDims)) {
            params.put("localized_window",authoredCount > 0 || (llmDims.width() == 1 ? llmDims.depth() : llmDims.width()) <= openingWidth+2);
            params.put("floor_count",1);
            params.put("floor_height",llmDims.height());
            var pos = llmFacade.relativePosition();
            params.put("localized_face",llmDims.width()==1 && llmDims.depth()>1
                    ? (pos.x() <= massOrigin.x() ? "EAST" : "WEST")
                    : (pos.z() <= massOrigin.z() ? "SOUTH" : "NORTH"));
            return new Component("FACADE_WINDOWS",llmFacade.slotId(),pos,llmDims,features,params);
        }
        // A thin authored strip identifies a wall and often one floor. Do not
        // expand every strip into the same full-building facade.
        if (llmDims != null && llmFacade.relativePosition() != null
                && (llmDims.width() == 1 || llmDims.depth() == 1)
                && isFacadeStripOnHost(llmFacade, massOrigin, massDims)) {
            var position = llmFacade.relativePosition();
            if (!params.containsKey("faces")) {
                String face = llmDims.width() == 1 && llmDims.depth() > 1
                        ? (Math.abs(position.x()-massOrigin.x()) <= Math.abs(position.x()-(massOrigin.x()+massDims.width()-1)) ? "EAST" : "WEST")
                        : (Math.abs(position.z()-massOrigin.z()) <= Math.abs(position.z()-(massOrigin.z()+massDims.depth()-1)) ? "SOUTH" : "NORTH");
                params.put("faces", List.of(face));
            }
            int fh = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
            int fc = ComponentParamParsers.intParam(mass.params(), "floor_count", "floorCount");
            if ("clerestory".equalsIgnoreCase(String.valueOf(params.get("height_level"))) && fh > 0) {
                params.putIfAbsent("sill_offset", Math.max(1, Math.floorMod(position.y()-massOrigin.y(), fh)));
            }
            if (fh > 0 && fc > 0 && llmDims.height() < massDims.height()) {
                int first = Math.max(0, Math.min(fc-1, (position.y()-massOrigin.y())/fh));
                int last = Math.max(first, Math.min(fc-1, (position.y()-massOrigin.y()+llmDims.height()-1)/fh));
                params.putIfAbsent("min_floor", first);
                params.putIfAbsent("max_floor", last);
            }
            var scopedFeatures = new ArrayList<>(features);
            scopedFeatures.add("wrap");
            features = scopedFeatures;
        }
        boolean wrap = hasWrapFeature(features);
        GlobalConstraints.Facing effectiveFacing = facing != null ? facing : GlobalConstraints.Facing.SOUTH;
        if ("front".equalsIgnoreCase(String.valueOf(params.get("wall")))) {
            // Explicit front-wall openings below the roof override a broad gable-wall opt-out.
            params.remove("excluded_window_axis");
        } else if (mass.params() != null && Boolean.FALSE.equals(mass.params().get("gable_windows"))) {
            params.put("excluded_window_axis", massDims.depth() >= massDims.width() ? "z" : "x");
        }

        // Window rows use the resolved host envelope, not stale model dimensions.
        int height = Math.max(2, massDims.height());
        int hostFloorHeight = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
        int hostFloorCount = ComponentParamParsers.intParam(mass.params(), "floor_count", "floorCount");
        if (hostFloorCount <= 0 && openingHeight >= hostFloorHeight && openingHeight < massDims.height()
                && openingWidth > 0 && plan.components() != null) {
            Object hostId = mass.params().get("component_id");
            boolean hasRaisedPlate = plan.components().stream().anyMatch(c -> c.params()!=null && c.relativePosition()!=null
                    && hostId!=null && hostId.equals(c.params().get("host_id"))
                    && "plate".equals(c.params().get("extrude_mode"))
                    && c.relativePosition().y()>massOrigin.y());
            if (!hasRaisedPlate) { hostFloorCount=1; hostFloorHeight=massDims.height(); }
        }
        if (hostFloorHeight > 0) params.put("floor_height", hostFloorHeight);
        if (hostFloorCount > 0) params.put("floor_count", hostFloorCount);
        // A height-only slit hint may exceed the clear wall band of a short
        // storey. Fit that hint; full explicit rectangles and locked dimensions
        // remain authoritative, as do locally authored tall openings above.
        if (openingWidth <= 0 && openingHeight >= hostFloorHeight && hostFloorHeight > 1 && hostFloorCount > 1
                && com.formacraft.common.generation.design.DesignProvenance.canMutateParam(plan,params,"window_height")) {
            params.put("window_height",hostFloorHeight-1);
            AssemblyCompileDiagnostics.addSoftWarning("WINDOW_HEIGHT_FITTED",
                    "窗高提示已适配楼层净空："+openingHeight+" -> "+(hostFloorHeight-1),
                    "FACADE_WINDOWS",ComponentPlanTypes.paramIdentity(llmFacade));
        }

        int width;
        int depth;
        Vec3i origin = massOrigin;
        if (wrap) {
            width = massDims.width();
            depth = massDims.depth();
            if (llmDims != null && llmDims.depth() > 0 && llmDims.depth() != depth) {
                FormacraftMod.LOGGER.debug(
                        "ComponentPlanCompiler: clamped FACADE_WINDOWS depth from {} to mass depth {} (wrap/perimeter)",
                        llmDims.depth(), depth);
            }
        } else {
            OrientedFacade oriented = orientSingleFaceFacade(massOrigin, massDims, effectiveFacing);
            width = oriented.width();
            depth = oriented.depth();
            origin = oriented.origin();
            if (llmDims != null && llmDims.depth() > 1) {
                FormacraftMod.LOGGER.debug(
                        "ComponentPlanCompiler: ignored FACADE_WINDOWS depth {} (single-face facade uses depth=1)",
                        llmDims.depth());
            }
        }

        return new Component(
                "FACADE_WINDOWS",
                llmFacade.slotId(),
                origin,
                new Dimensions(width, depth, height),
                features,
                params
        );
    }

    private record OrientedFacade(int width, int depth, Vec3i origin) {}

    private static OrientedFacade orientSingleFaceFacade(
            Vec3i massOrigin,
            Dimensions massDims,
            GlobalConstraints.Facing facing
    ) {
        int mw = Math.max(1, massDims.width());
        int md = Math.max(1, massDims.depth());
        return switch (facing) {
            case NORTH -> new OrientedFacade(
                    mw, 1,
                    new Vec3i(massOrigin.x(), massOrigin.y(), massOrigin.z() + md - 1));
            case EAST -> new OrientedFacade(1, md, massOrigin);
            case WEST -> new OrientedFacade(
                    1, md,
                    new Vec3i(massOrigin.x() + mw - 1, massOrigin.y(), massOrigin.z()));
            case SOUTH -> new OrientedFacade(mw, 1, massOrigin);
        };
    }

    private static boolean isFacadeStripOnHost(Component facade, Vec3i origin, Dimensions dims) {
        var p = facade.relativePosition();
        if (facade.dimensions().width() == 1 && facade.dimensions().depth() > 1)
            return Math.min(Math.abs(p.x()-origin.x()), Math.abs(p.x()-(origin.x()+dims.width()-1))) <= 1;
        return Math.min(Math.abs(p.z()-origin.z()), Math.abs(p.z()-(origin.z()+dims.depth()-1))) <= 1;
    }

    private static boolean hasWrapFeature(List<String> features) {
        if (features == null || features.isEmpty()) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if ("wrap".equals(lower) || lower.contains("all_sides") || lower.contains("perimeter")
                    || lower.contains("around")) {
                return true;
            }
        }
        return false;
    }

    static Component alignEntranceToMass(Component llmEntrance, Component mass, LlmPlan plan,
                                                 GlobalConstraints.Facing facing) {
        if (llmEntrance.params() != null && (llmEntrance.params().containsKey("stair_connection")
                || llmEntrance.params().containsKey("facing"))) {
            return alignStairEntrance(llmEntrance, mass, plan);
        }
        Component template = ComponentPlanCompiler.makeEntranceComponent(plan, mass, mass.slotId(), facing);
        if (template == null) {
            return llmEntrance;
        }
        if (template.params().get("facing") != null)
            facing = GlobalConstraints.Facing.valueOf(String.valueOf(template.params().get("facing")));

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmEntrance.params() != null) {
            params.putAll(llmEntrance.params());
        }

        int floorHeight = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
        int authoredDoorHeight = ComponentParamParsers.intParam(params, 2, "door_height", "doorHeight");
        params.put("door_height", Math.max(2, Math.min(authoredDoorHeight, floorHeight > 0 ? floorHeight - 1 : 3)));
        return new Component(
                "ENTRANCE",
                llmEntrance.slotId(),
                template.relativePosition(),
                entranceDimensions(template.dimensions(), params, facing),
                mergeFeatureLists(template.features(), llmEntrance.features()),
                params
        );
    }

    /** Preserve a flight's actual entrance, including upper-floor Y and wall side. */
    static Component alignStairEntrance(Component door, Component mass, LlmPlan plan) {
        var params = new HashMap<String,Object>(door.params());
        var origin = ComponentPlanCompiler.resolveMassOrigin(mass);
        var rp = door.relativePosition();
        var dims = door.dimensions();
        if (origin == null || rp == null || dims == null) return door;
        String face = String.valueOf(params.getOrDefault("facing", "SOUTH")).toUpperCase(Locale.ROOT);
        if (!params.containsKey("facing")) {
            face = dims.width()==1 && dims.depth()>1
                    ? (Math.abs(rp.x()-origin.x()) <= Math.abs(rp.x()-(origin.x()+mass.dimensions().width()-1)) ? "EAST" : "WEST")
                    : (Math.abs(rp.z()-origin.z()) <= Math.abs(rp.z()-(origin.z()+mass.dimensions().depth()-1)) ? "SOUTH" : "NORTH");
        }
        if (Boolean.TRUE.equals(params.remove("entrance_world_facing"))) {
            // Legacy facade names point inward; portal contracts use world outward normals.
            face = switch(face) { case "EAST" -> "WEST"; case "WEST" -> "EAST";
                case "NORTH" -> "SOUTH"; default -> "NORTH"; };
        }
        params.put("facing",face);
        boolean side = Set.of("EAST", "WEST").contains(face);
        int width = ComponentParamParsers.intParam(params, 3, "door_width");
        int height = ComponentParamParsers.intParam(params, 3, "door_height");
        int thickness = Math.max(1, ComponentParamParsers.intParam(mass.params(),1,"wall_thickness"));
        int center = side ? rp.z()+dims.depth()/2 : rp.x()+dims.width()/2;
        Object intent = plan==null || plan.proportionHints()==null ? null : plan.proportionHints().get("architecture_intent");
        if (!params.containsKey("stair_connection") && intent instanceof Map<?,?> observation
                && String.valueOf(observation.get("original_text")).contains("中央入口"))
            center = side ? origin.z()+mass.dimensions().depth()/2 : origin.x()+mass.dimensions().width()/2;
        int span = side ? mass.dimensions().depth() : mass.dimensions().width();
        int base = side ? origin.z() : origin.x();
        int bay = Math.min(width+2,span);
        // A full opening and its jambs must fit the chosen wall, never spill around a corner.
        int start = Math.max(base,Math.min(base+span-bay,center-bay/2));
        int x = side ? ("EAST".equals(face) ? origin.x() : origin.x()+mass.dimensions().width()-thickness) : start;
        int z = side ? start : ("SOUTH".equals(face) ? origin.z() : origin.z()+mass.dimensions().depth()-thickness);
        params.put("anchor_mode","min_corner");
        params.put("canopy_depth",0);
        int openingY = rp.y() + (Boolean.TRUE.equals(params.get("entrance_at_deck_level")) ? 1 : 0);
        return new Component("ENTRANCE",door.slotId(),new Vec3i(x,openingY,z),
                new Dimensions(side ? thickness : bay,side ? bay : thickness,height+1),
                door.features(),params);
    }

    private static Dimensions entranceDimensions(Dimensions dims, Map<String, Object> params, GlobalConstraints.Facing facing) {
        int width = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
        if (width <= 0 || dims == null) return dims;
        return facing == GlobalConstraints.Facing.EAST || facing == GlobalConstraints.Facing.WEST
                ? new Dimensions(dims.width(), Math.max(dims.depth(), width+1), dims.height())
                : new Dimensions(Math.max(dims.width(), width+1), dims.depth(), dims.height());
    }

    static void copyFootprintParams(Map<String, Object> from, Map<String, Object> to) {
        if (from == null || to == null) {
            return;
        }
        for (String key : List.of(
                "plan_type", "planType",
                "shape", "footprint_shape", "footprintShape",
                "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size",
                "l_corner", "lCorner", "l_cut", "lCut",
                "arm_width", "cross_arm", "cross_arm_width", "armWidth",
                "courtyard_ratio", "courtyardRatio", "court_ratio", "void_ratio", "voidRatio",
                "wall_thickness", "wallThickness",
                "corner_radius", "cornerRadius"
        )) {
            if (from.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }

    private static boolean hasRawOverhangKey(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        for (String key : ComponentParamParsers.keysFor("overhang")) {
            if (key != null && params.containsKey(key) && params.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    static List<String> mergeFeatureLists(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            for (String feature : secondary) {
                if (feature != null && !merged.contains(feature)) {
                    merged.add(feature);
                }
            }
        }
        return merged;
    }

    private static Component alignCrownToMass(Component llmCrown, Component mass, LlmPlan plan, List<Component> all) {
        Component roof = ComponentPlanCompiler.findRoofForSlot(all, List.of(), ComponentPlanTypes.slotKey(mass));
        if (roof == null) {
            roof = ComponentPlanCompiler.makeRoofComponent(plan, mass, mass.slotId());
        }
        Component template = ComponentPlanCompiler.makeCrownComponent(plan, mass, roof, mass.slotId());
        if (template == null) {
            return CrownGrammarResolver.apply(plan, llmCrown);
        }

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmCrown.params() != null) {
            params.putAll(llmCrown.params());
        }

        Dimensions templateDims = template.dimensions();
        Dimensions llmDims = llmCrown.dimensions();
        int crownHeight = templateDims != null ? templateDims.height() : 6;
        int crownWidth = templateDims != null ? templateDims.width() : 5;
        if (llmDims != null) {
            if (llmDims.height() > 0) {
                crownHeight = llmDims.height();
            }
            if (llmDims.width() > 0) {
                crownWidth = llmDims.width();
            }
        }

        Component aligned = new Component(
                "CROWN",
                llmCrown.slotId(),
                template.relativePosition(),
                new Dimensions(crownWidth, crownWidth, crownHeight),
                mergeFeatureLists(template.features(), llmCrown.features()),
                params
        );
        return CrownGrammarResolver.apply(plan, aligned);
    }

}
