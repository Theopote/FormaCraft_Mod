package com.formacraft.common.network;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IncrementalPrimitivePolicyTest {
    private Component box(Dimensions d,Map<String,Object> p) {
        return new Component("PRIMITIVE","cabin_slot",new Vec3i(4,1,-3),d,List.of(),p);
    }
    @Test void loggedGlassWindowIsAllowed() {
        assertTrue(IncrementalPrimitivePolicy.allows(box(new Dimensions(1,2,2),Map.of("kind","box","material","minecraft:glass"))));
    }
    @Test void solidBuildingsAndOperationsAreRejected() {
        assertFalse(IncrementalPrimitivePolicy.allows(box(new Dimensions(7,7,5),Map.of("kind","box","material","minecraft:glass"))));
        assertFalse(IncrementalPrimitivePolicy.allows(box(new Dimensions(1,2,2),Map.of("kind","box","material","minecraft:stone"))));
        assertFalse(IncrementalPrimitivePolicy.allows(box(new Dimensions(1,2,2),Map.of("kind","box","material","minecraft:glass","subtract",Map.of()))));
    }
}
