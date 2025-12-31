package com.formacraft.server.assembly.validation;

import java.util.List;

/**
 * Normalization result for extra.assembly.
 * <p>
 * normalized: a deep-copied and canonicalized object graph (Map/List primitives).
 * issues: WARNING issues describing what was changed (path + code + message).
 */
public record AssemblySpecNormalizeResult(Object normalized, List<AssemblyValidationIssue> issues) {}


