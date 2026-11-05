package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TibetanStupaMorphologyTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void typologyIdIsDistinctFromDenseEaves() {
        assertEquals("tibetan_stupa", TibetanStupaBuilder.TYPOLOGY_ID);
        assertNotEquals(DenseEavesPagodaBuilder.TYPOLOGY_ID, TibetanStupaBuilder.TYPOLOGY_ID);
    }

    @Test
    void morphologyExceptionCodeIsStupaNotPagoda() {
        TypologyGenerationException ex = new TypologyGenerationException(
                TibetanStupaBuilder.TYPOLOGY_ID,
                "E_STUPA_MORPHOLOGY",
                "missing anda",
                java.util.List.of("hasAnda=false"));
        assertEquals("E_STUPA_MORPHOLOGY", ex.code());
        assertEquals("tibetan_stupa", ex.typologyId());
    }

    @Test
    void andaBulbIsWiderThanBaseTopTerrace() {
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = TibetanStupaBuilder.generate(
                Map.of(
                        "baseWidth", 9,
                        "height", 24,
                        "anda_scale", 1.35,
                        "chhatra_count", 5
                ),
                origin,
                null,
                null);

        int baseTopHalf = 0;
        int andaMaxRadial = 0;
        int baseTiersEndY = origin.getY() + 6; // 3 terraces × 2
        for (PlannedBlock b : structure.getBlocks()) {
            if (b.getTargetState() == null || b.getTargetState().isAir()) continue;
            int dy = b.getPos().getY() - origin.getY();
            int radial = Math.max(
                    Math.abs(b.getPos().getX() - origin.getX()),
                    Math.abs(b.getPos().getZ() - origin.getZ()));
            if (dy >= 4 && dy < 6) {
                baseTopHalf = Math.max(baseTopHalf, radial);
            }
            if (dy >= 6 && dy < 6 + 12) {
                andaMaxRadial = Math.max(andaMaxRadial, radial);
            }
        }
        assertTrue(andaMaxRadial > baseTopHalf,
                "覆钵 equator should outspan top base terrace (anda=" + andaMaxRadial
                        + " baseTop=" + baseTopHalf + ")");
    }
}
