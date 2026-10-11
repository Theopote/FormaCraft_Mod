package com.formacraft.common.component;

import com.formacraft.common.component.validate.ValidationResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 构件验证失败（ERROR 级别），禁止持久化。
 */
public final class InvalidComponentException extends RuntimeException {
    private final List<ValidationResult.Issue> errors;

    public InvalidComponentException(List<ValidationResult.Issue> errors) {
        super(formatMessage(errors));
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public InvalidComponentException(String message) {
        super(message);
        this.errors = List.of();
    }

    public List<ValidationResult.Issue> errors() {
        return errors;
    }

    private static String formatMessage(List<ValidationResult.Issue> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Invalid component";
        }
        return "Invalid component: " + errors.stream()
                .limit(5)
                .map(Object::toString)
                .collect(Collectors.joining("; "));
    }
}
