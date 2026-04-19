package demo.validator.exception;

/**
 * Thrown when XML validation cannot be performed due to a technical error,
 * e.g. malformed XML that prevents parsing, or an I/O problem.
 * <p>
 * Schema-validation errors (wrong structure) are NOT exceptions –
 * they are collected into {@link demo.validator.dto.ValidationResponse#errors()}.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
