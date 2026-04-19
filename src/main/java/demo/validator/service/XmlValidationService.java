package demo.validator.service;

import demo.validator.dto.ValidationResponse;
import demo.validator.exception.ValidationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Validates XML content against the bundled {@code classpath:xsd/order.xsd}.
 *
 * <p>Thread-safety: The {@link Schema} instance is thread-safe and created once
 * at startup. A new {@link Validator} is created per request, as required by the
 * JAXP specification.
 */
@Service
public class XmlValidationService {

    private static final String XSD_PATH = "xsd/order.xsd";

    private final Schema schema;

    public XmlValidationService() {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            ClassPathResource xsdResource = new ClassPathResource(XSD_PATH);
            this.schema = factory.newSchema(new StreamSource(xsdResource.getInputStream()));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException(
                    "Failed to load XSD schema from classpath:" + XSD_PATH, e);
        }
    }

    /**
     * Validates the given XML string against the order XSD.
     *
     * @param xmlContent raw XML to validate (must not be {@code null})
     * @return a {@link ValidationResponse} – never {@code null}
     * @throws ValidationException if the XML cannot be parsed at all (technical error)
     */
    public ValidationResponse validate(String xmlContent) {
        List<String> errors = new ArrayList<>();

        Validator validator = schema.newValidator();
        validator.setErrorHandler(new CollectingErrorHandler(errors));

        try {
            validator.validate(new StreamSource(new StringReader(xmlContent)));
        } catch (SAXParseException e) {
            // Already recorded by the fatalError handler – just stop here
        } catch (SAXException e) {
            throw new ValidationException("XML cannot be parsed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ValidationException("I/O error during validation: " + e.getMessage(), e);
        }

        return errors.isEmpty() ? ValidationResponse.ok() : ValidationResponse.failed(errors);
    }

    // ---------------------------------------------------------------------------

    /** Collects all SAX errors into the given list instead of throwing immediately. */
    private record CollectingErrorHandler(List<String> errors) implements ErrorHandler {

        @Override
        public void warning(SAXParseException e) {
            errors.add(format("WARNING", e));
        }

        @Override
        public void error(SAXParseException e) {
            errors.add(format("ERROR", e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            errors.add(format("FATAL", e));
            throw e; // fatal → stop parsing
        }

        private static String format(String level, SAXParseException e) {
            return "[%s] line %d, col %d: %s"
                    .formatted(level, e.getLineNumber(), e.getColumnNumber(), e.getMessage());
        }
    }
}
