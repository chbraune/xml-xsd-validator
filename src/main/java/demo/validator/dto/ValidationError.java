package demo.validator.dto;

/**
 * A single structured validation error.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>{@code field} – XPath-like path to the offending element, e.g.
 *       {@code order.items.item[0].price}. May be {@code null} when the error
 *       cannot be attributed to a specific field (e.g. structural errors on the
 *       parent element where a required child is missing).</li>
 *   <li>{@code value} – the text content that failed validation, e.g. {@code "abc"}
 *       when an integer was expected. {@code null} when no value is available
 *       (missing elements, wrong ordering, attribute errors not yet supported).</li>
 *   <li>{@code message} – always non-null human-readable description of the problem.</li>
 * </ul>
 *
 * @param field   field path or {@code null} if not determinable
 * @param value   offending value or {@code null} if not applicable
 * @param message description of the error (never {@code null})
 */
public record ValidationError(String field, String value, String message) {

    public ValidationError {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
    }

    /** Factory for an error where field and value are known. */
    public static ValidationError of(String field, String value, String message) {
        return new ValidationError(field, value, message);
    }

    /** Factory for an error where only the field path is known (value not applicable). */
    public static ValidationError ofField(String field, String message) {
        return new ValidationError(field, null, message);
    }

    /** Factory for an error that cannot be attributed to a specific field. */
    public static ValidationError ofMessage(String message) {
        return new ValidationError(null, null, message);
    }
}
