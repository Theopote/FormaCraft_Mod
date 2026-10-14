package com.formacraft.common.component;

/** One diagnostic attached to a {@link ComponentGenerationOutcome}. */
public record GenerationDiagnostic(String code, String message) {
    public GenerationDiagnostic {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }
}
