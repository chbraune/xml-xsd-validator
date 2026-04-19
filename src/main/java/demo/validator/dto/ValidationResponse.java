package demo.validator.dto;

import java.util.List;

/**
 * Response DTO for XML/XSD validation.
 *
 * @param valid  {@code true} when the XML is schema-valid
 * @param errors structured validation errors; empty list when valid
 */
public record ValidationResponse(boolean valid, List<ValidationError> errors) {

    public static ValidationResponse ok() {
        return new ValidationResponse(true, List.of());
    }

    public static ValidationResponse failed(List<ValidationError> errors) {
        return new ValidationResponse(false, List.copyOf(errors));
    }
}
