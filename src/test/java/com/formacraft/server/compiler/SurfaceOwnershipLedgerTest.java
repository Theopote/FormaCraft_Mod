package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceOwnershipLedgerTest {
    private Component component(String type, Map<String,Object> params) {
        return new Component(type, "main", new Vec3i(0,0,0), new Dimensions(5,5,8), List.of(), params);
    }
    private BlockPatch place(String block) { return new BlockPatch(BlockPatch.PLACE, 10,3,20, block); }
    private BlockPatch remove() { return new BlockPatch(BlockPatch.REMOVE, 10,3,20,"minecraft:air"); }
    private SurfaceOwnershipLedger ledger(GeneratedSurfaceCapture.Role role) {
        var ledger = new SurfaceOwnershipLedger();
        ledger.accept(component("MASS_MAIN", Map.of("component_id","house")), List.of(place("minecraft:stone_bricks")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0,1,0), role)), new BlockPos(10,2,20));
        return ledger;
    }
    @Test void decorationCannotRemoveSurfaceButMayReplaceMaterialOrFinishTemporaryRemoval() {
        var ledger = ledger(GeneratedSurfaceCapture.Role.WALL);
        var decor = component("DECOR_DETAIL", Map.of("component_id","trim"));
        var conflict = ledger.check(decor, List.of(remove())).orElseThrow();
        assertEquals("house", conflict.surface().owner()); assertEquals("trim", conflict.writer());
        assertTrue(ledger.check(decor, List.of(place("minecraft:air"))).isPresent());
        assertTrue(ledger.check(decor, List.of(remove(), place("minecraft:mossy_stone_bricks"))).isEmpty());
    }
    @Test void explicitOpeningHostMustOwnWallAndCannotRemoveRoof() {
        var opening = component("FACADE_WINDOWS", Map.of("host_id","house"));
        assertTrue(ledger(GeneratedSurfaceCapture.Role.WALL).check(opening, List.of(remove())).isEmpty());
        assertTrue(ledger(GeneratedSurfaceCapture.Role.ROOF).check(opening, List.of(remove())).isPresent());
        assertTrue(ledger(GeneratedSurfaceCapture.Role.WINDOW).check(component("ENTRANCE", Map.of("host_id","other")), List.of(remove())).isPresent());
        assertTrue(ledger(GeneratedSurfaceCapture.Role.WALL).check(component("ENTRANCE", Map.of()), List.of(remove())).isEmpty());
    }
    @Test void acceptedDoorwayIsNotRestoredOrReportedAsDecorativeDestruction() {
        var ledger = ledger(GeneratedSurfaceCapture.Role.WALL);
        var entry = component("ENTRANCE", Map.of("host_id","house"));
        ledger.accept(entry, List.of(remove()), List.of(), BlockPos.ORIGIN);
        assertTrue(ledger.check(component("DECOR_DETAIL", Map.of()), List.of(remove())).isEmpty());
    }
}
