package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Structure and explicit circulation requirements, both in emitted world coordinates. */
public record StructureGenerationResult(GeneratedStructure structure,
        List<AssemblyCirculationConstraints.Flight> circulation) {
    public StructureGenerationResult {
        circulation = List.copyOf(circulation);
    }

    /** Isolates one synchronous generation, restoring the enclosing capture on failure too. */
    public static StructureGenerationResult capture(Supplier<GeneratedStructure> generate) {
        var circulation = new ArrayList<AssemblyCirculationConstraints.Flight>();
        GeneratedStructure structure;
        try (var scope = AssemblyCirculationConstraints.captureTo(circulation::addAll)) {
            structure = generate.get();
        }
        return new StructureGenerationResult(structure, circulation);
    }
}
