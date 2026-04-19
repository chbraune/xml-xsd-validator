package demo.validator.service;

import static org.assertj.core.api.Assertions.*;

import demo.validator.dto.ValidationResponse;
import org.junit.jupiter.api.*;

/**
 * Unit tests for {@link XmlValidationService}.
 * No Spring context – the service is instantiated directly.
 * The XSD is loaded from the test classpath (src/main/resources is on the classpath).
 */
class XmlValidationServiceTest {

    private XmlValidationService service;

    @BeforeEach
    void setUp() {
        service = new XmlValidationService();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void validXml_shouldReturnValidTrue() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <order>
                    <orderId>ORD-001</orderId>
                    <customer>Acme Corp</customer>
                    <items>
                        <item>
                            <productId>PROD-1</productId>
                            <quantity>2</quantity>
                            <price>19.99</price>
                        </item>
                    </items>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Schema violations
    // -------------------------------------------------------------------------

    @Test
    void missingRequiredElement_shouldReturnErrors() {
        // 'customer' element is intentionally missing
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <order>
                    <orderId>ORD-002</orderId>
                    <items>
                        <item>
                            <productId>PROD-2</productId>
                            <quantity>1</quantity>
                            <price>9.99</price>
                        </item>
                    </items>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void wrongElementType_shouldReturnErrors() {
        // quantity must be xs:positiveInteger – 'abc' is invalid
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <order>
                    <orderId>ORD-003</orderId>
                    <customer>Test GmbH</customer>
                    <items>
                        <item>
                            <productId>PROD-3</productId>
                            <quantity>abc</quantity>
                            <price>5.00</price>
                        </item>
                    </items>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("quantity"));
    }

    @Test
    void emptyItemsList_shouldReturnErrors() {
        // 'items' must contain at least one 'item' (maxOccurs="unbounded", minOccurs defaults to 1)
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <order>
                    <orderId>ORD-004</orderId>
                    <customer>Empty Order Co.</customer>
                    <items/>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void multipleSchemaViolations_shouldReturn3Errors() {
        // Multiple violations: missing 'customer', wrong type for 'quantity' (2 errors), missing 'price'
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <order>
                <orderId>ORD-005</orderId>
                <items>
                    <item>
                        <productId>PROD-5</productId>
                        <quantity>not-a-number</quantity>
                    </item>
                </items>
            </order>
            """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(4);
    }

    // -------------------------------------------------------------------------
    // Technical failures
    // -------------------------------------------------------------------------

    @Test
    void completelyMalformedXml_shouldReturnErrors() {
        String notXml = "this is definitely not xml <<<";

        ValidationResponse result = service.validate(notXml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }
}
