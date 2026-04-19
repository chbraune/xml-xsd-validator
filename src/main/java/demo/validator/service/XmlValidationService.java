package demo.validator.service;

import demo.validator.dto.ValidationError;
import demo.validator.dto.ValidationResponse;
import demo.validator.exception.ValidationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * Validates XML content against the bundled {@code classpath:xsd/order.xsd}.
 *
 * <h2>Design</h2>
 * <p>Uses a SAX pipeline with JAXP's {@link ValidatorHandler} (a SAX {@link ContentHandler}
 * that performs schema validation inline). An additional {@link PathTrackingHandler} sits
 * in front and intercepts every SAX event to maintain a semantic element-path stack
 * (e.g. {@code order.items.item[1].price}) without relying on line/column positions.
 * This makes the implementation robust against both formatted and minified XML.
 *
 * <h2>Thread-safety</h2>
 * <p>The {@link Schema} instance is thread-safe and created once at startup.
 * All mutable state ({@link ValidatorHandler}, path stack, etc.) is local to each
 * {@link #validate(String)} invocation.
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li>When a required child element is missing, the XSD validator fires the error
 *       at the <em>parent's</em> end tag. The reported {@code field} path therefore
 *       points to the parent, not the missing child. The error {@code message} still
 *       names the missing element.</li>
 *   <li>{@code value} is {@code null} for structural errors (missing or out-of-order
 *       elements) – there is no wrong value to report.</li>
 *   <li>A fatal well-formedness error stops parsing immediately; at most one error
 *       is reported.</li>
 * </ul>
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
     * @param xmlContent raw XML (formatted or minified); must not be {@code null}
     * @return a {@link ValidationResponse} – never {@code null}
     * @throws ValidationException if the XML cannot be processed at all (technical error)
     */
    public ValidationResponse validate(String xmlContent) {
        List<ValidationError> errors = new ArrayList<>();

        ValidatorHandler validatorHandler = schema.newValidatorHandler();
        PathTrackingHandler pathTracker = new PathTrackingHandler(validatorHandler);
        validatorHandler.setErrorHandler(new CollectingErrorHandler(errors, pathTracker));

        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setNamespaceAware(true); // required for XSD validation
            XMLReader reader = parserFactory.newSAXParser().getXMLReader();
            reader.setContentHandler(pathTracker);
            reader.parse(new InputSource(new StringReader(xmlContent)));

        } catch (SAXParseException e) {
            // Fatal well-formedness error from the XML parser (not the schema validator).
            // The fatalError() handler may have already recorded it; avoid duplicates.
            if (errors.isEmpty()) {
                errors.add(ValidationError.ofMessage("XML is not well-formed: " + e.getMessage()));
            }
        } catch (SAXException | ParserConfigurationException e) {
            throw new ValidationException("XML cannot be parsed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ValidationException("I/O error during validation: " + e.getMessage(), e);
        }

        return errors.isEmpty() ? ValidationResponse.ok() : ValidationResponse.failed(errors);
    }

    // =========================================================================
    // SAX pipeline: PathTrackingHandler → ValidatorHandler
    // =========================================================================

    /**
     * SAX {@link ContentHandler} that:
     * <ol>
     *   <li>Delegates all events to the wrapped {@link ValidatorHandler}.</li>
     *   <li>Maintains an element-path stack (e.g. {@code order.items.item[1].price})
     *       based on element names and sibling indices – independent of line/column.</li>
     *   <li>Buffers the current element's text content so the error handler can
     *       report the offending value.</li>
     * </ol>
     */
    private static final class PathTrackingHandler extends DefaultHandler {

        private final ContentHandler delegate;

        /** Path from root to the current element; front = root, back = current. */
        private final ArrayDeque<PathSegment> pathStack = new ArrayDeque<>();

        /**
         * Stack of sibling-count maps – one map per nesting level.
         * Each map records how many times a child element name has been seen
         * under the current parent, so repeated elements can be indexed.
         */
        private final ArrayDeque<Map<String, Integer>> siblingCounters = new ArrayDeque<>();

        /** Accumulates PCDATA for the element currently being parsed. */
        private final StringBuilder textBuffer = new StringBuilder();

        PathTrackingHandler(ContentHandler delegate) {
            this.delegate = delegate;
        }

        // ------------------------------------------------------------------
        // State accessors (called by CollectingErrorHandler)
        // ------------------------------------------------------------------

        /**
         * Returns the dot-notation field path for the current element,
         * e.g. {@code order.items.item[1].price}.
         * Index {@code [0]} is omitted (first sibling has no bracket).
         * Returns {@code null} when the path stack is empty.
         */
        String currentPath() {
            if (pathStack.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (PathSegment seg : pathStack) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(seg.name());
                if (seg.index() > 0) sb.append('[').append(seg.index()).append(']');
            }
            return sb.toString();
        }

        /**
         * Returns the trimmed text content of the current element,
         * or {@code null} when the element has no (non-whitespace) text content.
         */
        String currentTextValue() {
            String text = textBuffer.toString().strip();
            return text.isEmpty() ? null : text;
        }

        // ------------------------------------------------------------------
        // SAX events
        // ------------------------------------------------------------------

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            textBuffer.setLength(0); // reset for the new element

            // Initialise root-level sibling counter on first element
            if (siblingCounters.isEmpty()) {
                siblingCounters.push(new HashMap<>());
            }
            Map<String, Integer> counters = siblingCounters.peek();
            int siblingIndex = counters.getOrDefault(localName, 0);
            counters.put(localName, siblingIndex + 1);

            pathStack.addLast(new PathSegment(localName, siblingIndex));
            siblingCounters.push(new HashMap<>()); // fresh counter for this element's children

            delegate.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            // Delegate BEFORE popping – errors fired here still read the correct path/value
            delegate.endElement(uri, localName, qName);
            pathStack.pollLast();
            siblingCounters.pop();
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            textBuffer.append(ch, start, length);
            delegate.characters(ch, start, length);
        }

        @Override public void startDocument() throws SAXException          { delegate.startDocument(); }
        @Override public void endDocument() throws SAXException            { delegate.endDocument(); }
        @Override public void setDocumentLocator(Locator l)                { delegate.setDocumentLocator(l); }
        @Override public void startPrefixMapping(String p, String u) throws SAXException { delegate.startPrefixMapping(p, u); }
        @Override public void endPrefixMapping(String p) throws SAXException             { delegate.endPrefixMapping(p); }
        @Override public void ignorableWhitespace(char[] ch, int s, int l) throws SAXException { delegate.ignorableWhitespace(ch, s, l); }
        @Override public void processingInstruction(String t, String d) throws SAXException    { delegate.processingInstruction(t, d); }
        @Override public void skippedEntity(String name) throws SAXException                   { delegate.skippedEntity(name); }

        private record PathSegment(String name, int index) {}
    }

    // =========================================================================
    // Error handler
    // =========================================================================

    private record CollectingErrorHandler(
            List<ValidationError> errors,
            PathTrackingHandler tracker
    ) implements ErrorHandler {

        @Override
        public void warning(SAXParseException e) {
            errors.add(buildError(e));
        }

        @Override
        public void error(SAXParseException e) {
            errors.add(buildError(e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            // Fatal schema errors: record and stop parsing
            errors.add(ValidationError.ofMessage(e.getMessage()));
            throw e;
        }

        private ValidationError buildError(SAXParseException e) {
            return ValidationError.of(
                    tracker.currentPath(),
                    tracker.currentTextValue(),
                    e.getMessage()
            );
        }
    }
}
