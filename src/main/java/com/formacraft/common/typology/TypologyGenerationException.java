package com.formacraft.common.typology;

import java.util.List;

/**
 * Thrown when a typology interpreter produces an explicit generation failure
 * (as opposed to "unsupported / no patches"). Upstream routers must convert this
 * into a user-visible compile/preview error — never treat it as an empty success.
 */
public final class TypologyGenerationException extends RuntimeException {

    private final String typologyId;
    private final String code;
    private final List<String> diagnostics;

    public TypologyGenerationException(String typologyId, String code, String message, List<String> diagnostics) {
        super(message == null ? code : message);
        this.typologyId = typologyId == null ? "" : typologyId;
        this.code = code == null ? "TYPOLOGY_FAILED" : code;
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public String typologyId() {
        return typologyId;
    }

    public String code() {
        return code;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }
}
