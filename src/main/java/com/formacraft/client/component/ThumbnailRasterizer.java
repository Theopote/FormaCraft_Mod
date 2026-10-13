package com.formacraft.client.component;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;

/** Detached textured triangles with depth testing and alpha; no Minecraft thread dependencies. */
public final class ThumbnailRasterizer {
    private ThumbnailRasterizer() {}
    public record Texture(int width, int height, int[] argb) {}
    public record Vertex(double x, double y, double z, double u, double v) {}
    public record Face(Vertex[] vertices, Texture texture, int tint, double light) {}
    private record Projected(double x, double y, double depth, double u, double v) {}
    private static double px(Vertex v) { return (v.x - v.z) * Math.sqrt(.5); }
    private static double py(Vertex v) { return (v.x + v.z) * .40558 - v.y * .81915; }
    private static double depth(Vertex v) { return (v.x + v.z) * .57923 + v.y * .57358; }

    public static BufferedImage render(List<Face> faces, int size) {
        if (faces == null || faces.isEmpty()) return null;
        int resolution = size * 2;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (Face face : faces) for (Vertex v : face.vertices) {
            minX = Math.min(minX, px(v)); maxX = Math.max(maxX, px(v));
            minY = Math.min(minY, py(v)); maxY = Math.max(maxY, py(v));
        }
        double scale = resolution * .86 / Math.max(1e-6, Math.max(maxX - minX, maxY - minY));
        double centerX = (minX + maxX) / 2, centerY = (minY + maxY) / 2;
        BufferedImage image = new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_ARGB);
        double[] depths = new double[resolution * resolution]; Arrays.fill(depths, Double.NEGATIVE_INFINITY);
        List<Face> sorted = new ArrayList<>(faces);
        sorted.sort(Comparator.comparingDouble(f -> Arrays.stream(f.vertices).mapToDouble(ThumbnailRasterizer::depth).average().orElse(0)));
        // Solid/cutout first, then translucent pixels back to front against solid depth.
        for (int pass = 0; pass < 2; pass++) for (Face face : sorted) {
            Projected[] vertices = Arrays.stream(face.vertices).map(v -> new Projected(
                    resolution / 2.0 + (px(v) - centerX) * scale,
                    resolution / 2.0 + (py(v) - centerY) * scale, depth(v), v.u, v.v)).toArray(Projected[]::new);
            triangle(image, depths, vertices[0], vertices[1], vertices[2], face, pass);
            triangle(image, depths, vertices[0], vertices[2], vertices[3], face, pass);
        }
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, size, size, null); g.dispose(); return result;
    }

    private static double edge(Projected a, Projected b, double x, double y) {
        return (x - a.x) * (b.y - a.y) - (y - a.y) * (b.x - a.x);
    }

    private static void triangle(BufferedImage image, double[] depths, Projected a, Projected b, Projected c, Face face, int pass) {
        double area = edge(a, b, c.x, c.y);
        if (area <= 1e-9) return;
        int size = image.getWidth();
        int minX = Math.max(0, (int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))));
        int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))));
        int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))));
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double ea = edge(b, c, x + .5, y + .5), eb = edge(c, a, x + .5, y + .5), ec = edge(a, b, x + .5, y + .5);
            double epsilon = area * 1e-10;
            if (!inside(ea, epsilon, b, c) || !inside(eb, epsilon, c, a) || !inside(ec, epsilon, a, b)) continue;
            double wa = ea / area, wb = eb / area, wc = ec / area;
            double d = a.depth * wa + b.depth * wb + c.depth * wc;
            if (d < depths[y * size + x] - 1e-7) continue;
            Texture t = face.texture;
            int tx = Math.max(0, Math.min(t.width - 1, (int) ((a.u * wa + b.u * wb + c.u * wc) * t.width)));
            int ty = Math.max(0, Math.min(t.height - 1, (int) ((a.v * wa + b.v * wb + c.v * wc) * t.height)));
            int color = t.argb[ty * t.width + tx], alpha = color >>> 24;
            if (alpha == 0 || (pass == 0 ? alpha < 250 : alpha >= 250)) continue;
            int r = channel(color, face.tint, 16, face.light), g = channel(color, face.tint, 8, face.light), blue = channel(color, face.tint, 0, face.light);
            if (pass == 0) { image.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | blue); depths[y * size + x] = d; }
            else {
                int old = image.getRGB(x, y); double sa = alpha / 255.0, da = (old >>> 24) / 255.0;
                double out = sa + da * (1 - sa);
                int rr = (int) ((r * sa + ((old >> 16) & 255) * da * (1 - sa)) / out);
                int gg = (int) ((g * sa + ((old >> 8) & 255) * da * (1 - sa)) / out);
                int bb = (int) ((blue * sa + (old & 255) * da * (1 - sa)) / out);
                image.setRGB(x, y, (int) (out * 255) << 24 | rr << 16 | gg << 8 | bb);
            }
        }
    }
    private static boolean ownsEdge(Projected a, Projected b) { return b.y > a.y || (b.y == a.y && b.x < a.x); }
    private static boolean inside(double edge, double epsilon, Projected a, Projected b) {
        return edge > epsilon || (edge >= -epsilon && ownsEdge(a, b));
    }
    private static int channel(int color, int tint, int shift, double light) {
        return Math.min(255, (int) (((color >> shift) & 255) * ((tint >> shift) & 255) / 255.0 * light));
    }
}
