package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.List;

/**
 * Macro application result for extra.assembly.
 *
 * applied: normalized+macro-applied object (Map/List primitives).
 * issues: WARNING issues describing what was applied (path + code + message).
 */
public record AssemblyMacroApplyResult(Object applied, List<AssemblyValidationIssue> issues) {}


