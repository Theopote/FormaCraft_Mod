package com.formacraft.server.init;

import com.formacraft.common.typology.TypologyInterpreter;
import com.formacraft.common.typology.TypologyInterpreterRegistry;
import com.formacraft.server.generation.typology.interpreter.*;

import java.util.List;

/** Runtime-owned registration; invoked for integrated and dedicated servers. */
public final class TypologySystemInitializer {
    private static boolean initialized;

    private TypologySystemInitializer() {}

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        List<TypologyInterpreter> builtIns = List.of(
                new DenseEavesPagodaInterpreter(),
                new TailiangTimberHallInterpreter(),
                new RadialTerraceHallInterpreter(),
                new StadiumBowlInterpreter(),
                new SuspensionBridgeInterpreter(),
                new GothicCathedralHallInterpreter(),
                new CourtyardCompoundInterpreter(),
                new RadialFortressInterpreter(),
                new SetbackTowerInterpreter(),
                new TieredMountainPalaceInterpreter());
        for (TypologyInterpreter interpreter : builtIns) {
            // Do not overwrite an interpreter registered by an extension before startup.
            if (!TypologyInterpreterRegistry.has(interpreter.typologyId())) {
                TypologyInterpreterRegistry.register(interpreter);
            }
        }
        initialized = true;
    }
}
