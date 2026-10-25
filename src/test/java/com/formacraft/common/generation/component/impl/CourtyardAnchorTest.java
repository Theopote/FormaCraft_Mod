package com.formacraft.common.generation.component.impl;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CourtyardAnchorTest {
    @Test void loggedCenteredSkywellDoesNotReachSouthWingAtZFive() {
        var court=new Component("COURTYARD_SPACE","compound",new Vec3i(0,0,0),new Dimensions(16,7,1),
                List.of(),Map.of("anchor_mode","center"));
        var blocks=new CourtyardSpaceGenerator().generate(new SemanticComponent("COURTYARD_SPACE",null,court));
        assertEquals(112,blocks.size());
        assertEquals(-3,blocks.stream().mapToInt(b->b.dz()).min().orElseThrow());
        assertEquals(3,blocks.stream().mapToInt(b->b.dz()).max().orElseThrow());
        assertFalse(blocks.stream().anyMatch(b->b.dz()==5));
    }
    @Test void legacyUnspecifiedAnchorStaysAtMinCorner() {
        var court=new Component("COURTYARD_SPACE",null,new Vec3i(10,0,20),new Dimensions(3,3,1),List.of(),Map.of());
        var blocks=new CourtyardSpaceGenerator().generate(new SemanticComponent("COURTYARD_SPACE",null,court));
        assertEquals(10,blocks.stream().mapToInt(b->b.dx()).min().orElseThrow());
        assertEquals(20,blocks.stream().mapToInt(b->b.dz()).min().orElseThrow());
    }
}
