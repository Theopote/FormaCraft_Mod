package com.formacraft.server.assembly.validation;

/**
 * A single validation issue with a JSON-like path for pinpointing errors in extra.assembly.
 */
public record AssemblyValidationIssue(String path, Severity severity, String code, String message) {
    public enum Severity { ERROR, WARNING }
}


