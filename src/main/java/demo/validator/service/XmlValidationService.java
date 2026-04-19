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
 * <h2>Two-phase design</h2>
 * <ol>
 *   <li><b>Pre-pass</b> (no schema validation): parses the XML once to collect a
 *       {@code rawPath → maxSiblingIndex} map. This tells us which element names
 *       appear more than once at each path level.</li>
 *   <li><b>Validation pass</b>: parses the XML again with JAXP's {@link ValidatorHandler}.
 *       The pre-computed map is used to show {@code [n]} only for repeated element names,
 *       including {@code [0]} for the first occurrence – ensuring consistent indexing
 *       regardless of when errors are detected.</li>
 * </ol>
 *
 * <h2>Deduplication</h2>
 * <p>Xerces fires two error codes per type violation ({@code cvc-datatype-valid} +
 * {@code cvc-type.3.1.3}). The {@link CollectingErrorHandler} deduplicates by
 * {@code (field, value)} and keeps only the first error per combination.
 * Structural errors (missing elements, wrong order) where {@code value} is {@code null}
 * are never deduplicated.
 *
 * <h2>Thread-safety</h2>
 * <p>The {@link Schema} instance is thread-safe and created once at startup.
 * All mutable state is local to each {@link #validate(String)} invocation.
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li>When a required child is missing, the XSD validator fires the error at the
 *       <em>parent's</em> end tag. The {@code field} path points to the parent; the
 *       {@code message} still names the missing element.</li>
 *   <li>Two SAX parses per call. Negligible for typical payloads (KB range).</li>
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
     * @throws ValidationException if the XML cannot be processed due to a technical error
     */
    public ValidationResponse validate(String xmlContent) {

        // ── Phase 1: pre-pass ────────────────────────────────────────────────
        Map<String, Integer> maxSiblingByPath;
        try {
            maxSiblingByPath = prePass(xmlContent);
        } catch (SAXParseException e) {
            // XML is not well-formed → skip Phase 2
            return ValidationResponse.failed(
                    List.of(ValidationError.ofMessage("XML is not well-formed: " + e.getMessage())));
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new ValidationException("XML cannot be parsed: " + e.getMessage(), e);
        }

        // ── Phase 2: schema validation ───────────────────────────────────────
        List<ValidationError> errors = new ArrayList<>();
        ValidatorHandler validatorHandler = schema.newValidatorHandler();
        PathTrackingHandler pathTracker = new PathTrackingHandler(validatorHandler, maxSiblingByPath);
        validatorHandler.setErrorHandler(new CollectingErrorHandler(errors, pathTracker));

        try {
            XMLReader reader = namespacedReader();
            reader.setContentHandler(pathTracker);
            reader.parse(new InputSource(new StringReader(xmlContent)));
        } catch (SAXParseException e) {
            // Propagated from fatalError() – already recorded, or a well-formedness error
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
    // Shared utility
    // =========================================================================

    private static XMLReader namespacedReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true); // required for XSD validation
        return factory.newSAXParser().getXMLReader();
    }

    // =========================================================================
    // Phase 1: SiblingIndexCollector
    // =========================================================================

    /**
     * Runs a lightweight, schema-free SAX parse to build a map of
     * {@code rawPath → maxSiblingIndex}.
     *
     * <p>Raw path uses local element names only (no indices), e.g. {@code "order.items.item"}.
     * A value of {@code 0} means the element appears exactly once at that path level;
     * a value of {@code 1} or more means it appears at least twice.
     */
    private static Map<String, Integer> prePass(String xmlContent)
            throws SAXException, ParserConfigurationException, IOException {
        SiblingIndexCollector collector = new SiblingIndexCollector();
        XMLReader reader = namespacedReader();
        reader.setContentHandler(collector);
        reader.parse(new InputSource(new StringReader(xmlContent)));
        return collector.result();
    }

    /**
     * SAX handler (no schema validation) that produces a
     * {@code rawPath → maxSiblingIndex} map for all elements in the document.
     */
    private static final class SiblingIndexCollector extends DefaultHandler {

        /** Element name path from root to current (no indices). */
        private final ArrayDeque<String> nameStack = new ArrayDeque<>();

        /** One sibling counter per nesting level. */
        private final ArrayDeque<Map<String, Integer>> siblingCounters = new ArrayDeque<>();

        /** Result: "order.items.item" → max index seen (0 = unique, 1+ = repeated). */
        private final Map<String, Integer> maxIndex = new HashMap<>();

        Map<String, Integer> result() {
            return Collections.unmodifiableMap(maxIndex);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            if (siblingCounters.isEmpty()) {
                siblingCounters.push(new HashMap<>());
            }
            Map<String, Integer> counters = siblingCounters.peek();
            int idx = counters.getOrDefault(localName, 0);
            counters.put(localName, idx + 1);

            // Key uses the parent name-path (nameStack) + current name – NO indices
            String rawKey = nameStack.isEmpty()
                    ? localName
                    : String.join(".", nameStack) + "." + localName;
            maxIndex.merge(rawKey, idx, Math::max);

            nameStack.addLast(localName);
            siblingCounters.push(new HashMap<>());
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            nameStack.pollLast();
            siblingCounters.pop();
        }
    }

    // =========================================================================
    // Phase 2: PathTrackingHandler → ValidatorHandler
    // =========================================================================

    /**
     * SAX {@link ContentHandler} that:
     * <ol>
     *   <li>Delegates all events to the wrapped {@link ValidatorHandler}.</li>
     *   <li>Builds a semantic element-path (e.g. {@code order.items.item[0].quantity})
     *       using the pre-computed {@code maxSiblingByPath} map:
     *       {@code [n]} is shown if and only if that element name appeared more than
     *       once at this path level – including {@code [0]} for the first occurrence.</li>
     *   <li>Buffers the current element's text content for error reporting.</li>
     * </ol>
     */
    private static final class PathTrackingHandler extends DefaultHandler {

        private final ContentHandler delegate;

        /** Pre-computed: rawPath → max sibling index. Determines when to show [n]. */
        private final Map<String, Integer> maxSiblingByPath;

        /** Ordered path from root to current; front = root, back = current. */
        private final ArrayDeque<PathSegment> pathStack = new ArrayDeque<>();

        /** One sibling counter per nesting level (mirrors SiblingIndexCollector). */
        private final ArrayDeque<Map<String, Integer>> siblingCounters = new ArrayDeque<>();

        /** Accumulates PCDATA of the element currently being parsed. */
        private final StringBuilder textBuffer = new StringBuilder();

        PathTrackingHandler(ContentHandler delegate, Map<String, Integer> maxSiblingByPath) {
            this.delegate = delegate;
            this.maxSiblingByPath = maxSiblingByPath;
        }

        // ------------------------------------------------------------------
        // State accessors (called by CollectingErrorHandler)
        // ------------------------------------------------------------------

        /**
         * Builds the dot-notation field path using the pre-computed sibling map.
         * Example: {@code order.items.item[0].quantity} or {@code order.items.item[1].price}.
         * Returns {@code null} when the path stack is empty.
         */
        String currentPath() {
            if (pathStack.isEmpty()) return null;

            StringBuilder path      = new StringBuilder();
            StringBuilder rawParent = new StringBuilder();

            for (PathSegment seg : pathStack) {
                String rawKey = rawParent.isEmpty()
                        ? seg.name()
                        : rawParent + "." + seg.name();

                if (!path.isEmpty()) path.append('.');
                path.append(seg.name());
                // Show [n] only if this element has ever appeared with a sibling at this level
                if (maxSiblingByPath.getOrDefault(rawKey, 0) > 0) {
                    path.append('[').append(seg.index()).append(']');
                }

                if (!rawParent.isEmpty()) rawParent.append('.');
                rawParent.append(seg.name());
            }

            return path.toString();
        }

        /**
         * Returns trimmed text content of the current element,
         * or {@code null} when empty / whitespace-only.
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
            textBuffer.setLength(0);

            if (siblingCounters.isEmpty()) {
                siblingCounters.push(new HashMap<>());
            }
            Map<String, Integer> counters = siblingCounters.peek();
            int siblingIndex = counters.getOrDefault(localName, 0);
            counters.put(localName, siblingIndex + 1);

            pathStack.addLast(new PathSegment(localName, siblingIndex));
            siblingCounters.push(new HashMap<>());

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
    // Phase 2: CollectingErrorHandler with deduplication
    // =========================================================================

    /**
     * Collects schema validation errors with deduplication.
     *
     * <p>Xerces fires two error codes per type violation:
     * {@code cvc-datatype-valid.1.2.1} and {@code cvc-type.3.1.3}.
     * Both have the same {@code field} and {@code value}.
     * We keep only the first by tracking seen {@code "field|value"} keys.
     *
     * <p>Structural errors where {@code value == null} (missing elements,
     * wrong content order) are never deduplicated, as multiple distinct structural
     * problems may occur under the same parent element.
     */
    private static final class CollectingErrorHandler implements ErrorHandler {

        private final List<ValidationError> errors;
        private final PathTrackingHandler tracker;

        /** Already-seen (field, value) combinations for type-error deduplication. */
        private final Set<String> seen = new HashSet<>();

        CollectingErrorHandler(List<ValidationError> errors, PathTrackingHandler tracker) {
            this.errors = errors;
            this.tracker = tracker;
        }

        @Override
        public void warning(SAXParseException e) {
            addIfNew(buildError(e));
        }

        @Override
        public void error(SAXParseException e) {
            addIfNew(buildError(e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            errors.add(ValidationError.ofMessage(e.getMessage()));
            throw e;
        }

        private void addIfNew(ValidationError error) {
            if (error.value() != null) {
                // Deduplicate type errors: same (field, value) → same root cause
                String key = error.field() + "|" + error.value();
                if (!seen.add(key)) return;
            }
            errors.add(error);
        }

        private ValidationError buildError(SAXParseException e) {
            return ValidationError.of(
                    tracker.currentPath(),
                    tracker.currentTextValue(),
                    e.getMessage());
        }
    }
}
