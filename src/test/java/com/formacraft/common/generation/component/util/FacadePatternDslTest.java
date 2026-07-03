package com.formacraft.common.generation.component.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formacraft.common.generation.component.util.FacadePatternDsl.Cell;
import org.junit.jupiter.api.Test;

/**
 * 纯函数回归：{@link FacadePatternDsl#cellAt} 的确定性与关键图案不变量。
 * 无 Minecraft / LLM 依赖。
 */
class FacadePatternDslTest {

    @Test
    void blankOrSolidPatternIsAllWall() {
        assertSame(Cell.WALL, FacadePatternDsl.cellAt(null, 3, 3, 10, 10));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("", 3, 3, 10, 10));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("none", 3, 3, 10, 10));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("solid", 3, 3, 10, 10));
    }

    @Test
    void edgesStaySolidForAnyPattern() {
        for (String p : new String[] {"lattice", "diagrid", "checker", "rose", "arch"}) {
            assertSame(Cell.WALL, FacadePatternDsl.cellAt(p, 0, 5, 12, 12), p + " left edge");
            assertSame(Cell.WALL, FacadePatternDsl.cellAt(p, 5, 0, 12, 12), p + " bottom edge");
            assertSame(Cell.WALL, FacadePatternDsl.cellAt(p, 11, 5, 12, 12), p + " right edge");
            assertSame(Cell.WALL, FacadePatternDsl.cellAt(p, 5, 11, 12, 12), p + " top edge");
        }
    }

    @Test
    void deterministicForSameInputs() {
        for (String p : new String[] {"lattice", "diagrid", "checker", "rose", "arch"}) {
            assertEquals(FacadePatternDsl.cellAt(p, 4, 3, 12, 12),
                    FacadePatternDsl.cellAt(p, 4, 3, 12, 12), p);
        }
    }

    @Test
    void latticeCarvesOddOddHoles() {
        // holeU=(u%2==1), holeV=(v%2==1) => AIR
        assertSame(Cell.AIR, FacadePatternDsl.cellAt("lattice", 3, 3, 10, 10));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("lattice", 2, 3, 10, 10));
    }

    @Test
    void checkerAlternatesBlocks() {
        // cut = ((u/2)+(v/2))%2==0
        assertSame(Cell.AIR, FacadePatternDsl.cellAt("checker", 2, 2, 12, 12));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("checker", 4, 2, 12, 12));
    }

    @Test
    void roseHasAirCoreAndFrameRing() {
        // odd square so the centre lands on a cell
        assertSame(Cell.AIR, FacadePatternDsl.cellAt("rose", 5, 5, 11, 11));
        // somewhere between core and wall there must be a FRAME ring cell
        boolean sawFrame = false;
        for (int u = 1; u < 10 && !sawFrame; u++) {
            for (int v = 1; v < 10; v++) {
                if (FacadePatternDsl.cellAt("rose", u, v, 11, 11) == Cell.FRAME) {
                    sawFrame = true;
                    break;
                }
            }
        }
        assertTrue(sawFrame, "rose pattern should produce a frame ring");
    }

    @Test
    void archProducesOpeningsAndCrown() {
        // bay=4, local u%4 in {1,2} is inside a bay
        assertSame(Cell.AIR, FacadePatternDsl.cellAt("arch", 1, 3, 12, 10));
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("arch", 3, 3, 12, 10)); // local==3 => not in bay
        assertSame(Cell.FRAME, FacadePatternDsl.cellAt("arch", 1, 8, 12, 10)); // crown near top
    }

    @Test
    void diagridMixesFrameAirWall() {
        assertSame(Cell.FRAME, FacadePatternDsl.cellAt("diagrid", 3, 3, 12, 12)); // s%3==0
        assertSame(Cell.AIR, FacadePatternDsl.cellAt("diagrid", 4, 3, 12, 12));   // s%3==1 && floorMod(d,3)==1
    }

    @Test
    void unknownPatternFallsBackToWall() {
        assertSame(Cell.WALL, FacadePatternDsl.cellAt("totally-unknown", 4, 4, 12, 12));
    }
}
