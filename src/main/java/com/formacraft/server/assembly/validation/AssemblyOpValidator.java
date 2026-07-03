package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Dispatches per-op validation for assembly ops lists. */
final class AssemblyOpValidator {
    private AssemblyOpValidator() {}

    static void validateOps(List<AssemblyValidationIssue> out, String path, List<?> ops) {
        for (int i = 0; i < ops.size(); i++) {
            Object it = ops.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(AssemblyValidationSupport.err(p, "E_OP_NOT_OBJECT", "op 必须是对象（map）"));
                continue;
            }
            AssemblyValidationSupport.warnUnknownKeys(out, p, m, Set.of(
                    "op",
                    "at", "x", "y", "z", "dx", "dy", "dz",
                    // common bounds
                    "x0", "x1", "y0", "y1", "z0", "z1",
                    // materials
                    "material", "wall", "roof", "slab", "floor", "window", "fill", "frame", "accent", "air",
                    // truss
                    "chord", "web", "joint", "pattern", "module", "step",
                    // arch rib
                    "from", "to", "rise", "sagitta", "samples", "steps",
                    // buttress
                    "rib", "pier", "pierDown", "pier_down",
                    // cable
                    "sag", "droop",
                    "hangersEvery", "hangerEvery",
                    "hangersToY", "hangerToY",
                    "hangersMaterial",
                    "cableCount", "count",
                    "cableSpacing", "spacing",
                    "cableAxis",
                    // frame grid 3d
                    "stepX", "stepY", "stepZ",
                    "sx", "sy", "sz",
                    "mode",
                    "diagonal",
                    // stair system
                    "clearHeight", "clear_h",
                    "carve",
                    "support",
                    "stairs",
                    "supportMaterial",
                    // anchoring / anchorage
                    "yBase",
                    "maxDepth", "anchorDepth",
                    "stopOnSolid",
                    "allowWaterEdit", "allowLavaEdit",
                    "solid",
                    // anchorage detailing
                    "topBevel", "top_bevel", "bevel",
                    "guardWallHeight", "guard_wall_height", "parapetHeight",
                    "guardWallInset", "guard_wall_inset",
                    "guardWallCrenels", "guard_wall_crenels", "crenels",
                    "guardWallMaterial", "guardWall",
                    "holes", "cableHoles",
                    // generic knobs
                    "type", "kind", "face", "faces",
                    "w", "d", "h", "width", "depth", "height",
                    // spline
                    "points", "profile", "profileFrame", "profileSnap", "snap",
                    "profileW", "profileH", "profilePoints", "profileRings", "rings",
                    "profileScale0", "profileScale1", "scale0", "scale1",
                    "profileW0", "profileW1", "profileH0", "profileH1",
                    "twistTurns", "twistPhase",
                    "capEnds", "capThickness", "carveInterior",
                    "connectSamples", "connectMaxStep",
                    // bezier surface
                    "uSamples", "vSamples", "u", "v",
                    // loft / revolve
                    "sections", "segments", "angleDeg", "angle",
                    // bezier surface set
                    "patches", "grid", "topology", "stitch",
                    "stitchEpsilon", "stitch_eps",
                    "stitchSamples", "stitch_samples",
                    "stitchResampleMode", "stitch_resample_mode",
                    "capWidth", "cap_width",
                    "capMaterial", "cap_material",
                    // surface offset / implicit / marching
                    "source", "offset", "distance", "shellThickness", "shell_thickness",
                    "normalMode", "normal_mode",
                    "stepLen", "step_len",
                    "dedupe", "deDupe",
                    "connect_samples",
                    "connect_max_step",
                    "field", "center", "cx", "cy", "cz", "r", "radius", "R", "majorR", "r2", "minorR",
                    "metaballs", "iso", "band",
                    "r0", "r1", "radius0", "radius1",
                    "hollow", "thickness", "samplesPerBlock",
                    // openings
                    "rows", "cols", "winW", "winH", "sillY", "marginX", "marginY", "gapX", "gapY", "frameThickness", "mullionStep",
                    "doorW", "doorH",
                    "archType", "arch", "archThickness", "archT",
                    "keystone", "keystoneOn",
                    "tracery", "traceryType", "traceryMaterial", "traceryThickness", "traceryT", "traceryY", "traceryInset",
                    "foilRadius", "foilCenterY", "foilCount", "foilStepY", "foilGapY",
                    "traceryFoilRadius", "traceryFoilCenterY", "traceryFoilCount", "traceryFoilStepY",
                    "centerY", "petals", "spokes", "ring", "phase", "phi", "spokeWidth", "spokeW", "spokeThreshold", "spokeThresh",
                    "innerFill", "spokeMaterial",
                    // facade grid
                    "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
                    "mullionThickness", "mullionT", "transomThickness", "transomT", "borderThickness", "borderT",
                    "marginU", "inset",
                    "spandrelEvery", "spEvery", "spandrelHeight", "spH", "spandrelOffset", "spOffset", "spandrelFill",
                    // surface bands
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
            String op = AssemblyValidationSupport.str(m.get("op"), "").trim().toUpperCase(Locale.ROOT);
            if (op.isBlank()) out.add(AssemblyValidationSupport.err(p + ".op", "E_OP_MISSING", "缺少 op 字段"));

            // Common structural ops
            AssemblyOpStructuralValidator.validate(op, out, p, m);
            AssemblyOpSurfaceValidator.validate(op, out, p, m);
            AssemblyOpRouteValidator.validate(op, out, p, m);
            AssemblyOpVolumeValidator.validate(op, out, p, m);
            AssemblyOpFacadeValidator.validate(op, out, p, m);
        }
    }
}
