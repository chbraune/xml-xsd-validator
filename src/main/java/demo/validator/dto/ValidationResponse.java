package demo.validator.dto;

import java.util.List;

/**
 * Response DTO for XML/XSD validation.
 *
 * @param valid  {@code true} when the XML is schema-valid
 * @param errors human-readable error messages; empty list when valid
 */
public record ValidationResponse(boolean valid, List<String> errors) {

    public static ValidationResponse ok() {
        return new ValidationResponse(true, List.of());
    }

    public static ValidationResponse failed(List<String> errors) {
        return new ValidationResponse(false, List.copyOf(errors));
    }
}
