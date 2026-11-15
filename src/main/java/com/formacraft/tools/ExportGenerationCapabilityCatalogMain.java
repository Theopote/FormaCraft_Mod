package com.formacraft.tools;

import com.formacraft.common.generation.capability.GenerationCapabilityCatalog;

import java.nio.file.Path;

/**
 * Dev utility: regenerate {@code assets/formacraft/generation_capability_catalog.json}
 * from the live Java registries (component generators, roof catalog, typology registry).
 *
 * <p>Run: {@code ./gradlew exportGenerationCapabilityCatalog}
 *
 * <p>The Python backend reads this exact file; keeping it generated avoids the
 * hand-maintained table drifting away from the Java runtime.
 */
public final class ExportGenerationCapabilityCatalogMain {

    private ExportGenerationCapabilityCatalogMain() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of("src/main/resources/assets/formacraft/generation_capability_catalog.json");
        if (args.length > 0 && !args[0].isBlank()) {
            out = Path.of(args[0]);
        }
        GenerationCapabilityCatalog.writeToPath(out);
        System.out.println("Wrote " + out.toAbsolutePath());
    }
}
