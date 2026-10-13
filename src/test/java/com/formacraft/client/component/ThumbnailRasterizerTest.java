package com.formacraft.client.component;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.formacraft.client.component.ThumbnailRasterizer.*;

class ThumbnailRasterizerTest {
    private static Texture color(int argb) { return new Texture(1, 1, new int[]{argb}); }
    private static Face front(double height, Texture texture, double offset) {
        // Move along the camera ray without changing the projected position.
        double y = offset * .81116 / .81915;
        return new Face(new Vertex[]{new Vertex(offset, y + height, offset, 0, 0),
                new Vertex(offset, y, offset, 0, 1), new Vertex(offset + 1, y, offset, 1, 1),
                new Vertex(offset + 1, y + height, offset, 1, 0)}, texture, 0xFFFFFF, 1);
    }
    @Test void emptyComponentsHaveNoImage() { assertNull(render(List.of(), 128)); }
    @Test void tallAndWideComponentsFitWithTransparentMargins() {
        for (double height : new double[]{.02, 1, 100}) {
            BufferedImage image = render(List.of(front(height, color(0xFFFFFFFF), 0)), 128);
            int minY = 128, maxY = -1, minX = 128, maxX = -1;
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) if ((image.getRGB(x, y) >>> 24) > 16) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            assertTrue(maxX >= minX); assertTrue(minX >= 7 && maxX <= 120 && minY >= 7 && maxY <= 120);
            assertEquals(127, minX + maxX, 2); assertEquals(127, minY + maxY, 2);
        }
    }
    @Test void opaqueFrontSurfaceOccludesRearIndependentOfInputOrder() {
        Face far = front(1, color(0xFFFF0000), 0), near = front(1, color(0xFF00FF00), 2);
        for (var faces : List.of(List.of(far, near), List.of(near, far))) {
            assertEquals(0xFF00FF00, render(faces, 128).getRGB(64, 64));
        }
    }
    @Test void glassPreservesAlphaWithoutDoublePaintingTriangleDiagonal() {
        BufferedImage image = render(List.of(front(1, color(0x8000FFFF), 0)), 128);
        assertEquals(128, image.getRGB(64, 64) >>> 24, 1);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) assertTrue((image.getRGB(x, y) >>> 24) <= 140);
    }
    @Test void glassBlendsWithOpaqueGeometryBehindIt() {
        var image = render(List.of(front(1, color(0xFFFF0000), 0), front(1, color(0x8000FF00), 2)), 128);
        int pixel = image.getRGB(64, 64);
        assertEquals(255, pixel >>> 24); assertEquals(127, (pixel >> 16) & 255, 2); assertEquals(128, (pixel >> 8) & 255, 2);
    }
    @Test void cutoutPixelsRevealGeometryBehindThem() {
        Texture cutout = new Texture(2, 1, new int[]{0, 0xFF00FF00});
        BufferedImage image = render(List.of(front(1, color(0xFFFF0000), 0), front(1, cutout, 2)), 128);
        int red = 0, green = 0;
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            if (image.getRGB(x, y) == 0xFFFF0000) red++;
            if (image.getRGB(x, y) == 0xFF00FF00) green++;
        }
        assertTrue(red > 1000 && green > 1000);
    }
    @Test void uvCoordinatesRetainTextureDetails() {
        Texture checker = new Texture(2, 2, new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF});
        var image = render(List.of(front(1, checker, 0)), 128);
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) colors.add(image.getRGB(x, y));
        for (int color : checker.argb()) assertTrue(colors.contains(color));
    }
}
