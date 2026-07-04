package com.formacraft.common.geometry.shape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M1 + M2 + M3 体素基元库。
 * <p>
 * M2：sphere / ellipse / sector / triangle、XYZ 旋转、CSG union/subtract/intersect。
 * M3：extrude_mode=plate 2D 单层、Voronoi / Möbius 体素近似。
 * M3+：Voronoi 3D 体积细胞、Möbius CSG 组合。
 */
public final class ShapeLibrary {

    public record Voxel(int x, int y, int z) {}

    private record Seed2D(double x, double z) {}

    private record Seed3D(double x, double y, double z) {}

    private record VoronoiSeeds(List<Seed2D> planar, List<Seed3D> volume) {
        static VoronoiSeeds empty() {
            return new VoronoiSeeds(List.of(), List.of());
        }

        boolean isVolume() {
            return volume != null && !volume.isEmpty();
        }
    }

    private ShapeLibrary() {}

    public static List<Voxel> generate(ShapeSpec spec) {
        if (spec == null) {
            return List.of();
        }
        VoronoiSeeds voronoiSeeds = spec.kind() == ShapeKind.VORONOI ? buildVoronoiSeeds(spec) : VoronoiSeeds.empty();
        List<Voxel> out = new ArrayList<>();
        if (spec.extrudeMode() == ShapeExtrudeMode.PLATE && spec.kind() != ShapeKind.MOBIUS) {
            for (int x = 0; x < spec.width(); x++) {
                for (int z = 0; z < spec.depth(); z++) {
                    if (isInside(spec, x, 0, z, voronoiSeeds)) {
                        out.add(new Voxel(x, 0, z));
                    }
                }
            }
            return out;
        }
        for (int y = 0; y < spec.height(); y++) {
            for (int x = 0; x < spec.width(); x++) {
                for (int z = 0; z < spec.depth(); z++) {
                    if (isInside(spec, x, y, z, voronoiSeeds)) {
                        out.add(new Voxel(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    public static List<Voxel> generateComposite(List<ShapeCsgOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        Set<Voxel> acc = new HashSet<>();
        for (ShapeCsgOperation operation : operations) {
            if (operation == null || operation.spec() == null) {
                continue;
            }
            List<Voxel> chunk = generate(operation.spec());
            switch (operation.op()) {
                case UNION -> acc.addAll(chunk);
                case SUBTRACT -> chunk.forEach(acc::remove);
                case INTERSECT -> {
                    if (acc.isEmpty()) {
                        acc.addAll(chunk);
                    } else {
                        Set<Voxel> next = new HashSet<>();
                        for (Voxel v : chunk) {
                            if (acc.contains(v)) {
                                next.add(v);
                            }
                        }
                        acc = next;
                    }
                }
            }
        }
        return new ArrayList<>(acc);
    }

    private static boolean isInside(ShapeSpec spec, int x, int y, int z, VoronoiSeeds voronoiSeeds) {
        double lx0 = x - spec.halfX();
        double ly0 = y - spec.halfY();
        double lz0 = z - spec.halfZ();
        if (spec.extrudeMode() == ShapeExtrudeMode.PLATE && spec.kind() != ShapeKind.MOBIUS) {
            ly0 = -spec.halfY();
        }
        double[] local = ShapeTransform.worldToLocal(lx0, ly0, lz0, spec);
        if (!insideCanonical(spec, local[0], local[1], local[2], voronoiSeeds)) {
            return false;
        }
        if (spec.hollow() && isInteriorHollow(spec, local[0], local[1], local[2], voronoiSeeds)) {
            return false;
        }
        return true;
    }

    private static boolean insideCanonical(
            ShapeSpec spec, double lx, double ly, double lz, VoronoiSeeds voronoiSeeds
    ) {
        return switch (spec.kind()) {
            case BOX -> Math.abs(lx) <= spec.halfX() + 0.25
                    && Math.abs(ly) <= spec.halfY() + 0.25
                    && Math.abs(lz) <= spec.halfZ() + 0.25;
            case CYLINDER -> insideExtrudedCircle(spec, lx, ly, lz, spec.effectiveRadiusX());
            case ELLIPSE -> insideExtrudedEllipse(spec, lx, ly, lz);
            case PRISM -> insideExtrudedPolygon(spec, lx, ly, lz, spec.sides(), spec.effectiveRadiusX());
            case CONE -> {
                double t = heightT(spec, ly);
                double rAtY = spec.radius() * (1.0 - t);
                yield insideExtrudedCircleAt(spec, lx, ly, lz, rAtY);
            }
            case FRUSTUM -> {
                double t = heightT(spec, ly);
                double rAtY = spec.topRadius() + (spec.radius() - spec.topRadius()) * (1.0 - t);
                yield insideExtrudedCircleAt(spec, lx, ly, lz, rAtY);
            }
            case SPHERE -> insideEllipsoid(lx, ly, lz,
                    spec.effectiveRadiusX(), spec.effectiveRadiusY(), spec.effectiveRadiusZ());
            case HEMISPHERE -> insideEllipsoid(lx, ly, lz,
                    spec.effectiveRadiusX(), spec.effectiveRadiusY(), spec.effectiveRadiusZ())
                    && ly >= -0.25;
            case SECTOR -> insideExtrudedSector(spec, lx, ly, lz);
            case TRIANGLE -> insideExtrudedTriangle(spec, lx, ly, lz);
            case VORONOI -> insideVoronoi(spec, lx, ly, lz, voronoiSeeds);
            case MOBIUS -> insideMobius(spec, lx, ly, lz);
        };
    }

    private static VoronoiSeeds buildVoronoiSeeds(ShapeSpec spec) {
        int n = Math.max(3, spec.voronoiCells());
        long seed = spec.voronoiSeed();
        java.util.Random rng = new java.util.Random(seed);
        if (spec.voronoiDimension() == VoronoiDimension.VOLUME) {
            double rx = spec.effectiveRadiusX();
            double ry = spec.effectiveRadiusY();
            double rz = spec.effectiveRadiusZ();
            List<Seed3D> seeds = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                seeds.add(new Seed3D(
                        (rng.nextDouble() * 2.0 - 1.0) * rx * 0.88,
                        (rng.nextDouble() * 2.0 - 1.0) * ry * 0.88,
                        (rng.nextDouble() * 2.0 - 1.0) * rz * 0.88
                ));
            }
            return new VoronoiSeeds(List.of(), seeds);
        }
        double rx = spec.effectiveRadiusX();
        double rz = spec.effectiveRadiusZ();
        List<Seed2D> seeds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            seeds.add(new Seed2D(
                    (rng.nextDouble() * 2.0 - 1.0) * rx * 0.88,
                    (rng.nextDouble() * 2.0 - 1.0) * rz * 0.88
            ));
        }
        return new VoronoiSeeds(seeds, List.of());
    }

    private static boolean insideVoronoi(
            ShapeSpec spec, double lx, double ly, double lz, VoronoiSeeds seeds
    ) {
        if (seeds != null && seeds.isVolume()) {
            return insideVoronoiVolume(spec, lx, ly, lz, seeds.volume());
        }
        return insideVoronoiPlanar(spec, lx, ly, lz, seeds != null ? seeds.planar() : List.of());
    }

    private static boolean insideVoronoiVolume(
            ShapeSpec spec, double lx, double ly, double lz, List<Seed3D> seeds
    ) {
        if (!insideEllipsoid(lx, ly, lz,
                spec.effectiveRadiusX(), spec.effectiveRadiusY(), spec.effectiveRadiusZ())) {
            return false;
        }
        if (seeds == null || seeds.isEmpty()) {
            return false;
        }
        double d1 = Double.MAX_VALUE;
        double d2 = Double.MAX_VALUE;
        for (Seed3D s : seeds) {
            double d = Math.sqrt(
                    (lx - s.x()) * (lx - s.x())
                            + (ly - s.y()) * (ly - s.y())
                            + (lz - s.z()) * (lz - s.z())
            );
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
        return voronoiCellInterior(d1, d2, spec.voronoiEdge());
    }

    private static boolean insideVoronoiPlanar(
            ShapeSpec spec, double lx, double ly, double lz, List<Seed2D> seeds
    ) {
        if (spec.extrudeMode() != ShapeExtrudeMode.PLATE && Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        if (!insideCircle(lx, lz, Math.max(spec.effectiveRadiusX(), spec.effectiveRadiusZ()) + 0.25)) {
            return false;
        }
        if (seeds == null || seeds.isEmpty()) {
            return false;
        }
        double d1 = Double.MAX_VALUE;
        double d2 = Double.MAX_VALUE;
        for (Seed2D s : seeds) {
            double d = Math.hypot(lx - s.x(), lz - s.z());
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
        return voronoiCellInterior(d1, d2, spec.voronoiEdge());
    }

    private static boolean voronoiCellInterior(double d1, double d2, double edge) {
        if (d2 >= Double.MAX_VALUE * 0.5) {
            return true;
        }
        if (edge > 0.05) {
            return (d2 - d1) <= edge + 0.35;
        }
        return d1 * d1 <= d2 * d2 * 0.92;
    }

    private static boolean insideMobius(ShapeSpec spec, double lx, double ly, double lz) {
        double major = spec.effectiveRadiusX();
        double halfW = Math.max(0.75, spec.mobiusWidth() * 0.5);
        double twist = spec.mobiusTwist() > 0 ? spec.mobiusTwist() : 1.0;
        int uSteps = 56;
        for (int i = 0; i < uSteps; i++) {
            double u = (2.0 * Math.PI * i) / uSteps;
            double cu = Math.cos(u);
            double su = Math.sin(u);
            double halfAngle = u * 0.5 * twist;
            double cHalf = Math.cos(halfAngle);
            double sHalf = Math.sin(halfAngle);
            double v;
            if (Math.abs(sHalf) > 0.04) {
                v = ly / sHalf;
            } else {
                v = 0;
            }
            if (Math.abs(v) > halfW + 0.6) {
                continue;
            }
            double px = (major + v * cHalf) * cu;
            double py = v * sHalf;
            double pz = (major + v * cHalf) * su;
            double dist2 = (lx - px) * (lx - px) + (ly - py) * (ly - py) + (lz - pz) * (lz - pz);
            if (dist2 <= 1.35) {
                return true;
            }
        }
        return false;
    }

    private static double heightT(ShapeSpec spec, double ly) {
        if (spec.height() <= 1) {
            return 0;
        }
        return (ly + spec.halfY()) / (spec.height() - 1);
    }

    private static boolean insideExtrudedCircle(ShapeSpec spec, double lx, double ly, double lz, double radius) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        return insideCircle(lx, lz, radius);
    }

    private static boolean insideExtrudedCircleAt(ShapeSpec spec, double lx, double ly, double lz, double radius) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        return insideCircle(lx, lz, Math.max(0, radius));
    }

    private static boolean insideExtrudedEllipse(ShapeSpec spec, double lx, double ly, double lz) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        double rx = spec.effectiveRadiusX();
        double rz = spec.effectiveRadiusZ();
        return (lx * lx) / (rx * rx + 1e-6) + (lz * lz) / (rz * rz + 1e-6) <= 1.05;
    }

    private static boolean insideExtrudedPolygon(
            ShapeSpec spec, double lx, double ly, double lz, int sides, double radius
    ) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        return insideRegularPolygon(lx, lz, sides, radius);
    }

    private static boolean insideExtrudedSector(ShapeSpec spec, double lx, double ly, double lz) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        if (!insideCircle(lx, lz, spec.effectiveRadiusX())) {
            return false;
        }
        double angle = Math.toDegrees(Math.atan2(lz, lx));
        double start = spec.sectorStartDeg();
        double sweep = Math.max(1, spec.sectorSweepDeg());
        double rel = angle - start;
        while (rel < 0) rel += 360;
        while (rel >= 360) rel -= 360;
        return rel <= sweep + 0.5;
    }

    private static boolean insideExtrudedTriangle(ShapeSpec spec, double lx, double ly, double lz) {
        if (Math.abs(ly) > spec.halfY() + 0.25) {
            return false;
        }
        double rx = spec.effectiveRadiusX();
        double rz = spec.effectiveRadiusZ();
        String mode = spec.triangleMode() != null ? spec.triangleMode().toLowerCase() : "right";
        if ("equilateral".equals(mode)) {
            return insideEquilateralTriangle(lx, lz, rx, rz);
        }
        // right triangle anchored at (-rx,-rz) corner
        return lx >= -rx - 0.25 && lz >= -rz - 0.25
                && (lx + rx) / (rx + 1e-6) + (lz + rz) / (rz + 1e-6) <= 1.05;
    }

    private static boolean insideEquilateralTriangle(double lx, double lz, double rx, double rz) {
        double h = Math.min(rz * 2, rx * 1.732);
        if (lz < -rz - 0.25 || lz > h - rz + 0.25) {
            return false;
        }
        double halfBase = (h - (lz + rz)) / 1.732 + rx * 0.5;
        return Math.abs(lx) <= halfBase + 0.25;
    }

    private static boolean insideEllipsoid(double lx, double ly, double lz, double rx, double ry, double rz) {
        return (lx * lx) / (rx * rx + 1e-6)
                + (ly * ly) / (ry * ry + 1e-6)
                + (lz * lz) / (rz * rz + 1e-6) <= 1.05;
    }

    private static boolean insideCircle(double lx, double lz, double radius) {
        return (lx * lx + lz * lz) <= radius * radius + 0.25;
    }

    private static boolean insideRegularPolygon(double lx, double lz, int sides, double radius) {
        double dist = Math.hypot(lx, lz);
        if (dist > radius + 0.5) {
            return false;
        }
        if (dist < 1e-6) {
            return true;
        }
        double angle = Math.atan2(lz, lx);
        double sector = (2.0 * Math.PI) / Math.max(3, sides);
        double boundaryRadius = radius * Math.cos(sector * 0.5) / Math.max(1e-6,
                Math.cos((angle % sector + sector) % sector - sector * 0.5));
        return dist <= boundaryRadius + 0.35;
    }

    private static boolean isInteriorHollow(
            ShapeSpec spec, double lx, double ly, double lz, VoronoiSeeds voronoiSeeds
    ) {
        double t = spec.wallThickness();
        ShapeSpec inner = new ShapeSpec(
                spec.kind(), spec.width(), spec.depth(), spec.height(),
                false, spec.wallThickness(), spec.sides(),
                spec.rotationXDeg(), spec.rotationYDeg(), spec.rotationZDeg(),
                Math.max(0.5, spec.radius() - t),
                Math.max(0.5, spec.topRadius() - t),
                spec.radiusX() > 0 ? Math.max(0.5, spec.radiusX() - t) : 0,
                spec.radiusY() > 0 ? Math.max(0.5, spec.radiusY() - t) : 0,
                spec.radiusZ() > 0 ? Math.max(0.5, spec.radiusZ() - t) : 0,
                spec.sectorStartDeg(), spec.sectorSweepDeg(), spec.triangleMode(),
                spec.extrudeMode(), spec.voronoiCells(), spec.voronoiSeed(),
                spec.voronoiEdge(), spec.mobiusWidth(), spec.mobiusTwist(),
                spec.voronoiDimension()
        );
        return insideCanonical(inner, lx, ly, lz, voronoiSeeds);
    }
}
