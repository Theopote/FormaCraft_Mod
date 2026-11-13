package com.formacraft.common.generation.component.impl;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.generation.component.util.StyleRoofGeometry;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.generation.component.util.RoofTypeKind;
import com.formacraft.common.generation.component.util.ComponentRoofSpecialtyDecorator;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RoofGenerator（屋顶生成器）
 * <p>
 * 生成屋顶结构（斜屋顶、平屋顶等）
 */
public class RoofGenerator implements ComponentGenerator {

    private enum DomeProfile { LEGACY, HEMISPHERE, POINTED }

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        if (com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(semantic.source())) return List.of();
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);
        Map<String, Object> params = c.params();

        int coreWidth = Math.max(1, d.width());
        int coreDepth = Math.max(1, d.depth());
        ComponentFootprintMask footprint = ComponentFootprintMask.from(semantic, params, coreWidth, coreDepth);

        int width = coreWidth;
        int depth = coreDepth;
        int height;

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        RoofTypeKind roofType = resolveRoofType(c, semantic, params);
        int domeDrumHeight = roofType.isDome()
                ? domeDetailInteger(params, "dome_drum_height", Integer.MAX_VALUE) : 0;
        Direction saltboxLongSlope = roofType == RoofTypeKind.SALTBOX
                ? roofCardinalDirection(params, "saltbox_long_slope") : null;
        double saltboxRidgeFraction = roofType == RoofTypeKind.SALTBOX ? saltboxRidgeFraction(params) : 1.0 / 3;
        // Prefer explicit roof_height / roof_pitch_degrees over span/3 technical default (cap 8).
        try {
            height = com.formacraft.common.generation.component.util.RoofPitchGeometry.resolveRise(
                    width, depth, d.height(), params);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: " + e.getMessage(), e);
        }
        boolean alongDepthHint = saltboxLongSlope == null ? resolveGableAlongDepth(params, width, depth)
                : saltboxLongSlope.getAxis() == Direction.Axis.X;
        boolean authoredMansardFold = roofType == RoofTypeKind.MANSARD
                && getParamString(params, "mansard_break_run", "mansard_break_rise") != null;
        if (!authoredMansardFold && domeDrumHeight == 0) {
            height = normalizeStairRoofRise(height, width, depth, roofType, params, alongDepthHint);
        }

        int overhang = ComponentParamParsers.intParam(params, ComponentParamParsers.keysFor("overhang"));
        if ((roofType == RoofTypeKind.XUANSHAN || roofType == RoofTypeKind.JUANPENG_XUANSHAN) && overhang <= 0) {
            overhang = 2;
        } else if (roofType == RoofTypeKind.XIESHAN && overhang <= 0) {
            overhang = 1;
        } else if (roofType == RoofTypeKind.HIP && overhang <= 0
                && (isAsianStyleProfile(styleProfile) || hasRoofFeature(c, semantic, "flying_eaves", "flying eaves", "飞檐", "重檐"))) {
            // East-asian hip roofs read incomplete without a one-block eave strip.
            overhang = 1;
        }
        if (hasRoofFeature(c, semantic, "flying_eaves", "flying eaves", "飞檐")) {
            int minOverhang = (roofType == RoofTypeKind.XIESHAN || roofType == RoofTypeKind.XUANSHAN) ? 3 : 2;
            overhang = Math.max(overhang, minOverhang);
        }
        int baseX = rp.x();
        int baseZ = rp.z();
        int appliedOverhang = overhang;
        if (overhang > 0) {
            baseX = rp.x() - overhang;
            baseZ = rp.z() - overhang;
            width = Math.max(1, coreWidth + overhang * 2);
            depth = Math.max(1, coreDepth + overhang * 2);
        }

        var mansardProfile = roofType == RoofTypeKind.MANSARD
                ? ComponentRoofSpecialtyDecorator.resolveMansardProfile(width, depth, height, params) : null;
        boolean profiledGable = roofType.isProfiledGable();
        boolean doubleEave = !profiledGable && isDoubleEave(params, c.features());
        if (doubleEave && (roofType == RoofTypeKind.XIESHAN || roofType == RoofTypeKind.XUANSHAN || roofType == RoofTypeKind.GABLE
                || roofType == RoofTypeKind.DOUBLE_GABLE || roofType == RoofTypeKind.HIP)) {
            generateDoubleEaveRoof(out, semantic, roofType, baseX, rp.y(), baseZ, width, depth, height, palette,
                    footprint, appliedOverhang);
        } else {
            switch (roofType) {
                case GAMBREL, BARREL_VAULT, ONION_DOME, STEEP_THATCH -> out.addAll(
                        com.formacraft.common.generation.component.util.SpecialtyRoofSurfaces.generate(
                                roofType, baseX, rp.y(), baseZ, width, depth, height,
                                resolveFullRoofBlock(semantic, palette), footprint, appliedOverhang, params));
                case JUANPENG, JUANPENG_YINGSHAN, JUANPENG_XUANSHAN, SALTBOX, BUTTERFLY, BELLCAST ->
                    generateProfiledGable(out, roofType, baseX, rp.y(), baseZ,
                        width, depth, height, resolveFullRoofBlock(semantic, palette), footprint, appliedOverhang,
                        alongDepthHint, saltboxLongSlope, saltboxRidgeFraction, params);
                case SHED -> generateShedRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height,
                        palette, footprint, appliedOverhang);
                case SAWTOOTH -> generateSawtoothRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height,
                        palette, footprint, appliedOverhang);
                case GABLE, DOUBLE_GABLE, XUANSHAN -> generateGableRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang, resolveGableAlongDepth(params, width, depth));
                case CROSS_GABLE -> generateCrossGableRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height,
                        resolveFullRoofBlock(semantic, palette), footprint, appliedOverhang, alongDepthHint);
                case HIP -> generateHipRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case XIESHAN -> generateXieshanRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case PYRAMID -> generatePyramidRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case CONE -> generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, false,
                        footprint, appliedOverhang, 0);
                case DOME, HEMISPHERICAL_DOME, POINTED_DOME ->
                    generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, true,
                        footprint, appliedOverhang, domeDrumHeight);
                case MANSARD -> generateMansardRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang, mansardProfile);
                default -> generateFlatRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
            }
        }

        // Specialty hollow shells keep authored full blocks — slope-riser fill is for pitched skins.
        if (!doubleEave && roofType != RoofTypeKind.FLAT && roofType != RoofTypeKind.SAWTOOTH
                && roofType != RoofTypeKind.ONION_DOME && roofType != RoofTypeKind.BARREL_VAULT
                && roofType != RoofTypeKind.STEEP_THATCH) {
            sealSlopeRisers(out);
        }
        List<BlockPatch> roofShell = List.copyOf(out);
        // Dormers are a mansard specialty — never stamp three floating dormers onto every pitched roof.
        boolean wantsDormers = mansardProfile != null
                && ComponentRoofSpecialtyDecorator.shouldApplyDormers(null, params, c);
        Integer mansardDormerCount = wantsDormers
                ? ComponentRoofSpecialtyDecorator.mansardDormerCount(params) : null;
        if (wantsDormers && (Math.max(width, depth) >= 7 || mansardDormerCount != null)) {
            String trim = getBlockForPart(semantic, palette, SemanticPart.WALL_ACCENT);
            String glass = "minecraft:glass_pane";
            String roofBlock = getBlockForPart(semantic, palette, SemanticPart.ROOF);
            ComponentRoofSpecialtyDecorator.emitDormers(
                    out, roofShell, baseX, rp.y(), baseZ, width, depth, mansardProfile,
                    mansardDormerCount, params, trim, glass, roofBlock);
        }

        boolean emphasizeEaves = hasRoofFeature(c, semantic, "dougong", "flying_eaves", "flying eaves", "飞檐", "斗拱", "重檐")
                || (roofType == RoofTypeKind.HIP && appliedOverhang > 0 && isAsianStyleProfile(styleProfile));
        if (emphasizeEaves && roofType != RoofTypeKind.FLAT
                && !com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(null, params)) {
            int eaveOutset = hasRoofFeature(c, semantic, "dougong", "斗拱") ? 2 : 1;
            int eaveY = rp.y() > 0 ? rp.y() - 1 : rp.y();
            addEaveLayer(out, semantic, baseX, eaveY, baseZ, width, depth, palette, eaveOutset, footprint, appliedOverhang);
        }

        if (roofType == RoofTypeKind.JUANPENG_YINGSHAN) {
            // Clip the roof and its trim at the gable walls, keeping the side eaves.
            int endStart = alongDepthHint ? rp.z() : rp.x();
            int endLimit = endStart + (alongDepthHint ? coreDepth : coreWidth);
            out.removeIf(patch -> {
                int along = alongDepthHint ? patch.dz() : patch.dx();
                return along < endStart || along >= endLimit;
            });
        }
        // Record only final occupancy: dormer / specialty REMOVE carves must not leave
        // earlier PLACE cells as required ROOF surfaces (E_SURFACE_GENERATION_INCOMPLETE).
        if (GeneratedSurfaceCapture.isCapturing()) {
            var finalAt = new java.util.HashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
            for (var patch : out) {
                if (patch != null)
                    finalAt.put(new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz()), patch);
            }
            for (var patch : finalAt.values()) {
                if (GeneratedSurfaceCapture.occupied(patch))
                    GeneratedSurfaceCapture.record(patch.dx(), patch.dy(), patch.dz(),
                            GeneratedSurfaceCapture.Role.ROOF);
            }
        }
        if (!doubleEave && (profiledGable || roofType == RoofTypeKind.GABLE || roofType == RoofTypeKind.DOUBLE_GABLE || roofType == RoofTypeKind.XUANSHAN
                || roofType == RoofTypeKind.GAMBREL || roofType == RoofTypeKind.BARREL_VAULT || roofType == RoofTypeKind.STEEP_THATCH)
                && !"false".equalsIgnoreCase(getParamString(params, "gable_walls"))) {
            boolean ridgeAlongDepth = profiledGable ? alongDepthHint : coreDepth >= coreWidth;
            sealRoofWalls(out, roofShell, semantic, footprint, rp, coreWidth, coreDepth, palette,
                    ridgeAlongDepth, roofType == RoofTypeKind.BUTTERFLY || roofType == RoofTypeKind.CROSS_GABLE);
            if (getParamBoolean(params, "horse_head_walls", "horseHeadWalls")
                    && !com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(null, params))
                emitHorseHeadWalls(out, roofShell, semantic, footprint, rp, coreWidth, coreDepth, palette, ridgeAlongDepth);
        }
        return out;
    }

    /**
     * Fill under stair surfaces with full blocks so shallow quantized slopes do not show grooves.
     * Top cell stays as authored (usually stairs); risers use the solid family block.
     */
    private static void sealSlopeRisers(List<BlockPatch> out) {
        var tops = new java.util.HashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
        for (var p : out) if (GeneratedSurfaceCapture.occupied(p)) {
            var key = new net.minecraft.util.math.BlockPos(p.dx(), 0, p.dz());
            var old = tops.get(key);
            if (old == null || p.dy() > old.dy()) tops.put(key, p);
        }
        var additions = new ArrayList<BlockPatch>();
        for (var entry : tops.entrySet()) {
            var top = entry.getValue();
            int bottom = top.dy();
            for (var neighbor : List.of(entry.getKey().east(), entry.getKey().west(), entry.getKey().north(), entry.getKey().south())) {
                var lower = tops.get(neighbor);
                if (lower != null) bottom = Math.min(bottom, lower.dy());
            }
            String fill = slopeFillBlock(top.targetBlock());
            for (int y = bottom; y < top.dy(); y++)
                additions.add(new BlockPatch(BlockPatch.PLACE, top.dx(), y, top.dz(), fill));
        }
        out.addAll(additions);
    }

    static String slopeFillBlock(String surface) {
        if (surface == null || surface.isBlank()) return "minecraft:oak_planks";
        String full = com.formacraft.common.palette.component.MaterialBlockFamily.fullBlock(surface);
        if (full != null) return full;
        String base = surface.split("\\[", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (base.endsWith("_stairs") || base.endsWith("_slab")) {
            return base.replace("_stairs", "_planks").replace("_slab", "_planks");
        }
        return surface.contains(":") ? base : "minecraft:" + base;
    }

    /**
     * Stair-only roofs look right near 45°. Shallower authored rises leave multi-cell flats
     * (e.g. 3 stairs at one Y) and groove artifacts — bump toward half-span rise unless an
     * explicit shallow pitch was requested.
     */
    static int normalizeStairRoofRise(int height, int width, int depth, RoofTypeKind roofType,
                                      Map<String, Object> params, boolean alongDepth) {
        int across = switch (roofType) {
            case HIP, XIESHAN, PYRAMID, CONE, DOME, HEMISPHERICAL_DOME, POINTED_DOME -> Math.min(width, depth);
            // Full-block roof profiles and flats keep their resolved rise.
            case FLAT, SAWTOOTH, SHED, BARREL_VAULT, GAMBREL, ONION_DOME, STEEP_THATCH,
                    JUANPENG, JUANPENG_YINGSHAN, JUANPENG_XUANSHAN, SALTBOX, BUTTERFLY, CROSS_GABLE, BELLCAST -> 0;
            default -> alongDepth ? width : depth;
        };
        if (across <= 0) return Math.max(1, height);
        int half = Math.max(1, (across - 1) / 2);
        int rise = Math.max(1, height) - 1;
        boolean stairs = wantsStairSurface(params);
        if (hasExplicitShallowPitch(params, half) && !stairs) {
            return Math.max(2, height);
        }
        // Stair roofs: promote near-45° so we do not get 3-wide flat stair terraces / grooves.
        // Pure stair surfaces only look right near 45°; shallower angles need full-block fill
        // under stairs (emitSlopeColumn / sealSlopeRisers) AND enough rise to avoid plateaus.
        if (stairs && rise < half * 0.65) {
            return Math.max(height, half + 1);
        }
        // Any pitched roof with almost no rise fails ridge audit and looks flat.
        if (rise < Math.max(2, half / 3)) {
            int bumped = stairs ? half + 1 : Math.max(4, half / 2 + 1);
            return Math.max(height, Math.min(bumped, half + 1));
        }
        // Non-stair shallow pitches (e.g. roof_height=3 on a 12+ span) quantize to a
        // multi-cell ridge plateau that RoofShapeAudit reads as no_ridge_rise when
        // sampling is noisy — bump at least to half/2+1 so the ridge is distinct.
        if (!stairs && rise < half * 0.45 && half >= 4) {
            return Math.max(height, Math.min(half / 2 + 1, half + 1));
        }
        return Math.max(2, height);
    }

    static boolean wantsStairSurface(Map<String, Object> params) {
        if (params == null) return false;
        if (getParamBoolean(params, "prefer_stairs", "preferStairs", "stair_roof")) return true;
        String roof = getParamString(params, "roof_block", "roof_material", "material");
        return roof != null && roof.toLowerCase(java.util.Locale.ROOT).contains("stairs");
    }

    static boolean hasExplicitShallowPitch(Map<String, Object> params, int halfSpan) {
        if (params == null) return false;
        Object pitch = params.get("roof_pitch_degrees");
        if (pitch == null) pitch = params.get("pitch_degrees");
        if (pitch instanceof Number n) {
            return n.doubleValue() > 0 && n.doubleValue() < 40.0;
        }
        // Explicit roof_height that is intentionally shallow and not stairs-forced by pitch.
        return com.formacraft.common.generation.component.util.RoofPitchGeometry.hasExplicitRiseIntent(params)
                && !getParamBoolean(params, "prefer_stairs", "preferStairs", "stair_roof");
    }

    /** Place solid fill under a stair/slab surface cell (shallow slopes). */
    private static void emitSlopeColumn(List<BlockPatch> out, int x, int baseY, int z, int rise,
                                        String surfaceBlock, boolean fillUnder) {
        if (fillUnder && rise > 0) {
            String fill = slopeFillBlock(surfaceBlock);
            for (int y = 0; y < rise; y++) {
                out.add(new BlockPatch(BlockPatch.PLACE, x, baseY + y, z, fill));
            }
        }
        out.add(new BlockPatch(BlockPatch.PLACE, x, baseY + rise, z, surfaceBlock));
    }

    /** Close the two gable ends, or all footprint boundaries for roofs needing a perimeter closure. */
    private void sealRoofWalls(List<BlockPatch> out, List<BlockPatch> roofShell, SemanticComponent semantic,
                                      ComponentFootprintMask footprint, Vec3i origin, int width, int depth, Palette palette,
                                      boolean ridgeAlongDepth, boolean closePerimeter) {
        if (!closePerimeter && !footprint.isFullyRectangular()) {
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "[RoofGableSeal] skipped irregular footprint width={} depth={} component={}",
                    width, depth,
                    semantic.source() != null && semantic.source().params() != null
                            ? semantic.source().params().get("component_id") : "?");
            return; // Irregular bodies need their own boundary model.
        }
        var roofHeights = new java.util.HashMap<net.minecraft.util.math.BlockPos, Integer>();
        for (var patch : roofShell) if (GeneratedSurfaceCapture.occupied(patch)) {
            var column = new net.minecraft.util.math.BlockPos(patch.dx(), 0, patch.dz());
            roofHeights.merge(column, patch.dy(), Math::max);
        }
        String block = getParamString(semantic.source().params(), "wall_block");
        if (block == null) block = getBlockForPart(semantic, palette, SemanticPart.WALL);
        else if (!block.contains(":")) block = "minecraft:" + block;
        int span = ridgeAlongDepth ? width : depth;
        int length = ridgeAlongDepth ? depth : width;
        for (int along = 0; along < length; along++) {
            if (!closePerimeter && along != 0 && along != length - 1) continue;
            for (int across = 0; across < span; across++) {
                int localX = ridgeAlongDepth ? across : along;
                int localZ = ridgeAlongDepth ? along : across;
                if (closePerimeter && (!footprint.contains(localX, localZ)
                        || (footprint.contains(localX - 1, localZ) && footprint.contains(localX + 1, localZ)
                        && footprint.contains(localX, localZ - 1) && footprint.contains(localX, localZ + 1)))) continue;
                int x = origin.x() + localX;
                int z = origin.z() + localZ;
                Integer roofY = roofHeights.get(new net.minecraft.util.math.BlockPos(x, 0, z));
                if (roofY == null) continue;
                for (int y = origin.y(); y < roofY; y++) {
                    out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
                    GeneratedSurfaceCapture.record(x, y, z, GeneratedSurfaceCapture.Role.WALL);
                }
            }
        }
    }

    /** Stepped fire walls remain on the body end planes and leave the attic hollow. */
    private void emitHorseHeadWalls(List<BlockPatch> out, List<BlockPatch> roofShell, SemanticComponent semantic,
                                    ComponentFootprintMask footprint, Vec3i origin, int width, int depth, Palette palette,
                                    boolean alongDepth) {
        if (Math.min(width, depth) < 5) return;
        for (int x=0;x<width;x++) for(int z=0;z<depth;z++) if(!footprint.contains(x,z)) return;
        var heights=new java.util.HashMap<net.minecraft.util.math.BlockPos,Integer>();
        for(var p:roofShell) if(GeneratedSurfaceCapture.occupied(p))
            heights.merge(new net.minecraft.util.math.BlockPos(p.dx(),0,p.dz()),p.dy(),Math::max);
        String wall=getParamString(semantic.source().params(),"wall_block");
        if(wall==null) wall=getBlockForPart(semantic,palette,SemanticPart.WALL);
        else if(!wall.contains(":")) wall="minecraft:"+wall;
        String cap=resolveFullRoofBlock(semantic,palette);
        int span=alongDepth?width:depth, length=alongDepth?depth:width;
        for(int end:new int[]{0,length-1}) for(int across=0;across<span;across++) {
            int x=origin.x()+(alongDepth?across:end), z=origin.z()+(alongDepth?end:across);
            Integer y=heights.get(new net.minecraft.util.math.BlockPos(x,0,z));
            if(y==null) continue;
            // Cap must sit on/above the roof shell so ridge slabs do not outrank the tile coping.
            int stepped=origin.y()+((y-origin.y()+1)/2)*2+1;
            int top=Math.max(y, stepped);
            for(int level=origin.y();level<=top;level++) {
                out.add(new BlockPatch(BlockPatch.PLACE,x,level,z,level==top?cap:wall));
                GeneratedSurfaceCapture.record(x,level,z,GeneratedSurfaceCapture.Role.WALL);
            }
        }
    }

    private static boolean allowsRoofCell(ComponentFootprintMask footprint, int overhang, int localX, int localZ) {
        return allowsRoofCell(footprint, overhang, localX, localZ, 0, 0);
    }

    private static boolean allowsRoofCell(ComponentFootprintMask footprint, int overhang, int localX, int localZ,
                                          int fpOffsetX, int fpOffsetZ) {
        return footprint == null || footprint.shouldPlaceRoof(localX + fpOffsetX, localZ + fpOffsetZ, overhang);
    }

    private void generateShedRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height, Palette palette,
                                  ComponentFootprintMask footprint, int overhang) {
        boolean acrossX = width <= depth;
        int span = acrossX ? width : depth;
        for (int x=0; x<width; x++) for (int z=0; z<depth; z++) {
            if (!allowsRoofCell(footprint, overhang, x-overhang, z-overhang)) continue;
            int axis = acrossX ? x : z;
            int rise = axis * Math.max(0,height-1) / Math.max(1,span-1);
            out.add(new BlockPatch(BlockPatch.PLACE,baseX+x,baseY+rise,baseZ+z,
                    getBlockForPart(semantic,palette,SemanticPart.ROOF)));
        }
    }

    private void generateSawtoothRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                      int width, int depth, int height, Palette palette,
                                      ComponentFootprintMask footprint, int overhang) {
        var params = semantic.source().params();
        Direction glazingFacing = roofCardinalDirection(params, "sawtooth_glazing_facing");
        boolean acrossX = glazingFacing == null ? "x".equalsIgnoreCase(getParamString(params, "sawtooth_axis"))
                : glazingFacing.getAxis() == Direction.Axis.X;
        boolean mirrored = glazingFacing == Direction.NORTH || glazingFacing == Direction.WEST;
        int span = acrossX ? width : depth;
        int count = ComponentParamParsers.intParam(params, 0, "sawtooth_count");
        int bay = Math.max(3, Math.min(span, ComponentParamParsers.intParam(params, 5,
                "sawtooth_bay_depth", "bay_depth")));
        count = Math.max(1, Math.min(span / 2, count > 0 ? count : (span + bay - 1) / bay));
        String glass = getParamString(params, "glazing_block");
        if (glass == null) glass = "minecraft:glass";
        else if (!glass.contains(":")) glass = "minecraft:" + glass;
        // Factory sawtooth decks are authored full cubes (roof_block), not stair skins.
        String roof = resolveFullRoofBlock(semantic, palette);
        String wall = getBlockForPart(semantic, palette, SemanticPart.WALL);
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) continue;
            int axis = acrossX ? x : z;
            // Glazing sits at each bay's low start, exposed toward the rising slope.
            if (mirrored) axis = span - 1 - axis;
            int index = Math.min(count-1, axis * count / span);
            int start = (index * span + count - 1) / count;
            int end = ((index + 1) * span + count - 1) / count - 1;
            int rise = (axis-start) * Math.max(1, height-1) / Math.max(1,end-start);
            out.add(new BlockPatch(BlockPatch.PLACE, baseX+x, baseY+rise, baseZ+z, roof));
            // Close the rising step as well as the vertical north-light return.
            int previous = (axis-start-1) * Math.max(1,height-1) / Math.max(1,end-start);
            if (axis > start) for (int y=Math.max(0,previous);y<rise;y++)
                out.add(new BlockPatch(BlockPatch.PLACE,baseX+x,baseY+y,baseZ+z, roof));
            if (axis == start || axis == span - 1) {
                for (int y = rise+1; y < height; y++)
                    out.add(new BlockPatch(BlockPatch.PLACE, baseX+x, baseY+y, baseZ+z, glass));
            }
            // Seal the two gable-end profiles above the supporting wall.
            if (acrossX ? (z == overhang || z == depth-overhang-1) : (x == overhang || x == width-overhang-1))
                for (int y=0;y<rise;y++)
                    out.add(new BlockPatch(BlockPatch.PLACE,baseX+x,baseY+y,baseZ+z, wall));
        }
    }

    private void generateGableRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                   int width, int depth, int height, Palette palette,
                                   ComponentFootprintMask footprint, int overhang, boolean alongDepth) {
        generateGableRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang, 0, 0,
                alongDepth);
    }

    private void generateGableRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                   int width, int depth, int height, Palette palette,
                                   ComponentFootprintMask footprint, int overhang, int fpOffsetX, int fpOffsetZ) {
        generateGableRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang,
                fpOffsetX, fpOffsetZ, depth >= width);
    }

    private void generateGableRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                   int width, int depth, int height, Palette palette,
                                   ComponentFootprintMask footprint, int overhang, int fpOffsetX, int fpOffsetZ,
                                   boolean alongDepth) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang,
                    fpOffsetX, fpOffsetZ);
            return;
        }
        int across = alongDepth ? width : depth;
        int maxSpan = Math.max(1, (across - 1) / 2);
        boolean fillUnder = wantsStairSurface(semantic.source() != null ? semantic.source().params() : null)
                || (height - 1) < maxSpan * 0.9;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang, fpOffsetX, fpOffsetZ)) {
                    continue;
                }
                int axis = alongDepth ? x : z;
                int edgeDistance = Math.min(axis, across - 1 - axis);
                int rise = (int) Math.round(com.formacraft.common.generation.component.util.StyleRoofGeometry.profile(
                        semantic, edgeDistance / (double) maxSpan) * (height - 1));
                SemanticPart part = (edgeDistance >= maxSpan) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                if (part == SemanticPart.ROOF) {
                    // Minecraft stairs facing = higher half. Point toward the ridge (inward).
                    block = withStairsFacing(block, gableRidgeFacing(alongDepth, x, z, width, depth));
                }
                emitSlopeColumn(out, baseX + x, baseY, baseZ + z, rise, block, fillUnder && part == SemanticPart.ROOF);
            }
        }
    }

    private static void generateCrossGableRoof(List<BlockPatch> out, SemanticComponent semantic,
                                               int baseX, int baseY, int baseZ, int width, int depth, int height,
                                               String fullBlock, ComponentFootprintMask footprint, int overhang,
                                               boolean mainRidgeAlongDepth) {
        int runX = Math.max(1, (width - 1) / 2);
        int runZ = Math.max(1, (depth - 1) / 2);
        int secondaryHeight = crossGableSecondaryHeight(semantic.source().params(), height);
        boolean equalRidges = secondaryHeight == height;
        int maxRiseX = (mainRidgeAlongDepth ? height : secondaryHeight) - 1;
        int maxRiseZ = (mainRidgeAlongDepth ? secondaryHeight : height) - 1;
        int valleyBuffer = Math.max(runX, runZ);
        double[] risesX = sampleRoofProfile(semantic, runX, maxRiseX);
        double[] risesZ = sampleRoofProfile(semantic, runZ, maxRiseZ);
        String stairs = MaterialBlockFamily.stairs(fullBlock);
        String slab = MaterialBlockFamily.slab(fullBlock);
        String lowerSlab = slab == null ? fullBlock : BlockStateStringUtil.withProperty(slab, "type", "bottom");
        for (int x = 0; x < width; x++) {
            int distanceX = Math.min(x, width - 1 - x);
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) continue;
                int distanceZ = Math.min(z, depth - 1 - z);
                long normalizedRunDifference = (long) distanceX * runZ - (long) distanceZ * runX;
                double riseX = risesX[distanceX];
                double riseZ = risesZ[distanceZ];
                boolean slopeX = equalRidges ? normalizedRunDifference >= 0 : riseX >= riseZ;
                boolean valley = equalRidges ? Math.abs(normalizedRunDifference) <= valleyBuffer
                        : crossGableValley(risesX, distanceX, risesZ, distanceZ, slopeX);
                int distance = slopeX ? distanceX : distanceZ;
                double[] profile = slopeX ? risesX : risesZ;
                double rise = slopeX ? riseX : riseZ;
                int blockRise = (int) Math.round(rise);
                String surface = fullBlock;
                int maxRise = slopeX ? maxRiseX : maxRiseZ;
                if (blockRise < maxRise && !valley) {
                    String facing = slopeX ? (x < width / 2 ? "east" : "west")
                            : (z < depth / 2 ? "south" : "north");
                    surface = slopeSurface(fullBlock, lowerSlab, stairs, rise, profile[distance + 1], facing);
                }
                out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, baseY + blockRise, baseZ + z, surface));
            }
        }
    }

    private static boolean crossGableValley(double[] risesX, int x, double[] risesZ, int z, boolean slopeX) {
        // Keep both sides of a face transition solid, including ties between cells.
        if (slopeX) {
            return risesX[Math.max(0, x - 1)] <= risesZ[z]
                    || risesX[x] <= risesZ[Math.min(risesZ.length - 1, z + 1)];
        }
        return risesZ[Math.max(0, z - 1)] <= risesX[x]
                || risesZ[z] <= risesX[Math.min(risesX.length - 1, x + 1)];
    }

    private static int crossGableSecondaryHeight(Map<String, Object> params, int mainHeight) {
        String raw = getParamString(params, "cross_gable_secondary_height");
        if (raw == null) return mainHeight;
        String error = "E_ROOF_GEOMETRY_INVALID: cross_gable_secondary_height must be a whole number "
                + "from 2 through the main roof height";
        try {
            double height = Double.parseDouble(raw);
            if (height >= 2 && height <= mainHeight && height == Math.rint(height)) return (int) height;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        throw new IllegalArgumentException(error);
    }

    private static double[] sampleRoofProfile(SemanticComponent semantic, int run, int maxRise) {
        double[] rises = new double[run + 1];
        for (int distance = 0; distance <= run; distance++) {
            rises[distance] = StyleRoofGeometry.profile(semantic, distance / (double) run) * maxRise;
        }
        return rises;
    }

    private static void generateProfiledGable(List<BlockPatch> out, RoofTypeKind kind, int baseX, int baseY,
                                              int baseZ, int width, int depth, int height, String fullBlock,
                                              ComponentFootprintMask footprint, int overhang, boolean alongDepth,
                                              Direction saltboxLongSlope, double saltboxRidgeFraction, Map<String, Object> params) {
        BellcastProfile bellcast = kind == RoofTypeKind.BELLCAST ? BellcastProfile.resolve(params) : null;
        int span = alongDepth ? width : depth;
        int length = alongDepth ? depth : width;
        int last = span - 1;
        int maxRise = height - 1;
        // Measure from the short eave. Keep unequal runs whenever the span allows it.
        int saltboxRidge = Math.clamp((int) Math.round(last * saltboxRidgeFraction), 1, Math.max(1, (last - 1) / 2));
        int ridgeStart = kind == RoofTypeKind.SALTBOX ? saltboxRidge : last / 2;
        boolean mirrored = saltboxLongSlope == Direction.WEST || saltboxLongSlope == Direction.NORTH;
        String stairs = MaterialBlockFamily.stairs(fullBlock);
        String slab = MaterialBlockFamily.slab(fullBlock);
        String lowerSlab = slab == null ? fullBlock : BlockStateStringUtil.withProperty(slab, "type", "bottom");
        for (int across = 0; across < span; across++) {
            double rise = profiledGableRise(across, last, ridgeStart, kind, maxRise, bellcast);
            int blockRise = (int) Math.round(rise);
            String surface = fullBlock;
            boolean butterfly = kind == RoofTypeKind.BUTTERFLY;
            boolean valley = butterfly && Math.min(across, last - across) == ridgeStart;
            if (span >= 3 && blockRise < maxRise && !valley) {
                int towardPeak = across < ridgeStart ? 1 : -1;
                if (butterfly) towardPeak = -towardPeak;
                double nextRise = profiledGableRise(across + towardPeak, last, ridgeStart, kind, maxRise, bellcast);
                boolean towardPositive = mirrored ? towardPeak < 0 : towardPeak > 0;
                String facing = alongDepth
                        ? (towardPositive ? "east" : "west")
                        : (towardPositive ? "south" : "north");
                surface = slopeSurface(fullBlock, lowerSlab, stairs, rise, nextRise, facing);
            }
            int y = baseY + blockRise;
            int column = mirrored ? last - across : across;
            for (int along = 0; along < length; along++) {
                int x = alongDepth ? column : along;
                int z = alongDepth ? along : column;
                if (allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, y, baseZ + z, surface));
                }
            }
        }
    }

    private static Direction roofCardinalDirection(Map<String, Object> params, String key) {
        String raw = getParamString(params, key);
        if (raw == null) return null;
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException(
                    "E_ROOF_GEOMETRY_INVALID: " + key + " must be north, south, east or west");
        };
    }

    private record BellcastProfile(double run, double rise) {
        double fractionAt(double u) {
            return u <= run ? u * rise / run : rise + (u - run) * (1 - rise) / (1 - run);
        }

        static BellcastProfile resolve(Map<String, Object> params) {
            double run = fraction(params, "bellcast_break_run_fraction", 0.25);
            double rise = fraction(params, "bellcast_break_rise_fraction", 0.1);
            if (rise >= run) throw new IllegalArgumentException(
                    "E_ROOF_GEOMETRY_INVALID: bellcast_break_rise_fraction must be less than bellcast_break_run_fraction");
            return new BellcastProfile(run, rise);
        }

        private static double fraction(Map<String, Object> params, String key, double fallback) {
            String raw = getParamString(params, key);
            if (raw == null) return fallback;
            try {
                double value = Double.parseDouble(raw);
                if (value > 0 && value < 1) return value;
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: " + key
                    + " must be a number between 0 and 1 (exclusive)");
        }
    }

    private static double saltboxRidgeFraction(Map<String, Object> params) {
        String raw = getParamString(params, "saltbox_ridge_fraction");
        if (raw == null) return 1.0 / 3;
        String error = "E_ROOF_GEOMETRY_INVALID: saltbox_ridge_fraction must be a number between 0 and 0.5 (exclusive)";
        try {
            double fraction = Double.parseDouble(raw);
            if (fraction > 0 && fraction < 0.5) return fraction;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        throw new IllegalArgumentException(error);
    }

    private static String slopeSurface(String fullBlock, String lowerSlab, String stairs,
                                       double rise, double nextRise, String facing) {
        double slope = nextRise - rise;
        long step = Math.round(nextRise) - Math.round(rise);
        // Lower slabs and upright stairs share a solid lower half at equal elevations.
        if (slope < 0.75 || step == 0) return lowerSlab;
        if (slope < 1.5 && step == 1 && stairs != null) return withStairsFacing(stairs, facing, "straight");
        return fullBlock;
    }

    private static double profiledGableRise(int across, int last, int ridgeStart, RoofTypeKind kind, int maxRise, BellcastProfile bellcast) {
        if (last < 2) return 0;
        double fraction = switch (kind) {
            case JUANPENG, JUANPENG_YINGSHAN, JUANPENG_XUANSHAN ->
                    Math.sin(Math.PI / 2 * Math.min(across, last - across) / ridgeStart);
            case SALTBOX -> across <= ridgeStart ? across / (double) ridgeStart
                    : (last - across) / (double) (last - ridgeStart);
            case BELLCAST -> bellcast.fractionAt(Math.min(across, last - across) / (double) ridgeStart);
            case BUTTERFLY -> 1 - Math.min(across, last - across) / (double) ridgeStart;
            default -> throw new IllegalArgumentException("Unsupported gable profile: " + kind);
        };
        return fraction * maxRise;
    }

    /** ridge_axis x ⇒ slopes face N/S (ridge along width); z ⇒ slopes face E/W. */
    public static boolean resolveGableAlongDepth(Map<String, Object> params, int width, int depth) {
        String axis = getParamString(params, "ridge_axis", "ridgeAxis");
        if (axis != null) {
            String a = axis.trim().toLowerCase(java.util.Locale.ROOT);
            switch (a) {
                case "x", "east_west", "east-west", "ew" -> {
                    return false; // across = depth → N/S slopes
                }
                case "z", "north_south", "north-south", "ns" -> {
                    return true; // across = width → E/W slopes
                }
                case "long", "long_side" -> {
                    return width >= depth; // ridge along longer plan axis
                }
            }
        }
        return depth >= width;
    }

    private void generateHipRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                 int width, int depth, int height, Palette palette,
                                 ComponentFootprintMask footprint, int overhang) {
        ResolvedFootprint plan = footprint == null ? null : footprint.footprint();
        if (plan != null && plan.shape() == ResolvedFootprint.FootprintShape.RECTANGLE
                && plan.pattern() == ResolvedFootprint.PlanPattern.L_SHAPE && plan.patternSize() > 0
                && Math.min(plan.width(), plan.depth()) > 1) {
            generateLShapedHip(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        generateHipRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang, 0, 0);
    }

    private static void generateLShapedHip(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY,
                                          int baseZ, int width, int depth, int height, Palette palette,
                                          ComponentFootprintMask footprint, int overhang) {
        var plan = footprint.footprint();
        // Both the inner and outer eaves shift by the same overhang, so the
        // missing corner keeps its dimensions in expanded roof coordinates.
        int cut = Math.min(plan.patternSize(), Math.min(plan.width(), plan.depth()) - 1);
        String corner = plan.lCorner() == null ? "NE" : plan.lCorner();
        boolean cutOnEast = corner.endsWith("E");
        boolean cutOnSouth = corner.startsWith("S");
        int maxRun = Math.max(Math.min(width - cut, depth), Math.min(width, depth - cut)) - 1;
        maxRun = Math.max(1, maxRun / 2);
        String fullBlock = resolveFullRoofBlock(semantic, palette);
        String stairs = MaterialBlockFamily.stairs(fullBlock);
        String slab = MaterialBlockFamily.slab(fullBlock);
        String lowerSlab = slab == null ? fullBlock : BlockStateStringUtil.withProperty(slab, "type", "bottom");
        double[] rises = sampleRoofProfile(semantic, maxRun, height - 1);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) continue;
                int fromCornerX = cutOnEast ? width - 1 - x : x;
                int fromCornerZ = cutOnSouth ? depth - 1 - z : z;
                int run = Math.clamp(lHipRun(fromCornerX, fromCornerZ, width, depth, cut), 0, maxRun);
                int rise = (int) Math.round(rises[run]);
                String surface = fullBlock;
                if (run < maxRun && rise < height - 1) {
                    Direction uphill = null;
                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        int nx = fromCornerX + (cutOnEast ? -direction.getOffsetX() : direction.getOffsetX());
                        int nz = fromCornerZ + (cutOnSouth ? -direction.getOffsetZ() : direction.getOffsetZ());
                        if (lHipRun(nx, nz, width, depth, cut) <= run) continue;
                        if (uphill != null) {
                            // Two rising neighbors meet at the valley, which needs a solid join.
                            uphill = null;
                            break;
                        }
                        uphill = direction;
                    }
                    if (uphill != null) {
                        surface = slopeSurface(fullBlock, lowerSlab, stairs, rises[run], rises[run + 1], uphill.asString());
                    }
                }
                out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, baseY + rise, baseZ + z, surface));
            }
        }
    }

    private static int lHipRun(int x, int z, int width, int depth, int cut) {
        int outerRun = Math.min(Math.min(x, width - 1 - x), Math.min(z, depth - 1 - z));
        // The two inner-eave wavefronts meet where their runs to the cutout are equal.
        return Math.min(outerRun, Math.max(x, z) - cut);
    }

    private void generateHipRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                 int width, int depth, int height, Palette palette,
                                 ComponentFootprintMask footprint, int overhang, int fpOffsetX, int fpOffsetZ) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang,
                    fpOffsetX, fpOffsetZ);
            return;
        }
        int maxSpan = Math.max(1, (Math.min(width, depth) - 1) / 2);
        boolean fillUnder = wantsStairSurface(semantic.source() != null ? semantic.source().params() : null)
                || (height - 1) < maxSpan * 0.9;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang, fpOffsetX, fpOffsetZ)) {
                    continue;
                }
                int edgeDistance = Math.min(Math.min(x, width - 1 - x), Math.min(z, depth - 1 - z));
                int rise = (int) Math.round(com.formacraft.common.generation.component.util.StyleRoofGeometry.profile(
                        semantic, edgeDistance / (double) maxSpan) * (height - 1));
                SemanticPart part = (edgeDistance >= maxSpan) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                if (part == SemanticPart.ROOF) {
                    // Higher half toward ridge (inward), opposite of eave outward.
                    String facing = inwardFacingFromEdges(x, z, width, depth);
                    String shape = isPerimeterCorner(x, z, width, depth)
                            ? cornerOuterShape(x, z, width, depth, facing) : null;
                    block = withStairsFacing(block, facing, shape);
                }
                emitSlopeColumn(out, baseX + x, baseY, baseZ + z, rise, block, fillUnder && part == SemanticPart.ROOF);
            }
        }
    }

    private void generateXieshanRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang) {
        generateXieshanRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang, 0, 0);
    }

    private void generateXieshanRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang, int fpOffsetX, int fpOffsetZ) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang,
                    fpOffsetX, fpOffsetZ);
            return;
        }
        boolean ridgeAlongDepth = depth >= width;
        int across = ridgeAlongDepth ? width : depth;
        int spanAcross = Math.max(1, (across - 1) / 2);
        int ridgeInset = Math.max(1, (ridgeAlongDepth ? depth : width) / 4);
        int maxRise = height - 1;

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang, fpOffsetX, fpOffsetZ)) {
                    continue;
                }
                int axis = ridgeAlongDepth ? x : z;
                int edgeDistance = Math.min(axis, across - 1 - axis);
                int distAcross = Math.max(0, spanAcross - edgeDistance);
                int riseAcross = (int) Math.round(com.formacraft.common.generation.component.util.StyleRoofGeometry.profile(
                        semantic, 1.0 - distAcross / (double) spanAcross) * maxRise);
                if (riseAcross < 0) continue;

                int distAlong = ridgeAlongDepth ? Math.min(z, depth - 1 - z) : Math.min(x, width - 1 - x);
                int riseAlong;
                if (distAlong >= ridgeInset) {
                    riseAlong = maxRise;
                } else {
                    riseAlong = (int) Math.round(com.formacraft.common.generation.component.util.StyleRoofGeometry.profile(
                            semantic, distAlong / (double) ridgeInset) * maxRise);
                }

                int rise = Math.min(riseAcross, riseAlong);
                if (rise < 0) continue;
                SemanticPart part = (rise >= maxRise && distAcross == 0 && distAlong >= ridgeInset)
                        ? SemanticPart.ROOF_SURFACE
                        : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                if (part == SemanticPart.ROOF) {
                    // Higher half toward ridge: mid-span like gable; hip ends use inward nearest-edge.
                    String facing = isPerimeterCorner(x, z, width, depth) || distAlong < ridgeInset
                            ? inwardFacingFromEdges(x, z, width, depth)
                            : gableRidgeFacing(ridgeAlongDepth, x, z, width, depth);
                    String shape = isPerimeterCorner(x, z, width, depth)
                            ? cornerOuterShape(x, z, width, depth, facing) : null;
                    block = withStairsFacing(block, facing, shape);
                }
                boolean fillUnder = wantsStairSurface(semantic.source() != null ? semantic.source().params() : null)
                        || maxRise < spanAcross * 0.9;
                emitSlopeColumn(out, baseX + x, baseY, baseZ + z, rise, block, fillUnder && part == SemanticPart.ROOF);
            }
        }
    }

    private void generateDoubleEaveRoof(List<BlockPatch> out, SemanticComponent semantic, RoofTypeKind roofType, int baseX, int baseY,
                                        int baseZ, int width, int depth, int height, Palette palette,
                                        ComponentFootprintMask footprint, int overhang) {
        int upperHeight = Math.max(2, height / 2);
        int upperInset = Math.max(2, Math.min(width, depth) / 4);
        int upperWidth = Math.max(2, width - (upperInset * 2));
        int upperDepth = Math.max(2, depth - (upperInset * 2));
        var lower = com.formacraft.common.generation.component.util.RoofLayerFrame.of(baseX, baseZ, width, depth, overhang);
        var upper = com.formacraft.common.generation.component.util.RoofLayerFrame.inset(lower, upperInset, upperWidth, upperDepth);
        int upperBaseY = baseY + (height - upperHeight);
        // Seat the mid cornice just under the upper tier so it reads as resting on the lower eave.
        int midEaveY = Math.max(baseY, upperBaseY - 1);

        switch (roofType) {
            case XIESHAN -> {
                generateXieshanRoof(out, semantic, lower.worldBaseX(), baseY, lower.worldBaseZ(),
                        lower.width(), lower.depth(), height, palette, footprint, overhang, 0, 0);
                generateXieshanRoof(out, semantic, upper.worldBaseX(), upperBaseY, upper.worldBaseZ(),
                        upper.width(), upper.depth(), upperHeight, palette, footprint, overhang,
                        upper.originOffsetX(), upper.originOffsetZ());
            }
            case XUANSHAN, GABLE, DOUBLE_GABLE -> {
                generateGableRoof(out, semantic, lower.worldBaseX(), baseY, lower.worldBaseZ(),
                        lower.width(), lower.depth(), height, palette, footprint, overhang, 0, 0);
                generateGableRoof(out, semantic, upper.worldBaseX(), upperBaseY, upper.worldBaseZ(),
                        upper.width(), upper.depth(), upperHeight, palette, footprint, overhang,
                        upper.originOffsetX(), upper.originOffsetZ());
            }
            case HIP -> {
                generateHipRoof(out, semantic, lower.worldBaseX(), baseY, lower.worldBaseZ(),
                        lower.width(), lower.depth(), height, palette, footprint, overhang, 0, 0);
                generateHipRoof(out, semantic, upper.worldBaseX(), upperBaseY, upper.worldBaseZ(),
                        upper.width(), upper.depth(), upperHeight, palette, footprint, overhang,
                        upper.originOffsetX(), upper.originOffsetZ());
            }
            default -> {
                generateFlatRoof(out, semantic, lower.worldBaseX(), baseY, lower.worldBaseZ(),
                        lower.width(), lower.depth(), height, palette, footprint, overhang, 0, 0);
                generateFlatRoof(out, semantic, upper.worldBaseX(), upperBaseY, upper.worldBaseZ(),
                        upper.width(), upper.depth(), upperHeight, palette, footprint, overhang,
                        upper.originOffsetX(), upper.originOffsetZ());
            }
        }
        emitMidEaveShelf(out, semantic, palette, upper, midEaveY);
    }

    /**
     * Mid-eave shelf at the upper-tier attachment: a two-cell-wide band that seats on the
     * lower roof edge (inner slab course on the upper footprint rim + outer stairs lip),
     * so it reads as a continuous cornice rather than a floating one-block ring.
     */
    private void emitMidEaveShelf(
            List<BlockPatch> out,
            SemanticComponent semantic,
            Palette palette,
            com.formacraft.common.generation.component.util.RoofLayerFrame upper,
            int midY
    ) {
        String slab = getBlockForPart(semantic, palette, SemanticPart.ROOF_SURFACE);
        String lip = getBlockForPart(semantic, palette, SemanticPart.ROOF);
        int ix0 = upper.worldBaseX();
        int iz0 = upper.worldBaseZ();
        int ix1 = upper.worldBaseX() + Math.max(0, upper.width() - 1);
        int iz1 = upper.worldBaseZ() + Math.max(0, upper.depth() - 1);
        int ox0 = ix0 - 1;
        int oz0 = iz0 - 1;
        int ox1 = ix1 + 1;
        int oz1 = iz1 + 1;
        int outerW = ox1 - ox0 + 1;
        int outerD = oz1 - oz0 + 1;
        for (int x = ox0; x <= ox1; x++) {
            for (int z = oz0; z <= oz1; z++) {
                boolean outer = x == ox0 || x == ox1 || z == oz0 || z == oz1;
                boolean onUpperRim = x >= ix0 && x <= ix1 && z >= iz0 && z <= iz1
                        && (x == ix0 || x == ix1 || z == iz0 || z == iz1);
                if (!outer && !onUpperRim) continue;
                String block;
                if (outer) {
                    int lx = x - ox0;
                    int lz = z - oz0;
                    String facing = inwardFacingFromEdges(lx, lz, outerW, outerD);
                    String shape = isPerimeterCorner(lx, lz, outerW, outerD)
                            ? cornerOuterShape(lx, lz, outerW, outerD, facing) : null;
                    block = withStairsFacing(lip, facing, shape);
                } else {
                    block = slab;
                }
                out.add(new BlockPatch(BlockPatch.PLACE, x, midY, z, block));
            }
        }
    }

    /**
     * Stairs block id with horizontal facing. Merges into existing properties
     * (keeps {@code half}/{@code shape}/{@code waterlogged}) via {@link BlockStateStringUtil#withFacing}.
     */
    static String withStairsFacing(String blockId, String facing) {
        return withStairsFacing(blockId, facing, null);
    }

    static String withStairsFacing(String blockId, String facing, String shape) {
        if (blockId == null || facing == null || facing.isBlank()) return blockId;
        String lower = blockId.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("stairs")) return blockId;
        String base = blockId.contains(":") ? blockId.trim() : "minecraft:" + blockId.trim();
        String out = com.formacraft.common.component.transform.BlockStateStringUtil.withFacing(base, facing);
        if (shape != null && !shape.isBlank()) {
            out = com.formacraft.common.component.transform.BlockStateStringUtil.withProperty(out, "shape", shape);
        }
        return out;
    }

    /** Cardinal facing toward the nearest roof edge (outward / downslope). */
    static String outwardFacingFromEdges(int x, int z, int width, int depth) {
        int dW = x;
        int dE = Math.max(0, width - 1 - x);
        int dN = z;
        int dS = Math.max(0, depth - 1 - z);
        int min = Math.min(Math.min(dW, dE), Math.min(dN, dS));
        boolean ns = dN == min || dS == min;
        boolean ew = dW == min || dE == min;
        // 45° hip corners: prefer E/W so miter pairs are not all forced north/south.
        if (ns && ew) {
            if (dW == min) return "west";
            if (dE == min) return "east";
        }
        if (dN == min) return "north";
        if (dS == min) return "south";
        if (dW == min) return "west";
        return "east";
    }

    /** Stair facing toward the ridge (Minecraft higher-half direction). */
    static String inwardFacingFromEdges(int x, int z, int width, int depth) {
        return oppositeFacing(outwardFacingFromEdges(x, z, width, depth));
    }

    static String oppositeFacing(String facing) {
        if (facing == null) return null;
        return switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            default -> facing;
        };
    }

    static boolean isPerimeterCorner(int x, int z, int width, int depth) {
        boolean xEdge = x == 0 || x == width - 1;
        boolean zEdge = z == 0 || z == depth - 1;
        return xEdge && zEdge && width >= 2 && depth >= 2;
    }

    /**
     * Outer miter shape for hip/xieshan corner stairs (Minecraft outer_left / outer_right).
     * Facing is the higher-half axis (toward ridge / inward).
     */
    static String cornerOuterShape(int x, int z, int width, int depth, String facing) {
        if (facing == null || !isPerimeterCorner(x, z, width, depth)) return null;
        boolean north = z == 0;
        boolean south = z == depth - 1;
        boolean west = x == 0;
        boolean east = x == width - 1;
        // Mirror of the outward-facing table: inward facing swaps left/right.
        return switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "west" -> north ? "outer_left" : "outer_right";
            case "east" -> north ? "outer_right" : "outer_left";
            case "north" -> west ? "outer_right" : "outer_left";
            case "south" -> west ? "outer_left" : "outer_right";
            default -> null;
        };
    }

    static String gableOutwardFacing(boolean alongDepth, int x, int z, int width, int depth) {
        if (alongDepth) {
            int mid = Math.max(0, (width - 1) / 2);
            return x <= mid ? "west" : "east";
        }
        int mid = Math.max(0, (depth - 1) / 2);
        return z <= mid ? "north" : "south";
    }

    /** Higher half toward the ridge for gable/xuanshan pitches. */
    static String gableRidgeFacing(boolean alongDepth, int x, int z, int width, int depth) {
        return oppositeFacing(gableOutwardFacing(alongDepth, x, z, width, depth));
    }

    private void generatePyramidRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang) {
        if (footprint != null && footprint.footprint() != null
                && footprint.footprint().shape()
                == com.formacraft.common.generation.component.util.ResolvedFootprint.FootprintShape.HEXAGON) {
            generateHexagonPyramidRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette,
                    footprint, overhang);
            return;
        }
        generateHipRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang, 0, 0);
    }

    /**
     * Six-slope 攒尖: rise follows inward normal distance to the hexagon perimeter
     * (not AABB Chebyshev), so six facets converge at the center apex.
     */
    private void generateHexagonPyramidRoof(List<BlockPatch> out, SemanticComponent semantic,
                                            int baseX, int baseY, int baseZ,
                                            int width, int depth, int height, Palette palette,
                                            ComponentFootprintMask footprint, int overhang) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang,
                    0, 0);
            return;
        }
        boolean fillUnder = wantsStairSurface(semantic.source() != null ? semantic.source().params() : null)
                || height >= 3;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang, 0, 0)) {
                    continue;
                }
                // Six facets: distance to hex perimeter (not AABB Chebyshev).
                double apex = com.formacraft.common.generation.component.util.ResolvedFootprint
                        .hexagonApexFactor(x, z, width, depth);
                int rise = (int) Math.round(com.formacraft.common.generation.component.util.StyleRoofGeometry.profile(
                        semantic, apex) * (height - 1));
                boolean nearApex = apex >= 0.85;
                SemanticPart part = nearApex ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                if (part == SemanticPart.ROOF) {
                    String facing = hexagonInwardFacing(x, z, width, depth);
                    block = withStairsFacing(block, facing);
                }
                emitSlopeColumn(out, baseX + x, baseY, baseZ + z, rise, block, fillUnder && part == SemanticPart.ROOF);
            }
        }
    }

    /** Stair facing toward the hexagon center (higher half), using dominant axis. */
    static String hexagonInwardFacing(int x, int z, int width, int depth) {
        double cx = (width - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        double dx = cx - x;
        double dz = cz - z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    private void generateConeRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height,
                                  Palette palette, boolean dome,
                                  ComponentFootprintMask footprint, int overhang, int drumHeight) {
        var params = semantic.source().params();
        DomeProfile domeProfile = dome ? resolveDomeProfile(semantic.source(), params) : DomeProfile.LEGACY;
        int openingRadius = dome ? domeDetailInteger(params, "dome_oculus_radius", Integer.MAX_VALUE) : 0;
        String openingGlass = dome ? domeOculusGlass(params, openingRadius) : null;
        String openingError = "E_ROOF_GEOMETRY_INVALID: dome_oculus_radius must leave a complete roof rim";
        if (drumHeight > 0 && (Math.min(width, depth) < 3 || drumHeight > height - 2)) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: dome_drum_height requires a footprint "
                    + "of at least 3x3 and must leave at least two layers for the dome cap");
        }
        if (width < 2 || depth < 2 || height < 2) {
            if (openingRadius > 0) throw new IllegalArgumentException(openingError);
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        int capRise = height - drumHeight - 1;
        int[][] capColumns = drumHeight > 0 || openingRadius > 0 ? new int[width][depth] : null;
        double openingRadiusSquared = (double) openingRadius * openingRadius;
        int ribCount = dome ? domeDetailInteger(params, "dome_rib_count", 32) : 0;
        double ribSpacing = ribCount > 0 ? 2 * Math.PI / ribCount : 0;
        String ribBlock = null;
        if (ribCount > 0) {
            String ribMaterial = getParamString(params, "dome_rib_block");
            if (ribMaterial == null) ribMaterial = getBlockForPart(semantic, palette, SemanticPart.WALL_ACCENT);
            ribBlock = slopeFillBlock(ribMaterial);
        }
        double cx = (width - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        double radius = Math.max(1.0, Math.min(width, depth) / 2.0);
        String authoredType = getParamString(semantic.source().params(), "roof_type", "roofType");
        boolean faceted = dome && authoredType != null && java.util.Set.of("polyhedral_dome","faceted_dome","polygonal_dome")
                .contains(authoredType.toLowerCase(java.util.Locale.ROOT));
        int facets = Math.max(4,Math.min(32,ComponentParamParsers.intParam(semantic.source().params(),8,"segments","dome_segments")));
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
                double dx = x - cx;
                double dz = z - cz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (faceted) {
                    double plane = 0;
                    for (int face=0;face<facets;face++) {
                        double angle=2*Math.PI*face/facets;
                        plane=Math.max(plane,dx*Math.cos(angle)+dz*Math.sin(angle));
                    }
                    dist=plane/Math.cos(Math.PI/facets);
                }
                if (dist > radius + 0.25) {
                    continue;
                }
                double t = Math.max(0.0, 1.0 - (dist / radius));
                double normalizedRadius = Math.clamp(dist / radius, 0.0, 1.0);
                double curve = t;
                if (dome) {
                    curve = switch (domeProfile) {
                        case LEGACY -> Math.sqrt(t);
                        case HEMISPHERE -> Math.sqrt(Math.max(0, 1 - normalizedRadius * normalizedRadius));
                        // Two circular arcs meet with a finite slope at the apex.
                        case POINTED -> Math.sqrt((1 - normalizedRadius) * (2 + normalizedRadius) / 2);
                    };
                }
                int rise = (int) Math.round(curve * capRise);
                // Record the original cap footprint so the oculus never becomes an inner drum wall.
                if (capColumns != null) capColumns[x][z] = rise + 1;
                boolean opening = openingRadius > 0 && dx * dx + dz * dz <= openingRadiusSquared;
                if (opening && openingGlass == null) continue;
                SemanticPart part = (dist <= 0.5) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block;
                if (opening) {
                    block = openingGlass;
                } else {
                    block = ribBlock != null && part != SemanticPart.ROOF_SURFACE && intersectsDomeRib(dx, dz, ribSpacing)
                            ? ribBlock : getBlockForPart(semantic, palette, part);
                    if (part == SemanticPart.ROOF && dist > 0.5) {
                        // Radial stairs: higher half toward the apex (dome centre).
                        block = withStairsFacing(block, hexagonInwardFacing(x, z, width, depth));
                    }
                }
                boolean fillUnder = dome && (wantsStairSurface(params)
                        || capRise < Math.max(2, (int) (radius * 0.65)));
                if (fillUnder && part == SemanticPart.ROOF && !opening) {
                    emitSlopeColumn(out, baseX + x, baseY + drumHeight, baseZ + z, rise, block, true);
                } else {
                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            baseX + x,
                            baseY + drumHeight + rise,
                            baseZ + z,
                            block
                    ));
                }
            }
        }
        if (openingRadius > 0 && !hasDomeOculusRim(capColumns, cx, cz, openingRadiusSquared)) {
            throw new IllegalArgumentException(openingError);
        }
        if (drumHeight > 0) {
            String drumMaterial = getParamString(semantic.source().params(), "dome_drum_block");
            if (drumMaterial == null) drumMaterial = getBlockForPart(semantic, palette, SemanticPart.WALL);
            emitDomeDrum(out, baseX, baseY, baseZ, capColumns, drumHeight, slopeFillBlock(drumMaterial),
                    resolveFullRoofBlock(semantic, palette));
        }
    }

    private static DomeProfile resolveDomeProfile(Component component, Map<String, Object> params) {
        RoofTypeKind kind = RoofTypeCatalog.extractExplicitType(component, params)
                .flatMap(RoofTypeCatalog::parseExplicit).orElse(RoofTypeKind.DOME);
        DomeProfile required = switch (kind) {
            case HEMISPHERICAL_DOME -> DomeProfile.HEMISPHERE;
            case POINTED_DOME -> DomeProfile.POINTED;
            default -> DomeProfile.LEGACY;
        };
        String raw = getParamString(params, "dome_profile");
        if (raw == null) return required;
        DomeProfile profile;
        try {
            profile = DomeProfile.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "E_ROOF_GEOMETRY_INVALID: dome_profile must be legacy, hemisphere or pointed", e);
        }
        if (required != DomeProfile.LEGACY && profile != required) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: dome_profile conflicts with the explicit roof_type");
        }
        return profile;
    }

    private static String domeOculusGlass(Map<String, Object> params, int radius) {
        String raw = getParamString(params, "dome_oculus_glass_block");
        if (raw == null) return null;
        String block = raw.contains(":") ? raw : "minecraft:" + raw;
        var id = net.minecraft.util.Identifier.tryParse(block);
        if (radius < 1 || id == null || !net.minecraft.registry.Registries.BLOCK.containsId(id)
                || !(id.getPath().equals("glass") || id.getPath().equals("tinted_glass")
                || id.getPath().endsWith("_stained_glass"))) {
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: dome_oculus_glass_block requires "
                    + "a positive dome_oculus_radius and a registered glass block");
        }
        return id.toString();
    }

    private static boolean hasDomeOculusRim(int[][] capColumns, double cx, double cz, double radiusSquared) {
        int width = capColumns.length;
        int depth = capColumns[0].length;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                double dx = x - cx;
                double dz = z - cz;
                if (dx * dx + dz * dz > radiusSquared) continue;
                if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) return false;
                for (int nx = x - 1; nx <= x + 1; nx++) {
                    for (int nz = z - 1; nz <= z + 1; nz++) {
                        if (capColumns[nx][nz] == 0) return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean intersectsDomeRib(double dx, double dz, double spacing) {
        double ray = Math.round(Math.atan2(dz, dx) / spacing) * spacing;
        double cos = Math.cos(ray);
        double sin = Math.sin(ray);
        double halfCell = 0.5 * (Math.abs(cos) + Math.abs(sin)) + 1e-9;
        double distance = Math.abs(dx * sin - dz * cos);
        // Include a cell when the rib crosses its unit square, including diagonal joins.
        return distance <= halfCell && dx * cos + dz * sin >= -halfCell;
    }

    private static int domeDetailInteger(Map<String, Object> params, String key, int max) {
        String raw = getParamString(params, key);
        if (raw == null) return 0;
        String error = "E_ROOF_GEOMETRY_INVALID: " + key + " must be a whole number from 0 through " + max;
        try {
            double value = Double.parseDouble(raw);
            if (value >= 0 && value <= max && value == Math.rint(value)) return (int) value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        throw new IllegalArgumentException(error);
    }

    private static void emitDomeDrum(List<BlockPatch> out, int baseX, int baseY, int baseZ,
                                     int[][] capColumns, int height, String drumBlock, String roofBlock) {
        int width = capColumns.length;
        int depth = capColumns[0].length;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int column = capColumns[x][z];
                if (column == 0) continue;
                if (x > 0 && x < width - 1 && z > 0 && z < depth - 1
                        && capColumns[x - 1][z] > 0 && capColumns[x + 1][z] > 0
                        && capColumns[x][z - 1] > 0 && capColumns[x][z + 1] > 0) continue;
                // At quantized edges the cap may start above the springing plane.
                for (int y = 0; y < height + column - 1; y++) {
                    out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, baseY + y, baseZ + z,
                            y < height ? drumBlock : roofBlock));
                }
            }
        }
    }

    private void generateMansardRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang,
                                     ComponentRoofSpecialtyDecorator.MansardProfile profile) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        int maxSpan = Math.max(1, (Math.min(width, depth) - 1) / 2);
        // Lower mansard pitch is often <45° — stairs alone look flat/same-facing; fill under + face inward.
        boolean fillUnder = wantsStairSurface(semantic.source() != null ? semantic.source().params() : null)
                || profile.breakRise() < Math.max(1, profile.setback())
                || (height - 1) < maxSpan * 0.9;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
                int dist = Math.min(Math.min(x, width - 1 - x), Math.min(z, depth - 1 - z));
                int rise = profile.riseAt(dist);
                SemanticPart part = (dist <= 1) ? SemanticPart.ROOF : SemanticPart.ROOF_SURFACE;
                String block = getBlockForPart(semantic, palette, part);
                if (part == SemanticPart.ROOF) {
                    String facing = inwardFacingFromEdges(x, z, width, depth);
                    String shape = isPerimeterCorner(x, z, width, depth)
                            ? cornerOuterShape(x, z, width, depth, facing) : null;
                    block = withStairsFacing(block, facing, shape);
                }
                emitSlopeColumn(out, baseX + x, baseY, baseZ + z, rise, block,
                        fillUnder && part == SemanticPart.ROOF);
            }
        }
    }

    /**
     * 生成平屋顶
     */
    private void generateFlatRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height, Palette palette,
                                  ComponentFootprintMask footprint, int overhang) {
        generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang, 0, 0);
    }

    private void generateFlatRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height, Palette palette,
                                  ComponentFootprintMask footprint, int overhang, int fpOffsetX, int fpOffsetZ) {
        // A flat roof is a plate at its attachment plane, not a floating sheet
        // lifted by the pitched-roof height budget.
        int topY = 0;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang, fpOffsetX, fpOffsetZ)) {
                    continue;
                }
                SemanticPart part = SemanticPart.ROOF_SURFACE;
                String block = getBlockForPart(semantic, palette, part);
                
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        baseX + x,
                        baseY + topY,
                        baseZ + z,
                        block
                ));
            }
        }
    }

    private void addEaveLayer(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                              int width, int depth, Palette palette, int outset,
                              ComponentFootprintMask footprint, int overhang) {
        if (width < 1 || depth < 1 || outset <= 0) {
            return;
        }
        String block = getBlockForPart(semantic, palette, SemanticPart.ROOF);
        if (footprint != null) {
            // Place eaves along true exterior and courtyard rims, not only the AABB box.
            var placed = new java.util.HashSet<net.minecraft.util.math.BlockPos>();
            for (var edge : footprint.boundaryEdges()) {
                for (int step = 1; step <= outset; step++) {
                    int localX = edge.x() + edge.outwardDx() * step;
                    int localZ = edge.z() + edge.outwardDz() * step;
                    // Courtyard eaves stay on the rim strip; do not walk deep into voids.
                    if (edge.courtyard() && step > outset) continue;
                    int wx = baseX + overhang + localX;
                    int wz = baseZ + overhang + localZ;
                    var key = new net.minecraft.util.math.BlockPos(wx, baseY, wz);
                    if (!placed.add(key)) continue;
                    out.add(new BlockPatch(BlockPatch.PLACE, wx, baseY, wz, block));
                }
            }
            return;
        }
        // Rectangular fallback when no footprint mask is available.
        int startX = baseX - outset;
        int startZ = baseZ - outset;
        int endX = baseX + width - 1 + outset;
        int endZ = baseZ + depth - 1 + outset;
        for (int x = startX; x <= endX; x++) {
            out.add(new BlockPatch(BlockPatch.PLACE, x, baseY, startZ, block));
            out.add(new BlockPatch(BlockPatch.PLACE, x, baseY, endZ, block));
        }
        for (int z = startZ; z <= endZ; z++) {
            out.add(new BlockPatch(BlockPatch.PLACE, startX, baseY, z, block));
            out.add(new BlockPatch(BlockPatch.PLACE, endX, baseY, z, block));
        }
    }

    private String getStyleProfile(SemanticComponent semantic) {
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC";
            }
            if (upper.contains("CHINESE_ROYAL") || upper.contains("CHINESE_IMPERIAL") || upper.contains("CHINESE_PALACE")
                    || (upper.contains("CHINESE") && (upper.contains("ROYAL") || upper.contains("IMPERIAL") || upper.contains("PALACE")))) {
                return "CHINESE_ROYAL";
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("gothic") || lower.contains("pointed") || lower.contains("ribbed")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if ((lower.contains("chinese") || lower.contains("imperial") || lower.contains("royal") || lower.contains("palace"))
                        && (lower.contains("imperial") || lower.contains("royal") || lower.contains("palace"))) {
                    return "CHINESE_ROYAL";
                }
                if (lower.contains("chinese") || lower.contains("中式")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }
        return "MEDIEVAL_CLASSIC";
    }

    private RoofTypeKind resolveRoofType(Component c, SemanticComponent semantic, Map<String, Object> params) {
        var explicitType = RoofTypeCatalog.extractExplicitType(c, params);
        if (explicitType.isPresent()) {
            String type = explicitType.get();
            // parseExplicit covers both plan-supported tokens and generator specialty roofs
            // (e.g. sawtooth) that are intentionally outside isSupportedExplicit.
            return RoofTypeCatalog.parseExplicit(type).orElseThrow(
                    () -> new IllegalArgumentException("roof_type_unsupported:" + type));
        }
        String type = null;
        if (semantic != null && semantic.genome() != null && semantic.genome().form != null) {
            String curvature = semantic.genome().form.curvature;
            if ("curved".equalsIgnoreCase(curvature)) {
                type = "dome";
            }
            String progression = semantic.genome().form.progression;
            if ("tapering".equalsIgnoreCase(progression)) {
                type = "pyramid";
            }
        }
        if (type != null) {
            return RoofTypeCatalog.parseExplicit(type).orElse(RoofTypeKind.GABLE);
        }
        String profile = getStyleProfile(semantic);
        String upper = profile.toUpperCase();
        if (upper.contains("HUI") || upper.contains("HUIZHOU")) {
            return RoofTypeKind.GABLE; // 硬山/徽派默认走 gable；显式 yingshan/xuanshan 由 params 覆盖
        }
        // 中式殿堂默认歇山；悬山/庑殿需显式 roof_type（见 RoofTypeCatalog / PlanIntentFidelityRepair）
        if (upper.contains("CHINESE") || upper.contains("ASIAN") || upper.contains("SIHEYUAN")) {
            return RoofTypeKind.XIESHAN;
        }
        if (upper.contains("GOTHIC") || upper.contains("MEDIEVAL")) {
            return RoofTypeKind.GABLE;
        }
        if (upper.contains("MODERN")) {
            return RoofTypeKind.FLAT;
        }
        return RoofTypeKind.GABLE;
    }

    private static boolean isDoubleEave(Map<String, Object> params, List<String> features) {
        if (getParamBoolean(params, "double_eave", "doubleEave", "double_eaved", "double_eave_roof")) {
            return true;
        }
        if (features == null) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(java.util.Locale.ROOT).trim();
            if (lower.contains("double_eave") || lower.contains("double-eave")
                    || (lower.contains("double") && lower.contains("eave"))
                    || lower.contains("重檐") || lower.contains("多层屋檐") || lower.contains("叠檐")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRoofFeature(Component component, SemanticComponent semantic, String... tokens) {
        if (component != null && component.features() != null) {
            for (String feature : component.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                for (String token : tokens) {
                    if (token != null && lower.contains(token.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        if (semantic != null && semantic.styleAttributes() != null
                && semantic.styleAttributes().decorativeElements() != null) {
            for (String element : semantic.styleAttributes().decorativeElements()) {
                if (element == null) continue;
                String lower = element.toLowerCase();
                for (String token : tokens) {
                    if (token != null && lower.contains(token.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v != null) {
                String s = String.valueOf(v).trim();
                if ("true".equalsIgnoreCase(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Full-cube roof material for vault shells / horse-head caps (never stairs/slab). */
    private static String resolveFullRoofBlock(SemanticComponent semantic, Palette palette) {
        Map<String, Object> params = semantic != null && semantic.source() != null
                ? semantic.source().params() : null;
        // Prefer the authored cube id before coerceRoofShape turns gables into stairs/slabs.
        String resolved = getParamString(params, "roof_block", "roof_material", "material");
        if (resolved == null || resolved.isBlank()) {
            resolved = getBlockForPart(semantic, palette, SemanticPart.ROOF);
        } else if (!resolved.contains(":")) {
            resolved = "minecraft:" + resolved.trim();
        }
        String full = com.formacraft.common.palette.component.MaterialBlockFamily.fullBlock(resolved);
        return full != null ? full : resolved;
    }

    private static String getBlockForPart(SemanticComponent semantic, Palette palette, SemanticPart part) {
        Map<String, Object> params = semantic != null && semantic.source() != null
                ? semantic.source().params() : null;
        if (params != null && (part == SemanticPart.ROOF || part == SemanticPart.ROOF_SURFACE)) {
            String resolved = resolveRoofMaterial(params, part);
            if (resolved != null) {
                return coerceRoofShape(resolved, part, params);
            }
        }
        String key = switch (part) {
            case WALL, WALL_BASE, WALL_ACCENT -> "wall_block";
            default -> null;
        };
        if (key != null && params != null) {
            String explicit = getParamString(params, key);
            if (explicit != null && !explicit.isBlank()) {
                return explicit.contains(":") ? explicit : "minecraft:" + explicit;
            }
        }
        String block = null;
        if (semantic != null && semantic.styleAttributes() != null) {
            block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
        }
        if ((block == null || block.isEmpty()) && palette != null) {
            block = palette.pick(part);
        }
        if (block == null || block.isEmpty()) {
            block = switch (part) {
                case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
                default -> "minecraft:stone_bricks";
            };
        }
        if (part == SemanticPart.ROOF || part == SemanticPart.ROOF_SURFACE) {
            return coerceRoofShape(block, part, params);
        }
        return block;
    }

    /**
     * Honors {@code roof_block} / {@code roof_material} / {@code material}.
     * Pitched slopes prefer stairs; roof plates prefer slabs when the family exists.
     */
    static String resolveRoofMaterial(Map<String, Object> params, SemanticPart part) {
        if (params == null) return null;
        String explicit = getParamString(params, "roof_block", "roof_material", "material");
        if (explicit == null || explicit.isBlank()) return null;
        return coerceRoofShape(
                explicit.contains(":") ? explicit.trim() : "minecraft:" + explicit.trim(),
                part,
                params);
    }

    static String coerceRoofShape(String id, SemanticPart part, Map<String, Object> params) {
        if (id == null || id.isBlank()) return id;
        String base = id.split("\\[", 2)[0].toLowerCase(java.util.Locale.ROOT);
        String roofType = params == null ? ""
                : String.valueOf(params.getOrDefault("roof_type", "")).toLowerCase(java.util.Locale.ROOT);
        boolean flatRoof = roofType.contains("flat") || roofType.equals("none");
        boolean preferStairs = getParamBoolean(params, "prefer_stairs", "preferStairs", "stair_roof")
                || base.endsWith("_stairs")
                || roofType.matches(".*(gable|hip|xieshan|xuanshan|yingshan|mansard|wudian|wu_dian).*")
                // Pitched ROOF slopes default to stairs when the family exists (planks → stairs).
                || (part == SemanticPart.ROOF && !flatRoof);
        if (part == SemanticPart.ROOF_SURFACE) {
            // Flat decks keep the authored full cube (smooth_stone etc.); pitched plates stay slabs.
            if (flatRoof) {
                return id.contains(":") ? id : "minecraft:" + id;
            }
            if (base.endsWith("_slab")) return id;
            String slab = com.formacraft.common.palette.component.MaterialBlockFamily.slab(base);
            if (slab != null) return slab;
            return id;
        }
        if (preferStairs || base.endsWith("_stairs")) {
            if (base.endsWith("_stairs")) return id.contains(":") ? id : "minecraft:" + id;
            String stairs = com.formacraft.common.palette.component.MaterialBlockFamily.stairs(base);
            if (stairs != null) return stairs;
        }
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static boolean isAsianStyleProfile(String styleProfile) {
        if (styleProfile == null || styleProfile.isBlank()) return false;
        String s = styleProfile.trim().toUpperCase(java.util.Locale.ROOT);
        return s.contains("ASIAN") || s.contains("CHINESE") || s.contains("JAPAN")
                || s.contains("MING") || s.contains("QING") || s.contains("TANG");
    }

}
