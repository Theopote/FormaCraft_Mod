package com.formacraft.server.generation.structure.util;

import com.formacraft.common.typology.detail.ChineseTypologyDetailUtil;

/**
 * @deprecated Use {@link ChineseTypologyDetailUtil} in the typology package.
 */
@Deprecated
public final class ChineseLandmarkDetailUtil {

    public record OctagonFace(int x, int z, net.minecraft.util.math.Direction outward) {}

    public enum PuzuoProfile {
        INTERIOR_EDGE,
        INTERIOR_CORNER,
        SUB_EAVES_EDGE,
        SUB_EAVES_CORNER
    }

    private ChineseLandmarkDetailUtil() {}

    public static boolean inRegularOctagon(int x, int z, int half) {
        return ChineseTypologyDetailUtil.inRegularOctagon(x, z, half);
    }

    public static boolean onRegularOctagonEdge(int x, int z, int half) {
        return ChineseTypologyDetailUtil.onRegularOctagonEdge(x, z, half);
    }

    public static void ringRegularOctagon(
            java.util.List<com.formacraft.common.build.PlannedBlock> blocks,
            net.minecraft.util.math.BlockPos origin, int half, int y, net.minecraft.block.BlockState state
    ) {
        ChineseTypologyDetailUtil.ringRegularOctagon(blocks, origin, half, y, state);
    }

    public static net.minecraft.util.math.BlockPos octagonFaceCell(int half, net.minecraft.util.math.Direction face) {
        return ChineseTypologyDetailUtil.octagonFaceCell(half, face);
    }

    public static OctagonFace octagonFaceByIndex(int half, int faceIndex) {
        var f = ChineseTypologyDetailUtil.octagonFaceByIndex(half, faceIndex);
        return new OctagonFace(f.x(), f.z(), f.outward());
    }

    public static void addPagodaTierNiche(
            java.util.List<com.formacraft.common.build.PlannedBlock> blocks,
            net.minecraft.util.math.BlockPos origin, int half,
            int y0, int levelH, int levelIndex, int totalLevels,
            boolean refined, net.minecraft.block.BlockState trim, net.minecraft.block.BlockState stair
    ) {
        ChineseTypologyDetailUtil.addPagodaTierNiche(
                blocks, origin, half, y0, levelH, levelIndex, totalLevels, refined, trim, stair);
    }

    public static void addStupaFinial(
            java.util.List<com.formacraft.common.build.PlannedBlock> blocks,
            net.minecraft.util.math.BlockPos origin, int baseY,
            net.minecraft.block.BlockState accent, net.minecraft.block.BlockState ring, net.minecraft.block.BlockState spire
    ) {
        ChineseTypologyDetailUtil.addStupaFinial(blocks, origin, baseY, accent, ring, spire);
    }

    public static void addPuzuoZoning(
            java.util.function.BiConsumer<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState> place,
            int x, int y, int z,
            net.minecraft.util.math.Direction outward, net.minecraft.util.math.Direction along,
            PuzuoProfile profile,
            net.minecraft.block.BlockState luDou, net.minecraft.block.BlockState gong,
            net.minecraft.block.BlockState fang, net.minecraft.block.BlockState paint
    ) {
        ChineseTypologyDetailUtil.PuzuoProfile mapped = switch (profile) {
            case INTERIOR_EDGE -> ChineseTypologyDetailUtil.PuzuoProfile.INTERIOR_EDGE;
            case INTERIOR_CORNER -> ChineseTypologyDetailUtil.PuzuoProfile.INTERIOR_CORNER;
            case SUB_EAVES_EDGE -> ChineseTypologyDetailUtil.PuzuoProfile.SUB_EAVES_EDGE;
            case SUB_EAVES_CORNER -> ChineseTypologyDetailUtil.PuzuoProfile.SUB_EAVES_CORNER;
        };
        ChineseTypologyDetailUtil.addPuzuoZoning(place, x, y, z, outward, along, mapped, luDou, gong, fang, paint);
    }

    public static boolean isCornerColumn(int x, int z, int x0, int z0, int x1, int z1) {
        return ChineseTypologyDetailUtil.isCornerColumn(x, z, x0, z0, x1, z1);
    }

    public static net.minecraft.util.math.Direction alongWallFromEdge(int x, int z, int x0, int z0, int x1, int z1) {
        return ChineseTypologyDetailUtil.alongWallFromEdge(x, z, x0, z0, x1, z1);
    }

    public static PuzuoProfile resolvePuzuoProfile(boolean subEaves, boolean corner) {
        return switch (ChineseTypologyDetailUtil.resolvePuzuoProfile(subEaves, corner)) {
            case INTERIOR_EDGE -> PuzuoProfile.INTERIOR_EDGE;
            case INTERIOR_CORNER -> PuzuoProfile.INTERIOR_CORNER;
            case SUB_EAVES_EDGE -> PuzuoProfile.SUB_EAVES_EDGE;
            case SUB_EAVES_CORNER -> PuzuoProfile.SUB_EAVES_CORNER;
        };
    }

    public static void addSubEavesInnerRoof(
            java.util.function.BiConsumer<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState> place,
            int innerX0, int innerZ0, int innerX1, int innerZ1,
            int outerX0, int outerZ0, int outerX1, int outerZ1,
            int y, net.minecraft.block.BlockState slab, net.minecraft.block.BlockState stair
    ) {
        ChineseTypologyDetailUtil.addSubEavesInnerRoof(
                place, innerX0, innerZ0, innerX1, innerZ1, outerX0, outerZ0, outerX1, outerZ1, y, slab, stair);
    }

    public static void addLatticeWindow(
            java.util.function.BiConsumer<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState> place,
            int x0, int x1, int y0, int y1, int z,
            net.minecraft.block.BlockState frame, net.minecraft.block.BlockState infill
    ) {
        ChineseTypologyDetailUtil.addLatticeWindow(place, x0, x1, y0, y1, z, frame, infill);
    }

    public static java.util.List<int[]> perimeterColumnPositions(int x0, int z0, int x1, int z1, int spacing) {
        return ChineseTypologyDetailUtil.perimeterColumnPositions(x0, z0, x1, z1, spacing);
    }

    public static net.minecraft.util.math.Direction outwardFromRectEdge(int x, int z, int x0, int z0, int x1, int z1) {
        return ChineseTypologyDetailUtil.outwardFromRectEdge(x, z, x0, z0, x1, z1);
    }
}
