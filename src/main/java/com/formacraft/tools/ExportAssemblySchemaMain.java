package com.formacraft.tools;

import com.formacraft.server.assembly.schema.AssemblySchemaExporter;

import java.nio.file.Path;

/**
 * Dev utility: regenerate assets/formacraft/ai-assembly-schema.json from runtime MetaAssembly metadata.
 *
 * Run: ./gradlew exportAssemblySchema
 */
public final class ExportAssemblySchemaMain {

    private ExportAssemblySchemaMain() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of("src/main/resources/assets/formacraft/ai-assembly-schema.json");
        if (args.length > 0 && !args[0].isBlank()) {
            out = Path.of(args[0]);
        }
        AssemblySchemaExporter.SchemaSnapshot snap = AssemblySchemaExporter.exportRuntime();
        AssemblySchemaExporter.writeToPath(out, snap);
        System.out.println("Wrote " + out.toAbsolutePath()
                + " (components=" + snap.components().size()
                + ", presets=" + snap.presets().size()
                + ", ops=" + snap.ops().size() + ")");
    }
}
