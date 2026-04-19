package demo.validator.service;

import demo.validator.dto.ValidationError;
import demo.validator.dto.ValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void validMinifiedXml_shouldReturnValidTrue() {
        // Must work without any line breaks or indentation
        String xml = "<?xml version=\"1.0\"?>" +
                "<order><orderId>ORD-001</orderId><customer>Acme</customer>" +
                "<items><item><productId>P1</productId><quantity>1</quantity>" +
                "<price>9.99</price></item></items></order>";

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void multipleItems_shouldReturnValidTrue() {
        String xml = """
                <order>
                    <orderId>ORD-002</orderId>
                    <customer>Bobs Store</customer>
                    <items>
                        <item><productId>P1</productId><quantity>1</quantity><price>5.00</price></item>
                        <item><productId>P2</productId><quantity>3</quantity><price>12.50</price></item>
                    </items>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Schema violations
    // -------------------------------------------------------------------------

    @Test
    void missingRequiredElement_shouldReturnError() {
        // 'customer' element is intentionally missing
        String xml = """
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
        // field points to the parent ('order') because the error fires at its end tag
        // (known limitation: XSD validators report missing-child errors on the parent)
        ValidationError error = result.errors().getFirst();
        assertThat(error.field()).isNotNull();
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void wrongElementType_shouldReportFieldAndValue() {
        // quantity must be xs:positiveInteger – 'abc' is invalid
        String xml = """
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

        ValidationError error = result.errors().getFirst();
        assertThat(error.field()).endsWith("quantity");     // e.g. order.items.item.quantity
        assertThat(error.value()).isEqualTo("abc");         // the offending text value
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void secondItemWithWrongType_shouldReportCorrectIndex() {
        // Second item has invalid quantity – path must include [1]
        String xml = """
                <order>
                    <orderId>ORD-004</orderId>
                    <customer>Multi Item Co</customer>
                    <items>
                        <item><productId>P1</productId><quantity>5</quantity><price>1.00</price></item>
                        <item><productId>P2</productId><quantity>not-a-number</quantity><price>2.00</price></item>
                    </items>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        ValidationError error = result.errors().getFirst();
        assertThat(error.field()).contains("item[1]");      // second item is indexed
        assertThat(error.field()).endsWith("quantity");
        assertThat(error.value()).isEqualTo("not-a-number");
    }

    @Test
    void emptyItemsList_shouldReturnError() {
        // 'items' must contain at least one 'item'
        String xml = """
                <order>
                    <orderId>ORD-005</orderId>
                    <customer>Empty Order Co.</customer>
                    <items/>
                </order>
                """;

        ValidationResponse result = service.validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Well-formedness errors (not schema violations)
    // -------------------------------------------------------------------------

    @Test
    void completelyMalformedXml_shouldReturnErrorInstead() {
        // Not valid XML at all – parser cannot even start
        // The service catches this and returns a structured error (no exception thrown)
        String notXml = "this is definitely not xml <<<";

        ValidationResponse result = service.validate(notXml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).isNotBlank();
        assertThat(result.errors().getFirst().field()).isNull();   // no path determinable
        assertThat(result.errors().getFirst().value()).isNull();
    }
}
