package com.formacraft.server.dsl;

import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.server.assembly.AssemblySpec;
import java.util.*;

/** Consumes only the authority's typed compilation, not client-supplied op metadata. */
public final class FbdAssemblyAdapter {
    private FbdAssemblyAdapter() {}

    public static AssemblySpec spec(FbdCompiler.Compilation compilation) {
        var ops = new ArrayList<Map<String, Object>>();
        for (var command : compilation.commands()) {
            var op = new LinkedHashMap<String, Object>(command.op());
            op.put("_fbd_node", command.node());
            op.put("_fbd_instance", command.instance());
            op.put("_fbd_path", command.path());
            ops.add(op);
        }
        return AssemblySpec.of(null, null, ops);
    }
}
